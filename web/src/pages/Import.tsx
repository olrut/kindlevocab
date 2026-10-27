import { useEffect, useRef, useState, type DragEvent } from 'react';
import { api, errorMessage, type ImportProgress, type KindleCandidate } from '../api';
import forms from '../components/forms.module.css';
import styles from './Import.module.css';

/** Progress polling interval; tests shorten it. */
export const timing = { pollMs: 1000 };

const ERROR_TEXT: Record<string, string> = {
  not_english: 'not English, skipped',
  'lookup failed': 'lookup failed; it will be offered again next time',
  'not in upload': 'not in the uploaded file',
  'internal error': 'server error',
};

export function ImportPage() {
  const [file, setFile] = useState<File | null>(null);
  const [onlyNew, setOnlyNew] = useState(true);
  const [markMastered, setMarkMastered] = useState(true);
  const [preview, setPreview] = useState<{ importId: string; candidates: KindleCandidate[] } | null>(null);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [progress, setProgress] = useState<ImportProgress | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function read(f: File, only: boolean) {
    setError(null);
    setPreview(null);
    setProgress(null);
    try {
      const p = await api.kindlePreview(f, only);
      setPreview(p);
      setSelected(new Set(p.candidates.filter((c) => !c.exists).map((c) => c.word)));
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  function choose(f: File | undefined) {
    if (!f) return;
    setFile(f);
    void read(f, onlyNew);
  }

  function toggleOnlyNew(value: boolean) {
    setOnlyNew(value);
    // The filter is applied on the server, so read the file again.
    if (file && !progress) void read(file, value);
  }

  function drop(e: DragEvent) {
    e.preventDefault();
    setDragging(false);
    choose(e.dataTransfer.files[0]);
  }

  function toggle(word: string) {
    setSelected((s) => {
      const next = new Set(s);
      if (!next.delete(word)) next.add(word);
      return next;
    });
  }

  async function confirm() {
    if (!preview) return;
    setError(null);
    const words = preview.candidates.map((c) => c.word).filter((w) => selected.has(w));
    try {
      setProgress(await api.kindleConfirm(preview.importId, words, markMastered));
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const importId = preview?.importId;
  const running = progress?.state === 'running';
  useEffect(() => {
    if (!running || !importId) return;
    const timer = setInterval(() => {
      api.kindleProgress(importId).then(setProgress, (err) => setError(errorMessage(err)));
    }, timing.pollMs);
    return () => clearInterval(timer);
  }, [running, importId]);

  return (
    <>
      <h1>Import</h1>
      <section className={styles.section}>
        <h2>Kindle</h2>
        <p className={forms.hint}>
          Connect the Kindle over USB and pick <code>system/vocabulary/vocab.db</code>. Nothing is saved until you confirm.
        </p>
        <div
          className={dragging ? `${styles.drop} ${styles.dragging}` : styles.drop}
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={drop}
          onClick={() => fileInput.current?.click()}
        >
          <p>{file ? file.name : 'Drop vocab.db here, or click to choose it'}</p>
          <input
            ref={fileInput}
            type="file"
            aria-label="Kindle vocab.db"
            accept=".db"
            className={styles.fileInput}
            onClick={(e) => e.stopPropagation()}
            onChange={(e) => choose(e.target.files?.[0])}
          />
        </div>
        <label className={forms.check}>
          <input type="checkbox" checked={onlyNew} onChange={(e) => toggleOnlyNew(e.target.checked)} disabled={!!progress} />
          Only words new since the last import
        </label>
        <label className={forms.check}>
          <input type="checkbox" checked={markMastered} onChange={(e) => setMarkMastered(e.target.checked)} disabled={!!progress} />
          Mark mastered words as learned
        </label>

        {error && (
          <p role="alert" className={forms.error}>
            {error}
          </p>
        )}

        {preview && preview.candidates.length === 0 && <p>No new words in this file.</p>}
        {preview && preview.candidates.length > 0 && !progress && (
          <>
            <div className={forms.actions}>
              <button type="button" onClick={() => setSelected(new Set(preview.candidates.map((c) => c.word)))}>
                Select all
              </button>
              <button type="button" onClick={() => setSelected(new Set())}>
                Select none
              </button>
            </div>
            <table aria-label="Kindle words" className={styles.table}>
              <thead>
                <tr>
                  <th>
                    <span className={styles.hidden}>Import</span>
                  </th>
                  <th>Word</th>
                  <th>Sentence</th>
                  <th>Book</th>
                  <th>Kindle</th>
                </tr>
              </thead>
              <tbody>
                {preview.candidates.map((c) => (
                  <tr key={c.word} className={c.exists ? styles.exists : undefined}>
                    <td>
                      <input type="checkbox" aria-label={`Import ${c.word}`} checked={selected.has(c.word)} onChange={() => toggle(c.word)} />
                    </td>
                    <th scope="row">
                      {c.word}
                      {c.exists && <span className={styles.badge}>already saved</span>}
                    </th>
                    <td>{c.usage}</td>
                    <td>
                      {c.bookTitle}
                      {c.bookAuthor && <div className={styles.author}>{c.bookAuthor}</div>}
                    </td>
                    <td>{c.kindleCategory}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <button type="button" onClick={confirm} disabled={selected.size === 0}>
              Import {selected.size} words
            </button>
          </>
        )}

        {progress && <Progress progress={progress} />}
      </section>

      <FileImport />
    </>
  );
}

function FileImport() {
  const [result, setResult] = useState<{ imported: number; skipped: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function choose(input: HTMLInputElement) {
    const f = input.files?.[0];
    if (!f) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      setResult(await api.importWords(f));
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
      // Choosing the same file again should upload it again.
      input.value = '';
    }
  }

  return (
    <section className={styles.section}>
      <h2>CSV / JSON</h2>
      <p className={forms.hint}>
        A file in the format of the export on the Settings page. Words you already have are skipped. Pronunciation and audio are
        looked up in the background.
      </p>
      <div className={forms.form}>
        <label>
          CSV or JSON file
          <input type="file" accept=".csv,.json" disabled={busy} onChange={(e) => choose(e.target)} />
        </label>
      </div>
      {busy && <p>Importing…</p>}
      {result && (
        <p>
          Imported {result.imported} {result.imported === 1 ? 'word' : 'words'}; {result.skipped} already existed and{' '}
          {result.skipped === 1 ? 'was' : 'were'} skipped.
        </p>
      )}
      {error && (
        <p role="alert" className={forms.error}>
          {error}
        </p>
      )}
    </section>
  );
}

function Progress({ progress: p }: { progress: ImportProgress }) {
  return (
    <div className={styles.progress}>
      <progress max={p.total} value={p.done} aria-valuenow={p.done} aria-valuemax={p.total} />
      <p>
        {p.state === 'finished'
          ? `Done: ${p.imported} imported, ${p.skipped} skipped, ${p.errors.length} failed.`
          : `Looking up words… ${p.done} / ${p.total}`}
      </p>
      {p.errors.length > 0 && (
        <ul className={styles.errors}>
          {p.errors.map((e) => (
            <li key={e.word}>
              <strong>{e.word}</strong>: {ERROR_TEXT[e.error] ?? e.error}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
