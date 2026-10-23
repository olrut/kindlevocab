type Handler = (req: { url: URL; body: any; signal?: AbortSignal | null }) => Response | Promise<Response>;

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

/** A Messages API response whose text block is `text`, preceded by an (omitted-display) thinking block. */
export function claudeMessage(text: string, stopReason = 'end_turn') {
  return json({
    id: 'msg_test',
    type: 'message',
    role: 'assistant',
    model: 'claude-haiku-5-5',
    content: [
      { type: 'thinking', thinking: '', signature: 'sig' },
      { type: 'text', text },
    ],
    stop_reason: stopReason,
    stop_sequence: null,
    usage: { input_tokens: 10, output_tokens: 10 },
  });
}

export const claudeJson = (body: unknown) => claudeMessage(JSON.stringify(body));

export const dictionaryEntry = (ipa: string | null, audio: string | null) => [
  { word: 'x', phonetics: [{ text: ipa ?? undefined, audio: audio ?? '' }] },
];

export const MP3_BYTES = new Uint8Array([0x49, 0x44, 0x33, 0x04, 0x00, 0x00]);

/**
 * Fake `fetch` routing by host: api.anthropic.com → `claude`, api.dictionaryapi.dev → `dictionary`,
 * anything else → `media`. Never touches the network. Every request is recorded in `requests`.
 */
export function fakeHttp() {
  const requests: { host: string; url: URL; body: any }[] = [];
  const handlers: { claude: Handler; dictionary: Handler; media: Handler } = {
    claude: () => claudeJson({ candidates: [{ definition: 'd', example: 'e', finnish: 'f' }] }),
    dictionary: () => json(dictionaryEntry('/ipa/', 'https://media.test/x.mp3')),
    media: () => new Response(MP3_BYTES, { headers: { 'Content-Type': 'audio/mpeg' } }),
  };

  const fetch = async (input: string | URL | Request, init?: RequestInit): Promise<Response> => {
    const url = new URL(input instanceof Request ? input.url : input.toString());
    const rawBody = init?.body ?? (input instanceof Request ? await input.text() : undefined);
    const body = typeof rawBody === 'string' && rawBody ? JSON.parse(rawBody) : undefined;
    requests.push({ host: url.host, url, body });
    const signal = init?.signal ?? (input instanceof Request ? input.signal : undefined);
    const req = { url, body, signal };
    if (url.host === 'api.anthropic.com') return handlers.claude(req);
    if (url.host === 'api.dictionaryapi.dev') return handlers.dictionary(req);
    return handlers.media(req);
  };

  const of = (host: string) => requests.filter((r) => r.host === host);
  return {
    fetch: fetch as typeof globalThis.fetch,
    handlers,
    requests,
    claudeRequests: () => of('api.anthropic.com'),
    dictionaryRequests: () => of('api.dictionaryapi.dev'),
  };
}
