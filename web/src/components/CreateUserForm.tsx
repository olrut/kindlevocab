import { useState, type FormEvent } from 'react';
import { api, errorMessage, type User } from '../api';
import styles from './forms.module.css';

/** Name + optional PIN. Used on /login (then logs in) and /settings (stays). */
export function CreateUserForm({ onCreated }: { onCreated: (user: User, pin: string | undefined) => void | Promise<void> }) {
  const [name, setName] = useState('');
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      const user = await api.createUser(name.trim(), pin || undefined);
      setName('');
      setPin('');
      await onCreated(user, pin || undefined);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className={styles.form}>
      <label>
        Name
        <input value={name} onChange={(e) => setName(e.target.value)} required />
      </label>
      <label>
        PIN (optional)
        <input value={pin} onChange={(e) => setPin(e.target.value)} type="password" inputMode="numeric" autoComplete="new-password" />
      </label>
      {error && (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      )}
      <button type="submit" disabled={busy}>
        Create user
      </button>
    </form>
  );
}
