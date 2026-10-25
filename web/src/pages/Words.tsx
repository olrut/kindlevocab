import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { api, errorMessage, type Status, type Word } from '../api';
import forms from '../components/forms.module.css';
import { useToast } from '../components/Toast';
import styles from './Words.module.css';

export function WordsPage() {
  const toast = useToast();
  const [tab, setTab] = useState<Status>('learning');
  const [lists, setLists] = useState<Partial<Record<Status, Word[]>>>({});
  const [query, setQuery] = useState('');
  const [editing, setEditing] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.words(tab).then(
      (ws) => !cancelled && setLists((l) => ({ ...l, [tab]: ws })),
      (err) => !cancelled && setError(errorMessage(err)),
    );
    return () => {
      cancelled = true;
    };
  }, [tab]);

  const words = lists[tab];
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q || !words) return words;
    return words.filter((w) => [w.word, w.finnish, w.definition].some((f) => f?.toLowerCase().includes(q)));
  }, [words, query]);

  const replace = (w: Word) => setLists((l) => ({ ...l, [tab]: l[tab]?.map((x) => (x.id === w.id ? w : x)) }));
  // The other tab is refetched when opened, so dropping the cached list keeps it honest.
  const remove = (id: number, other?: Status) =>
    setLists((l) => ({ ...l, [tab]: l[tab]?.filter((x) => x.id !== id), ...(other ? { [other]: undefined } : {}) }));

  async function act(fn: () => Promise<void>) {
    setError(null);
    try {
      await fn();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const setStatus = (w: Word, status: Status) =>
    act(async () => {
      await api.updateWord(w.id, { status });
      remove(w.id, status);
      toast(status === 'learned' ? `“${w.word}” marked learned` : `“${w.word}” is back in Learning`);
    });

  const del = (w: Word) =>
    act(async () => {
      await api.deleteWord(w.id);
      remove(w.id);
      toast(`Deleted “${w.word}”`);
    });

  const count = (s: Status) => (lists[s] ? ` (${lists[s]!.length})` : '');

  return (
    <>
      <h1>Words</h1>
      <div className={styles.toolbar}>
        <div role="tablist" className={styles.tabs}>
          {(['learning', 'learned'] as const).map((s) => (
            <button key={s} type="button" role="tab" aria-selected={tab === s} onClick={() => setTab(s)}>
              {s === 'learning' ? 'Learning' : 'Learned'}
              {count(s)}
            </button>
          ))}
        </div>
        <input type="search" aria-label="Search" placeholder="Search" value={query} onChange={(e) => setQuery(e.target.value)} />
      </div>
      {error && (
        <p role="alert" className={forms.error}>
          {error}
        </p>
      )}
      {shown && shown.length === 0 && <p>{query ? 'No matches.' : 'No words here yet.'}</p>}
      {shown && shown.length > 0 && (
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Word</th>
              <th>Finnish</th>
              <th>Definition</th>
              <th>Book</th>
              <th>
                <span className={styles.hidden}>Actions</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {shown.map((w) =>
              editing === w.id ? (
                <tr key={w.id}>
                  <td colSpan={5}>
                    <EditForm
                      word={w}
                      onCancel={() => setEditing(null)}
                      onSave={(patch) =>
                        act(async () => {
                          replace(await api.updateWord(w.id, patch));
                          setEditing(null);
                          toast(`Saved “${w.word}”`);
                        })
                      }
                    />
                  </td>
                </tr>
              ) : (
                <Row
                  key={w.id}
                  word={w}
                  onEdit={() => setEditing(w.id)}
                  onToggle={() => setStatus(w, w.status === 'learning' ? 'learned' : 'learning')}
                  onDelete={() => del(w)}
                />
              ),
            )}
          </tbody>
        </table>
      )}
    </>
  );
}

function Row({ word: w, onEdit, onToggle, onDelete }: { word: Word; onEdit: () => void; onToggle: () => void; onDelete: () => void }) {
  const [confirming, setConfirming] = useState(false);
  return (
    <tr>
      <th scope="row">{w.word}</th>
      <td>{w.finnish}</td>
      <td>
        {w.definition}
        {w.example && <div className={styles.example}>{w.example}</div>}
      </td>
      <td>{w.bookTitle}</td>
      <td className={styles.actions}>
        <button type="button" onClick={onEdit}>
          Edit
        </button>
        <button type="button" onClick={onToggle}>
          {w.status === 'learning' ? 'Mark learned' : 'Restore'}
        </button>
        {confirming ? (
          <button type="button" className={styles.danger} onClick={onDelete} onBlur={() => setConfirming(false)} autoFocus>
            Really delete?
          </button>
        ) : (
          <button type="button" onClick={() => setConfirming(true)}>
            Delete
          </button>
        )}
      </td>
    </tr>
  );
}

type Patch = { definition: string; example: string | null; finnish: string | null; notes: string | null };

function EditForm({ word: w, onSave, onCancel }: { word: Word; onSave: (p: Patch) => void; onCancel: () => void }) {
  const [definition, setDefinition] = useState(w.definition);
  const [example, setExample] = useState(w.example ?? '');
  const [finnish, setFinnish] = useState(w.finnish ?? '');
  const [notes, setNotes] = useState(w.notes ?? '');

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!definition.trim()) return;
    onSave({ definition: definition.trim(), example: example.trim() || null, finnish: finnish.trim() || null, notes: notes.trim() || null });
  }

  return (
    <form onSubmit={submit} aria-label={`Edit ${w.word}`} className={forms.form}>
      <label>
        Definition
        <textarea value={definition} onChange={(e) => setDefinition(e.target.value)} required rows={2} />
      </label>
      <label>
        Example
        <textarea value={example} onChange={(e) => setExample(e.target.value)} rows={2} />
      </label>
      <label>
        Finnish
        <input value={finnish} onChange={(e) => setFinnish(e.target.value)} />
      </label>
      <label>
        Notes
        <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} />
      </label>
      <p className={forms.hint}>
        Keep your notes here. The Obsidian note for this word is overwritten on every export, so edits made in the vault are lost.
      </p>
      <div className={forms.actions}>
        <button type="submit">Save</button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
