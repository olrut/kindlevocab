import { Hono } from 'hono';
import { z } from 'zod';
import type { AppEnv } from '../env.js';
import { HttpError, parseBody } from '../http.js';
import { LookupFailedError } from '../lookup/service.js';
import { requireAuth } from './auth.js';

const lookupBody = z.object({
  word: z.string().trim().min(1, 'word is required').max(100, 'word is too long'),
  context: z.string().trim().min(1).max(2000).optional(),
});

export const lookupRoutes = new Hono<AppEnv>()
  .use(requireAuth)
  .get('/audio/:word', async (c) => {
    const bytes = await c.get('deps').audio.preview(c.req.param('word'));
    if (!bytes) throw new HttpError(404, 'no audio');
    return c.body(bytes, 200, { 'Content-Type': 'audio/mpeg', 'Cache-Control': 'private, max-age=86400' });
  })
  .post('/', async (c) => {
    const { word, context } = await parseBody(c, lookupBody);
    try {
      const outcome = await c.get('deps').lookup.lookup(word, context);
      if (outcome.kind === 'not_english') {
        throw new HttpError(422, 'not_english', { suggestion: outcome.suggestion });
      }
      return c.json(outcome.response);
    } catch (err) {
      if (err instanceof LookupFailedError) {
        console.error(`lookup "${word}" failed: ${err.message}`);
        throw new HttpError(502, 'lookup failed');
      }
      throw err;
    }
  });
