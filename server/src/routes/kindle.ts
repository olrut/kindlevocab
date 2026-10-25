import { Hono } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { z } from 'zod';
import type { AppEnv } from '../env.js';
import { HttpError, parseBody } from '../http.js';
import { KindleFileError } from '../kindle/vocabDb.js';
import { requireAuth } from './auth.js';

export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;
// Room for the multipart boundaries and the onlyNew field around a 50 MB file.
const MULTIPART_OVERHEAD = 64 * 1024;

const onlyNewField = z.enum(['true', 'false']).default('true');

const confirmBody = z.object({
  words: z.array(z.string().trim().min(1).max(100)).min(1, 'select at least one word').max(10_000),
  markMasteredAsLearned: z.boolean().default(true),
});

export const kindleRoutes = new Hono<AppEnv>()
  .use(requireAuth)
  .post(
    '/',
    bodyLimit({
      maxSize: MAX_UPLOAD_BYTES + MULTIPART_OVERHEAD,
      onError: () => {
        throw new HttpError(413, 'file too large (max 50 MB)');
      },
    }),
    async (c) => {
      let form: Record<string, string | File>;
      try {
        form = await c.req.parseBody();
      } catch {
        throw new HttpError(400, 'expected a multipart upload');
      }
      const file = form.file;
      if (!(file instanceof File)) throw new HttpError(400, 'file is required');
      if (file.size > MAX_UPLOAD_BYTES) throw new HttpError(413, 'file too large (max 50 MB)');
      const onlyNew = onlyNewField.safeParse(form.onlyNew ?? undefined);
      if (!onlyNew.success) throw new HttpError(400, 'onlyNew must be "true" or "false"');

      try {
        const preview = c
          .get('deps')
          .kindle.preview(c.get('user').id, new Uint8Array(await file.arrayBuffer()), onlyNew.data === 'true');
        return c.json(preview);
      } catch (err) {
        if (err instanceof KindleFileError) throw new HttpError(400, err.message);
        throw err;
      }
    },
  )
  .post('/:importId/confirm', async (c) => {
    const { words, markMasteredAsLearned } = await parseBody(c, confirmBody);
    const result = c.get('deps').kindle.confirm(c.get('user').id, c.req.param('importId'), words, markMasteredAsLearned);
    if (result === 'not_found') throw new HttpError(404, 'import not found');
    if (result === 'already_confirmed') throw new HttpError(409, 'import already confirmed');
    return c.json(c.get('deps').kindle.progress(c.get('user').id, c.req.param('importId')), 202);
  })
  .get('/:importId', (c) => {
    const progress = c.get('deps').kindle.progress(c.get('user').id, c.req.param('importId'));
    if (!progress) throw new HttpError(404, 'import not found');
    return c.json(progress);
  });
