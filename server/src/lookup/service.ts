import Anthropic from '@anthropic-ai/sdk';
import { desc, eq, or, sql } from 'drizzle-orm';
import { createHash } from 'node:crypto';
import { z } from 'zod';
import type { Db } from '../db/index.js';
import { lookupCache } from '../db/schema.js';
import { askClaude, ClaudeReplyError, type Candidate } from './claude.js';
import { lookupDictionary } from './dictionary.js';

export type LookupResponse = {
  word: string;
  ipa: string | null;
  audioAvailable: boolean;
  candidates: Candidate[];
  cached: boolean;
};
export type LookupOutcome = { kind: 'ok'; response: LookupResponse } | { kind: 'not_english'; suggestion: string | null };
export type LookupService = { lookup(word: string, context?: string): Promise<LookupOutcome> };

/** Claude was unreachable or its reply was unusable. Maps to 502; nothing is cached. */
export class LookupFailedError extends Error {}

/** What lookup_cache.payload holds: the client response plus the internal audio URL. */
const cachedPayload = z.object({
  word: z.string(),
  ipa: z.string().nullable(),
  audioAvailable: z.boolean(),
  candidates: z.array(z.object({ definition: z.string(), example: z.string(), finnish: z.string() })),
  audioUrl: z.string().nullable(),
});
type CachedPayload = z.infer<typeof cachedPayload>;

export const normalizeWord = (word: string) => word.trim().toLowerCase();

export const cacheKey = (word: string, context?: string) =>
  context === undefined ? word : `${word}|${createHash('sha1').update(context).digest('hex')}`;

function readCache(db: Db, key: string): CachedPayload | null {
  const row = db.select().from(lookupCache).where(eq(lookupCache.cacheKey, key)).get();
  if (!row) return null;
  const parsed = cachedPayload.safeParse(JSON.parse(row.payload));
  return parsed.success ? parsed.data : null;
}

/**
 * Audio URL from the newest cached lookup of `word`, with or without context.
 * Kindle imports only ever look words up with a sentence, so both key forms count.
 */
export function findCachedAudioUrl(db: Db, word: string): string | null {
  const rows = db
    .select()
    .from(lookupCache)
    // instr(...) = 1 is a literal prefix match; LIKE would treat % and _ in the word as wildcards.
    .where(or(eq(lookupCache.cacheKey, word), sql`instr(${lookupCache.cacheKey}, ${`${word}|`}) = 1`))
    .orderBy(desc(lookupCache.createdAt))
    .all();
  for (const row of rows) {
    const parsed = cachedPayload.safeParse(JSON.parse(row.payload));
    if (parsed.success && parsed.data.audioUrl) return parsed.data.audioUrl;
  }
  return null;
}

export function createLookupService(deps: {
  db: Db;
  fetch: typeof fetch;
  anthropic: Anthropic;
  model: string;
  now: () => string;
}): LookupService {
  const { db } = deps;

  return {
    async lookup(rawWord, context) {
      const word = normalizeWord(rawWord);
      const key = cacheKey(word, context);
      const hit = readCache(db, key);
      if (hit) return { kind: 'ok', response: toResponse(hit, true) };

      const [dictionary, claude] = await Promise.all([
        lookupDictionary(deps.fetch, word),
        // Everything askClaude can throw means "no usable answer from Claude": an unusable reply,
        // an API/connection error, or client setup such as missing credentials (a plain Error).
        askClaude(deps.anthropic, deps.model, word, context).catch((err: unknown) => {
          if (err instanceof ClaudeReplyError) throw new LookupFailedError(err.message);
          if (err instanceof Anthropic.APIError) throw new LookupFailedError(`Claude API error ${err.status ?? ''}`.trim());
          throw new LookupFailedError(err instanceof Error ? err.message : String(err));
        }),
      ]);
      if (claude.kind === 'not_english') return claude;

      const payload: CachedPayload = {
        word,
        ipa: dictionary.ipa,
        audioAvailable: dictionary.audioUrl !== null,
        candidates: claude.candidates,
        audioUrl: dictionary.audioUrl,
      };
      db.insert(lookupCache)
        .values({ cacheKey: key, payload: JSON.stringify(payload), createdAt: deps.now() })
        .onConflictDoNothing()
        .run();
      return { kind: 'ok', response: toResponse(payload, false) };
    },
  };
}

function toResponse({ audioUrl: _internal, ...rest }: CachedPayload, cached: boolean): LookupResponse {
  return { ...rest, cached };
}
