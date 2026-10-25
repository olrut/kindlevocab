import { and, asc, eq, isNull } from 'drizzle-orm';
import { mkdir, readdir, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { Db } from '../db/index.js';
import { users, words, type WordRow } from '../db/schema.js';
import { noteName, renderBookNote, renderIndex, renderWordNote, VAULT_ROOT, type Book } from './render.js';

export type ExportCounts = { words: number; books: number };
export type ExportRun = { at: string; ok: boolean; error: string | null };

export type ObsidianExporter = {
  /** False when OBSIDIAN_DIR is unset: every call is then a no-op. */
  enabled: boolean;
  /** Schedules an export of this word (debounced). Plugged into onWordWritten. */
  notify(word: WordRow): void;
  /** Rewrites a user's folder (or every user's) from the database and removes stray notes. */
  exportAll(userId?: number): Promise<ExportCounts>;
  /** Runs the pending debounced export now and waits for it. For tests and shutdown. */
  flush(): Promise<void>;
  /** Waits for exports already running, without starting the pending one. For tests. */
  whenIdle(): Promise<void>;
  /** When the user's folder was last exported and whether that worked; null if not since start. */
  lastRun(userId: number): ExportRun | null;
};

/**
 * One-way mirror of the database into `<dir>/Vocab/<user>/` (docs/SPEC.md § 7). Note contents are
 * never read; the only look at the vault is listing our own Words/ and Books/ folders to remove
 * .md files that no longer correspond to a word or book.
 */
export function createObsidianExporter(deps: {
  db: Db;
  dir: string | null;
  now?: () => string;
  debounceMs?: number;
  maxWaitMs?: number;
}): ObsidianExporter {
  const { db, dir } = deps;
  const debounceMs = deps.debounceMs ?? 2000;
  const maxWaitMs = deps.maxWaitMs ?? 10_000;
  const now = deps.now ?? (() => new Date().toISOString());
  if (dir === null) {
    return {
      enabled: false,
      notify() {},
      exportAll: async () => ({ words: 0, books: 0 }),
      flush: async () => {},
      whenIdle: async () => {},
      lastRun: () => null,
    };
  }

  const runs = new Map<number, ExportRun>();
  /** Exports one user's folder and records the outcome for the settings page. */
  async function exportRecorded(userId: number, only?: Set<number>): Promise<ExportCounts> {
    try {
      const counts = await exportUser(userId, only);
      runs.set(userId, { at: now(), ok: true, error: null });
      return counts;
    } catch (err) {
      runs.set(userId, { at: now(), ok: false, error: err instanceof Error ? err.message : String(err) });
      throw err;
    }
  }

  /** userId → ids of words written since the last flush. */
  let dirty = new Map<number, Set<number>>();
  let debounceTimer: ReturnType<typeof setTimeout> | undefined;
  let maxWaitTimer: ReturnType<typeof setTimeout> | undefined;
  // Exports run one at a time so two of them never write the same folder concurrently.
  let chain: Promise<unknown> = Promise.resolve();
  const enqueue = <T>(job: () => Promise<T>): Promise<T> => {
    const p = chain.then(job);
    chain = p.catch(() => {});
    return p;
  };

  function startFlush(): Promise<void> {
    clearTimeout(debounceTimer);
    clearTimeout(maxWaitTimer);
    debounceTimer = maxWaitTimer = undefined;
    const batch = dirty;
    dirty = new Map();
    return enqueue(async () => {
      for (const [userId, ids] of batch) {
        try {
          await exportRecorded(userId, ids);
        } catch (err) {
          console.error(`obsidian export for user ${userId} failed:`, err);
        }
      }
    });
  }

  async function exportUser(userId: number, only?: Set<number>): Promise<ExportCounts> {
    const user = db.select().from(users).where(eq(users.id, userId)).get();
    if (!user) return { words: 0, books: 0 };
    const root = join(dir!, VAULT_ROOT, noteName(user.name));
    const live = db
      .select()
      .from(words)
      .where(and(eq(words.userId, userId), isNull(words.deletedAt)))
      .orderBy(asc(words.id))
      .all();

    // Two words could sanitise to the same file name; the older one owns the file.
    const byName = new Map<string, WordRow>();
    for (const w of live) if (!byName.has(noteName(w.word))) byName.set(noteName(w.word), w);

    const wordsDir = join(root, 'Words');
    await mkdir(wordsDir, { recursive: true });
    if (only) {
      for (const id of only) {
        const row = db.select().from(words).where(eq(words.id, id)).get();
        if (!row) continue;
        const name = noteName(row.word);
        const owner = byName.get(name);
        if (owner) await writeAtomic(join(wordsDir, `${name}.md`), renderWordNote(owner, user.name));
        else await rm(join(wordsDir, `${name}.md`), { force: true });
      }
    } else {
      for (const [name, w] of byName) await writeAtomic(join(wordsDir, `${name}.md`), renderWordNote(w, user.name));
      await removeStrayNotes(wordsDir, new Set(byName.keys()));
    }

    // Book notes are cheap to rebuild and depend on every word in the book, so always redo them all.
    const books = new Map<string, { book: Book; words: WordRow[] }>();
    for (const w of byName.values()) {
      if (!w.bookTitle) continue;
      const name = noteName(w.bookTitle);
      const entry = books.get(name) ?? { book: { title: w.bookTitle, author: null }, words: [] };
      entry.book.author ??= w.bookAuthor;
      entry.words.push(w);
      books.set(name, entry);
    }
    const booksDir = join(root, 'Books');
    if (books.size > 0) await mkdir(booksDir, { recursive: true });
    for (const [name, { book, words: ws }] of books) {
      await writeAtomic(join(booksDir, `${name}.md`), renderBookNote(book, ws, user.name));
    }
    await removeStrayNotes(booksDir, new Set(books.keys()));

    const learned = live.filter((w) => w.status === 'learned').length;
    await writeAtomic(join(root, 'index.md'), renderIndex(user.name, { learning: live.length - learned, learned }));
    return { words: byName.size, books: books.size };
  }

  return {
    enabled: true,
    notify(word) {
      const ids = dirty.get(word.userId) ?? new Set();
      ids.add(word.id);
      dirty.set(word.userId, ids);
      clearTimeout(debounceTimer);
      debounceTimer = setTimeout(startFlush, debounceMs);
      maxWaitTimer ??= setTimeout(startFlush, maxWaitMs);
    },
    exportAll(userId) {
      return enqueue(async () => {
        const ids = userId === undefined ? db.select({ id: users.id }).from(users).all().map((u) => u.id) : [userId];
        const total = { words: 0, books: 0 };
        for (const id of ids) {
          const counts = await exportRecorded(id);
          total.words += counts.words;
          total.books += counts.books;
        }
        return total;
      });
    },
    flush: startFlush,
    lastRun: (userId) => runs.get(userId) ?? null,
    async whenIdle() {
      // A job can enqueue behind the one awaited; loop until the chain stops moving.
      for (let c = chain; ; c = chain) {
        await c;
        if (c === chain) return;
      }
    },
  };
}

/** Write to a temp file next to the target, then rename over it, so readers never see half a note. */
async function writeAtomic(file: string, content: string) {
  const tmp = join(file, '..', `.${process.pid}.${Math.random().toString(36).slice(2)}.tmp`);
  try {
    await writeFile(tmp, content, 'utf8');
    await rename(tmp, file);
  } catch (err) {
    await rm(tmp, { force: true });
    throw err;
  }
}

async function removeStrayNotes(folder: string, keep: Set<string>) {
  let entries;
  try {
    entries = await readdir(folder, { withFileTypes: true });
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code === 'ENOENT') return;
    throw err;
  }
  for (const e of entries) {
    if (e.isFile() && e.name.endsWith('.md') && !keep.has(e.name.slice(0, -3))) {
      await rm(join(folder, e.name), { force: true });
    }
  }
}
