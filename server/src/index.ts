import Anthropic from '@anthropic-ai/sdk';
import { serve } from '@hono/node-server';
import { existsSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createApp } from './app.js';
import { createAudioService } from './audio.js';
import { openDb } from './db/index.js';
import { createKindleImporter } from './kindle/importer.js';
import { createLookupService } from './lookup/service.js';
import { createObsidianExporter } from './obsidian/exporter.js';

const dataDir = process.env.DATA_DIR ?? './data';
const port = Number(process.env.PORT ?? 3000);
const obsidianDir = process.env.OBSIDIAN_DIR || null;
// `npm run build` in web/ writes here. Absent in development, where Vite serves the UI.
const publicDir = process.env.PUBLIC_DIR || fileURLToPath(new URL('../public', import.meta.url));
const model = process.env.ANTHROPIC_MODEL || 'claude-haiku-5-5';

mkdirSync(dataDir, { recursive: true });
const db = openDb(join(dataDir, 'vocab.db'));
const now = () => new Date().toISOString();
const obsidian = createObsidianExporter({ db, dir: obsidianDir, now });
const onWordWritten = obsidian.notify;

// Credentials come from ANTHROPIC_API_KEY. Without one the server still starts; /lookup returns 502.
const anthropic = new Anthropic({ timeout: 30_000, maxRetries: 2 });
const lookup = createLookupService({ db, fetch, anthropic, model, now });
const audio = createAudioService({ db, fetch, dataDir, now, onWordWritten });
const kindle = createKindleImporter({ db, lookup, audio, dataDir, now, onWordWritten });
const app = createApp({
  db,
  now,
  onWordWritten,
  lookup,
  audio,
  kindle,
  obsidian,
  dataDir,
  publicDir: existsSync(join(publicDir, 'index.html')) ? publicDir : null,
});

serve({ fetch: app.fetch, port }, (info) => {
  console.log(`vocab server listening on http://localhost:${info.port} (lookup model ${model})`);
});

// Catch up on anything a crash or restart lost from the debounce window.
if (obsidian.enabled) {
  obsidian.exportAll().then(
    (n) => console.log(`obsidian: exported ${n.words} words, ${n.books} books to ${obsidianDir}`),
    (err: unknown) => console.error('obsidian: startup export failed:', err),
  );
} else {
  console.log('obsidian: export disabled (OBSIDIAN_DIR not set)');
}
