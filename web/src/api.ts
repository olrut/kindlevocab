/** Typed client for the backend API (docs/SPEC.md § 3). */

export type User = { id: number; name: string; hasPin: boolean };
export type Status = 'learning' | 'learned';
export type Source = 'claude' | 'manual' | 'import' | 'kindle';

export type Word = {
  id: number;
  word: string;
  definition: string;
  example: string | null;
  finnish: string | null;
  ipa: string | null;
  hasAudio: boolean;
  source: Source;
  status: Status;
  learnedAt: string | null;
  bookTitle: string | null;
  bookAuthor: string | null;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
};

export type Candidate = { definition: string; example: string; finnish: string };
export type LookupResult = { word: string; ipa: string | null; audioAvailable: boolean; candidates: Candidate[]; cached: boolean };

export type KindleCandidate = {
  word: string;
  usage: string | null;
  bookTitle: string | null;
  bookAuthor: string | null;
  kindleCategory: 'learning' | 'mastered';
  exists: boolean;
};
export type ImportProgress = {
  state: 'preview' | 'running' | 'finished';
  done: number;
  total: number;
  imported: number;
  skipped: number;
  errors: { word: string; error: string }[];
};

export type ObsidianStatus = { enabled: boolean; lastRun: { at: string; ok: boolean; error: string | null } | null };

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly body: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

const TOKEN_KEY = 'vocab.token';
export const token = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (t: string) => localStorage.setItem(TOKEN_KEY, t),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

let onUnauthorized = () => {};
/** Called after a 401 has cleared the token; the app navigates to /login. */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler;
}

async function request(path: string, init: { method?: string; json?: unknown; form?: FormData } = {}): Promise<Response> {
  const headers: Record<string, string> = {};
  const t = token.get();
  if (t) headers.Authorization = `Bearer ${t}`;
  let body: BodyInit | undefined;
  if (init.json !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(init.json);
  } else if (init.form) {
    body = init.form;
  }

  const res = await fetch(`/api${path}`, { method: init.method ?? 'GET', headers, body });
  if (res.ok) return res;

  const data = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (res.status === 401) {
    token.clear();
    onUnauthorized();
  }
  throw new ApiError(res.status, typeof data.error === 'string' ? data.error : `HTTP ${res.status}`, data);
}

async function json<T>(path: string, init?: Parameters<typeof request>[1]): Promise<T> {
  const res = await request(path, init);
  return (res.status === 204 ? undefined : await res.json()) as T;
}

export const api = {
  users: () => json<User[]>('/users'),
  createUser: (name: string, pin?: string) => json<User>('/users', { method: 'POST', json: pin ? { name, pin } : { name } }),
  login: (userId: number, pin?: string) =>
    json<{ token: string; user: { id: number; name: string } }>('/auth/login', {
      method: 'POST',
      json: pin ? { userId, pin, device: 'web' } : { userId, device: 'web' },
    }),
  logout: () => json<void>('/auth/logout', { method: 'POST' }),
  me: () => json<{ id: number; name: string }>('/auth/me'),

  lookup: (word: string) => json<LookupResult>('/lookup', { method: 'POST', json: { word } }),
  lookupAudio: async (word: string) => (await request(`/lookup/audio/${encodeURIComponent(word)}`)).blob(),
  wordAudio: async (id: number) => (await request(`/audio/${id}`)).blob(),

  words: (status: Status) => json<Word[]>(`/words?status=${status}`),
  createWord: (w: { word: string; definition: string; example: string | null; finnish: string | null; ipa: string | null; source: Source }) =>
    json<Word>('/words', { method: 'POST', json: w }),
  updateWord: (id: number, patch: Partial<Pick<Word, 'status' | 'definition' | 'example' | 'finnish' | 'notes'>>) =>
    json<Word>(`/words/${id}`, { method: 'PATCH', json: patch }),
  deleteWord: (id: number) => json<void>(`/words/${id}`, { method: 'DELETE' }),

  kindlePreview: (file: File, onlyNew: boolean) => {
    const form = new FormData();
    form.append('file', file);
    form.append('onlyNew', String(onlyNew));
    return json<{ importId: string; candidates: KindleCandidate[] }>('/import/kindle', { method: 'POST', form });
  },
  kindleConfirm: (importId: string, words: string[], markMasteredAsLearned: boolean) =>
    json<ImportProgress>(`/import/kindle/${importId}/confirm`, { method: 'POST', json: { words, markMasteredAsLearned } }),
  kindleProgress: (importId: string) => json<ImportProgress>(`/import/kindle/${importId}`),

  /** The export file and the name the server suggests for it. */
  exportWords: async (format: 'csv' | 'json') => {
    const res = await request(`/export?format=${format}`);
    const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1];
    return { blob: await res.blob(), filename: name ?? `vocab-${new Date().toISOString().slice(0, 10)}.${format}` };
  },
  importWords: (file: File) => {
    const form = new FormData();
    form.append('file', file);
    return json<{ imported: number; skipped: number }>('/import', { method: 'POST', form });
  },

  obsidianStatus: () => json<ObsidianStatus>('/obsidian/status'),
  obsidianExportAll: () => json<{ words: number; books: number }>('/obsidian/export-all', { method: 'POST' }),
};

/** Message for an error caught from `api`. */
export const errorMessage = (err: unknown) => (err instanceof Error ? err.message : String(err));
