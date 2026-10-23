import { describe, expect, it } from 'vitest';
import { lookupDictionary } from '../src/lookup/dictionary.js';
import { fakeHttp, json } from './fakeHttp.js';

describe('lookupDictionary', () => {
  it('takes the first phonetics text and the first non-empty audio, across entries', async () => {
    const http = fakeHttp();
    http.handlers.dictionary = () =>
      json([
        { word: 'hello', phonetics: [{ audio: '' }, { text: '/həˈloʊ/', audio: '' }] },
        { word: 'hello', phonetics: [{ text: '/hɛˈləʊ/', audio: 'https://media.test/hello-uk.mp3' }] },
      ]);
    expect(await lookupDictionary(http.fetch, 'hello')).toEqual({
      ipa: '/həˈloʊ/',
      audioUrl: 'https://media.test/hello-uk.mp3',
    });
  });

  it('requests the word URL-encoded from the Free Dictionary API', async () => {
    const http = fakeHttp();
    await lookupDictionary(http.fetch, 'ad hoc');
    expect(http.dictionaryRequests()[0].url.href).toBe('https://api.dictionaryapi.dev/api/v2/entries/en/ad%20hoc');
  });

  it('falls back to the entry-level phonetic field', async () => {
    const http = fakeHttp();
    http.handlers.dictionary = () => json([{ word: 'x', phonetic: '/eks/', phonetics: [] }]);
    expect(await lookupDictionary(http.fetch, 'x')).toEqual({ ipa: '/eks/', audioUrl: null });
  });

  it('makes protocol-relative audio URLs https', async () => {
    const http = fakeHttp();
    http.handlers.dictionary = () => json([{ phonetics: [{ audio: '//ssl.gstatic.com/x.mp3' }] }]);
    expect((await lookupDictionary(http.fetch, 'x')).audioUrl).toBe('https://ssl.gstatic.com/x.mp3');
  });

  it.each([
    ['404', () => json({ title: 'No Definitions Found' }, 404)],
    ['500', () => json({}, 500)],
    ['malformed body', () => new Response('<html>')],
    ['unexpected shape', () => json({ phonetics: 'nope' })],
    ['network error', () => Promise.reject(new TypeError('fetch failed'))],
  ])('returns nulls on %s', async (_label, handler) => {
    const http = fakeHttp();
    http.handlers.dictionary = handler;
    expect(await lookupDictionary(http.fetch, 'x')).toEqual({ ipa: null, audioUrl: null });
  });

  it('gives up after the timeout', async () => {
    const http = fakeHttp();
    http.handlers.dictionary = ({ signal }) =>
      new Promise((_resolve, reject) => signal?.addEventListener('abort', () => reject(signal.reason)));
    const started = Date.now();
    expect(await lookupDictionary(http.fetch, 'x', 50)).toEqual({ ipa: null, audioUrl: null });
    expect(Date.now() - started).toBeLessThan(1000);
  });
});
