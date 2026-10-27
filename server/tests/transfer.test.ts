import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { WordRow } from '../src/db/schema.js';
import { MAX_CONCURRENT_ENRICH } from '../src/audio.js';
import { EXPORT_COLUMNS } from '../src/transfer/format.js';
import { dictionaryEntry, json } from './fakeHttp.js';
import { fakeClock, makeApp } from './helpers.js';

const HEADER = EXPORT_COLUMNS.join(',');

async function setup() {
  const clock = fakeClock('2026-10-27T12:00:00.000Z');
  const written: WordRow[] = [];
  const ctx = makeApp({ now: clock.now, onWordWritten: (w) => written.push(w) });
  ctx.http.handlers.dictionary = ({ url }) => {
    const word = decodeURIComponent(url.pathname.split('/').pop()!);
    return json(dictionaryEntry(`/${word}/`, `https://media.test/${word}.mp3`));
  };
  const token = await ctx.loginAs('olli');

  async function exportAs(format: string | null, t: string | null = token) {
    const headers: Record<string, string> = t ? { Authorization: `Bearer ${t}` } : {};
    const res = await ctx.app.request(`/api/export${format === null ? '' : `?format=${format}`}`, { headers });
    return { status: res.status, headers: res.headers, text: await res.text() };
  }

  async function importFile(content: string | Uint8Array, name: string, opts: { token?: string | null; field?: string } = {}) {
    const form = new FormData();
    form.append(opts.field ?? 'file', new Blob([content as Uint8Array<ArrayBuffer> | string]), name);
    const t = opts.token === undefined ? token : opts.token;
    const headers: Record<string, string> = t ? { Authorization: `Bearer ${t}` } : {};
    const res = await ctx.app.request('/api/import', { method: 'POST', body: form, headers });
    return { status: res.status, body: await res.json() };
  }

  const addWord = (body: Record<string, unknown>, t = token) =>
    ctx.call('POST', '/words', { token: t, body: { definition: 'd', source: 'manual', ...body } });
  const allWords = async (t = token) => (await ctx.call('GET', '/words?status=all', { token: t })).body as any[];

  return { ...ctx, clock, token, written, exportAs, importFile, addWord, allWords };
}

describe('GET /export', () => {
  it('needs a token', async () => {
    const { exportAs } = await setup();
    expect((await exportAs('json', null)).status).toBe(401);
  });

  it('JSON: all live words of the user, learned ones included, in the export shape', async () => {
    const { exportAs, addWord, call, token, loginAs, clock } = await setup();
    const a = await addWord({ word: 'ubiquitous', definition: 'everywhere', example: 'It is ubiquitous.', finnish: 'kaikkialla', ipa: '/ipa/' });
    clock.advance();
    const b = await addWord({ word: 'torpid' });
    await call('PATCH', `/words/${b.body.id}`, { token, body: { status: 'learned', notes: 'slow' } });
    const gone = await addWord({ word: 'deleted' });
    await call('DELETE', `/words/${gone.body.id}`, { token });
    await addWord({ word: 'theirs' }, await loginAs('anna'));

    const res = await exportAs('json');
    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Type')).toMatch(/^application\/json/);
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="vocab-olli-2026-10-27.json"');
    expect(JSON.parse(res.text)).toEqual([
      {
        word: 'ubiquitous',
        definition: 'everywhere',
        example: 'It is ubiquitous.',
        finnish: 'kaikkialla',
        ipa: '/ipa/',
        status: 'learning',
        learned_at: null,
        created_at: a.body.createdAt,
        book_title: null,
        book_author: null,
        notes: null,
      },
      expect.objectContaining({ word: 'torpid', status: 'learned', learned_at: '2026-10-27T12:00:01.000Z', notes: 'slow' }),
    ]);
  });

  it('defaults to JSON and rejects an unknown format', async () => {
    const { exportAs } = await setup();
    expect(JSON.parse((await exportAs(null)).text)).toEqual([]);
    expect((await exportAs('xml')).status).toBe(400);
  });

  it('CSV: BOM, header row, empty cells for nulls, quoting where needed', async () => {
    const { exportAs, addWord } = await setup();
    const a = await addWord({ word: 'naïve', definition: 'simple, "innocent"', finnish: 'naiivi' });

    const res = await exportAs('csv');
    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Type')).toBe('text/csv; charset=utf-8');
    expect(res.headers.get('Content-Disposition')).toBe('attachment; filename="vocab-olli-2026-10-27.csv"');
    // fetch's text() drops the BOM, so check the raw header row and the line after it.
    expect(res.text.split('\r\n')).toEqual([
      HEADER,
      `naïve,"simple, ""innocent""",,naiivi,,learning,,${a.body.createdAt},,,`,
      '',
    ]);
  });

  it('CSV starts with a UTF-8 BOM so spreadsheet apps read ä and ö right', async () => {
    const { app, token } = await setup();
    const res = await app.request('/api/export?format=csv', { headers: { Authorization: `Bearer ${token}` } });
    expect([...new Uint8Array(await res.arrayBuffer()).slice(0, 3)]).toEqual([0xef, 0xbb, 0xbf]);
  });
});

describe('POST /import', () => {
  it('needs a token', async () => {
    const { importFile } = await setup();
    expect((await importFile('[]', 'a.json', { token: null })).status).toBe(401);
  });

  it('CSV: inserts new words as source "import", keeping status, dates, book and notes', async () => {
    const { importFile, allWords, written } = await setup();
    const csv = [
      HEADER,
      'ubiquitous,present everywhere,It is ubiquitous.,kaikkialla,/juːˈbɪkwɪtəs/,learned,2026-01-02T00:00:00.000Z,2026-01-01T00:00:00.000Z,Dune,Frank Herbert,"my note, with comma"',
      'torpid,sluggish,,,,learning,,,,,',
    ].join('\r\n');

    const res = await importFile(csv, 'words.csv');
    expect(res).toEqual({ status: 200, body: { imported: 2, skipped: 0 } });
    const [u, t] = await allWords();
    expect(u).toMatchObject({
      word: 'ubiquitous',
      definition: 'present everywhere',
      example: 'It is ubiquitous.',
      finnish: 'kaikkialla',
      ipa: '/juːˈbɪkwɪtəs/',
      source: 'import',
      status: 'learned',
      learnedAt: '2026-01-02T00:00:00.000Z',
      createdAt: '2026-01-01T00:00:00.000Z',
      bookTitle: 'Dune',
      bookAuthor: 'Frank Herbert',
      notes: 'my note, with comma',
    });
    expect(t).toMatchObject({
      word: 'torpid',
      example: null,
      finnish: null,
      status: 'learning',
      learnedAt: null,
      createdAt: '2026-10-27T12:00:00.000Z',
      updatedAt: '2026-10-27T12:00:00.000Z',
    });
    expect(written.map((w) => w.word)).toEqual(['ubiquitous', 'torpid']);
  });

  it('JSON: same shape as the export', async () => {
    const { importFile, allWords } = await setup();
    const body = JSON.stringify([
      { word: 'Lucid ', definition: 'clear', status: 'learned' },
      { word: 'torpid', definition: 'sluggish', notes: '  ', finnish: null },
    ]);
    expect((await importFile(body, 'export.json')).body).toEqual({ imported: 2, skipped: 0 });
    const [l, t] = await allWords();
    expect(l).toMatchObject({ word: 'lucid', status: 'learned', learnedAt: '2026-10-27T12:00:00.000Z', source: 'import' });
    expect(t).toMatchObject({ word: 'torpid', notes: null, finnish: null });
  });

  it('skips words that already exist (case-insensitively) and repeats within the file', async () => {
    const { importFile, addWord, allWords } = await setup();
    await addWord({ word: 'ubiquitous', definition: 'mine' });
    const csv = `word,definition\nUbiquitous,theirs\nlucid,clear\nlucid,again\n`;
    expect((await importFile(csv, 'w.csv')).body).toEqual({ imported: 1, skipped: 2 });
    const words = await allWords();
    expect(words.map((w) => [w.word, w.definition])).toEqual([
      ['ubiquitous', 'mine'],
      ['lucid', 'clear'],
    ]);
  });

  it('revives a soft-deleted word from the file', async () => {
    const { importFile, addWord, call, token, allWords } = await setup();
    const old = await addWord({ word: 'lucid', definition: 'old' });
    await call('DELETE', `/words/${old.body.id}`, { token });
    expect((await importFile('word,definition\nlucid,new\n', 'w.csv')).body).toEqual({ imported: 1, skipped: 0 });
    expect(await allWords()).toEqual([expect.objectContaining({ id: old.body.id, definition: 'new', source: 'import', deletedAt: null })]);
  });

  it('a status of "learning" drops learned_at; a blank status means learning', async () => {
    const { importFile, allWords } = await setup();
    const csv = `word,definition,status,learned_at\na,d,learning,2026-01-01T00:00:00Z\nb,d,,\n`;
    await importFile(csv, 'w.csv');
    expect((await allWords()).map((w) => [w.status, w.learnedAt])).toEqual([
      ['learning', null],
      ['learning', null],
    ]);
  });

  it('accepts columns in any order and ignores unknown ones', async () => {
    const { importFile, allWords } = await setup();
    await importFile('extra,definition,word\nx,clear,lucid\n', 'w.csv');
    expect(await allWords()).toEqual([expect.objectContaining({ word: 'lucid', definition: 'clear' })]);
  });

  it('is case-insensitive about the file extension', async () => {
    const { importFile } = await setup();
    expect((await importFile('word,definition\na,b\n', 'W.CSV')).body).toEqual({ imported: 1, skipped: 0 });
  });

  it.each([
    ['definition\nclear\n', 'w.csv', 'missing column: word'],
    ['word\nlucid\n', 'w.csv', 'missing column: definition'],
    ['word,definition\nlucid,clear\n,clear\n', 'w.csv', 'row 3: word is required'],
    ['word,definition\nlucid,\n', 'w.csv', 'row 2: definition is required'],
    ['word,definition,status\nlucid,clear,known\n', 'w.csv', 'row 2: status must be "learning" or "learned"'],
    ['word,definition,created_at\nlucid,clear,yesterday\n', 'w.csv', 'row 2: created_at must be an ISO-8601 timestamp'],
    ['word,definition\nlucid,"clear\n', 'w.csv', 'not a valid CSV file: unterminated quoted field'],
    ['word,definition\nlucid,clear,extra\n', 'w.csv', 'row 2: has 3 fields, the header has 2'],
    ['', 'w.csv', 'the file is empty'],
    ['{"word":"lucid"}', 'w.json', 'expected a JSON array of words'],
    ['[{"word":"lucid","definition":"clear"},{"word":"x"}]', 'w.json', 'item 2: definition is required'],
    ['[{"word":"lucid","definition":7}]', 'w.json', 'item 1: definition must be text'],
    ['[1]', 'w.json', 'item 1: expected an object'],
    ['[{', 'w.json', 'not a valid JSON file'],
    ['word,definition', 'w.txt', 'expected a .csv or .json file'],
  ])('rejects %j (%s) with 400 and imports nothing', async (content, name, error) => {
    const { importFile, allWords } = await setup();
    expect(await importFile(content, name)).toEqual({ status: 400, body: { error } });
    expect(await allWords()).toEqual([]);
  });

  it('requires a file field', async () => {
    const { importFile } = await setup();
    expect(await importFile('[]', 'a.json', { field: 'other' })).toEqual({ status: 400, body: { error: 'file is required' } });
  });

  it('rejects files over 10 MB with 413', async () => {
    const { importFile } = await setup();
    const res = await importFile(new Uint8Array(10 * 1024 * 1024 + 1), 'big.json');
    expect(res.status).toBe(413);
  });

  it('round-trips: a CSV export imported by another user exports the same', async () => {
    const { exportAs, importFile, addWord, call, token, loginAs } = await setup();
    await addWord({ word: 'naïve', definition: 'simple, "innocent"', example: 'line 1\nline 2', ipa: '/naɪˈiːv/' });
    const b = await addWord({ word: 'torpid', definition: 'sluggish' });
    await call('PATCH', `/words/${b.body.id}`, { token, body: { status: 'learned', notes: 'n' } });
    const csv = (await exportAs('csv')).text;
    const jsonText = (await exportAs('json')).text;

    const anna = await loginAs('anna');
    expect((await importFile(csv, 'x.csv', { token: anna })).body).toEqual({ imported: 2, skipped: 0 });
    expect((await exportAs('csv', anna)).text).toBe(csv);
    expect((await exportAs('json', anna)).text).toBe(jsonText);
  });
});

describe('dictionary lookup after import', () => {
  it('fills missing IPA and audio for imported words, without asking Claude', async () => {
    const { importFile, audio, allWords, dataDir, http, clock, written } = await setup();
    await importFile('word,definition,ipa\nlucid,clear,\ntorpid,sluggish,/mine/\n', 'w.csv');
    clock.advance();
    await audio.whenIdle();

    const [l, t] = await allWords();
    expect(l).toMatchObject({ ipa: '/lucid/', hasAudio: true, updatedAt: '2026-10-27T12:00:01.000Z' });
    expect(t).toMatchObject({ ipa: '/mine/', hasAudio: true });
    expect(existsSync(join(dataDir, 'audio', `${l.id}.mp3`))).toBe(true);
    expect(http.claudeRequests()).toEqual([]);
    expect(written.at(-1)).toMatchObject({ audioPath: expect.stringMatching(/^audio\/\d+\.mp3$/) });
  });

  it('does not look up skipped words', async () => {
    const { importFile, addWord, audio, http } = await setup();
    await addWord({ word: 'lucid', ipa: '/x/' });
    await audio.whenIdle();
    const before = http.requests.length;
    await importFile('word,definition\nlucid,clear\n', 'w.csv');
    await audio.whenIdle();
    expect(http.requests.length).toBe(before);
  });

  it('a dictionary miss leaves the word as it is', async () => {
    const { importFile, audio, allWords, http, written } = await setup();
    http.handlers.dictionary = () => json({ title: 'No Definitions Found' }, 404);
    await importFile('word,definition\nlucid,clear\n', 'w.csv');
    await audio.whenIdle();
    expect(await allWords()).toEqual([expect.objectContaining({ ipa: null, hasAudio: false })]);
    // Only the insert itself; no empty write that would bump updated_at.
    expect(written).toHaveLength(1);
  });

  it('keeps the IPA when the audio download fails', async () => {
    const { importFile, audio, allWords, http } = await setup();
    http.handlers.media = () => new Response('gone', { status: 404 });
    await importFile('word,definition\nlucid,clear\n', 'w.csv');
    await audio.whenIdle();
    expect(await allWords()).toEqual([expect.objectContaining({ ipa: '/lucid/', hasAudio: false })]);
  });

  it('skips a word deleted before its turn came', async () => {
    const { importFile, audio, allWords, call, token, http } = await setup();
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    const original = http.handlers.dictionary;
    http.handlers.dictionary = async (req) => (await gate, original(req));
    const words = Array.from({ length: MAX_CONCURRENT_ENRICH + 1 }, (_, i) => `w${i},d`);
    await importFile(`word,definition\n${words.join('\n')}\n`, 'w.csv');
    const last = (await allWords()).at(-1);
    await call('DELETE', `/words/${last.id}`, { token });
    release();
    await audio.whenIdle();
    const dictionaryCalls = http.dictionaryRequests();
    expect(dictionaryCalls).toHaveLength(MAX_CONCURRENT_ENRICH);
  });

  it(`asks the dictionary for at most ${MAX_CONCURRENT_ENRICH} words at a time`, async () => {
    const { importFile, audio, http } = await setup();
    let active = 0;
    let peak = 0;
    const original = http.handlers.dictionary;
    http.handlers.dictionary = async (req) => {
      peak = Math.max(peak, ++active);
      await new Promise((r) => setTimeout(r, 5));
      active--;
      return original(req);
    };
    const words = Array.from({ length: 8 }, (_, i) => `w${i},d`);
    await importFile(`word,definition\n${words.join('\n')}\n`, 'w.csv');
    await audio.whenIdle();
    expect(peak).toBe(MAX_CONCURRENT_ENRICH);
    expect(http.dictionaryRequests()).toHaveLength(8);
  });
});
