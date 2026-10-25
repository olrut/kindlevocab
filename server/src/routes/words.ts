import { and, asc, eq, gt, isNull, type SQL } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { words, type WordRow } from '../db/schema.js';
import type { AppEnv } from '../env.js';
import { HttpError, parseBody, parseId } from '../http.js';
import { insertWord } from '../wordStore.js';
import { requireAuth } from './auth.js';

const status = z.enum(['learning', 'learned']);
const optionalText = z.string().nullish().transform((v) => v ?? null);

const createBody = z.object({
  word: z.string().trim().toLowerCase().min(1, 'word is required'),
  definition: z.string().trim().min(1, 'definition is required'),
  example: optionalText,
  finnish: optionalText,
  ipa: optionalText,
  source: z.enum(['claude', 'manual', 'import', 'kindle']),
});

const patchBody = z
  .object({
    status: status.optional(),
    definition: z.string().trim().min(1, 'definition cannot be empty').optional(),
    example: z.string().nullable().optional(),
    finnish: z.string().nullable().optional(),
    notes: z
      .string()
      .max(10_000, 'notes are too long')
      .nullable()
      .optional()
      .transform((v) => (v === undefined ? undefined : v?.trim() || null)),
  })
  .refine((b) => Object.values(b).some((v) => v !== undefined), 'nothing to update');

const listQuery = z.enum(['learning', 'learned', 'all']).default('learning');

const isoTimestamp = z.iso.datetime({ offset: true }).transform((s) => new Date(s).toISOString());

/** Word JSON shape from docs/SPEC.md § 3. */
export function toWordJson(w: WordRow) {
  return {
    id: w.id,
    word: w.word,
    definition: w.definition,
    example: w.example,
    finnish: w.finnish,
    ipa: w.ipa,
    hasAudio: w.audioPath !== null,
    source: w.source,
    status: w.status,
    learnedAt: w.learnedAt,
    bookTitle: w.bookTitle,
    bookAuthor: w.bookAuthor,
    notes: w.notes,
    createdAt: w.createdAt,
    updatedAt: w.updatedAt,
    deletedAt: w.deletedAt,
  };
}

function queryParam<T extends z.ZodType>(schema: T, value: string | undefined, name: string): z.infer<T> {
  const result = schema.safeParse(value);
  if (!result.success) throw new HttpError(400, `invalid ${name}`);
  return result.data;
}

export const wordsRoutes = new Hono<AppEnv>()
  .use(requireAuth)
  .post('/', async (c) => {
    const { db, now, onWordWritten, audio } = c.get('deps');
    const userId = c.get('user').id;
    const input = await parseBody(c, createBody);
    const ts = now();

    const row = insertWord(db, userId, input, ts);
    if (!row) throw new HttpError(409, 'word already exists');
    onWordWritten(row);
    audio.downloadFor(row);
    return c.json(toWordJson(row), 201);
  })
  .get('/', (c) => {
    const { db } = c.get('deps');
    const filter = queryParam(listQuery, c.req.query('status'), 'status');
    const conditions: SQL[] = [eq(words.userId, c.get('user').id), isNull(words.deletedAt)];
    if (filter !== 'all') conditions.push(eq(words.status, filter));
    const rows = db.select().from(words).where(and(...conditions)).orderBy(asc(words.id)).all();
    return c.json(rows.map(toWordJson));
  })
  // Registered before '/:id' so "changes" is not parsed as an id.
  .get('/changes', (c) => {
    const { db, now } = c.get('deps');
    const since = queryParam(isoTimestamp, c.req.query('since'), 'since');
    // Report one millisecond before "now": a write later in this same millisecond
    // would otherwise have updated_at == serverTime and be skipped by the next `>` pull.
    // Re-sending a row is harmless; clients upsert.
    const serverTime = new Date(Date.parse(now()) - 1).toISOString();
    const rows = db
      .select()
      .from(words)
      .where(and(eq(words.userId, c.get('user').id), gt(words.updatedAt, since)))
      .orderBy(asc(words.updatedAt), asc(words.id))
      .all();
    return c.json({ serverTime, words: rows.map(toWordJson) });
  })
  .get('/:id', (c) => {
    const { db } = c.get('deps');
    return c.json(toWordJson(findLiveWord(db, c.get('user').id, parseId(c.req.param('id')))));
  })
  .patch('/:id', async (c) => {
    const { db, now, onWordWritten } = c.get('deps');
    const current = findLiveWord(db, c.get('user').id, parseId(c.req.param('id')));
    const patch = await parseBody(c, patchBody);
    const ts = now();

    const changes: Partial<WordRow> = { ...patch, updatedAt: ts };
    if (patch.status === 'learned' && current.status !== 'learned') changes.learnedAt = ts;
    if (patch.status === 'learning') changes.learnedAt = null;

    const row = db.update(words).set(changes).where(eq(words.id, current.id)).returning().get();
    onWordWritten(row);
    return c.json(toWordJson(row));
  })
  .delete('/:id', (c) => {
    const { db, now, onWordWritten } = c.get('deps');
    const current = findLiveWord(db, c.get('user').id, parseId(c.req.param('id')));
    const ts = now();
    const row = db
      .update(words)
      .set({ deletedAt: ts, updatedAt: ts })
      .where(eq(words.id, current.id))
      .returning()
      .get();
    onWordWritten(row);
    return c.body(null, 204);
  });

function findLiveWord(db: AppEnv['Variables']['deps']['db'], userId: number, id: number): WordRow {
  const row = db
    .select()
    .from(words)
    .where(and(eq(words.id, id), eq(words.userId, userId), isNull(words.deletedAt)))
    .get();
  if (!row) throw new HttpError(404, 'word not found');
  return row;
}
