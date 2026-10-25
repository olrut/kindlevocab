import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router';
import { api, ApiError, errorMessage, token, type User } from '../api';
import { CreateUserForm } from '../components/CreateUserForm';
import forms from '../components/forms.module.css';
import styles from './Login.module.css';

export function LoginPage() {
  const navigate = useNavigate();
  const [users, setUsers] = useState<User[] | null>(null);
  const [selected, setSelected] = useState<User | null>(null);
  const [pin, setPin] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.users().then(setUsers, (err) => setError(errorMessage(err)));
  }, []);

  async function login(user: User, pinValue?: string) {
    setError(null);
    try {
      const res = await api.login(user.id, pinValue);
      token.set(res.token);
      navigate('/add', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError && err.status === 403 ? 'Wrong PIN' : errorMessage(err));
    }
  }

  function choose(user: User) {
    setError(null);
    setPin('');
    if (user.hasPin) setSelected(user);
    else void login(user);
  }

  function submitPin(e: FormEvent) {
    e.preventDefault();
    if (selected) void login(selected, pin);
  }

  return (
    <main className={styles.page}>
      <h1>Who's learning?</h1>
      <div className={styles.users}>
        {users?.map((u) => (
          <button key={u.id} type="button" onClick={() => choose(u)} aria-pressed={selected?.id === u.id}>
            {u.name}
          </button>
        ))}
        {users?.length === 0 && <p>No users yet. Create one below.</p>}
      </div>

      {selected && (
        <form onSubmit={submitPin} className={forms.form}>
          <label>
            PIN for {selected.name}
            <input value={pin} onChange={(e) => setPin(e.target.value)} type="password" inputMode="numeric" autoFocus />
          </label>
          <button type="submit">Log in</button>
        </form>
      )}
      {error && (
        <p role="alert" className={forms.error}>
          {error}
        </p>
      )}

      <h2>New user</h2>
      <CreateUserForm onCreated={(user, newPin) => login(user, newPin)} />
    </main>
  );
}
