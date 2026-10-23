import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { WordRow } from '../src/db/schema.js';
import { dictionaryEntry, json, MP3_BYTES } from './fakeHttp.js';
import { fakeClock, makeApp } from './helpers.js';

const word = { word: 'ubiquitous', definition: 'present or found everywhere', source: 'claude' };

async function setup() {
  const clock = fakeClock();
  const written: WordRow[] = [];
  const ctx = makeApp({ now: clock.now, onWordWritten: (w) => written.push(w) });
  ctx.http.handlers.dictionary = () => json(dictionaryEntry('/ipa/', 'https://media.test/ubiquitous.mp3'));
  const token = await ctx.loginAs('olli');
  return { ...ctx, clock, token, written };
}

describe('audio download on POST /words', () => {
  it('downloads the cached audio URL to data/audio/<id>.mp3 and sets hasAudio', async () => {
    const { call, token, audio, dataDir, http, clock, written } = await setup();
    await call('POST', '/lookup', { token, body: { word: 'ubiquitous' } });
    const created = await call('POST', '/words', { token, body: word });
    expect(created.body.hasAudio).toBe(false);
    clock.advance();

    await audio.whenIdle();
    const file = join(dataDir, 'audio', `${created.body.id}.mp3`);
    expect(new Uint8Array(readFileSync(file))).toEqual(MP3_BYTES);
    expect(http.requests.some((r) => r.url.href === 'https://media.test/ubiquitous.mp3')).toBe(true);

    const fetched = await call('GET', `/words/${created.body.id}`, { token });
    // updatedAt moves so Android's /words/changes picks up hasAudio; the Obsidian hook fires too.
    expect(fetched.body).toMatchObject({ hasAudio: true, updatedAt: clock.now() });
    expect(written.at(-1)?.audioPath).toBe(`audio/${created.body.id}.mp3`);
  });

  it('uses the audio URL from a context lookup (Kindle import path)', async () => {
    const { call, token, audio } = await setup();
    await call('POST', '/lookup', { token, body: { word: 'ubiquitous', context: 'They were ubiquitous.' } });
    const created = await call('POST', '/words', { token, body: word });
    await audio.whenIdle();
    expect((await call('GET', `/words/${created.body.id}`, { token })).body.hasAudio).toBe(true);
  });

  it('does nothing when no lookup is cached for the word', async () => {
    const { call, token, audio, http } = await setup();
    const created = await call('POST', '/words', { token, body: word });
    await audio.whenIdle();
    expect(http.requests.filter((r) => r.host === 'media.test')).toHaveLength(0);
    expect((await call('GET', `/words/${created.body.id}`, { token })).body.hasAudio).toBe(false);
  });

  it('does nothing when the lookup found no audio', async () => {
    const { call, token, audio, http, dataDir } = await setup();
    http.handlers.dictionary = () => json(dictionaryEntry('/ipa/', null));
    await call('POST', '/lookup', { token, body: { word: 'ubiquitous' } });
    const created = await call('POST', '/words', { token, body: word });
    await audio.whenIdle();
    expect(existsSync(join(dataDir, 'audio', `${created.body.id}.mp3`))).toBe(false);
  });

  it.each([
    ['an HTTP error', () => new Response('nope', { status: 404 })],
    ['a network error', () => Promise.reject(new TypeError('fetch failed'))],
  ])('leaves audio_path NULL on %s', async (_label, handler) => {
    const { call, token, audio, http, dataDir } = await setup();
    http.handlers.media = handler;
    await call('POST', '/lookup', { token, body: { word: 'ubiquitous' } });
    const created = await call('POST', '/words', { token, body: word });
    await audio.whenIdle();
    expect((await call('GET', `/words/${created.body.id}`, { token })).body.hasAudio).toBe(false);
    expect(existsSync(join(dataDir, 'audio', `${created.body.id}.mp3`))).toBe(false);
  });

  it('does not block the POST /words response', async () => {
    const { call, token, audio, http } = await setup();
    await call('POST', '/lookup', { token, body: { word: 'ubiquitous' } });
    let release!: () => void;
    http.handlers.media = () =>
      new Promise((resolve) => {
        release = () => resolve(new Response(new Uint8Array([1]), { headers: { 'Content-Type': 'audio/mpeg' } }));
      });
    const created = await call('POST', '/words', { token, body: word });
    expect(created.status).toBe(201);
    release();
    await audio.whenIdle();
  });
});

describe('GET /audio/:id', () => {
  async function withAudio() {
    const ctx = await setup();
    await ctx.call('POST', '/lookup', { token: ctx.token, body: { word: 'ubiquitous' } });
    const created = await ctx.call('POST', '/words', { token: ctx.token, body: word });
    await ctx.audio.whenIdle();
    return { ...ctx, id: created.body.id as number };
  }

  it('serves the file as audio/mpeg', async () => {
    const { app, token, id } = await withAudio();
    const res = await app.request(`/api/audio/${id}`, { headers: { Authorization: `Bearer ${token}` } });
    expect(res.status).toBe(200);
    expect(res.headers.get('Content-Type')).toBe('audio/mpeg');
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(MP3_BYTES);
  });

  it("returns 404 when audio_path is NULL, for unknown ids, and for another user's word", async () => {
    const { call, token, loginAs, id } = await withAudio();
    const noAudio = await call('POST', '/words', { token, body: { ...word, word: 'terse' } });
    expect((await call('GET', `/audio/${noAudio.body.id}`, { token })).status).toBe(404);
    expect((await call('GET', '/audio/9999', { token })).status).toBe(404);
    const other = await loginAs('guest');
    expect((await call('GET', `/audio/${id}`, { token: other })).status).toBe(404);
  });

  it('returns 404 for a soft-deleted word and requires auth', async () => {
    const { call, token, id } = await withAudio();
    expect((await call('GET', `/audio/${id}`)).status).toBe(401);
    await call('DELETE', `/words/${id}`, { token });
    expect((await call('GET', `/audio/${id}`, { token })).status).toBe(404);
  });
});
