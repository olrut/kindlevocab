import { z } from 'zod';
import type { WordRow } from '../db/schema.js';
import type { NewWord } from '../wordStore.js';
import { CsvError, parseCsv, toCsv } from './csv.js';

/** Fields of an exported word, in CSV column order (docs/SPEC.md § 3, Export / import). */
export const EXPORT_COLUMNS = [
  'word',
  'definition',
  'example',
  'finnish',
  'ipa',
  'status',
  'learned_at',
  'created_at',
  'book_title',
  'book_author',
  'notes',
] as const;

type Column = (typeof EXPORT_COLUMNS)[number];
export type ExportedWord = Record<Column, string | null>;

/** The file cannot be imported. The message is shown to the user as is. */
export class ImportFileError extends Error {}

export function toExported(w: WordRow): ExportedWord {
  return {
    word: w.word,
    definition: w.definition,
    example: w.example,
    finnish: w.finnish,
    ipa: w.ipa,
    status: w.status,
    learned_at: w.learnedAt,
    created_at: w.createdAt,
    book_title: w.bookTitle,
    book_author: w.bookAuthor,
    notes: w.notes,
  };
}

export const exportJson = (rows: WordRow[]) => JSON.stringify(rows.map(toExported), null, 2) + '\n';

export const exportCsv = (rows: WordRow[]) =>
  toCsv([[...EXPORT_COLUMNS], ...rows.map((w) => EXPORT_COLUMNS.map((c) => toExported(w)[c]))]);

/** A word to import; `insertWord` fills in the rest. */
export type ImportedWord = Omit<NewWord, 'source'>;

// Blank text, in CSV or JSON, means "no value".
const text = z
  .string({ error: (iss) => (iss.input === undefined ? undefined : 'must be text') })
  .nullish()
  .transform((v) => v?.trim() || null);
const required = (name: string) => text.refine((v) => v !== null, `${name} is required`).transform((v) => v!);
const timestamp = (name: string) =>
  text
    .refine((v) => v === null || z.iso.datetime({ offset: true }).safeParse(v).success, `${name} must be an ISO-8601 timestamp`)
    .transform((v) => (v === null ? null : new Date(v).toISOString()));

const importedWord = z
  .object(
    {
      word: required('word')
        .transform((v) => v.toLowerCase())
        .refine((v) => v.length <= 100, 'word is too long'),
      definition: required('definition'),
      example: text,
      finnish: text,
      ipa: text,
      status: text.refine((v) => v === null || v === 'learning' || v === 'learned', 'status must be "learning" or "learned"'),
      learned_at: timestamp('learned_at'),
      created_at: timestamp('created_at'),
      book_title: text,
      book_author: text,
      notes: text.refine((v) => v === null || v.length <= 10_000, 'notes are too long'),
    },
    { error: 'expected an object' },
  )
  .transform((r): ImportedWord => {
    const status = r.status === 'learned' ? 'learned' : 'learning';
    return {
      word: r.word,
      definition: r.definition,
      example: r.example,
      finnish: r.finnish,
      ipa: r.ipa,
      status,
      learnedAt: r.learned_at,
      createdAt: r.created_at,
      bookTitle: r.book_title,
      bookAuthor: r.book_author,
      notes: r.notes,
    };
  });

function validate(records: unknown[], label: (index: number) => string): ImportedWord[] {
  return records.map((record, i) => {
    const result = importedWord.safeParse(record);
    if (result.success) return result.data;
    const issue = result.error.issues[0];
    const field = issue.path.length && issue.message === 'must be text' ? `${issue.path.join('.')} ` : '';
    throw new ImportFileError(`${label(i)}: ${field}${issue.message}`);
  });
}

/** Parses a file in the export shape. Throws ImportFileError on any problem; nothing partial is returned. */
export function parseImport(content: string, format: 'csv' | 'json'): ImportedWord[] {
  if (content.replace(/^﻿/, '').trim() === '') throw new ImportFileError('the file is empty');
  return format === 'csv' ? parseCsvImport(content) : parseJsonImport(content);
}

function parseJsonImport(content: string): ImportedWord[] {
  let data: unknown;
  try {
    data = JSON.parse(content.replace(/^﻿/, ''));
  } catch {
    throw new ImportFileError('not a valid JSON file');
  }
  if (!Array.isArray(data)) throw new ImportFileError('expected a JSON array of words');
  return validate(data, (i) => `item ${i + 1}`);
}

function parseCsvImport(content: string): ImportedWord[] {
  let rows: string[][];
  try {
    rows = parseCsv(content);
  } catch (err) {
    if (err instanceof CsvError) throw new ImportFileError(`not a valid CSV file: ${err.message}`);
    throw err;
  }
  const [header, ...body] = rows;
  const names = header.map((h) => h.trim().toLowerCase());
  for (const required of ['word', 'definition']) {
    if (!names.includes(required)) throw new ImportFileError(`missing column: ${required}`);
  }
  // Row numbers count the header as row 1, as a spreadsheet shows them.
  const records = body.map((cells, i) => {
    if (cells.length !== names.length) throw new ImportFileError(`row ${i + 2}: has ${cells.length} fields, the header has ${names.length}`);
    return Object.fromEntries(names.map((n, j) => [n, cells[j]]));
  });
  return validate(records, (i) => `row ${i + 2}`);
}
