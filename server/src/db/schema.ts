import { sql } from 'drizzle-orm';
import { index, integer, sqliteTable, text, unique } from 'drizzle-orm/sqlite-core';

// Mirrors docs/SPEC.md § 2. Timestamps are ISO-8601 UTC TEXT.
const nowDefault = sql`(strftime('%Y-%m-%dT%H:%M:%fZ','now'))`;

export const users = sqliteTable('users', {
  id: integer('id').primaryKey(),
  name: text('name').notNull().unique(),
  pinHash: text('pin_hash'),
  createdAt: text('created_at').notNull().default(nowDefault),
});

export const sessions = sqliteTable('sessions', {
  token: text('token').primaryKey(),
  userId: integer('user_id')
    .notNull()
    .references(() => users.id, { onDelete: 'cascade' }),
  device: text('device'),
  createdAt: text('created_at').notNull().default(nowDefault),
  lastSeen: text('last_seen'),
});

export const words = sqliteTable(
  'words',
  {
    id: integer('id').primaryKey(),
    userId: integer('user_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    word: text('word').notNull(),
    definition: text('definition').notNull(),
    example: text('example'),
    finnish: text('finnish'),
    ipa: text('ipa'),
    audioPath: text('audio_path'),
    source: text('source').notNull().default('claude'),
    status: text('status').notNull().default('learning'),
    learnedAt: text('learned_at'),
    bookTitle: text('book_title'),
    bookAuthor: text('book_author'),
    kindleLookupAt: text('kindle_lookup_at'),
    notes: text('notes'),
    createdAt: text('created_at').notNull().default(nowDefault),
    updatedAt: text('updated_at').notNull().default(nowDefault),
    deletedAt: text('deleted_at'),
  },
  (t) => [
    unique().on(t.userId, t.word),
    index('words_user_status').on(t.userId, t.status),
    index('words_user_updated').on(t.userId, t.updatedAt),
  ],
);

export const lookupCache = sqliteTable('lookup_cache', {
  cacheKey: text('cache_key').primaryKey(),
  payload: text('payload').notNull(),
  createdAt: text('created_at').notNull().default(nowDefault),
});

export const kindleImports = sqliteTable('kindle_imports', {
  id: integer('id').primaryKey(),
  userId: integer('user_id')
    .notNull()
    .references(() => users.id, { onDelete: 'cascade' }),
  importedAt: text('imported_at').notNull().default(nowDefault),
  wordsNew: integer('words_new').notNull(),
  wordsSkipped: integer('words_skipped').notNull(),
  lastKindleTimestamp: integer('last_kindle_timestamp'),
});

export type WordRow = typeof words.$inferSelect;
