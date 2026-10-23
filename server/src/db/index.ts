import Database from 'better-sqlite3';
import { drizzle } from 'drizzle-orm/better-sqlite3';
import { migrate } from 'drizzle-orm/better-sqlite3/migrator';
import { fileURLToPath } from 'node:url';
import * as schema from './schema.js';

const migrationsFolder = fileURLToPath(new URL('../../drizzle', import.meta.url));

export type Db = ReturnType<typeof openDb>;

/** Opens (or creates) the database and applies pending migrations. Use ':memory:' in tests. */
export function openDb(path: string) {
  const sqlite = new Database(path);
  sqlite.pragma('journal_mode = WAL');
  sqlite.pragma('foreign_keys = ON');
  const db = drizzle(sqlite, { schema });
  migrate(db, { migrationsFolder });
  return db;
}
