import { eq } from 'drizzle-orm';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, describe, expect, it, onTestFinished, vi } from 'vitest';
import { words } from '../src/db/schema.js';
import { renderBookNote, renderIndex, renderWordNote } from '../src/obsidian/render.js';
import { makeApp } from './helpers.js';

const torpid = { word: 'torpid', definition: 'mentally or physically inactive', example: 'A torpid afternoon.', finnish: 'veltto', source: 'manual' };
const ubiquitous = { word: 'ubiquitous', definition: 'present or found everywhere', source: 'claude' };

function vault() {
  const dir = mkdtempSync(join(tmpdir(), 'vault-'));
  onTestFinished(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

async function setup(opts: { obsidian?: boolean } = {}) {
  const dir = opts.obsidian === false ? null : vault();
  const ctx = makeApp({ obsidianDir: dir });
  const token = await ctx.loginAs('olli');
  const userDir = dir ? join(dir, 'Vocab', 'olli') : '';
  const read = (rel: string) => readFileSync(join(userDir, rel), 'utf8');
  const has = (rel: string) => existsSync(join(userDir, rel));
  const add = async (body: object, t = token) => {
    const res = await ctx.call('POST', '/words', { token: t, body });
    expect(res.status).toBe(201);
    return res.body;
  };
  const row = (id: number) => ctx.db.select().from(words).where(eq(words.id, id)).get()!;
  /** Puts a word on a Kindle book, as the Kindle import does. */
  const setBook = (id: number, bookTitle: string | null, bookAuthor: string | null = null) =>
    ctx.db.update(words).set({ bookTitle, bookAuthor }).where(eq(words.id, id)).run();
  return { ...ctx, dir, userDir, token, read, has, add, row, setBook };
}

describe('Obsidian export of single words', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('writes Words/<word>.md and index.md 2 s after the last write', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const { add, has, read, row, obsidian } = await setup();
    const created = await add(torpid);

    await vi.advanceTimersByTimeAsync(1999);
    await obsidian.whenIdle();
    expect(has('Words/torpid.md')).toBe(false);
    await vi.advanceTimersByTimeAsync(1);
    await obsidian.whenIdle();
    expect(read('Words/torpid.md')).toBe(renderWordNote(row(created.id), 'olli'));
    expect(read('index.md')).toBe(renderIndex('olli', { learning: 1, learned: 0 }));
  });

  it('restarts the 2 s wait on every write, but flushes after 10 s at the latest', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const { add, has, obsidian } = await setup();
    await add(torpid);
    await vi.advanceTimersByTimeAsync(1500);
    await add(ubiquitous);
    await vi.advanceTimersByTimeAsync(1500);
    await obsidian.whenIdle();
    expect(has('Words/torpid.md')).toBe(false);
    await vi.advanceTimersByTimeAsync(500);
    await obsidian.whenIdle();
    expect(has('Words/torpid.md') && has('Words/ubiquitous.md')).toBe(true);

    // A steady stream of writes (a Kindle import) still gets exported.
    for (let i = 0; i < 7; i++) {
      await add({ word: `stream${i}`, definition: 'd', source: 'manual' });
      await vi.advanceTimersByTimeAsync(1500);
    }
    await obsidian.whenIdle();
    expect(has('Words/stream0.md')).toBe(true);
  });

  it('rewrites the note when the word changes', async () => {
    const { add, call, token, read, obsidian } = await setup();
    const created = await add(torpid);
    await call('PATCH', `/words/${created.id}`, { token, body: { status: 'learned' } });
    await obsidian.flush();
    expect(read('Words/torpid.md')).toContain('\ntags: [vocab, vocab/learned]\n');
    expect(read('index.md')).toContain('- Learning: 0\n- Learned: 1\n');
  });

  it('deletes the note of a deleted word and drops it from its book', async () => {
    const { add, call, token, has, read, obsidian, setBook, row } = await setup();
    const a = await add(torpid);
    const b = await add(ubiquitous);
    setBook(a.id, 'The Sensor Age', 'Ada Writer');
    setBook(b.id, 'The Sensor Age', 'Ada Writer');
    await call('PATCH', `/words/${a.id}`, { token, body: { definition: 'sluggish' } });
    await call('PATCH', `/words/${b.id}`, { token, body: { definition: 'everywhere' } });
    await obsidian.flush();
    expect(read('Books/The Sensor Age.md')).toContain('[[Vocab/olli/Words/torpid|torpid]]');

    await call('DELETE', `/words/${a.id}`, { token });
    await obsidian.flush();
    expect(has('Words/torpid.md')).toBe(false);
    expect(read('Books/The Sensor Age.md')).toBe(
      renderBookNote({ title: 'The Sensor Age', author: 'Ada Writer' }, [row(b.id)], 'olli'),
    );

    await call('DELETE', `/words/${b.id}`, { token });
    await obsidian.flush();
    expect(has('Books/The Sensor Age.md')).toBe(false);
    expect(read('index.md')).toContain('- Total: 0\n');
  });

  it('moves a word to its new book note when the book changes', async () => {
    const { add, call, token, has, read, obsidian, setBook } = await setup();
    const a = await add(torpid);
    setBook(a.id, 'Old Book');
    await call('PATCH', `/words/${a.id}`, { token, body: { definition: 'x' } });
    await obsidian.flush();
    setBook(a.id, 'New Book');
    await call('PATCH', `/words/${a.id}`, { token, body: { definition: 'y' } });
    await obsidian.flush();
    expect(has('Books/Old Book.md')).toBe(false);
    expect(read('Books/New Book.md')).toContain('|torpid]]');
  });

  it('sanitises book titles and words into file names inside the user folder', async () => {
    const { add, call, token, dir, has, obsidian, setBook } = await setup();
    const evil = await add({ word: '../../escape', definition: 'd', source: 'manual' });
    setBook(evil.id, 'Part 1/2: "Quotes"');
    await call('PATCH', `/words/${evil.id}`, { token, body: { definition: 'e' } });
    await obsidian.flush();
    expect(has('Words/-..-escape.md')).toBe(true);
    expect(has('Books/Part 1-2- -Quotes-.md')).toBe(true);
    expect(readdirSync(dir!).sort()).toEqual(['Vocab']);
    expect(readdirSync(join(dir!, 'Vocab'))).toEqual(['olli']);
  });

  it('keeps users in separate folders', async () => {
    const { add, loginAs, obsidian, dir, read } = await setup();
    await add(torpid);
    const anna = await loginAs('anna');
    await add({ ...torpid, definition: 'annas definition' }, anna);
    await obsidian.flush();
    expect(read('Words/torpid.md')).toContain('mentally or physically inactive');
    expect(readFileSync(join(dir!, 'Vocab', 'anna', 'Words', 'torpid.md'), 'utf8')).toContain('annas definition');
  });

  it('leaves no temp files behind', async () => {
    const { add, obsidian, userDir } = await setup();
    await add(torpid);
    await obsidian.flush();
    expect(readdirSync(join(userDir, 'Words'))).toEqual(['torpid.md']);
    expect(readdirSync(userDir).sort()).toEqual(['Words', 'index.md']);
  });

  it('a failing export is logged and does not break the API', async () => {
    const { add, dir, obsidian } = await setup();
    const err = vi.spyOn(console, 'error').mockImplementation(() => {});
    writeFileSync(join(dir!, 'Vocab'), 'a file where the folder should be');
    await add(torpid);
    await obsidian.flush();
    expect(err).toHaveBeenCalled();
    err.mockRestore();
  });
});

describe('POST /obsidian/export-all', () => {
  it('rebuilds the user folder: restores edited or missing notes, removes stray notes', async () => {
    const { add, call, token, read, has, row, userDir, obsidian, setBook } = await setup();
    const a = await add(torpid);
    const b = await add(ubiquitous);
    setBook(a.id, 'The Sensor Age', 'Ada Writer');
    await obsidian.flush();
    writeFileSync(join(userDir, 'Words', 'torpid.md'), 'edited by hand');
    rmSync(join(userDir, 'Words', 'ubiquitous.md'));
    writeFileSync(join(userDir, 'Words', 'gone.md'), 'a word deleted while the server was down');
    writeFileSync(join(userDir, 'Words', 'my-own.txt'), 'not a note');

    const res = await call('POST', '/obsidian/export-all', { token });
    expect(res).toEqual({ status: 200, body: { words: 2, books: 1 } });
    expect(read('Words/torpid.md')).toBe(renderWordNote(row(a.id), 'olli'));
    expect(read('Words/ubiquitous.md')).toBe(renderWordNote(row(b.id), 'olli'));
    expect(has('Words/gone.md')).toBe(false);
    expect(has('Words/my-own.txt')).toBe(true);
    expect(read('Books/The Sensor Age.md')).toContain('|torpid]]');
  });

  it('never touches anything outside Vocab/<user>/', async () => {
    const { add, call, token, dir, loginAs } = await setup();
    mkdirSync(join(dir!, 'Daily'), { recursive: true });
    writeFileSync(join(dir!, 'Daily', 'today.md'), 'mine');
    mkdirSync(join(dir!, 'Vocab', 'anna', 'Words'), { recursive: true });
    writeFileSync(join(dir!, 'Vocab', 'anna', 'Words', 'stray.md'), 'annas folder');
    writeFileSync(join(dir!, 'Vocab', 'README.md'), 'mine too');
    await loginAs('anna');
    await add(torpid);

    await call('POST', '/obsidian/export-all', { token });
    expect(readFileSync(join(dir!, 'Daily', 'today.md'), 'utf8')).toBe('mine');
    expect(readFileSync(join(dir!, 'Vocab', 'anna', 'Words', 'stray.md'), 'utf8')).toBe('annas folder');
    expect(readFileSync(join(dir!, 'Vocab', 'README.md'), 'utf8')).toBe('mine too');
  });

  it('exports soft-deleted words as absent', async () => {
    const { add, call, token, has } = await setup();
    const a = await add(torpid);
    await call('DELETE', `/words/${a.id}`, { token });
    await call('POST', '/obsidian/export-all', { token });
    expect(has('Words/torpid.md')).toBe(false);
    expect(has('index.md')).toBe(true);
  });

  it('requires auth', async () => {
    const { call } = await setup();
    expect((await call('POST', '/obsidian/export-all')).status).toBe(401);
  });
});

describe('Obsidian export disabled (OBSIDIAN_DIR unset)', () => {
  it('reports disabled, refuses export-all with 503 and ignores word writes', async () => {
    const { call, token, add, obsidian } = await setup({ obsidian: false });
    expect((await call('GET', '/obsidian/status', { token })).body).toEqual({ enabled: false });
    expect((await call('POST', '/obsidian/export-all', { token })).status).toBe(503);
    await add(torpid);
    await obsidian.flush();
  });

  it('reports enabled when configured', async () => {
    const { call, token } = await setup();
    expect((await call('GET', '/obsidian/status', { token })).body).toEqual({ enabled: true });
  });
});
