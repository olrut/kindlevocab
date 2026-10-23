import { eq } from 'drizzle-orm';
import { mkdir, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { Db } from './db/index.js';
import { words, type WordRow } from './db/schema.js';
import { findCachedAudioUrl } from './lookup/service.js';

export type AudioService = {
  /** Fire-and-forget: fetch the word's cached audio URL into data/audio/<id>.mp3. Failures are logged. */
  downloadFor(word: WordRow): void;
  /** Resolves when no download is in flight. For tests and shutdown. */
  whenIdle(): Promise<void>;
};

const MAX_BYTES = 5 * 1024 * 1024;
const TIMEOUT_MS = 15_000;

export const audioRelPath = (wordId: number) => `audio/${wordId}.mp3`;

export function createAudioService(deps: {
  db: Db;
  fetch: typeof fetch;
  dataDir: string;
  now: () => string;
  onWordWritten: (word: WordRow) => void;
}): AudioService {
  const inFlight = new Set<Promise<void>>();

  async function download(word: WordRow) {
    if (word.audioPath !== null) return;
    const url = findCachedAudioUrl(deps.db, word.word);
    if (!url) return;

    const res = await deps.fetch(url, { signal: AbortSignal.timeout(TIMEOUT_MS) });
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${url}`);
    const bytes = new Uint8Array(await res.arrayBuffer());
    if (bytes.length === 0 || bytes.length > MAX_BYTES) throw new Error(`unexpected size ${bytes.length} from ${url}`);

    const rel = audioRelPath(word.id);
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

    // Bump updated_at so sync clients see hasAudio flip; a write to words also feeds the exporter.
    const row = deps.db
      .update(words)
      .set({ audioPath: rel, updatedAt: deps.now() })
      .where(eq(words.id, word.id))
      .returning()
      .get();
    if (row) deps.onWordWritten(row);
  }

  return {
    downloadFor(word) {
      const job = download(word).catch((err: unknown) => {
        console.error(`audio download for word ${word.id} ("${word.word}") failed:`, err instanceof Error ? err.message : err);
      });
      inFlight.add(job);
      void job.finally(() => inFlight.delete(job));
    },
    async whenIdle() {
      while (inFlight.size > 0) await Promise.all([...inFlight]);
    },
  };
}
