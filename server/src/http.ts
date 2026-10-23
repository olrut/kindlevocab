import type { Context } from 'hono';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import type { z } from 'zod';

export class HttpError extends Error {
  constructor(
    readonly status: ContentfulStatusCode,
    message: string,
  ) {
    super(message);
  }
}

/** Parses the JSON body against a zod schema; throws 400 on malformed JSON or schema mismatch. */
export async function parseBody<T extends z.ZodType>(c: Context, schema: T): Promise<z.infer<T>> {
  let raw: unknown;
  try {
    raw = await c.req.json();
  } catch {
    throw new HttpError(400, 'invalid JSON body');
  }
  const result = schema.safeParse(raw);
  if (!result.success) {
    const issue = result.error.issues[0];
    const where = issue.path.length ? `${issue.path.join('.')}: ` : '';
    throw new HttpError(400, `${where}${issue.message}`);
  }
  return result.data;
}

export function parseId(raw: string): number {
  const id = Number(raw);
  if (!Number.isSafeInteger(id) || id <= 0) throw new HttpError(404, 'not found');
  return id;
}

/** True for SQLite UNIQUE constraint violations raised by better-sqlite3. */
export function isUniqueViolation(err: unknown): boolean {
  return (err as { code?: string })?.code === 'SQLITE_CONSTRAINT_UNIQUE';
}
