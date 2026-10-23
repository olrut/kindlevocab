import { and, eq, isNull } from 'drizzle-orm';
import { Hono } from 'hono';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { audioRelPath } from '../audio.js';
import { words } from '../db/schema.js';
import type { AppEnv } from '../env.js';
import { HttpError, parseId } from '../http.js';
import { requireAuth } from './auth.js';

export const audioRoutes = new Hono<AppEnv>().use(requireAuth).get('/:id', async (c) => {
  const { db, dataDir } = c.get('deps');
  const id = parseId(c.req.param('id'));
  const row = db
    .select({ audioPath: words.audioPath })
    .from(words)
    .where(and(eq(words.id, id), eq(words.userId, c.get('user').id), isNull(words.deletedAt)))
    .get();
  if (!row?.audioPath) throw new HttpError(404, 'no audio');

  // audio_path is written only by the audio service, but serve the canonical path regardless.
  let bytes: Buffer;
  try {
    bytes = await readFile(join(dataDir, audioRelPath(id)));
  } catch {
    throw new HttpError(404, 'no audio');
  }
  return c.body(new Uint8Array(bytes), 200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'private, max-age=86400' });
});
