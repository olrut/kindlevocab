import { statSync, writeFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { describe, expect, it } from 'vitest';
import { KindleFileError, readKindleVocab } from '../src/kindle/vocabDb.js';
import { buildVocabDb, kindleWord, tablesOf } from './kindleFixture.js';

const T1 = Date.parse('2026-01-01T10:00:00Z');
const T2 = Date.parse('2026-02-01T10:00:00Z');
const T3 = Date.parse('2026-03-01T10:00:00Z');

describe('readKindleVocab', () => {
  it('reads English words with the stem, the usage sentence and the book', () => {
    const path = buildVocabDb(
      tablesOf(kindleWord('ubiquitous', { timestamp: T1, usage: 'Sensors were ubiquitous.' })),
    );
    expect(readKindleVocab(path)).toEqual({
      maxTimestamp: T1,
      words: [
        {
          word: 'ubiquitous',
          usage: 'Sensors were ubiquitous.',
          bookTitle: 'The Sensor Age',
          bookAuthor: 'Ada Writer',
          kindleCategory: 'learning',
          timestamp: T1,
          lookupAt: T1,
        },
      ],
    });
  });

  it('uses the stem as the word, falling back to word when the stem is empty', () => {
    const path = buildVocabDb(
      tablesOf(
        kindleWord('run', { word: 'running', timestamp: T1 }),
        { WORDS: [{ id: 'en:Mellifluous', word: 'Mellifluous', stem: null, lang: 'en', category: 0, timestamp: T2 }] },
        { WORDS: [{ id: 'en:Torpid', word: 'x', stem: '  ', lang: 'en', timestamp: T2 }] },
      ),
    );
    expect(readKindleVocab(path).words.map((w) => w.word).sort()).toEqual(['mellifluous', 'run', 'x']);
  });

  it('skips non-English words', () => {
    const path = buildVocabDb(
      tablesOf(kindleWord('ubiquitous', { timestamp: T1 }), kindleWord('sana', { timestamp: T2, lang: 'fi' })),
    );
    const result = readKindleVocab(path);
    expect(result.words.map((w) => w.word)).toEqual(['ubiquitous']);
    expect(result.maxTimestamp).toBe(T1);
  });

  it('accepts regional English tags such as en-GB', () => {
    const path = buildVocabDb(tablesOf(kindleWord('colour', { timestamp: T1, lang: 'en-GB' })));
    expect(readKindleVocab(path).words.map((w) => w.word)).toEqual(['colour']);
  });

  it('maps category 1 to mastered', () => {
    const path = buildVocabDb(tablesOf(kindleWord('torpid', { timestamp: T1, category: 1 })));
    expect(readKindleVocab(path).words[0].kindleCategory).toBe('mastered');
  });

  it('takes the newest lookup for the sentence and the book', () => {
    const tables = tablesOf(kindleWord('ubiquitous', { timestamp: T1, usage: 'Old sentence.' }));
    tables.LOOKUPS!.push({ id: 'l2', word_key: 'en:ubiquitous', book_key: 'b2', usage: 'New sentence.', timestamp: T3 });
    tables.LOOKUPS!.push({ id: 'l3', word_key: 'en:ubiquitous', book_key: 'b1', usage: 'Middle.', timestamp: T2 });
    const [w] = readKindleVocab(buildVocabDb(tables)).words;
    expect(w).toMatchObject({ usage: 'New sentence.', bookTitle: 'Second Book', bookAuthor: 'Bo Author', lookupAt: T3 });
  });

  it('merges WORDS rows that share a stem: newest row wins, newest lookup across them', () => {
    const path = buildVocabDb(
      tablesOf(
        kindleWord('run', { word: 'ran', timestamp: T1, category: 0, usage: 'He ran.' }),
        kindleWord('run', { word: 'running', timestamp: T2, category: 1, usage: 'Running late.' }),
      ),
    );
    const { words } = readKindleVocab(path);
    expect(words).toHaveLength(1);
    expect(words[0]).toMatchObject({ word: 'run', usage: 'Running late.', kindleCategory: 'mastered', timestamp: T2 });
  });

  it('returns words newest first', () => {
    const path = buildVocabDb(
      tablesOf(
        kindleWord('alpha', { timestamp: T1 }),
        kindleWord('gamma', { timestamp: T3 }),
        kindleWord('beta', { timestamp: T2 }),
      ),
    );
    expect(readKindleVocab(path).words.map((w) => w.word)).toEqual(['gamma', 'beta', 'alpha']);
  });

  it('works without LOOKUPS and BOOK_INFO (older firmware)', () => {
    const path = buildVocabDb(
      { WORDS: [{ id: 'en:torpid', word: 'torpid', stem: 'torpid', lang: 'en', category: 0, timestamp: T1 }] },
      `CREATE TABLE WORDS (id TEXT PRIMARY KEY, word TEXT, stem TEXT, lang TEXT, category INTEGER, timestamp INTEGER);`,
    );
    expect(readKindleVocab(path).words).toEqual([
      { word: 'torpid', usage: null, bookTitle: null, bookAuthor: null, kindleCategory: 'learning', timestamp: T1, lookupAt: null },
    ]);
  });

  it('tolerates missing optional columns (no stem, lang, category, usage, authors)', () => {
    const path = buildVocabDb(
      {
        WORDS: [{ id: 'w1', word: 'Torpid', timestamp: T1 }],
        LOOKUPS: [{ id: 'l1', word_key: 'w1', book_key: 'b1', timestamp: T1 }],
        BOOK_INFO: [{ id: 'b1', title: 'Only Title' }],
      },
      `CREATE TABLE WORDS (id TEXT PRIMARY KEY, word TEXT, timestamp INTEGER);
       CREATE TABLE LOOKUPS (id TEXT PRIMARY KEY, word_key TEXT, book_key TEXT, timestamp INTEGER);
       CREATE TABLE BOOK_INFO (id TEXT PRIMARY KEY, title TEXT);`,
    );
    expect(readKindleVocab(path).words).toEqual([
      { word: 'torpid', usage: null, bookTitle: 'Only Title', bookAuthor: null, kindleCategory: 'learning', timestamp: T1, lookupAt: T1 },
    ]);
  });

  it('treats values as untrusted: skips unusable words, trims and caps text', () => {
    const longUsage = 'x'.repeat(5000);
    const path = buildVocabDb({
      WORDS: [
        { id: 'a', word: 'ok', stem: 'ok', lang: 'en', timestamp: T1 },
        { id: 'b', word: Buffer.from('blob'), stem: null, lang: 'en', timestamp: T1 },
        { id: 'c', word: 'w'.repeat(101), stem: null, lang: 'en', timestamp: T1 },
        { id: 'd', word: '', stem: '', lang: 'en', timestamp: T1 },
        { id: 'e', word: 'notime', stem: 'notime', lang: 'en', timestamp: 'yesterday' },
      ],
      LOOKUPS: [{ id: 'l1', word_key: 'a', book_key: 'nope', usage: `  ${longUsage}  `, timestamp: T1 }],
    });
    const { words } = readKindleVocab(path);
    expect(words.map((w) => w.word)).toEqual(['ok', 'notime']);
    expect(words[0].usage).toHaveLength(2000);
    expect(words[0].bookTitle).toBeNull();
    expect(words[1].timestamp).toBe(0);
  });

  it('opens a WAL-mode database uploaded without its -wal/-shm files', () => {
    const path = buildVocabDb(tablesOf(kindleWord('ubiquitous', { timestamp: T1 })), undefined, { wal: true });
    expect(readKindleVocab(path).words.map((w) => w.word)).toEqual(['ubiquitous']);
  });

  it('rejects a database without a WORDS table', () => {
    const path = buildVocabDb({}, 'CREATE TABLE OTHER (id INTEGER);');
    expect(() => readKindleVocab(path)).toThrow(KindleFileError);
  });

  it('rejects a WORDS table without id or word columns', () => {
    const path = buildVocabDb({}, 'CREATE TABLE WORDS (foo TEXT);');
    expect(() => readKindleVocab(path)).toThrow(KindleFileError);
  });

  it('rejects a file that is not SQLite', () => {
    const path = join(dirname(buildVocabDb({}, 'CREATE TABLE X (a);')), 'junk.db');
    writeFileSync(path, 'this is not a database, just some text that is long enough to look like a file');
    expect(() => readKindleVocab(path)).toThrow(KindleFileError);
  });

  it('never writes to the uploaded file', () => {
    const path = buildVocabDb(tablesOf(kindleWord('ubiquitous', { timestamp: T1 })));
    const before = statSync(path).mtimeMs;
    readKindleVocab(path);
    expect(statSync(path).mtimeMs).toBe(before);
  });
});
