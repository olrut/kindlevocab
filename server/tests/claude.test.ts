import { describe, expect, it } from 'vitest';
import { buildSystemPrompt, ClaudeReplyError, parseClaudeReply } from '../src/lookup/claude.js';

const candidate = {
  definition: 'present or found everywhere',
  example: 'Smartphones are ubiquitous these days.',
  finnish: 'kaikkialla läsnä oleva, yleinen',
};

describe('buildSystemPrompt', () => {
  it('is the SPEC § 4 prompt without context', () => {
    const prompt = buildSystemPrompt();
    expect(prompt).toMatch(/^You are a dictionary for an English learner whose native language is Finnish\./);
    expect(prompt).toContain('{"candidates":[{"definition":"...","example":"...","finnish":"..."}]}');
    expect(prompt).toContain('{"error":"not_english","suggestion":"<likely intended word or null>"}');
    expect(prompt).not.toContain('The user saw the word');
  });

  it('appends the context block when a sentence is given', () => {
    const prompt = buildSystemPrompt('Phones were ubiquitous.');
    expect(prompt.startsWith(buildSystemPrompt())).toBe(true);
    expect(prompt).toContain('The user saw the word in this sentence: "Phones were ubiquitous."');
    expect(prompt).toContain('Return exactly one candidate');
    expect(prompt).toContain('If the word in the sentence is an inflected form, still define the base form.');
  });
});

describe('parseClaudeReply without context', () => {
  it('parses 1 to 3 candidates', () => {
    const reply = JSON.stringify({ candidates: [candidate, { ...candidate, definition: 'second' }] });
    expect(parseClaudeReply(reply)).toEqual({
      kind: 'candidates',
      candidates: [candidate, { ...candidate, definition: 'second' }],
    });
  });

  it('strips ```json code fences and surrounding whitespace', () => {
    const reply = '\n```json\n' + JSON.stringify({ candidates: [candidate] }) + '\n```\n';
    expect(parseClaudeReply(reply)).toEqual({ kind: 'candidates', candidates: [candidate] });
  });

  it('strips bare ``` fences', () => {
    const reply = '```\n' + JSON.stringify({ candidates: [candidate] }) + '\n```';
    expect(parseClaudeReply(reply).kind).toBe('candidates');
  });

  it('drops unknown keys from candidates', () => {
    const reply = JSON.stringify({ candidates: [{ ...candidate, pos: 'adj' }] });
    expect(parseClaudeReply(reply)).toEqual({ kind: 'candidates', candidates: [candidate] });
  });

  it('parses not_english with a suggestion or null', () => {
    expect(parseClaudeReply('{"error":"not_english","suggestion":"ubiquitous"}')).toEqual({
      kind: 'not_english',
      suggestion: 'ubiquitous',
    });
    expect(parseClaudeReply('{"error":"not_english","suggestion":null}')).toEqual({
      kind: 'not_english',
      suggestion: null,
    });
  });

  it.each([
    ['prose', 'Here is the definition: present everywhere'],
    ['empty', ''],
    ['zero candidates', '{"candidates":[]}'],
    ['four candidates', JSON.stringify({ candidates: [candidate, candidate, candidate, candidate] })],
    ['missing finnish', JSON.stringify({ candidates: [{ definition: 'd', example: 'e' }] })],
    ['empty definition', JSON.stringify({ candidates: [{ ...candidate, definition: '' }] })],
    ['unknown error', '{"error":"rate_limited"}'],
    ['truncated JSON', '{"candidates":[{"definition":"present'],
  ])('rejects %s', (_label, reply) => {
    expect(() => parseClaudeReply(reply)).toThrow(ClaudeReplyError);
  });
});

describe('parseClaudeReply with context', () => {
  const sentence = 'By 2030 the sensors had become ubiquitous.';

  it('accepts exactly one candidate and uses the given sentence verbatim as the example', () => {
    const reply = JSON.stringify({ candidates: [{ ...candidate, example: 'by 2030 the sensors had become ubiquitous' }] });
    expect(parseClaudeReply(reply, sentence)).toEqual({
      kind: 'candidates',
      candidates: [{ ...candidate, example: sentence }],
    });
  });

  it('rejects more than one candidate', () => {
    const reply = JSON.stringify({ candidates: [candidate, candidate] });
    expect(() => parseClaudeReply(reply, sentence)).toThrow(ClaudeReplyError);
  });

  it('still reports not_english', () => {
    expect(parseClaudeReply('{"error":"not_english","suggestion":null}', sentence).kind).toBe('not_english');
  });
});
