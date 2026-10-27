import { describe, expect, it } from 'vitest';
import { parseCsv, toCsv } from '../src/transfer/csv.js';

describe('toCsv', () => {
  it('quotes only fields that need it, doubles quotes, and ends rows with CRLF', () => {
    expect(toCsv([['a', 'b,c', 'say "hi"', 'two\nlines', null]])).toBe('a,"b,c","say ""hi""","two\nlines",\r\n');
  });

  it('quotes fields with leading or trailing spaces so they survive a round trip', () => {
    expect(toCsv([[' x', 'y ']])).toBe('" x","y "\r\n');
  });
});

describe('parseCsv', () => {
  it('reads quoted fields, escaped quotes, embedded newlines and both line endings', () => {
    expect(parseCsv('a,"b,c","say ""hi"""\r\n"two\nlines",,x\nlast,row,here')).toEqual([
      ['a', 'b,c', 'say "hi"'],
      ['two\nlines', '', 'x'],
      ['last', 'row', 'here'],
    ]);
  });

  it('ignores a UTF-8 BOM, a final newline and blank lines', () => {
    expect(parseCsv('﻿a,b\r\n\r\nc,d\r\n')).toEqual([
      ['a', 'b'],
      ['c', 'd'],
    ]);
  });

  it('round-trips what toCsv writes', () => {
    const rows = [
      ['word', 'notes'],
      ['naïve', 'line 1\r\nline 2, "quoted"'],
      ['', ' padded '],
    ];
    expect(parseCsv(toCsv(rows))).toEqual(rows);
  });

  it('rejects an unterminated quote', () => {
    expect(() => parseCsv('a,"b\nc')).toThrow(/unterminated/);
  });

  it('rejects text after a closing quote', () => {
    expect(() => parseCsv('"a"b,c')).toThrow(/after a closing quote/);
  });
});

describe('parseCsv trailing empty fields', () => {
  it('keeps a row whose last field is empty', () => {
    expect(parseCsv('a,\n,\n')).toEqual([
      ['a', ''],
      ['', ''],
    ]);
  });

  it('keeps a row that is a single quoted empty field', () => {
    expect(parseCsv('""\n')).toEqual([['']]);
  });
});
