import { describe, expect, it } from 'vitest';
import { claudeJson, claudeMessage, dictionaryEntry, json } from './fakeHttp.js';
import { makeApp } from './helpers.js';

const ubiquitous = {
  definition: 'present or found everywhere',
  example: 'Smartphones are ubiquitous these days.',
  finnish: 'kaikkialla läsnä oleva, yleinen',
};

async function setup() {
  const ctx = makeApp();
  ctx.http.handlers.claude = () => claudeJson({ candidates: [ubiquitous] });
  ctx.http.handlers.dictionary = () => json(dictionaryEntry('/juːˈbɪkwɪtəs/', 'https://media.test/u.mp3'));
  const token = await ctx.loginAs('olli');
  const lookup = (body: unknown) => ctx.call('POST', '/lookup', { token, body });
  return { ...ctx, token, lookup };
}

describe('POST /lookup', () => {
  it('returns IPA, audio availability and candidates; the audio URL stays internal', async () => {
    const { lookup } = await setup();
    const res = await lookup({ word: 'ubiquitous' });
    expect(res.status).toBe(200);
    expect(res.body).toEqual({
      word: 'ubiquitous',
      ipa: '/juːˈbɪkwɪtəs/',
      audioAvailable: true,
      candidates: [ubiquitous],
      cached: false,
    });
  });

  it('sends the SPEC § 4 request: system prompt, the bare word, no sampling params', async () => {
    const { lookup, http } = await setup();
    await lookup({ word: 'ubiquitous' });
    const [req] = http.claudeRequests();
    expect(req.url.pathname).toBe('/v1/messages');
    expect(req.body).toMatchObject({
      model: 'claude-haiku-5-5',
      max_tokens: 1024,
      output_config: { effort: 'low' },
      messages: [{ role: 'user', content: 'ubiquitous' }],
    });
    expect(req.body.system).toMatch(/^You are a dictionary for an English learner/);
    expect(req.body).not.toHaveProperty('temperature');
    expect(req.body).not.toHaveProperty('top_p');
    expect(req.body).not.toHaveProperty('top_k');
  });

  it('normalises the word and serves repeats from the cache without calling Claude again', async () => {
    const { lookup, http } = await setup();
    await lookup({ word: 'ubiquitous' });
    const again = await lookup({ word: '  Ubiquitous ' });
    expect(again.status).toBe(200);
    expect(again.body).toMatchObject({ word: 'ubiquitous', cached: true, audioAvailable: true });
    expect(again.body).not.toHaveProperty('audioUrl');
    expect(http.claudeRequests()).toHaveLength(1);
    expect(http.dictionaryRequests()).toHaveLength(1);
  });

  it('runs the dictionary and Claude calls in parallel', async () => {
    const { lookup, http } = await setup();
    let claudeSeen!: () => void;
    const claudeStarted = new Promise<void>((r) => (claudeSeen = r));
    // The dictionary answers only once the Claude request is in flight; sequential code would time out.
    http.handlers.dictionary = async () => {
      await Promise.race([claudeStarted, new Promise((_, rej) => setTimeout(() => rej(new Error('not parallel')), 2000))]);
      return json(dictionaryEntry('/ipa/', null));
    };
    http.handlers.claude = () => {
      claudeSeen();
      return claudeJson({ candidates: [ubiquitous] });
    };
    const res = await lookup({ word: 'ubiquitous' });
    expect(res.body.ipa).toBe('/ipa/');
  });

  it('still succeeds when the dictionary fails', async () => {
    const { lookup, http } = await setup();
    http.handlers.dictionary = () => json({}, 404);
    const res = await lookup({ word: 'ubiquitous' });
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({ ipa: null, audioAvailable: false, candidates: [ubiquitous] });
  });

  it('returns 422 not_english with the suggestion, and does not cache it', async () => {
    const { lookup, http } = await setup();
    http.handlers.claude = () => claudeJson({ error: 'not_english', suggestion: 'ubiquitous' });
    const res = await lookup({ word: 'ubiquitus' });
    expect(res.status).toBe(422);
    expect(res.body).toEqual({ error: 'not_english', suggestion: 'ubiquitous' });
    await lookup({ word: 'ubiquitus' });
    expect(http.claudeRequests()).toHaveLength(2);
  });

  it.each([
    ['an API error', () => json({ type: 'error', error: { type: 'api_error', message: 'boom' } }, 500)],
    ['a reply that is not JSON', () => claudeMessage('Ubiquitous means everywhere.')],
    ['a reply that fails the schema', () => claudeJson({ candidates: [] })],
    ['a refusal', () => claudeMessage('', 'refusal')],
    ['a reply cut off at max_tokens', () => claudeMessage('{"candidates":[', 'max_tokens')],
  ])('returns 502 and caches nothing on %s', async (_label, handler) => {
    const { lookup, http } = await setup();
    http.handlers.claude = handler;
    const res = await lookup({ word: 'ubiquitous' });
    expect(res.status).toBe(502);
    expect(res.body).toEqual({ error: expect.any(String) });

    http.handlers.claude = () => claudeJson({ candidates: [ubiquitous] });
    const retry = await lookup({ word: 'ubiquitous' });
    expect(retry.body.cached).toBe(false);
  });

  it('returns 502 when the server has no Anthropic credentials', async () => {
    const ctx = makeApp({ anthropicApiKey: null });
    const token = await ctx.loginAs('olli');
    const res = await ctx.call('POST', '/lookup', { token, body: { word: 'ubiquitous' } });
    expect(res.status).toBe(502);
    expect(ctx.http.claudeRequests()).toHaveLength(0);
  });

  it('rejects an empty word with 400 and requires auth', async () => {
    const { lookup, call } = await setup();
    expect((await lookup({ word: '  ' })).status).toBe(400);
    expect((await call('POST', '/lookup', { body: { word: 'x' } })).status).toBe(401);
  });
});

describe('POST /lookup with context', () => {
  const sentence = 'By then the sensors were ubiquitous in every home.';

  it('returns one candidate whose example is the given sentence', async () => {
    const { lookup, http } = await setup();
    const res = await lookup({ word: 'ubiquitous', context: sentence });
    expect(res.status).toBe(200);
    expect(res.body.candidates).toEqual([{ ...ubiquitous, example: sentence }]);
    expect(http.claudeRequests()[0].body.system).toContain(`The user saw the word in this sentence: "${sentence}"`);
  });

  it('caches by word + sentence, separately from the bare word', async () => {
    const { lookup, http } = await setup();
    await lookup({ word: 'ubiquitous', context: sentence });
    expect((await lookup({ word: 'ubiquitous', context: sentence })).body.cached).toBe(true);
    expect((await lookup({ word: 'ubiquitous' })).body.cached).toBe(false);
    expect((await lookup({ word: 'ubiquitous', context: 'Another sentence.' })).body.cached).toBe(false);
    expect(http.claudeRequests()).toHaveLength(3);
  });

  it('stores the cache key as word|sha1(context)', async () => {
    const { lookup, db } = await setup();
    await lookup({ word: 'ubiquitous', context: sentence });
    const { createHash } = await import('node:crypto');
    const key = `ubiquitous|${createHash('sha1').update(sentence).digest('hex')}`;
    const row = db.query.lookupCache.findFirst().sync();
    expect(row?.cacheKey).toBe(key);
  });
});
