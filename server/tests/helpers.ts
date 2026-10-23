import { createApp, type AppDeps } from '../src/app.js';
import { openDb } from '../src/db/index.js';

export function makeApp(deps: Partial<AppDeps> = {}) {
  const db = openDb(':memory:');
  const app = createApp({ db, ...deps });

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

  return { app, db, call, loginAs };
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
