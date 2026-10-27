import { and, asc, eq, isNull } from 'drizzle-orm';
import { Hono } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { z } from 'zod';
import { words, type WordRow } from '../db/schema.js';
import type { AppEnv } from '../env.js';
import { HttpError } from '../http.js';
import { exportCsv, exportJson, ImportFileError, parseImport } from '../transfer/format.js';
import { insertWord } from '../wordStore.js';
import { requireAuth } from './auth.js';

export const MAX_IMPORT_BYTES = 10 * 1024 * 1024;
const MULTIPART_OVERHEAD = 64 * 1024;

const formatQuery = z.enum(['json', 'csv']).default('json');

/** `GET /export` and `POST /import` (docs/SPEC.md § 3, Export / import). Mounted at the API root. */
export const transferRoutes = new Hono<AppEnv>()
  .get('/export', requireAuth, (c) => {
    const { db, now } = c.get('deps');
    const user = c.get('user');
    const format = formatQuery.safeParse(c.req.query('format'));
    if (!format.success) throw new HttpError(400, 'format must be "json" or "csv"');

    const rows = db
      .select()
      .from(words)
      .where(and(eq(words.userId, user.id), isNull(words.deletedAt)))
      .orderBy(asc(words.id))
      .all();
    const filename = `vocab-${user.name.replace(/[^\p{L}\p{N}_-]+/gu, '_')}-${now().slice(0, 10)}.${format.data}`;
    c.header('Content-Disposition', `attachment; filename="${filename}"`);
    if (format.data === 'json') return c.body(exportJson(rows), 200, { 'Content-Type': 'application/json; charset=utf-8' });
    // The BOM makes Excel read the file as UTF-8 (ä, ö, IPA).
    return c.body(`﻿${exportCsv(rows)}`, 200, { 'Content-Type': 'text/csv; charset=utf-8' });
  })
  .post(
    '/import',
    requireAuth,
    bodyLimit({
      maxSize: MAX_IMPORT_BYTES + MULTIPART_OVERHEAD,
      onError: () => {
        throw new HttpError(413, 'file too large (max 10 MB)');
      },
    }),
    async (c) => {
      const { db, now, onWordWritten, audio } = c.get('deps');
      let form: Record<string, string | File>;
      try {
        form = await c.req.parseBody();
      } catch {
        throw new HttpError(400, 'expected a multipart upload');
      }
      const file = form.file;
      if (!(file instanceof File)) throw new HttpError(400, 'file is required');
      if (file.size > MAX_IMPORT_BYTES) throw new HttpError(413, 'file too large (max 10 MB)');
      const format = /\.csv$/i.test(file.name) ? 'csv' : /\.json$/i.test(file.name) ? 'json' : null;
      if (!format) throw new HttpError(400, 'expected a .csv or .json file');

      let parsed;
      try {
        parsed = parseImport(await file.text(), format);
      } catch (err) {
        if (err instanceof ImportFileError) throw new HttpError(400, err.message);
        throw err;
      }

      const userId = c.get('user').id;
      const ts = now();
      // All or nothing, and one transaction is far faster than a commit per word.
      const inserted = db.transaction(() => {
        const rows: WordRow[] = [];
        for (const w of parsed) {
          const row = insertWord(db, userId, { ...w, source: 'import' }, ts);
          if (row) rows.push(row);
        }
        return rows;
      });
      for (const row of inserted) {
        onWordWritten(row);
        audio.enrich(row);
      }
      return c.json({ imported: inserted.length, skipped: parsed.length - inserted.length });
    },
  );
