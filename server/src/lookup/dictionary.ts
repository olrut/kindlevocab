import { z } from 'zod';

export type DictionaryResult = { ipa: string | null; audioUrl: string | null };

const NONE: DictionaryResult = { ipa: null, audioUrl: null };

const entries = z.array(
  z.object({
    phonetic: z.string().optional(),
    phonetics: z.array(z.object({ text: z.string().optional(), audio: z.string().optional() })).default([]),
  }),
);

/** Free Dictionary API lookup for IPA and an audio URL. Never throws: any failure yields nulls. */
export async function lookupDictionary(
  fetchFn: typeof fetch,
  word: string,
  timeoutMs = 5000,
): Promise<DictionaryResult> {
  try {
    const url = `https://api.dictionaryapi.dev/api/v2/entries/en/${encodeURIComponent(word)}`;
    const res = await fetchFn(url, { signal: AbortSignal.timeout(timeoutMs) });
    if (!res.ok) return NONE;
    const parsed = entries.safeParse(await res.json());
    if (!parsed.success) return NONE;

    const phonetics = parsed.data.flatMap((e) => e.phonetics);
    const ipa = phonetics.find((p) => p.text)?.text ?? parsed.data.find((e) => e.phonetic)?.phonetic ?? null;
    const audio = phonetics.find((p) => p.audio)?.audio ?? null;
    return { ipa, audioUrl: audio?.startsWith('//') ? `https:${audio}` : audio };
  } catch {
    return NONE;
  }
}
