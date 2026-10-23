import { describe, expect, it } from 'vitest';
import type { WordRow } from '../src/db/schema.js';
import { fakeClock, makeApp } from './helpers.js';

const ubiquitous = {
  word: 'ubiquitous',
  definition: 'present or found everywhere',
  example: 'Smartphones are ubiquitous these days.',
  finnish: 'kaikkialla läsnä oleva, yleinen',
  ipa: '/juːˈbɪkwɪtəs/',
  source: 'claude',
};

async function setup() {
  const clock = fakeClock();
  const written: WordRow[] = [];
  const ctx = makeApp({ now: clock.now, onWordWritten: (w) => written.push(w) });
  const token = await ctx.loginAs('olli');
  return { ...ctx, clock, token, written };
}

describe('POST /words', () => {
  it('creates a word and returns the camelCase shape', async () => {
    const { call, token, clock } = await setup();
    const res = await call('POST', '/words', { token, body: ubiquitous });
    expect(res.status).toBe(201);
    expect(res.body).toEqual({
      id: expect.any(Number),
      word: 'ubiquitous',
      definition: 'present or found everywhere',
      example: 'Smartphones are ubiquitous these days.',
      finnish: 'kaikkialla läsnä oleva, yleinen',
      ipa: '/juːˈbɪkwɪtəs/',
      hasAudio: false,
      source: 'claude',
      status: 'learning',
      learnedAt: null,
      createdAt: clock.now(),
      updatedAt: clock.now(),
      deletedAt: null,
    });
  });

  it('stores the word lowercase and trimmed', async () => {
    const { call, token } = await setup();
    const res = await call('POST', '/words', { token, body: { ...ubiquitous, word: '  Ubiquitous ' } });
    expect(res.body.word).toBe('ubiquitous');
  });

  it('defaults optional fields to null', async () => {
    const { call, token } = await setup();
    const res = await call('POST', '/words', {
      token,
      body: { word: 'terse', definition: 'brief and abrupt', source: 'manual' },
    });
    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({ example: null, finnish: null, ipa: null, source: 'manual' });
  });

  it('returns 409 when the word already exists', async () => {
    const { call, token } = await setup();
    await call('POST', '/words', { token, body: ubiquitous });
    const dup = await call('POST', '/words', { token, body: { ...ubiquitous, word: 'UBIQUITOUS' } });
    expect(dup.status).toBe(409);
    expect(dup.body).toEqual({ error: expect.any(String) });
  });

  it('revives and updates a soft-deleted word', async () => {
    const { call, token, clock } = await setup();
    const first = await call('POST', '/words', { token, body: ubiquitous });
    await call('PATCH', `/words/${first.body.id}`, { token, body: { status: 'learned' } });
    await call('DELETE', `/words/${first.body.id}`, { token });
    clock.advance();

    const revived = await call('POST', '/words', {
      token,
      body: { word: 'ubiquitous', definition: 'everywhere at once', source: 'manual' },
    });
    expect(revived.status).toBe(201);
    expect(revived.body).toMatchObject({
      id: first.body.id,
      definition: 'everywhere at once',
      example: null,
      source: 'manual',
      status: 'learning',
      learnedAt: null,
      deletedAt: null,
      updatedAt: clock.now(),
    });
  });

  it('rejects missing definition, empty word or unknown source with 400', async () => {
    const { call, token } = await setup();
    for (const body of [
      { word: 'x', source: 'claude' },
      { word: '   ', definition: 'd', source: 'claude' },
      { word: 'x', definition: 'd', source: 'robot' },
    ]) {
      const res = await call('POST', '/words', { token, body });
      expect(res.status, JSON.stringify(body)).toBe(400);
    }
  });

  it('requires auth', async () => {
    const { call } = await setup();
    expect((await call('POST', '/words', { body: ubiquitous })).status).toBe(401);
  });
});

describe('GET /words', () => {
  async function seeded() {
    const ctx = await setup();
    const { call, token } = ctx;
    const a = await call('POST', '/words', { token, body: { ...ubiquitous, word: 'alpha' } });
    const b = await call('POST', '/words', { token, body: { ...ubiquitous, word: 'beta' } });
    const c = await call('POST', '/words', { token, body: { ...ubiquitous, word: 'gamma' } });
    await call('PATCH', `/words/${b.body.id}`, { token, body: { status: 'learned' } });
    await call('DELETE', `/words/${c.body.id}`, { token });
    return { ...ctx, ids: { a: a.body.id, b: b.body.id, c: c.body.id } };
  }
  const words = (body: { word: string }[]) => body.map((w) => w.word).sort();

  it('defaults to learning words, excluding deleted', async () => {
    const { call, token } = await seeded();
    const res = await call('GET', '/words', { token });
    expect(res.status).toBe(200);
    expect(words(res.body)).toEqual(['alpha']);
  });

  it('filters by status=learned and status=all', async () => {
    const { call, token } = await seeded();
    expect(words((await call('GET', '/words?status=learned', { token })).body)).toEqual(['beta']);
    expect(words((await call('GET', '/words?status=all', { token })).body)).toEqual(['alpha', 'beta']);
  });

  it('rejects an unknown status with 400', async () => {
    const { call, token } = await seeded();
    expect((await call('GET', '/words?status=bogus', { token })).status).toBe(400);
  });

  it("does not show another user's words", async () => {
    const { call, loginAs } = await seeded();
    const other = await loginAs('guest');
    expect((await call('GET', '/words?status=all', { token: other })).body).toEqual([]);
  });

  it('lets two users save the same word', async () => {
    const { call, loginAs } = await seeded();
    const other = await loginAs('guest');
    const res = await call('POST', '/words', { token: other, body: { ...ubiquitous, word: 'alpha' } });
    expect(res.status).toBe(201);
  });
});

describe('GET /words/:id', () => {
  it('returns the word', async () => {
    const { call, token } = await setup();
    const created = await call('POST', '/words', { token, body: ubiquitous });
    const res = await call('GET', `/words/${created.body.id}`, { token });
    expect(res.status).toBe(200);
    expect(res.body).toEqual(created.body);
  });

  it("returns 404 for deleted, unknown, or another user's word", async () => {
    const { call, token, loginAs } = await setup();
    const created = await call('POST', '/words', { token, body: ubiquitous });
    const other = await loginAs('guest');
    expect((await call('GET', `/words/${created.body.id}`, { token: other })).status).toBe(404);
    expect((await call('GET', '/words/9999', { token })).status).toBe(404);
    expect((await call('GET', '/words/abc', { token })).status).toBe(404);
    await call('DELETE', `/words/${created.body.id}`, { token });
    expect((await call('GET', `/words/${created.body.id}`, { token })).status).toBe(404);
  });
});

describe('status transitions (PATCH /words/:id)', () => {
  async function withWord() {
    const ctx = await setup();
    const created = await ctx.call('POST', '/words', { token: ctx.token, body: ubiquitous });
    const id = created.body.id as number;
    const patch = (body: unknown) => ctx.call('PATCH', `/words/${id}`, { token: ctx.token, body });
    return { ...ctx, id, patch };
  }

  it('learning → learned sets learnedAt and updatedAt to now', async () => {
    const { patch, clock } = await withWord();
    clock.advance();
    const res = await patch({ status: 'learned' });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ status: 'learned', learnedAt: clock.now(), updatedAt: clock.now() });
  });

  it('learned → learning clears learnedAt', async () => {
    const { patch, clock } = await withWord();
    await patch({ status: 'learned' });
    clock.advance();
    const res = await patch({ status: 'learning' });
    expect(res.body).toMatchObject({ status: 'learning', learnedAt: null, updatedAt: clock.now() });
  });

  it('learned → learned keeps the original learnedAt', async () => {
    const { patch, clock } = await withWord();
    const first = await patch({ status: 'learned' });
    clock.advance();
    const again = await patch({ status: 'learned' });
    expect(again.body.learnedAt).toBe(first.body.learnedAt);
    expect(again.body.updatedAt).toBe(clock.now());
  });

  it('editing text fields leaves status alone and bumps updatedAt', async () => {
    const { patch, clock } = await withWord();
    await patch({ status: 'learned' });
    clock.advance();
    const res = await patch({ definition: 'everywhere', example: null, finnish: 'kaikkialla' });
    expect(res.body).toMatchObject({
      status: 'learned',
      definition: 'everywhere',
      example: null,
      finnish: 'kaikkialla',
      updatedAt: clock.now(),
    });
  });

  it('rejects an unknown status, an empty definition, or an empty patch with 400', async () => {
    const { patch } = await withWord();
    expect((await patch({ status: 'forgotten' })).status).toBe(400);
    expect((await patch({ definition: '' })).status).toBe(400);
    expect((await patch({})).status).toBe(400);
  });

  it('returns 404 for a deleted word', async () => {
    const { patch, call, token, id } = await withWord();
    await call('DELETE', `/words/${id}`, { token });
    expect((await patch({ status: 'learned' })).status).toBe(404);
  });
});

describe('DELETE /words/:id', () => {
  it('soft-deletes: 204, then 404, and the row keeps deleted_at', async () => {
    const { call, token, clock, db } = await setup();
    const created = await call('POST', '/words', { token, body: ubiquitous });
    clock.advance();
    expect((await call('DELETE', `/words/${created.body.id}`, { token })).status).toBe(204);
    expect((await call('DELETE', `/words/${created.body.id}`, { token })).status).toBe(404);

    const row = db.query.words.findFirst()!.sync();
    expect(row).toMatchObject({ deletedAt: clock.now(), updatedAt: clock.now() });
  });
});

describe('onWordWritten hook (Obsidian export trigger)', () => {
  it('fires on create, patch and delete', async () => {
    const { call, token, written } = await setup();
    const created = await call('POST', '/words', { token, body: ubiquitous });
    await call('PATCH', `/words/${created.body.id}`, { token, body: { status: 'learned' } });
    await call('DELETE', `/words/${created.body.id}`, { token });
    expect(written.map((w) => [w.word, w.status, w.deletedAt !== null])).toEqual([
      ['ubiquitous', 'learning', false],
      ['ubiquitous', 'learned', false],
      ['ubiquitous', 'learned', true],
    ]);
  });

  it('does not fire on failed writes', async () => {
    const { call, token, written } = await setup();
    await call('POST', '/words', { token, body: ubiquitous });
    await call('POST', '/words', { token, body: ubiquitous });
    await call('PATCH', '/words/9999', { token, body: { status: 'learned' } });
    expect(written).toHaveLength(1);
  });
});
