import { describe, expect, it } from 'vitest';
import type { WordRow } from '../src/db/schema.js';
import { noteName, renderBookNote, renderIndex, renderWordNote } from '../src/obsidian/render.js';

const base: WordRow = {
  id: 1,
  userId: 1,
  word: 'ubiquitous',
  definition: 'present or found everywhere',
  example: 'Smartphones are ubiquitous these days.',
  finnish: 'kaikkialla läsnä oleva, yleinen',
  ipa: '/juːˈbɪkwɪtəs/',
  audioPath: null,
  source: 'kindle',
  status: 'learning',
  learnedAt: null,
  bookTitle: 'The Sensor Age',
  bookAuthor: 'Ada Writer',
  kindleLookupAt: null,
  notes: null,
  createdAt: '2026-10-23T21:30:00.000Z',
  updatedAt: '2026-10-23T21:30:00.000Z',
  deletedAt: null,
};
const word = (patch: Partial<WordRow> = {}): WordRow => ({ ...base, ...patch });

describe('renderWordNote', () => {
  it('renders the SPEC § 7 template', () => {
    expect(renderWordNote(word(), 'olli')).toBe(`---
word: ubiquitous
status: learning
finnish: "kaikkialla läsnä oleva, yleinen"
ipa: "/juːˈbɪkwɪtəs/"
source: kindle
book: "[[Vocab/olli/Books/The Sensor Age]]"
added: 2026-10-23
learned:
tags: [vocab, vocab/learning]
---
# ubiquitous

**Definition:** present or found everywhere

**Example:** Smartphones are ubiquitous these days.

**Finnish:** kaikkialla läsnä oleva, yleinen
`);
  });

  it('renders a learned word with its date, tag and notes', () => {
    const md = renderWordNote(word({ status: 'learned', learnedAt: '2026-10-25T08:00:00.000Z', notes: 'Seen in Dune too.' }), 'olli');
    expect(md).toContain('\nlearned: 2026-10-25\n');
    expect(md).toContain('\ntags: [vocab, vocab/learned]\n');
    expect(md.endsWith('**Finnish:** kaikkialla läsnä oleva, yleinen\n\n**Notes:** Seen in Dune too.\n')).toBe(true);
  });

  it('leaves empty fields empty and omits empty body lines', () => {
    const md = renderWordNote(word({ finnish: null, ipa: null, example: null, bookTitle: null, bookAuthor: null, source: 'manual' }), 'olli');
    expect(md).toContain('\nfinnish:\nipa:\nsource: manual\nbook:\n');
    expect(md).not.toContain('**Example:**');
    expect(md).not.toContain('**Finnish:**');
    expect(md).not.toContain('**Notes:**');
  });

  it('quotes values that would break YAML', () => {
    const md = renderWordNote(word({ word: 'yes', finnish: 'say "hi": ok' }), 'olli');
    expect(md).toContain('\nword: "yes"\n');
    expect(md).toContain('\nfinnish: "say \\"hi\\": ok"\n');
    expect(renderWordNote(word({ word: 'a: b' }), 'olli')).toContain('\nword: "a: b"\n');
  });

  it('links the book by its sanitised file name, in the sanitised user folder', () => {
    const md = renderWordNote(word({ bookTitle: 'Part 1/2: The [Real] Story?' }), 'Ann/a');
    expect(md).toContain('\nbook: "[[Vocab/Ann-a/Books/Part 1-2- The -Real- Story-]]"\n');
  });

  it('keeps multi-line text inside its own line', () => {
    const md = renderWordNote(word({ example: 'Line one.\nLine two.' }), 'olli');
    expect(md).toContain('**Example:** Line one. Line two.\n');
  });
});

describe('renderBookNote', () => {
  it('lists the words alphabetically as links with the sentence under each', () => {
    const md = renderBookNote(
      { title: 'The Sensor Age', author: 'Ada Writer' },
      [
        word({ word: 'ubiquitous', example: 'Sensors were ubiquitous.' }),
        word({ word: 'torpid', example: 'A torpid afternoon.' }),
        word({ word: 'lucid', example: null }),
      ],
      'olli',
    );
    expect(md).toBe(`---
title: "The Sensor Age"
author: "Ada Writer"
tags: [vocab/book]
---
# The Sensor Age

- [[Vocab/olli/Words/lucid|lucid]]
- [[Vocab/olli/Words/torpid|torpid]]
  - A torpid afternoon.
- [[Vocab/olli/Words/ubiquitous|ubiquitous]]
  - Sensors were ubiquitous.
`);
  });

  it('leaves an unknown author empty', () => {
    expect(renderBookNote({ title: 'T', author: null }, [word()], 'olli')).toContain('\nauthor:\n');
  });
});

describe('renderIndex', () => {
  it('shows counts per status and a static explanation', () => {
    const md = renderIndex('olli', { learning: 12, learned: 30 });
    expect(md).toContain('- Learning: 12\n- Learned: 30\n- Total: 42\n');
    expect(md).toContain('FROM "Vocab/olli/Words"');
    expect(md).toContain('overwritten');
    expect(renderIndex('olli', { learning: 1, learned: 0 }).replace(/\d+/g, '#')).toBe(md.replace(/\d+/g, '#'));
  });
});

describe('noteName', () => {
  it.each([
    ['ubiquitous', 'ubiquitous'],
    ['The Sensor Age', 'The Sensor Age'],
    ['a/b\\c:d*e?f"g<h>i|j', 'a-b-c-d-e-f-g-h-i-j'],
    ['[x]#y^z', '-x--y-z'],
    ['../../etc/passwd', '-..-etc-passwd'],
    ['..', '_'],
    ['  .hidden. ', 'hidden'],
    ['tab\there\nnewline', 'tab here newline'],
    ['', '_'],
  ])('%j → %j', (input, expected) => {
    expect(noteName(input)).toBe(expected);
  });

  it('trims to 100 characters', () => {
    expect(noteName('x'.repeat(150))).toHaveLength(100);
    expect(noteName(`${'x'.repeat(99)} y`)).toBe('x'.repeat(99));
  });
});
