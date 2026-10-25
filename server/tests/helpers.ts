import Anthropic from '@anthropic-ai/sdk';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { onTestFinished } from 'vitest';
import { createApp, type AppDeps } from '../src/app.js';
import { createAudioService } from '../src/audio.js';
import { openDb } from '../src/db/index.js';
import { createLookupService } from '../src/lookup/service.js';
import { fakeHttp } from './fakeHttp.js';

/**
 * An app on an in-memory database and a per-test temp data dir. All outbound HTTP
 * (Anthropic, dictionary, audio) goes through `http`, a fake fetch; nothing hits the network.
 */
export function makeApp(
  overrides: Partial<Pick<AppDeps, 'now' | 'onWordWritten'>> & { anthropicApiKey?: string | null } = {},
) {
  const db = openDb(':memory:');
  const http = fakeHttp();
  const dataDir = mkdtempSync(join(tmpdir(), 'vocab-test-'));
  onTestFinished(() => rmSync(dataDir, { recursive: true, force: true }));

  const now = overrides.now ?? (() => new Date().toISOString());
  const onWordWritten = overrides.onWordWritten ?? (() => {});
  const anthropic = new Anthropic({
    apiKey: overrides.anthropicApiKey === undefined ? 'test-key' : overrides.anthropicApiKey,
    authToken: null,
    baseURL: 'https://api.anthropic.com',
    fetch: http.fetch,
    maxRetries: 0,
  });
  const lookup = createLookupService({ db, fetch: http.fetch, anthropic, model: 'claude-haiku-5-5', now });
  const audio = createAudioService({ db, fetch: http.fetch, dataDir, now, onWordWritten });
  const app = createApp({ db, now, onWordWritten, lookup, audio, dataDir });

  async function call(method: string, path: string, opts: { body?: unknown; token?: string } = {}) {
    const headers: Record<string, string> = {};
    if (opts.body !== undefined) headers['Content-Type'] = 'application/json';
    if (opts.token) headers['Authorization'] = `Bearer ${opts.token}`;
    const res = await app.request(`/api${path}`, {
      method,
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
    });
    const text = await res.text();
    return { status: res.status, body: text ? JSON.parse(text) : null };
  }

  async function loginAs(name: string, pin?: string) {
    const created = await call('POST', '/users', { body: { name, pin } });
    const login = await call('POST', '/auth/login', {
      body: { userId: created.body.id, pin, device: 'web' },
    });
    return login.body.token as string;
  }

  return { app, db, http, audio, dataDir, call, loginAs };
}

/** A controllable clock returning ISO strings; advance() moves it forward by ms. */
export function fakeClock(start = '2026-10-23T12:00:00.000Z') {
  let t = Date.parse(start);
  return {
    now: () => new Date(t).toISOString(),
    advance: (ms = 1000) => {
      t += ms;
    },
  };
}
