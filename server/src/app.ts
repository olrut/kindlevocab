import { Hono } from 'hono';
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
}

export function createApp(input: Partial<Pick<AppDeps, 'now' | 'onWordWritten'>> & Omit<AppDeps, 'now' | 'onWordWritten'>) {
  const deps: AppDeps = { now: () => new Date().toISOString(), onWordWritten: () => {}, ...input };

  const api = new Hono<AppEnv>()
    .use(async (c, next) => {
      c.set('deps', deps);
      await next();
    })
    .route('/users', usersRoutes)
    .route('/auth', authRoutes)
    .route('/words', wordsRoutes)
    .route('/lookup', lookupRoutes)
    .route('/audio', audioRoutes)
    .route('/import/kindle', kindleRoutes)
    .route('/obsidian', obsidianRoutes);

  const app = new Hono<AppEnv>();
  app.route('/api', api);
  app.notFound((c) => c.json({ error: 'not found' }, 404));
  app.onError((err, c) => {
    if (err instanceof HttpError) return c.json({ error: err.message, ...err.extra }, err.status);
    console.error(err);
    return c.json({ error: 'internal server error' }, 500);
  });
  return app;
}
