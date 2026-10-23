import bcrypt from 'bcryptjs';
import { eq } from 'drizzle-orm';
import { Hono, type MiddlewareHandler } from 'hono';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import { sessions, users } from '../db/schema.js';
import type { AppEnv } from '../env.js';
import { HttpError, parseBody } from '../http.js';

const loginBody = z.object({
  userId: z.number().int(),
  pin: z.string().optional(),
  device: z.string().min(1, 'device is required'),
});

/** Resolves `Authorization: Bearer <token>` to a user, or responds 401. */
export const requireAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const { db, now } = c.get('deps');
  const match = /^Bearer (.+)$/.exec(c.req.header('Authorization') ?? '');
  if (!match) throw new HttpError(401, 'missing bearer token');
  const token = match[1];
  const row = db
    .select({ id: users.id, name: users.name })
    .from(sessions)
    .innerJoin(users, eq(users.id, sessions.userId))
    .where(eq(sessions.token, token))
    .get();
  if (!row) throw new HttpError(401, 'invalid token');
  db.update(sessions).set({ lastSeen: now() }).where(eq(sessions.token, token)).run();
  c.set('user', row);
  c.set('token', token);
  await next();
};

export const authRoutes = new Hono<AppEnv>()
  .post('/login', async (c) => {
    const { db, now } = c.get('deps');
    const { userId, pin, device } = await parseBody(c, loginBody);
    const user = db.select().from(users).where(eq(users.id, userId)).get();
    if (!user) throw new HttpError(404, 'user not found');
    if (user.pinHash !== null && !(pin && (await bcrypt.compare(pin, user.pinHash)))) {
      throw new HttpError(403, 'wrong PIN');
    }
    const token = randomBytes(32).toString('base64url');
    const ts = now();
    db.insert(sessions).values({ token, userId: user.id, device, createdAt: ts, lastSeen: ts }).run();
    return c.json({ token, user: { id: user.id, name: user.name } });
  })
  .post('/logout', requireAuth, (c) => {
    const { db } = c.get('deps');
    db.delete(sessions).where(eq(sessions.token, c.get('token'))).run();
    return c.body(null, 204);
  })
  .get('/me', requireAuth, (c) => c.json(c.get('user')));
