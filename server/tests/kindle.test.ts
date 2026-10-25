import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { WordRow } from '../src/db/schema.js';
import { claudeJson, json } from './fakeHttp.js';
import { fakeClock, makeApp } from './helpers.js';
import { kindleWord, tablesOf, vocabDbBytes, type FixtureTables } from './kindleFixture.js';

const T1 = Date.parse('2026-01-01T10:00:00Z');
const T2 = Date.parse('2026-02-01T10:00:00Z');
const T3 = Date.parse('2026-03-01T10:00:00Z');

/** Claude answers every word with a definition derived from the word itself. */
const claudeByWord = (req: { body: any }) => {
  const word = req.body.messages[0].content as string;
  return claudeJson({ candidates: [{ definition: `def of ${word}`, example: `Example for ${word}.`, finnish: `fi-${word}` }] });
};

const library = () =>
  tablesOf(
    kindleWord('ubiquitous', { timestamp: T1, usage: 'Sensors were ubiquitous.', book: 'b1' }),
    kindleWord('torpid', { timestamp: T2, usage: 'A torpid afternoon.', book: 'b2', category: 1 }),
  );

async function setup() {
  const clock = fakeClock('2026-10-25T12:00:00.000Z');
  const written: WordRow[] = [];
  const ctx = makeApp({ now: clock.now, onWordWritten: (w) => written.push(w) });
  ctx.http.handlers.claude = claudeByWord;
  const token = await ctx.loginAs('olli');

  async function upload(file: Uint8Array | string, opts: { onlyNew?: string; token?: string | null; field?: string } = {}) {
    const form = new FormData();
    form.append(opts.field ?? 'file', new Blob([file as Uint8Array<ArrayBuffer> | string]), 'vocab.db');
    if (opts.onlyNew !== undefined) form.append('onlyNew', opts.onlyNew);
    const headers: Record<string, string> = {};
    const t = opts.token === undefined ? token : opts.token;
    if (t) headers.Authorization = `Bearer ${t}`;
    const res = await ctx.app.request('/api/import/kindle', { method: 'POST', body: form, headers });
    return { status: res.status, body: await res.json() };
  }
  const preview = async (tables: FixtureTables, opts?: Parameters<typeof upload>[1]) => {
    const res = await upload(vocabDbBytes(tables), opts);
    expect(res.status).toBe(200);
    return res.body as { importId: string; candidates: any[] };
  };
  const confirm = (importId: string, body: unknown, t = token) =>
    ctx.call('POST', `/import/kindle/${importId}/confirm`, { token: t, body });
  const progress = (importId: string, t = token) => ctx.call('GET', `/import/kindle/${importId}`, { token: t });
  /** Confirms and waits for the background job. */
  async function runImport(importId: string, body: unknown) {
    const res = await confirm(importId, body);
    expect(res.status).toBe(202);
    await ctx.kindle.whenIdle();
    return (await progress(importId)).body;
  }
  const allWords = async () => (await ctx.call('GET', '/words?status=all', { token })).body as any[];

  return { ...ctx, clock, token, written, upload, preview, confirm, progress, runImport, allWords };
}

describe('POST /import/kindle (preview)', () => {
  it('returns candidates with sentence, book, category and exists flag; nothing is inserted', async () => {
    const { preview, call, token, allWords } = await setup();
    await call('POST', '/words', { token, body: { word: 'torpid', definition: 'sluggish', source: 'manual' } });

    const { importId, candidates } = await preview(library());
    expect(importId).toMatch(/^[0-9a-f]{32}$/);
    expect(candidates).toEqual([
      { word: 'torpid', usage: 'A torpid afternoon.', bookTitle: 'Second Book', bookAuthor: 'Bo Author', kindleCategory: 'mastered', exists: true },
      { word: 'ubiquitous', usage: 'Sensors were ubiquitous.', bookTitle: 'The Sensor Age', bookAuthor: 'Ada Writer', kindleCategory: 'learning', exists: false },
    ]);
    expect((await allWords()).map((w) => w.word)).toEqual(['torpid']);
  });

  it('keeps the upload as data/tmp/<importId>.db', async () => {
    const { preview, dataDir } = await setup();
    const { importId } = await preview(library());
    expect(existsSync(join(dataDir, 'tmp', `${importId}.db`))).toBe(true);
  });

  it('a soft-deleted word does not count as existing', async () => {
    const { preview, call, token } = await setup();
    const created = await call('POST', '/words', { token, body: { word: 'torpid', definition: 'sluggish', source: 'manual' } });
    await call('DELETE', `/words/${created.body.id}`, { token });
    const { candidates } = await preview(library());
    expect(candidates.find((c) => c.word === 'torpid').exists).toBe(false);
  });

  it('rejects a file without a WORDS table with 400 and does not keep it', async () => {
    const { upload, dataDir } = await setup();
    const res = await upload(vocabDbBytes({}, 'CREATE TABLE OTHER (a);'));
    expect(res.status).toBe(400);
    expect(res.body.error).toMatch(/WORDS/);
    expect(readdirSync(join(dataDir, 'tmp'))).toEqual([]);
  });

  it('rejects a non-SQLite file with 400', async () => {
    const { upload } = await setup();
    expect((await upload('just text, not a database at all, long enough to be a file')).status).toBe(400);
  });

  it('rejects a request without the file field with 400', async () => {
    const { upload } = await setup();
    expect((await upload(vocabDbBytes(library()), { field: 'other' })).status).toBe(400);
  });

  it('rejects uploads over 50 MB with 413', async () => {
    const { upload } = await setup();
    const res = await upload(new Uint8Array(50 * 1024 * 1024 + 1));
    expect(res.status).toBe(413);
  });

  it('requires auth', async () => {
    const { upload } = await setup();
    expect((await upload(vocabDbBytes(library()), { token: null })).status).toBe(401);
  });

  it('rejects an invalid onlyNew value with 400', async () => {
    const { upload } = await setup();
    expect((await upload(vocabDbBytes(library()), { onlyNew: 'maybe' })).status).toBe(400);
  });
});

describe('POST /import/kindle/:importId/confirm', () => {
  it('looks each word up with the sentence as context and inserts it as a kindle word', async () => {
    const { preview, runImport, allWords, http, audio } = await setup();
    const { importId } = await preview(library());

    const status = await runImport(importId, { words: ['ubiquitous'], markMasteredAsLearned: true });
    expect(status).toEqual({ state: 'finished', done: 1, total: 1, imported: 1, skipped: 0, errors: [] });

    const [claudeReq] = http.claudeRequests();
    expect(claudeReq.body.messages[0].content).toBe('ubiquitous');
    expect(claudeReq.body.system).toContain('The user saw the word in this sentence: "Sensors were ubiquitous."');

    await audio.whenIdle();
    const [w] = await allWords();
    expect(w).toMatchObject({
      word: 'ubiquitous',
      definition: 'def of ubiquitous',
      example: 'Sensors were ubiquitous.',
      finnish: 'fi-ubiquitous',
      ipa: '/ipa/',
      hasAudio: true,
      source: 'kindle',
      status: 'learning',
      learnedAt: null,
    });
  });

  it('stores the book and the Kindle lookup time, and notifies the exporter', async () => {
    const { preview, runImport, db, written } = await setup();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['ubiquitous'] });
    const row = db.query.words.findFirst().sync()!;
    expect(row).toMatchObject({ bookTitle: 'The Sensor Age', bookAuthor: 'Ada Writer', kindleLookupAt: new Date(T1).toISOString() });
    expect(written.some((w) => w.word === 'ubiquitous' && w.source === 'kindle')).toBe(true);
  });

  it('imports mastered words as learned by default (markMasteredAsLearned defaults to true)', async () => {
    const { preview, runImport, allWords, clock } = await setup();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['torpid', 'ubiquitous'] });
    const words = await allWords();
    expect(words.find((w) => w.word === 'torpid')).toMatchObject({ status: 'learned', learnedAt: clock.now() });
    expect(words.find((w) => w.word === 'ubiquitous')).toMatchObject({ status: 'learning', learnedAt: null });
  });

  it('imports mastered words as learning when markMasteredAsLearned is false', async () => {
    const { preview, runImport, allWords } = await setup();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['torpid'], markMasteredAsLearned: false });
    expect((await allWords())[0]).toMatchObject({ word: 'torpid', status: 'learning' });
  });

  it('looks a word without a sentence up without context and keeps Claude\'s example', async () => {
    const { preview, runImport, allWords, http, db } = await setup();
    const { importId } = await preview({
      WORDS: [{ id: 'en:torpid', word: 'torpid', stem: 'torpid', lang: 'en', category: 0, timestamp: T1 }],
    });
    await runImport(importId, { words: ['torpid'] });
    expect(http.claudeRequests()[0].body.system).not.toContain('The user saw the word');
    expect((await allWords())[0]).toMatchObject({ example: 'Example for torpid.' });
    expect(db.query.words.findFirst().sync()).toMatchObject({ bookTitle: null, bookAuthor: null, kindleLookupAt: null });
  });

  it('skips existing words without overwriting them', async () => {
    const { preview, runImport, allWords, call, token, http } = await setup();
    await call('POST', '/words', { token, body: { word: 'ubiquitous', definition: 'mine', source: 'manual' } });
    const { importId } = await preview(library());
    const status = await runImport(importId, { words: ['ubiquitous'] });
    expect(status).toMatchObject({ done: 1, total: 1, imported: 0, skipped: 1, errors: [] });
    expect((await allWords())[0]).toMatchObject({ definition: 'mine', source: 'manual' });
    expect(http.claudeRequests()).toHaveLength(0);
  });

  it('marks an existing learning word learned when Kindle says mastered and the flag is on', async () => {
    const { preview, runImport, allWords, call, token, clock } = await setup();
    await call('POST', '/words', { token, body: { word: 'torpid', definition: 'mine', source: 'manual' } });
    clock.advance();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['torpid'] });
    expect((await allWords())[0]).toMatchObject({ definition: 'mine', status: 'learned', learnedAt: clock.now(), updatedAt: clock.now() });
  });

  it('leaves an existing learning word alone when the flag is off', async () => {
    const { preview, runImport, allWords, call, token } = await setup();
    await call('POST', '/words', { token, body: { word: 'torpid', definition: 'mine', source: 'manual' } });
    const { importId } = await preview(library());
    await runImport(importId, { words: ['torpid'], markMasteredAsLearned: false });
    expect((await allWords())[0]).toMatchObject({ status: 'learning' });
  });

  it('keeps the original learnedAt of a word that is already learned', async () => {
    const { preview, runImport, allWords, call, token, clock } = await setup();
    const created = await call('POST', '/words', { token, body: { word: 'torpid', definition: 'mine', source: 'manual' } });
    const learned = await call('PATCH', `/words/${created.body.id}`, { token, body: { status: 'learned' } });
    clock.advance();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['torpid'] });
    expect((await allWords())[0]).toMatchObject({ learnedAt: learned.body.learnedAt, updatedAt: learned.body.updatedAt });
  });

  it('revives a soft-deleted word as a fresh kindle word', async () => {
    const { preview, runImport, allWords, call, token } = await setup();
    const created = await call('POST', '/words', { token, body: { word: 'ubiquitous', definition: 'old', source: 'manual' } });
    await call('DELETE', `/words/${created.body.id}`, { token });
    const { importId } = await preview(library());
    await runImport(importId, { words: ['ubiquitous'] });
    expect(await allWords()).toMatchObject([{ id: created.body.id, definition: 'def of ubiquitous', source: 'kindle', deletedAt: null }]);
  });

  it('lists not_english and failed lookups in errors and skips them', async () => {
    const { preview, runImport, allWords, http } = await setup();
    http.handlers.claude = (req) => {
      const word = req.body.messages[0].content;
      if (word === 'torpid') return claudeJson({ error: 'not_english', suggestion: null });
      if (word === 'ubiquitous') return json({ type: 'error', error: { type: 'api_error', message: 'boom' } }, 500);
      return claudeByWord(req);
    };
    const { importId } = await preview(tablesOf(library(), kindleWord('lucid', { timestamp: T3 })));
    const status = await runImport(importId, { words: ['torpid', 'ubiquitous', 'lucid'] });
    expect(status).toMatchObject({ state: 'finished', done: 3, total: 3, imported: 1, skipped: 0 });
    expect(status.errors).toEqual(
      expect.arrayContaining([
        { word: 'torpid', error: 'not_english' },
        { word: 'ubiquitous', error: 'lookup failed' },
      ]),
    );
    expect((await allWords()).map((w) => w.word)).toEqual(['lucid']);
  });

  it('counts a word added by hand while the job ran as skipped, keeping the hand-made one', async () => {
    const { preview, runImport, allWords, http, call, token } = await setup();
    http.handlers.claude = async (req) => {
      await call('POST', '/words', { token, body: { word: 'ubiquitous', definition: 'mine', source: 'manual' } });
      return claudeByWord(req);
    };
    const { importId } = await preview(library());
    const status = await runImport(importId, { words: ['ubiquitous'] });
    expect(status).toMatchObject({ done: 1, imported: 0, skipped: 1, errors: [] });
    expect((await allWords())[0]).toMatchObject({ definition: 'mine', source: 'manual' });
  });

  it('reports words that are not in the upload as errors', async () => {
    const { preview, runImport } = await setup();
    const { importId } = await preview(library());
    const status = await runImport(importId, { words: ['ubiquitous', 'Nonexistent'] });
    expect(status).toMatchObject({ done: 2, total: 2, imported: 1, errors: [{ word: 'nonexistent', error: 'not in upload' }] });
  });

  it('runs in the background: confirm returns 202 before the lookups finish, progress shows running', async () => {
    const { preview, confirm, progress, http, kindle } = await setup();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    http.handlers.claude = async (req) => {
      await gate;
      return claudeByWord(req);
    };
    const { importId } = await preview(library());
    expect((await confirm(importId, { words: ['ubiquitous', 'torpid'] })).status).toBe(202);
    expect((await progress(importId)).body).toEqual({ state: 'running', done: 0, total: 2, imported: 0, skipped: 0, errors: [] });
    release();
    await kindle.whenIdle();
    expect((await progress(importId)).body).toMatchObject({ state: 'finished', done: 2, total: 2 });
  });

  it('keeps at most 5 Claude requests in flight', async () => {
    const { preview, runImport, http } = await setup();
    let inFlight = 0;
    let peak = 0;
    http.handlers.claude = async (req) => {
      inFlight++;
      peak = Math.max(peak, inFlight);
      await new Promise((r) => setTimeout(r, 5));
      inFlight--;
      return claudeByWord(req);
    };
    const many = Array.from({ length: 12 }, (_, i) => kindleWord(`word${i}`, { timestamp: T1 + i }));
    const { importId } = await preview(tablesOf(...many));
    const status = await runImport(importId, { words: many.map((_, i) => `word${i}`) });
    expect(status).toMatchObject({ done: 12, imported: 12 });
    expect(peak).toBe(5);
  });

  it('records a kindle_imports row and removes the temp file at the end', async () => {
    const { preview, runImport, db, dataDir, clock } = await setup();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['ubiquitous'] });
    expect(db.query.kindleImports.findMany().sync()).toMatchObject([
      { importedAt: clock.now(), wordsNew: 1, wordsSkipped: 0, lastKindleTimestamp: T2 },
    ]);
    expect(existsSync(join(dataDir, 'tmp', `${importId}.db`))).toBe(false);
  });

  it('rejects a second confirm with 409', async () => {
    const { preview, runImport, confirm } = await setup();
    const { importId } = await preview(library());
    await runImport(importId, { words: ['ubiquitous'] });
    expect((await confirm(importId, { words: ['torpid'] })).status).toBe(409);
  });

  it('validates the body', async () => {
    const { preview, confirm } = await setup();
    const { importId } = await preview(library());
    expect((await confirm(importId, { words: [] })).status).toBe(400);
    expect((await confirm(importId, { words: 'ubiquitous' })).status).toBe(400);
    expect((await confirm(importId, { words: ['x'], markMasteredAsLearned: 'yes' })).status).toBe(400);
  });

  it('returns 404 for an unknown import, and for another user\'s import', async () => {
    const { preview, confirm, progress, loginAs } = await setup();
    const { importId } = await preview(library());
    const other = await loginAs('anna');
    expect((await confirm(importId, { words: ['ubiquitous'] }, other)).status).toBe(404);
    expect((await progress(importId, other)).status).toBe(404);
    expect((await confirm('0'.repeat(32), { words: ['ubiquitous'] })).status).toBe(404);
    expect((await progress('../../vocab')).status).toBe(404);
  });

  it('expires an unconfirmed upload after 1 hour and deletes the temp file', async () => {
    const { preview, confirm, progress, upload, clock, dataDir } = await setup();
    const { importId } = await preview(library());
    clock.advance(60 * 60 * 1000 - 1);
    expect((await progress(importId)).status).toBe(200);
    clock.advance(2);
    expect((await confirm(importId, { words: ['ubiquitous'] })).status).toBe(404);
    await upload(vocabDbBytes(library())); // any import request sweeps
    expect(existsSync(join(dataDir, 'tmp', `${importId}.db`))).toBe(false);
  });
});

describe('GET /import/kindle/:importId', () => {
  it('shows an unconfirmed import as preview', async () => {
    const { preview, progress } = await setup();
    const { importId } = await preview(library());
    expect((await progress(importId)).body).toEqual({ state: 'preview', done: 0, total: 0, imported: 0, skipped: 0, errors: [] });
  });
});

describe('incremental import', () => {
  it('with onlyNew (default) previews only words newer than the last import', async () => {
    const { preview, runImport } = await setup();
    const first = await preview(library());
    await runImport(first.importId, { words: ['ubiquitous'] }); // torpid deselected, but seen

    const later = tablesOf(library(), kindleWord('lucid', { timestamp: T3 }));
    const second = await preview(later);
    expect(second.candidates.map((c) => c.word)).toEqual(['lucid']);
  });

  it('a failed lookup holds the cutoff back so the word comes back next time; not_english does not', async () => {
    const { preview, runImport, http } = await setup();
    http.handlers.claude = (req) => {
      const word = req.body.messages[0].content;
      if (word === 'ubiquitous') return json({ type: 'error', error: { type: 'overloaded_error', message: 'busy' } }, 529);
      if (word === 'lucid') return claudeJson({ error: 'not_english', suggestion: null });
      return claudeByWord(req);
    };
    const tables = tablesOf(library(), kindleWord('lucid', { timestamp: T3 }));
    const first = await preview(tables);
    await runImport(first.importId, { words: ['ubiquitous', 'torpid', 'lucid'] });

    // ubiquitous (T1) failed: everything from T1 on is offered again; torpid now exists.
    const second = await preview(tables);
    expect(second.candidates.map((c) => [c.word, c.exists])).toEqual([
      ['lucid', false],
      ['torpid', true],
      ['ubiquitous', false],
    ]);
  });

  it('with onlyNew=false previews everything again, flagging what exists', async () => {
    const { preview, runImport } = await setup();
    const first = await preview(library());
    await runImport(first.importId, { words: ['ubiquitous'] });

    const second = await preview(tablesOf(library(), kindleWord('lucid', { timestamp: T3 })), { onlyNew: 'false' });
    expect(second.candidates.map((c) => [c.word, c.exists])).toEqual([
      ['lucid', false],
      ['torpid', false],
      ['ubiquitous', true],
    ]);
  });

  it('the last timestamp never moves backwards, and is per user', async () => {
    const { preview, runImport, loginAs, upload } = await setup();
    const first = await preview(tablesOf(kindleWord('lucid', { timestamp: T3 })));
    await runImport(first.importId, { words: ['lucid'] });
    // An older device backup: nothing in it is newer than T3.
    const older = await preview(library(), { onlyNew: 'false' });
    await runImport(older.importId, { words: ['ubiquitous'] });
    // Newer than the backup's T2, older than the first import's T3: already seen.
    const third = await preview(tablesOf(library(), kindleWord('middle', { timestamp: T2 + 1 })));
    expect(third.candidates).toEqual([]);

    const anna = await loginAs('anna');
    const annas = await upload(vocabDbBytes(library()), { token: anna });
    expect(annas.body.candidates).toHaveLength(2);
  });
});
