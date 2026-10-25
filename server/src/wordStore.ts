import { and, eq } from 'drizzle-orm';
import type { Db } from './db/index.js';
import { words, type WordRow } from './db/schema.js';
import { isUniqueViolation } from './http.js';

export type NewWord = {
  word: string;
  definition: string;
  example: string | null;
  finnish: string | null;
  ipa: string | null;
  source: 'claude' | 'manual' | 'import' | 'kindle';
  status?: 'learning' | 'learned';
  bookTitle?: string | null;
  bookAuthor?: string | null;
  kindleLookupAt?: string | null;
};

/** Live (non-deleted) word of a user by its normalised text. */
export function findWordByText(db: Db, userId: number, word: string): WordRow | undefined {
  const row = db
    .select()
    .from(words)
    .where(and(eq(words.userId, userId), eq(words.word, word)))
    .get();
  return row && row.deletedAt === null ? row : undefined;
}

/**
 * Inserts a word, or revives a soft-deleted one with the same text as a fresh word.
 * Returns null if a live word with that text already exists.
 */
export function insertWord(db: Db, userId: number, input: NewWord, ts: string): WordRow | null {
  const status = input.status ?? 'learning';
  const values = {
    bookTitle: null,
    bookAuthor: null,
    kindleLookupAt: null,
    notes: null,
    ...input,
    status,
    learnedAt: status === 'learned' ? ts : null,
    updatedAt: ts,
  };
  const existing = db
    .select()
    .from(words)
    .where(and(eq(words.userId, userId), eq(words.word, input.word)))
    .get();
  if (existing && existing.deletedAt === null) return null;
  if (existing) {
    return db
      .update(words)
      .set({ ...values, deletedAt: null })
      .where(eq(words.id, existing.id))
      .returning()
      .get();
  }
  try {
    return db
      .insert(words)
      .values({ ...values, userId, createdAt: ts })
      .returning()
      .get();
  } catch (err) {
    if (isUniqueViolation(err)) return null;
    throw err;
  }
}
