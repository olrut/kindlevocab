import { serve } from '@hono/node-server';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { createApp } from './app.js';
import { openDb } from './db/index.js';

const dataDir = process.env.DATA_DIR ?? './data';
const port = Number(process.env.PORT ?? 3000);

mkdirSync(dataDir, { recursive: true });
const db = openDb(join(dataDir, 'vocab.db'));
const app = createApp({ db });

serve({ fetch: app.fetch, port }, (info) => {
  console.log(`vocab server listening on http://localhost:${info.port}`);
});
