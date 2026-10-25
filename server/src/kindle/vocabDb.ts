import Database from 'better-sqlite3';
import { normalizeWord } from '../lookup/service.js';

export type KindleCategory = 'learning' | 'mastered';

export type KindleWord = {
  /** Normalised stem (fallback: the looked-up form). */
  word: string;
  /** Sentence from the book, from the newest lookup. */
  usage: string | null;
  bookTitle: string | null;
  bookAuthor: string | null;
  kindleCategory: KindleCategory;
  /** WORDS.timestamp (ms since epoch) of the newest row for this word; drives incremental import. */
  timestamp: number;
  /** LOOKUPS.timestamp of the newest lookup, if any. */
  lookupAt: number | null;
};

export type KindleVocab = {
  /** One entry per distinct word, newest first. */
  words: KindleWord[];
  /** Max WORDS.timestamp over the English words, null if there are none. */
  maxTimestamp: number | null;
};

/** The upload is not a usable Kindle vocab.db. Maps to 400. */
export class KindleFileError extends Error {}

const MAX_WORD = 100;
const MAX_USAGE = 2000;
const MAX_BOOK_FIELD = 500;

type Row = Record<string, unknown>;

/**
 * Reads a Kindle vocab.db. The file is untrusted: it is opened read-only, column sets are
 * detected per table (they vary between firmware versions), and every value is type-checked.
 */
export function readKindleVocab(path: string): KindleVocab {
  let db: Database.Database;
  try {
    db = new Database(path, { readonly: true, fileMustExist: true });
  } catch {
    throw new KindleFileError('not a SQLite database');
  }
  try {
    // Don't let views or triggers in a crafted file call SQL functions with side effects.
    db.pragma('trusted_schema = OFF');
    return read(db);
  } catch (err) {
    if (err instanceof KindleFileError) throw err;
    // SQLITE_NOTADB and friends surface on the first query, not on open.
    throw new KindleFileError('not a Kindle vocab.db');
  } finally {
    db.close();
  }
}

function read(db: Database.Database): KindleVocab {
  const wordsCols = columnsOf(db, 'WORDS');
  if (!wordsCols) throw new KindleFileError('WORDS table is missing');
  if (!wordsCols.has('id') || !(wordsCols.has('word') || wordsCols.has('stem'))) {
    throw new KindleFileError('WORDS table has no id/word columns');
  }

  const books = new Map<string, { title: string | null; author: string | null }>();
  const bookCols = columnsOf(db, 'BOOK_INFO');
  if (bookCols?.has('id')) {
    for (const row of selectAll(db, 'BOOK_INFO', bookCols, ['id', 'title', 'authors'])) {
      if (typeof row.id !== 'string' && typeof row.id !== 'number') continue;
      books.set(String(row.id), { title: text(row.title, MAX_BOOK_FIELD), author: text(row.authors, MAX_BOOK_FIELD) });
    }
  }

  type Lookup = { usage: string | null; bookKey: string | null; timestamp: number };
  const newestLookup = new Map<string, Lookup>();
  const lookupCols = columnsOf(db, 'LOOKUPS');
  if (lookupCols?.has('word_key')) {
    for (const row of selectAll(db, 'LOOKUPS', lookupCols, ['word_key', 'book_key', 'usage', 'timestamp'])) {
      if (typeof row.word_key !== 'string' && typeof row.word_key !== 'number') continue;
      const key = String(row.word_key);
      const lookup: Lookup = {
        usage: text(row.usage, MAX_USAGE),
        bookKey: row.book_key == null ? null : String(row.book_key),
        timestamp: millis(row.timestamp),
      };
      const prev = newestLookup.get(key);
      if (!prev || lookup.timestamp > prev.timestamp) newestLookup.set(key, lookup);
    }
  }

  const byWord = new Map<string, KindleWord>();
  let maxTimestamp: number | null = null;
  for (const row of selectAll(db, 'WORDS', wordsCols, ['id', 'word', 'stem', 'lang', 'category', 'timestamp'])) {
    if (wordsCols.has('lang') && !isEnglish(row.lang)) continue;
    const word = wordOf(row);
    if (!word) continue;
    const timestamp = millis(row.timestamp);
    maxTimestamp = Math.max(maxTimestamp ?? timestamp, timestamp);

    const lookup = newestLookup.get(String(row.id));
    const book = lookup?.bookKey ? books.get(lookup.bookKey) : undefined;
    const prev = byWord.get(word);
    // Several WORDS rows can share a stem ("ran", "running"): the newest row sets the
    // category, and the newest lookup across all of them gives the sentence.
    const rowIsNewer = !prev || timestamp > prev.timestamp;
    const lookupIsNewer = lookup && (!prev || prev.lookupAt === null || lookup.timestamp > prev.lookupAt);
    byWord.set(word, {
      word,
      usage: lookupIsNewer ? lookup.usage : (prev?.usage ?? null),
      bookTitle: lookupIsNewer ? (book?.title ?? null) : (prev?.bookTitle ?? null),
      bookAuthor: lookupIsNewer ? (book?.author ?? null) : (prev?.bookAuthor ?? null),
      lookupAt: lookupIsNewer ? lookup.timestamp : (prev?.lookupAt ?? null),
      kindleCategory: rowIsNewer ? (row.category === 1 ? 'mastered' : 'learning') : prev.kindleCategory,
      timestamp: rowIsNewer ? timestamp : prev.timestamp,
    });
  }

  const words = [...byWord.values()].sort((a, b) => b.timestamp - a.timestamp || a.word.localeCompare(b.word));
  return { words, maxTimestamp };
}

/** Lower-cased column names of `table`, or null if the table does not exist. */
function columnsOf(db: Database.Database, table: string): Set<string> | null {
  const cols = db.prepare('SELECT name FROM pragma_table_info(?)').all(table) as { name: string }[];
  return cols.length ? new Set(cols.map((c) => c.name.toLowerCase())) : null;
}

/** SELECT of the wanted columns that exist; missing ones come back undefined. Names are from a fixed list. */
function selectAll(db: Database.Database, table: string, have: Set<string>, want: string[]): Iterable<Row> {
  const cols = want.filter((c) => have.has(c));
  return db.prepare(`SELECT ${cols.map((c) => `"${c}"`).join(', ')} FROM "${table}"`).iterate() as Iterable<Row>;
}

function isEnglish(lang: unknown): boolean {
  if (typeof lang !== 'string') return false;
  const l = lang.trim().toLowerCase();
  return l === 'en' || l.startsWith('en-') || l.startsWith('en_');
}

function wordOf(row: Row): string | null {
  for (const candidate of [row.stem, row.word]) {
    if (typeof candidate !== 'string') continue;
    const w = normalizeWord(candidate);
    if (w) return w.length <= MAX_WORD ? w : null;
  }
  return null;
}

function text(value: unknown, max: number): string | null {
  if (typeof value !== 'string') return null;
  const t = value.trim();
  return t ? t.slice(0, max) : null;
}

function millis(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;
}
