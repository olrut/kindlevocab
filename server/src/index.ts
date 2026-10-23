import Anthropic from '@anthropic-ai/sdk';
import { serve } from '@hono/node-server';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { createApp } from './app.js';
import { createAudioService } from './audio.js';
import { openDb } from './db/index.js';
import { createLookupService } from './lookup/service.js';

const dataDir = process.env.DATA_DIR ?? './data';
const port = Number(process.env.PORT ?? 3000);
const model = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';

mkdirSync(dataDir, { recursive: true });
const db = openDb(join(dataDir, 'vocab.db'));
const now = () => new Date().toISOString();
const onWordWritten = () => {};

// Credentials come from ANTHROPIC_API_KEY. Without one the server still starts; /lookup returns 502.
const anthropic = new Anthropic({ timeout: 30_000, maxRetries: 2 });
const lookup = createLookupService({ db, fetch, anthropic, model, now });
const audio = createAudioService({ db, fetch, dataDir, now, onWordWritten });
const app = createApp({ db, now, onWordWritten, lookup, audio, dataDir });

serve({ fetch: app.fetch, port }, (info) => {
  console.log(`vocab server listening on http://localhost:${info.port} (lookup model ${model})`);
});
