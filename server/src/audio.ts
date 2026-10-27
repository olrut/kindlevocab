import { eq } from 'drizzle-orm';
import { mkdir, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { Db } from './db/index.js';
import { words, type WordRow } from './db/schema.js';
import { lookupDictionary } from './lookup/dictionary.js';
import { findCachedAudioUrl, normalizeWord } from './lookup/service.js';

export type AudioService = {
  /** Fire-and-forget: fetch the word's cached audio URL into data/audio/<id>.mp3. Failures are logged. */
  downloadFor(word: WordRow): void;
  /**
   * Fire-and-forget, queued: fill a missing IPA and audio from the dictionary API, without Claude.
   * For imported words, which never went through /lookup. Failures are logged.
   */
  enrich(word: WordRow): void;
  /** Resolves when no download or enrichment is in flight or queued. For tests and shutdown. */
  whenIdle(): Promise<void>;
  /** Audio for a looked-up word that may not be saved yet (the /add page), or null. Failures are logged. */
  preview(word: string): Promise<Uint8Array<ArrayBuffer> | null>;
};

const MAX_BYTES = 5 * 1024 * 1024;
const TIMEOUT_MS = 15_000;
/** Dictionary lookups running at once for `enrich`; the Free Dictionary API is a free service. */
export const MAX_CONCURRENT_ENRICH = 2;

export const audioRelPath = (wordId: number) => `audio/${wordId}.mp3`;

export function createAudioService(deps: {
  db: Db;
  fetch: typeof fetch;
  dataDir: string;
  now: () => string;
  onWordWritten: (word: WordRow) => void;
}): AudioService {
  const inFlight = new Set<Promise<void>>();

  async function fetchBytes(url: string) {
    const res = await deps.fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${url}`);
    const bytes = new Uint8Array(await res.arrayBuffer());
    if (bytes.length === 0 || bytes.length > MAX_BYTES) throw new Error(`unexpected size ${bytes.length} from ${url}`);
    return bytes;
  }

  /** Saves the audio at `url` as data/audio/<id>.mp3 and returns its relative path. */
  async function save(wordId: number, url: string) {
    const bytes = await fetchBytes(url);
    const rel = audioRelPath(wordId);
    const file = join(deps.dataDir, rel);
    const tmp = `${file}.${process.pid}.tmp`;
    await mkdir(join(deps.dataDir, 'audio'), { recursive: true });
    try {
      await writeFile(tmp, bytes);
      await rename(tmp, file);
    } catch (err) {
      await rm(tmp, { force: true });
      throw err;
    }
    return rel;
  }

  // Bump updated_at so sync clients see the change; a write to words also feeds the exporter.
  function write(wordId: number, changes: Partial<Pick<WordRow, 'audioPath' | 'ipa'>>) {
    const row = deps.db
      .update(words)
      .set({ ...changes, updatedAt: deps.now() })
      .where(eq(words.id, wordId))
      .returning()
      .get();
    if (row) deps.onWordWritten(row);
  }

  async function download(word: WordRow) {
    if (word.audioPath !== null) return;
    const url = findCachedAudioUrl(deps.db, word.word);
    if (!url) return;
    write(word.id, { audioPath: await save(word.id, url) });
  }

  async function enrichNow(wordId: number) {
    // Re-read: the word may have changed or been deleted while it waited in the queue.
    const word = deps.db.select().from(words).where(eq(words.id, wordId)).get();
    if (!word || word.deletedAt !== null) return;
    const needAudio = word.audioPath === null;
    if (word.ipa !== null && !needAudio) return;

    let url = needAudio ? findCachedAudioUrl(deps.db, word.word) : null;
    let ipa: string | null = null;
    if (word.ipa === null || (needAudio && !url)) {
      const found = await lookupDictionary(deps.fetch, word.word);
      ipa = word.ipa === null ? found.ipa : null;
      url ??= needAudio ? found.audioUrl : null;
    }

    const changes: Partial<Pick<WordRow, 'audioPath' | 'ipa'>> = {};
    if (ipa) changes.ipa = ipa;
    if (url) {
      try {
        changes.audioPath = await save(word.id, url);
      } catch (err) {
        console.error(`audio download for word ${word.id} ("${word.word}") failed:`, err instanceof Error ? err.message : err);
      }
    }
    if (Object.keys(changes).length > 0) write(word.id, changes);
  }

  const queue: number[] = [];
  let workers = 0;
  async function work() {
    try {
      for (let id = queue.shift(); id !== undefined; id = queue.shift()) {
        await enrichNow(id).catch((err: unknown) => {
          console.error(`dictionary lookup for word ${id} failed:`, err instanceof Error ? err.message : err);
        });
      }
    } finally {
      // Runs synchronously once the queue is empty, so an enrich() call cannot strand a word.
      workers--;
    }
  }

  function track(job: Promise<void>) {
    inFlight.add(job);
    void job.finally(() => inFlight.delete(job));
  }

  return {
    downloadFor(word) {
      const job = download(word).catch((err: unknown) => {
        console.error(`audio download for word ${word.id} ("${word.word}") failed:`, err instanceof Error ? err.message : err);
      });
      track(job);
    },
    enrich(word) {
      queue.push(word.id);
      if (workers < MAX_CONCURRENT_ENRICH) {
        workers++;
        track(work());
      }
    },
    async preview(word) {
      const url = findCachedAudioUrl(deps.db, normalizeWord(word));
      if (!url) return null;
      try {
        return await fetchBytes(url);
      } catch (err) {
        console.error(`audio preview for "${word}" failed:`, err instanceof Error ? err.message : err);
        return null;
      }
    },
    async whenIdle() {
      while (inFlight.size > 0) await Promise.all([...inFlight]);
    },
  };
}
