import { serveStatic } from '@hono/node-server/serve-static';
import { Hono, type MiddlewareHandler } from 'hono';
import type { AudioService } from './audio.js';
import type { Db } from './db/index.js';
import type { KindleImporter } from './kindle/importer.js';
import type { LookupService } from './lookup/service.js';
import type { ObsidianExporter } from './obsidian/exporter.js';
import type { WordRow } from './db/schema.js';
import type { AppEnv } from './env.js';
import { HttpError } from './http.js';
import { audioRoutes } from './routes/audio.js';
import { authRoutes } from './routes/auth.js';
import { kindleRoutes } from './routes/kindle.js';
import { lookupRoutes } from './routes/lookup.js';
import { obsidianRoutes } from './routes/obsidian.js';
import { transferRoutes } from './routes/transfer.js';
import { usersRoutes } from './routes/users.js';
import { wordsRoutes } from './routes/words.js';

export interface AppDeps {
  db: Db;
  /** Current time as an ISO-8601 UTC string. Injectable so tests control timestamps. */
  now: () => string;
  /** Called after every successful write to `words` (create, update, soft delete). The Obsidian exporter hooks in here. */
  onWordWritten: (word: WordRow) => void;
  lookup: LookupService;
  audio: AudioService;
  kindle: KindleImporter;
  obsidian: ObsidianExporter;
  /** Root for audio/ and tmp/. */
  dataDir: string;
  /** Built web UI (web/ → server/public). Null in development, where Vite serves it. */
  publicDir: string | null;
}

type Defaulted = 'now' | 'onWordWritten' | 'publicDir';

export function createApp(input: Partial<Pick<AppDeps, Defaulted>> & Omit<AppDeps, Defaulted>) {
  const deps: AppDeps = { now: () => new Date().toISOString(), onWordWritten: () => {}, publicDir: null, ...input };

  const api = new Hono<AppEnv>()
    .use(async (c, next) => {
      c.set('deps', deps);
      await next();
    })
    // Docker's HEALTHCHECK; the database is open by the time the app exists.
    .get('/health', (c) => c.json({ ok: true }))
    .route('/users', usersRoutes)
    .route('/auth', authRoutes)
    .route('/words', wordsRoutes)
    .route('/lookup', lookupRoutes)
    .route('/audio', audioRoutes)
    .route('/import/kindle', kindleRoutes)
    .route('/obsidian', obsidianRoutes)
    .route('/', transferRoutes);

  const app = new Hono<AppEnv>();
  app.route('/api', api);
  if (deps.publicDir) serveWebUi(app, deps.publicDir);
  app.notFound((c) => c.json({ error: 'not found' }, 404));
  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json({ error: err.message, ...err.extra }, err.status);
    console.error(err);
    return c.json({ error: 'internal server error' }, 500);
  });
  return app;
}

/**
 * Files from the Vite build, plus index.html for client-side routes (/words, /import, …).
 * Hashed assets are cached forever; everything else, index.html included, is revalidated so a
 * new build shows up at once.
 */
function serveWebUi(app: Hono<AppEnv>, root: string) {
  const page = cached(serveStatic({ root, path: 'index.html' }), 'no-cache');
  app.use('/assets/*', cached(serveStatic({ root }), 'public, max-age=31536000, immutable'));
  app.use('*', cached(serveStatic({ root }), 'no-cache'));
  // A path without a file extension is a page of the app; a missing file stays a 404.
  app.get('*', (c, next) => (c.req.path.startsWith('/api/') || /\.[^/]*$/.test(c.req.path) ? next() : page(c, next)));
}

/** serveStatic's onFound runs after the response exists, so set the header on what it returns. */
function cached(handler: MiddlewareHandler<AppEnv>, cacheControl: string): MiddlewareHandler<AppEnv> {
  return async (c, next) => {
    const res = await handler(c, next);
    if (res instanceof Response) res.headers.set('Cache-Control', cacheControl);
    return res;
  };
}
