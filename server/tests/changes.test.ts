import { describe, expect, it } from 'vitest';
import { fakeClock, makeApp } from './helpers.js';

const body = (word: string) => ({ word, definition: 'd', source: 'manual' });
const EPOCH = '1970-01-01T00:00:00.000Z';

async function setup() {
  const clock = fakeClock();
  const ctx = makeApp({ now: clock.now });
  const token = await ctx.loginAs('olli');
  const changes = (since: string) =>
    ctx.call('GET', `/words/changes?since=${encodeURIComponent(since)}`, { token });
  return { ...ctx, clock, token, changes };
}

describe('GET /words/changes', () => {
  it('returns every word changed after `since`, plus serverTime', async () => {
    const { call, token, changes } = await setup();
    await call('POST', '/words', { token, body: body('alpha') });
    await call('POST', '/words', { token, body: body('beta') });

    const res = await changes(EPOCH);
    expect(res.status).toBe(200);
    expect(res.body.serverTime).toEqual(expect.stringMatching(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$/));
    expect(res.body.words.map((w: { word: string }) => w.word).sort()).toEqual(['alpha', 'beta']);
  });

  it('includes soft-deleted words with deletedAt set, and learned words', async () => {
    const { call, token, changes, clock } = await setup();
    const a = await call('POST', '/words', { token, body: body('alpha') });
    const b = await call('POST', '/words', { token, body: body('beta') });
    const since = (await changes(EPOCH)).body.serverTime;
    clock.advance();
    await call('DELETE', `/words/${a.body.id}`, { token });
    await call('PATCH', `/words/${b.body.id}`, { token, body: { status: 'learned' } });

    const res = await changes(since);
    const byWord = Object.fromEntries(res.body.words.map((w: { word: string }) => [w.word, w]));
    expect(byWord.alpha).toMatchObject({ deletedAt: clock.now() });
    expect(byWord.beta).toMatchObject({ status: 'learned', deletedAt: null });
  });

  it('only returns rows changed after the previous serverTime', async () => {
    const { call, token, changes, clock } = await setup();
    await call('POST', '/words', { token, body: body('alpha') });
    clock.advance();
    const since = (await changes(EPOCH)).body.serverTime;
    clock.advance();
    await call('POST', '/words', { token, body: body('beta') });

    const res = await changes(since);
    expect(res.body.words.map((w: { word: string }) => w.word)).toEqual(['beta']);
  });

  it('may re-send a row written in the same millisecond as the previous pull (clients upsert)', async () => {
    const { call, token, changes } = await setup();
    await call('POST', '/words', { token, body: body('alpha') });
    const since = (await changes(EPOCH)).body.serverTime;
    const res = await changes(since);
    expect(res.body.words.map((w: { word: string }) => w.word)).toEqual(['alpha']);
  });

  it('does not miss a write in the same millisecond as serverTime', async () => {
    const { call, token, changes } = await setup();
    const since = (await changes(EPOCH)).body.serverTime;
    // Clock not advanced: this write shares the millisecond the previous pull reported.
    await call('POST', '/words', { token, body: body('alpha') });

    const res = await changes(since);
    expect(res.body.words.map((w: { word: string }) => w.word)).toEqual(['alpha']);
  });

  it("excludes other users' words", async () => {
    const { call, loginAs, changes } = await setup();
    const other = await loginAs('guest');
    await call('POST', '/words', { token: other, body: body('alpha') });
    expect((await changes(EPOCH)).body.words).toEqual([]);
  });

  it('returns 400 when since is missing or not an ISO timestamp', async () => {
    const { call, token, changes } = await setup();
    expect((await call('GET', '/words/changes', { token })).status).toBe(400);
    expect((await changes('yesterday')).status).toBe(400);
  });

  it('accepts a since without milliseconds', async () => {
    const { call, token, changes } = await setup();
    await call('POST', '/words', { token, body: body('alpha') });
    const res = await changes('2026-10-23T11:59:59Z');
    expect(res.body.words).toHaveLength(1);
  });

  it('requires auth', async () => {
    const { call } = await setup();
    expect((await call('GET', `/words/changes?since=${EPOCH}`)).status).toBe(401);
  });
});
