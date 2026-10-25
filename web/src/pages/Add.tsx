import { useRef, useState, type FormEvent } from 'react';
import { api, ApiError, errorMessage, type Candidate, type LookupResult, type Source } from '../api';
import { AudioButton } from '../components/AudioButton';
import forms from '../components/forms.module.css';
import { useToast } from '../components/Toast';
import styles from './Add.module.css';

type Draft = { definition: string; example: string; finnish: string; source: Source };
type Problem = { message: string; suggestion?: string };

export function AddPage() {
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [text, setText] = useState('');
  const [result, setResult] = useState<LookupResult | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [problem, setProblem] = useState<Problem | null>(null);
  const [busy, setBusy] = useState(false);

  async function lookUp(word: string) {
    if (!word.trim()) return;
    setBusy(true);
    setProblem(null);
    setResult(null);
    setDraft(null);
    try {
      setResult(await api.lookup(word.trim()));
    } catch (err) {
      if (err instanceof ApiError && err.status === 422) {
        const suggestion = typeof err.body.suggestion === 'string' ? err.body.suggestion : undefined;
        setProblem({ message: `“${word.trim()}” doesn't look like an English word.`, suggestion });
      } else if (err instanceof ApiError && err.status === 502) {
        setProblem({ message: 'Lookup failed. Try again, or write your own definition.' });
      } else {
        setProblem({ message: errorMessage(err) });
      }
    } finally {
      setBusy(false);
    }
  }

  function submitLookup(e: FormEvent) {
    e.preventDefault();
    void lookUp(text);
  }

  function applySuggestion(suggestion: string) {
    setText(suggestion);
    void lookUp(suggestion);
  }

  const pick = (c: Candidate) => setDraft({ ...c, source: 'claude' });
  const writeOwn = () => setDraft({ definition: '', example: '', finnish: '', source: 'manual' });

  async function save(e: FormEvent) {
    e.preventDefault();
    if (!draft || !draft.definition.trim()) return;
    const word = result?.word ?? text.trim().toLowerCase();
    try {
      await api.createWord({
        word,
        definition: draft.definition.trim(),
        example: draft.example.trim() || null,
        finnish: draft.finnish.trim() || null,
        ipa: result?.ipa ?? null,
        source: draft.source,
      });
      toast(`Saved “${word}”`);
      setText('');
      setResult(null);
      setDraft(null);
      setProblem(null);
      input.current?.focus();
    } catch (err) {
      setProblem({ message: err instanceof ApiError && err.status === 409 ? `“${word}” is already in your list.` : errorMessage(err) });
    }
  }

  const looked = result !== null || problem !== null;

  return (
    <>
      <h1>Add a word</h1>
      <form onSubmit={submitLookup} className={styles.search}>
        <label className={styles.wordLabel}>
          Word
          <input ref={input} value={text} onChange={(e) => setText(e.target.value)} autoFocus autoComplete="off" maxLength={100} />
        </label>
        <button type="submit" disabled={busy || !text.trim()}>
          {busy ? 'Looking up…' : 'Look up'}
        </button>
      </form>

      {problem && (
        <div role="alert" className={forms.error}>
          {problem.message}{' '}
          {problem.suggestion && (
            <button type="button" className={styles.link} onClick={() => applySuggestion(problem.suggestion!)}>
              Did you mean “{problem.suggestion}”?
            </button>
          )}
        </div>
      )}

      {result && (
        <section aria-label={result.word} className={styles.result}>
          <h2>
            {result.word} {result.ipa && <span className={styles.ipa}>{result.ipa}</span>}{' '}
            <AudioButton word={result.word} load={result.audioAvailable ? () => api.lookupAudio(result.word) : null} />
          </h2>
          <div className={styles.cards}>
            {result.candidates.map((c, i) => (
              <button key={i} type="button" className={styles.card} onClick={() => pick(c)} aria-label={`Use this meaning: ${c.definition}`}>
                <strong>{c.definition}</strong>
                <em>{c.example}</em>
                <span>{c.finnish}</span>
              </button>
            ))}
          </div>
        </section>
      )}

      {looked && !draft && (
        <button type="button" onClick={writeOwn}>
          Write my own
        </button>
      )}

      {draft && (
        <form onSubmit={save} className={forms.form}>
          <label>
            Definition
            <textarea value={draft.definition} onChange={(e) => setDraft({ ...draft, definition: e.target.value })} required rows={2} />
          </label>
          <label>
            Example
            <textarea value={draft.example} onChange={(e) => setDraft({ ...draft, example: e.target.value })} rows={2} />
          </label>
          <label>
            Finnish
            <input value={draft.finnish} onChange={(e) => setDraft({ ...draft, finnish: e.target.value })} />
          </label>
          <div className={forms.actions}>
            <button type="submit">Save</button>
            <button type="button" onClick={() => setDraft(null)}>
              Cancel
            </button>
          </div>
        </form>
      )}
    </>
  );
}
