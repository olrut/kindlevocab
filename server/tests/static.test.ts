import Anthropic from '@anthropic-ai/sdk';
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { describe, expect, it, onTestFinished } from 'vitest';
import { createApp } from '../src/app.js';
import { createAudioService } from '../src/audio.js';
import { openDb } from '../src/db/index.js';
import { createKindleImporter } from '../src/kindle/importer.js';
import { createLookupService } from '../src/lookup/service.js';
import { createObsidianExporter } from '../src/obsidian/exporter.js';
import { fakeHttp } from './fakeHttp.js';

const INDEX = '<!doctype html><title>vocab</title><div id="root"></div>';

/** The production app serving a built web UI from `publicDir`. */
function appWithPublic(publicDir: string | null) {
  const db = openDb(':memory:');
  const http = fakeHttp();
  const dataDir = mkdtempSync(join(tmpdir(), 'vocab-static-'));
  onTestFinished(() => rmSync(dataDir, { recursive: true, force: true }));
  const now = () => new Date().toISOString();
  const anthropic = new Anthropic({ apiKey: 'k', fetch: http.fetch, maxRetries: 0 });
  const lookup = createLookupService({ db, fetch: http.fetch, anthropic, model: 'm', now });
  const audio = createAudioService({ db, fetch: http.fetch, dataDir, now, onWordWritten: () => {} });
  const kindle = createKindleImporter({ db, lookup, audio, dataDir, now, onWordWritten: () => {} });
  const obsidian = createObsidianExporter({ db, dir: null });
  return createApp({ db, lookup, audio, kindle, obsidian, dataDir, publicDir });
}

function builtWeb() {
  const dir = mkdtempSync(join(tmpdir(), 'vocab-public-'));
  onTestFinished(() => rmSync(dir, { recursive: true, force: true }));
  writeFileSync(join(dir, 'index.html'), INDEX);
  mkdirSync(join(dir, 'assets'));
  writeFileSync(join(dir, 'assets', 'app-abc123.js'), 'console.log(1)');
  return dir;
}

describe('serving the built web UI', () => {
  it('serves index.html at / and for client-side routes', async () => {
    const app = appWithPublic(builtWeb());
    for (const path of ['/', '/words', '/import/kindle', '/login?next=%2Fadd']) {
      const res = await app.request(path);
      expect(res.status, path).toBe(200);
      expect(res.headers.get('Content-Type'), path).toMatch(/^text\/html/);
      expect(await res.text(), path).toBe(INDEX);
    }
  });

  it('serves assets with their type and a long cache; index.html is never cached', async () => {
    const app = appWithPublic(builtWeb());
    const js = await app.request('/assets/app-abc123.js');
    expect(js.status).toBe(200);
    expect(js.headers.get('Content-Type')).toMatch(/javascript/);
    expect(js.headers.get('Cache-Control')).toBe('public, max-age=31536000, immutable');
    expect((await app.request('/')).headers.get('Cache-Control')).toBe('no-cache');
  });

  it('returns 404 for a missing asset instead of the page', async () => {
    const app = appWithPublic(builtWeb());
    expect((await app.request('/assets/missing.js')).status).toBe(404);
    expect((await app.request('/favicon.ico')).status).toBe(404);
  });

  it('keeps /api JSON: unknown API routes are a JSON 404, not the page', async () => {
    const app = appWithPublic(builtWeb());
    const res = await app.request('/api/nope');
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: 'not found' });
    expect((await app.request('/api/users')).status).toBe(200);
  });

  it('does not serve files outside the public folder', async () => {
    const app = appWithPublic(builtWeb());
    for (const path of ['/../package.json', '/assets/..%2F..%2Fpackage.json', '/%2e%2e/package.json']) {
      const res = await app.request(path);
      expect(await res.text(), path).not.toContain('"name"');
    }
  });

  it('serves nothing but the API without a public folder (development: Vite serves the UI)', async () => {
    const app = appWithPublic(null);
    expect((await app.request('/')).status).toBe(404);
  });
});
