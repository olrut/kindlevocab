import bcrypt from 'bcryptjs';
import { asc } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { users } from '../db/schema.js';
import type { AppEnv } from '../env.js';
import { HttpError, isUniqueViolation, parseBody } from '../http.js';

const createUserBody = z.object({
  name: z.string().trim().min(1, 'name is required'),
  pin: z.string().min(1).optional(),
});

const toJson = (u: typeof users.$inferSelect) => ({ id: u.id, name: u.name, hasPin: u.pinHash !== null });

export const usersRoutes = new Hono<AppEnv>()
  .get('/', (c) => {
    const { db } = c.get('deps');
    return c.json(db.select().from(users).orderBy(asc(users.id)).all().map(toJson));
  })
  .post('/', async (c) => {
    const { db, now } = c.get('deps');
    const { name, pin } = await parseBody(c, createUserBody);
    const pinHash = pin ? await bcrypt.hash(pin, 10) : null;
    try {
      const row = db.insert(users).values({ name, pinHash, createdAt: now() }).returning().get();
      return c.json(toJson(row), 201);
    } catch (err) {
      if (isUniqueViolation(err)) throw new HttpError(409, 'name already taken');
      throw err;
    }
  });
