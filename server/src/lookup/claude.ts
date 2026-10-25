import type Anthropic from '@anthropic-ai/sdk';
import { z } from 'zod';

// Prompt text is docs/SPEC.md § 4 verbatim.
const SYSTEM_PROMPT = `You are a dictionary for an English learner whose native language is Finnish.
Given one English word, return ONLY a JSON object, no prose:

{"candidates":[{"definition":"...","example":"...","finnish":"..."}]}

Rules:
- 1 to 3 candidates, most common meaning first. Separate candidates only for clearly different senses.
- definition: plain English, max 12 words, no leading article, no repetition of the word.
- example: one natural sentence using the word, max 15 words.
- finnish: 1-3 Finnish equivalents separated by commas, base form.
- If the input is not an English word or is misspelled, return {"error":"not_english","suggestion":"<likely intended word or null>"}.`;

const contextBlock = (context: string) => `

The user saw the word in this sentence: "${context}"
Return exactly one candidate: the sense that fits this sentence. Use the given sentence verbatim as the example.
If the word in the sentence is an inflected form, still define the base form.`;

const candidateSchema = z.object({
  definition: z.string().trim().min(1),
  example: z.string().trim().min(1),
  finnish: z.string().trim().min(1),
});
const candidatesReply = z.object({ candidates: z.array(candidateSchema).min(1).max(3) });
const notEnglishReply = z.object({ error: z.literal('not_english'), suggestion: z.string().nullable() });

export type Candidate = z.infer<typeof candidateSchema>;
export type ClaudeResult =
  | { kind: 'candidates'; candidates: Candidate[] }
  | { kind: 'not_english'; suggestion: string | null };

/** The reply could not be used. The lookup must fail with 502 and nothing is cached. */
export class ClaudeReplyError extends Error {}

export function buildSystemPrompt(context?: string): string {
  return context ? SYSTEM_PROMPT + contextBlock(context) : SYSTEM_PROMPT;
}

export function parseClaudeReply(text: string, context?: string): ClaudeResult {
  const body = text
    .trim()
    .replace(/^```(?:json)?\s*/i, '')
    .replace(/\s*```$/, '');
  let raw: unknown;
  try {
    raw = JSON.parse(body);
  } catch {
    throw new ClaudeReplyError('reply is not JSON');
  }

  const notEnglish = notEnglishReply.safeParse(raw);
  if (notEnglish.success) return { kind: 'not_english', suggestion: notEnglish.data.suggestion };

  const parsed = candidatesReply.safeParse(raw);
  if (!parsed.success) throw new ClaudeReplyError(`reply does not match schema: ${parsed.error.issues[0].message}`);
  if (context === undefined) return { kind: 'candidates', candidates: parsed.data.candidates };

  if (parsed.data.candidates.length !== 1) throw new ClaudeReplyError('expected exactly one candidate for context');
  // The spec promises the book sentence as the example; don't trust the model to copy it exactly.
  return { kind: 'candidates', candidates: [{ ...parsed.data.candidates[0], example: context }] };
}

export async function askClaude(
  client: Anthropic,
  model: string,
  word: string,
  context?: string,
): Promise<ClaudeResult> {
  const message = await client.messages.create({
    model,
    // Haiku 5.5 thinks by default and thinking counts toward max_tokens; low effort keeps
    // it short, and 1024 leaves room for it plus the ~150-token JSON answer.
    max_tokens: 1024,
    output_config: { effort: 'low' },
    system: buildSystemPrompt(context),
    messages: [{ role: 'user', content: word }],
  });
  if (message.stop_reason === 'refusal') throw new ClaudeReplyError('model declined the request');
  if (message.stop_reason === 'max_tokens') throw new ClaudeReplyError('reply was cut off at max_tokens');
  const text = message.content.flatMap((b) => (b.type === 'text' ? [b.text] : [])).join('');
  return parseClaudeReply(text, context);
}
