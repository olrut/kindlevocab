import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { App } from '../src/App';

// Raw bytes rather than a Blob: Node 22's Response cannot read jsdom's Blob.
export type Reply = { status?: number; body?: unknown; bytes?: Uint8Array<ArrayBuffer> };
export type Request = { method: string; path: string; params: Record<string, string>; body: any; headers: Headers };
type Route = Reply | ((req: Request) => Reply | Promise<Reply>);

/**
 * Stubs global fetch with a fake backend. Routes are keyed "METHOD /api/path", where a
 * `:name` segment matches anything. Unknown routes answer 404. Every request is in `calls`.
 */
export function fakeApi(routes: Record<string, Route> = {}) {
  const calls: Request[] = [];
  const all: Record<string, Route> = { 'GET /api/auth/me': { body: { id: 1, name: 'olli' } }, ...routes };

  function find(method: string, path: string): [Route, Record<string, string>] | null {
    for (const [key, route] of Object.entries(all)) {
      const [m, pattern] = key.split(' ');
      if (m !== method) continue;
      const names: string[] = [];
      const re = new RegExp(`^${pattern.replace(/:(\w+)/g, (_, n) => (names.push(n), '([^/]+)'))}$`);
      const match = re.exec(path);
      if (match) return [route, Object.fromEntries(names.map((n, i) => [n, decodeURIComponent(match[i + 1])]))];
    }
    return null;
  }

  const fetch = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(String(input), 'http://localhost');
    const method = init.method ?? 'GET';
    const body = typeof init.body === 'string' ? JSON.parse(init.body) : init.body;
    const found = find(method, url.pathname);
    const req: Request = { method, path: url.pathname + url.search, params: found?.[1] ?? {}, body, headers: new Headers(init.headers) };
    calls.push(req);
    if (!found) return json({ error: 'not found' }, 404);
    const route = found[0];
    const reply = typeof route === 'function' ? await route(req) : route;
    if (reply.bytes) return new Response(reply.bytes, { status: reply.status ?? 200 });
    if (reply.status === 204) return new Response(null, { status: 204 });
    return json(reply.body ?? null, reply.status ?? 200);
  });
  vi.stubGlobal('fetch', fetch);

  return {
    calls,
    /** Adds or replaces a route. */
    on(key: string, route: Route) {
      all[key] = route;
    },
    /** Requests matching "METHOD /path" (path without the query string). */
    callsTo(key: string) {
      const [m, p] = key.split(' ');
      return calls.filter((c) => c.method === m && c.path.split('?')[0] === p);
    },
  };
}

const json = (body: unknown, status: number) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** Renders the whole app at `path`, logged in unless `token` is null. */
export function renderApp(path: string, opts: { token?: string | null } = {}) {
  const token = opts.token === undefined ? 'test-token' : opts.token;
  if (token) localStorage.setItem('vocab.token', token);
  const user = userEvent.setup();
  const utils = render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
  return { user, ...utils };
}

export const word = (patch: Record<string, unknown> = {}) => ({
  id: 1,
  word: 'ubiquitous',
  definition: 'present or found everywhere',
  example: 'Smartphones are ubiquitous these days.',
  finnish: 'kaikkialla läsnä oleva',
  ipa: '/juːˈbɪkwɪtəs/',
  hasAudio: false,
  source: 'claude',
  status: 'learning',
  learnedAt: null,
  bookTitle: null,
  bookAuthor: null,
  notes: null,
  createdAt: '2026-10-25T10:00:00.000Z',
  updatedAt: '2026-10-25T10:00:00.000Z',
  deletedAt: null,
  ...patch,
});
