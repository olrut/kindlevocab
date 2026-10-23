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

export const lookupRoutes = new Hono<AppEnv>().use(requireAuth).post('/', async (c) => {
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
