/** RFC 4180 CSV: comma-separated, `"`-quoted, `""` for a quote inside a quoted field. */

export class CsvError extends Error {}

// Leading/trailing whitespace is quoted too, so a trimming reader cannot lose it.
const NEEDS_QUOTES = /[",\r\n]|^\s|\s$/;

/** Rows to CSV text with CRLF line ends. Null becomes an empty cell. */
export function toCsv(rows: (string | null)[][]): string {
  return rows
    .map((row) => row.map((cell) => (cell !== null && NEEDS_QUOTES.test(cell) ? `"${cell.replaceAll('"', '""')}"` : (cell ?? ''))).join(',') + '\r\n')
    .join('');
}

/** CSV text to rows. Accepts CRLF or LF, skips a leading BOM and blank lines. Throws CsvError. */
export function parseCsv(text: string): string[][] {
  const s = text.startsWith('﻿') ? text.slice(1) : text;
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;
  let i = 0;

  const endField = () => {
    row.push(field);
    field = '';
  };
  const endRow = () => {
    // A blank line is a single unquoted empty field.
    if (row.length > 0 || field !== '' || quoted) {
      endField();
      rows.push(row);
    }
    row = [];
    field = '';
    quoted = false;
  };

  while (i < s.length) {
    const ch = s[i];
    if (ch === '"' && field === '' && !quoted) {
      quoted = true;
      i++;
      for (;;) {
        const end = s.indexOf('"', i);
        if (end === -1) throw new CsvError('unterminated quoted field');
        field += s.slice(i, end);
        i = end + 1;
        if (s[i] !== '"') break;
        field += '"';
        i++;
      }
      if (i < s.length && !',\r\n'.includes(s[i])) throw new CsvError('text after a closing quote');
    } else if (ch === ',') {
      endField();
      quoted = false;
      i++;
    } else if (ch === '\r' || ch === '\n') {
      endRow();
      i += ch === '\r' && s[i + 1] === '\n' ? 2 : 1;
    } else {
      field += ch;
      i++;
    }
  }
  endRow();
  return rows;
}
