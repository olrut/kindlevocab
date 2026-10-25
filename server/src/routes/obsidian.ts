import { Hono } from 'hono';
import type { AppEnv } from '../env.js';
import { HttpError } from '../http.js';
import { requireAuth } from './auth.js';

export const obsidianRoutes = new Hono<AppEnv>()
  .use(requireAuth)
  .get('/status', (c) => {
    const { obsidian } = c.get('deps');
    return c.json({ enabled: obsidian.enabled, lastRun: obsidian.lastRun(c.get('user').id) });
  })
  .post('/export-all', async (c) => {
    const { obsidian } = c.get('deps');
    if (!obsidian.enabled) throw new HttpError(503, 'Obsidian export is disabled (OBSIDIAN_DIR is not set)');
    return c.json(await obsidian.exportAll(c.get('user').id));
  });
