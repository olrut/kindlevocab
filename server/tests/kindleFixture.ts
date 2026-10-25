import Database from 'better-sqlite3';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { onTestFinished } from 'vitest';

/** The Kindle vocab.db schema from docs/SPEC.md § 3 (current firmware). */
export const KINDLE_SCHEMA = `
  CREATE TABLE WORDS (id TEXT PRIMARY KEY NOT NULL UNIQUE, word TEXT, stem TEXT, lang TEXT,
    category INTEGER DEFAULT 0, timestamp INTEGER DEFAULT 0, profileid TEXT);
  CREATE TABLE LOOKUPS (id TEXT PRIMARY KEY NOT NULL, word_key TEXT, book_key TEXT, dict_key TEXT,
    pos TEXT, usage TEXT, timestamp INTEGER DEFAULT 0);
  CREATE TABLE BOOK_INFO (id TEXT PRIMARY KEY NOT NULL, asin TEXT, guid TEXT, lang TEXT, title TEXT, authors TEXT);
`;

type Row = Record<string, unknown>;
export type FixtureTables = { WORDS?: Row[]; LOOKUPS?: Row[]; BOOK_INFO?: Row[] };

/** Builds a vocab.db in a temp dir and returns its path. Rows insert only the columns they name. */
export function buildVocabDb(tables: FixtureTables, schema = KINDLE_SCHEMA, opts: { wal?: boolean } = {}): string {
  const dir = mkdtempSync(join(tmpdir(), 'kindle-fixture-'));
  onTestFinished(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'vocab.db');
  const db = new Database(path);
  if (opts.wal) db.pragma('journal_mode = WAL');
  db.exec(schema);
  for (const [table, rows] of Object.entries(tables)) {
    for (const row of rows ?? []) {
      const cols = Object.keys(row);
      db.prepare(`INSERT INTO ${table} (${cols.join(', ')}) VALUES (${cols.map(() => '?').join(', ')})`).run(
        ...Object.values(row),
      );
    }
  }
  db.close();
  return path;
}

export const vocabDbBytes = (tables: FixtureTables, schema?: string) => readFileSync(buildVocabDb(tables, schema));

/** One Kindle word with one lookup in one book: the common case. */
export function kindleWord(
  stem: string,
  opts: { word?: string; timestamp: number; category?: 0 | 1; usage?: string; book?: string; lang?: string },
) {
  const id = `${opts.lang ?? 'en'}:${opts.word ?? stem}`;
  return {
    WORDS: [{ id, word: opts.word ?? stem, stem, lang: opts.lang ?? 'en', category: opts.category ?? 0, timestamp: opts.timestamp }],
    LOOKUPS: [
      {
        id: `${opts.book ?? 'b1'}_${id}_${opts.timestamp}`,
        word_key: id,
        book_key: opts.book ?? 'b1',
        usage: opts.usage ?? `A sentence with ${opts.word ?? stem}.`,
        timestamp: opts.timestamp,
      },
    ],
  };
}

export const BOOKS = [
  { id: 'b1', asin: 'B001', guid: 'g1', lang: 'en', title: 'The Sensor Age', authors: 'Ada Writer' },
  { id: 'b2', asin: 'B002', guid: 'g2', lang: 'en', title: 'Second Book', authors: 'Bo Author' },
];

/** Merges kindleWord() results and the default books into one table set. */
export function tablesOf(...parts: FixtureTables[]): FixtureTables {
  const out: Required<FixtureTables> = { WORDS: [], LOOKUPS: [], BOOK_INFO: [...BOOKS] };
  for (const p of parts) {
    out.WORDS.push(...(p.WORDS ?? []));
    out.LOOKUPS.push(...(p.LOOKUPS ?? []));
    for (const b of p.BOOK_INFO ?? []) if (!out.BOOK_INFO.some((x) => x.id === b.id)) out.BOOK_INFO.push(b);
  }
  return out;
}
