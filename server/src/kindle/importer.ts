import { eq, max } from 'drizzle-orm';
import { randomBytes } from 'node:crypto';
import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import type { AudioService } from '../audio.js';
import type { Db } from '../db/index.js';
import { kindleImports, words, type WordRow } from '../db/schema.js';
import { LookupFailedError, normalizeWord, type LookupService } from '../lookup/service.js';
import { findWordByText, insertWord } from '../wordStore.js';
import { readKindleVocab, type KindleCategory, type KindleWord } from './vocabDb.js';

export const IMPORT_TTL_MS = 60 * 60 * 1000;
export const MAX_CONCURRENT_LOOKUPS = 5;

export type KindleCandidate = {
  word: string;
  usage: string | null;
  bookTitle: string | null;
  bookAuthor: string | null;
  kindleCategory: KindleCategory;
  exists: boolean;
};

export type ImportProgress = {
  state: 'preview' | 'running' | 'finished';
  done: number;
  total: number;
  imported: number;
  skipped: number;
  errors: { word: string; error: string }[];
};

export type ConfirmResult = 'started' | 'not_found' | 'already_confirmed';

export type KindleImporter = {
  /** Stores the upload as tmp/<importId>.db and returns the preview. Throws KindleFileError for unusable files. */
  preview(userId: number, file: Uint8Array, onlyNew: boolean): { importId: string; candidates: KindleCandidate[] };
  /** Starts the background import of the selected words. */
  confirm(userId: number, importId: string, selected: string[], markMasteredAsLearned: boolean): ConfirmResult;
  progress(userId: number, importId: string): ImportProgress | null;
  /** Resolves when no import job is running. For tests and shutdown. */
  whenIdle(): Promise<void>;
};

type Pending = {
  userId: number;
  file: string;
  expiresAt: number;
  maxTimestamp: number | null;
  progress: ImportProgress;
};

const IMPORT_ID = /^[0-9a-f]{32}$/;

export function createKindleImporter(deps: {
  db: Db;
  lookup: LookupService;
  audio: AudioService;
  dataDir: string;
  now: () => string;
  onWordWritten: (word: WordRow) => void;
}): KindleImporter {
  const { db } = deps;
  const tmpDir = join(deps.dataDir, 'tmp');
  const imports = new Map<string, Pending>();
  const running = new Set<Promise<void>>();

  // Uploads from before a restart have no state left to confirm them with.
  rmSync(tmpDir, { recursive: true, force: true });
  mkdirSync(tmpDir, { recursive: true });

  const nowMs = () => Date.parse(deps.now());
  // better-sqlite3 may leave -wal/-shm siblings next to a WAL-mode upload.
  const removeFile = (file: string) => {
    for (const suffix of ['', '-wal', '-shm', '-journal']) rmSync(file + suffix, { force: true });
  };

  function sweep() {
    const t = nowMs();
    for (const [id, imp] of imports) {
      if (imp.progress.state !== 'running' && imp.expiresAt <= t) {
        removeFile(imp.file);
        imports.delete(id);
      }
    }
  }

  function find(userId: number, importId: string): Pending | undefined {
    sweep();
    if (!IMPORT_ID.test(importId)) return undefined;
    const imp = imports.get(importId);
    return imp?.userId === userId ? imp : undefined;
  }

  function lastKindleTimestamp(userId: number): number | null {
    const row = db
      .select({ last: max(kindleImports.lastKindleTimestamp) })
      .from(kindleImports)
      .where(eq(kindleImports.userId, userId))
      .get();
    return row?.last ?? null;
  }

  async function run(imp: Pending, selected: string[], markMastered: boolean) {
    const { progress } = imp;
    const byWord = new Map(readKindleVocab(imp.file).words.map((w) => [w.word, w]));
    const queue = [...selected];
    // Kindle timestamps of words that failed for a transient reason (Claude down, a bug): they must
    // stay "new" for the next incremental import, so the cutoff cannot move past them.
    const failedAt: number[] = [];

    async function importOne(text: string) {
      const kw = byWord.get(text);
      if (!kw) return progress.errors.push({ word: text, error: 'not in upload' });
      const existing = findWordByText(db, imp.userId, text);
      if (existing) {
        progress.skipped++;
        if (markMastered && kw.kindleCategory === 'mastered' && existing.status === 'learning') {
          const ts = deps.now();
          const row = db
            .update(words)
            .set({ status: 'learned', learnedAt: ts, updatedAt: ts })
            .where(eq(words.id, existing.id))
            .returning()
            .get();
          deps.onWordWritten(row);
        }
        return;
      }
      await lookupAndInsert(kw, markMastered);
    }

    async function lookupAndInsert(kw: KindleWord, markMastered: boolean) {
      let outcome;
      try {
        outcome = await deps.lookup.lookup(kw.word, kw.usage ?? undefined);
      } catch (err) {
        if (!(err instanceof LookupFailedError)) throw err;
        console.error(`kindle import: lookup "${kw.word}" failed: ${err.message}`);
        failedAt.push(kw.timestamp);
        return progress.errors.push({ word: kw.word, error: 'lookup failed' });
      }
      if (outcome.kind === 'not_english') return progress.errors.push({ word: kw.word, error: 'not_english' });

      const [candidate] = outcome.response.candidates;
      const row = insertWord(
        db,
        imp.userId,
        {
          word: kw.word,
          definition: candidate.definition,
          example: kw.usage ?? candidate.example,
          finnish: candidate.finnish,
          ipa: outcome.response.ipa,
          source: 'kindle',
          status: markMastered && kw.kindleCategory === 'mastered' ? 'learned' : 'learning',
          bookTitle: kw.bookTitle,
          bookAuthor: kw.bookAuthor,
          kindleLookupAt: kw.lookupAt === null ? null : new Date(kw.lookupAt).toISOString(),
        },
        deps.now(),
      );
      // Added by hand while the job ran: that one wins, like any existing word.
      if (!row) return progress.skipped++;
      progress.imported++;
      deps.onWordWritten(row);
      deps.audio.downloadFor(row);
    }

    async function worker() {
      for (let text = queue.shift(); text !== undefined; text = queue.shift()) {
        try {
          await importOne(text);
        } catch (err) {
          console.error(`kindle import: "${text}" failed:`, err);
          const kw = byWord.get(text);
          if (kw) failedAt.push(kw.timestamp);
          progress.errors.push({ word: text, error: 'internal error' });
        }
        progress.done++;
      }
    }

    try {
      await Promise.all(Array.from({ length: MAX_CONCURRENT_LOOKUPS }, worker));
      db.insert(kindleImports)
        .values({
          userId: imp.userId,
          importedAt: deps.now(),
          wordsNew: progress.imported,
          wordsSkipped: progress.skipped,
          // Everything in this upload was seen in the preview, selected or not, except words that
          // failed. The cutoff is the MAX over all of the user's rows, so an older backup uploaded
          // later cannot move it backwards.
          lastKindleTimestamp: failedAt.length
            ? Math.min(imp.maxTimestamp ?? Infinity, Math.min(...failedAt) - 1)
            : imp.maxTimestamp,
        })
        .run();
    } finally {
      progress.state = 'finished';
      removeFile(imp.file);
      imp.expiresAt = nowMs() + IMPORT_TTL_MS;
    }
  }

  return {
    preview(userId, bytes, onlyNew) {
      sweep();
      const importId = randomBytes(16).toString('hex');
      const file = join(tmpDir, `${importId}.db`);
      writeFileSync(file, bytes);
      let vocab;
      try {
        vocab = readKindleVocab(file);
      } catch (err) {
        removeFile(file);
        throw err;
      }
      const since = onlyNew ? lastKindleTimestamp(userId) : null;
      const candidates = vocab.words
        .filter((w) => since === null || w.timestamp > since)
        .map((w) => ({
          word: w.word,
          usage: w.usage,
          bookTitle: w.bookTitle,
          bookAuthor: w.bookAuthor,
          kindleCategory: w.kindleCategory,
          exists: findWordByText(db, userId, w.word) !== undefined,
        }));
      imports.set(importId, {
        userId,
        file,
        expiresAt: nowMs() + IMPORT_TTL_MS,
        maxTimestamp: vocab.maxTimestamp,
        progress: { state: 'preview', done: 0, total: 0, imported: 0, skipped: 0, errors: [] },
      });
      return { importId, candidates };
    },

    confirm(userId, importId, selected, markMasteredAsLearned) {
      const imp = find(userId, importId);
      if (!imp) return 'not_found';
      if (imp.progress.state !== 'preview') return 'already_confirmed';
      const unique = [...new Set(selected.map(normalizeWord))];
      Object.assign(imp.progress, { state: 'running', total: unique.length });
      const job = run(imp, unique, markMasteredAsLearned).catch((err: unknown) => {
        console.error(`kindle import ${importId} failed:`, err);
      });
      running.add(job);
      void job.finally(() => running.delete(job));
      return 'started';
    },

    progress(userId, importId) {
      const imp = find(userId, importId);
      return imp ? structuredClone(imp.progress) : null;
    },

    async whenIdle() {
      while (running.size > 0) await Promise.all([...running]);
    },
  };
}
