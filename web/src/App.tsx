import { createContext, useContext, useEffect, useState } from 'react';
import { Navigate, NavLink, Outlet, Route, Routes, useNavigate } from 'react-router';
import { api, setUnauthorizedHandler, token } from './api';
import styles from './App.module.css';
import { ToastProvider } from './components/Toast';
import { AddPage } from './pages/Add';
import { ImportPage } from './pages/Import';
import { LoginPage } from './pages/Login';
import { SettingsPage } from './pages/Settings';
import { WordsPage } from './pages/Words';

type CurrentUser = { id: number; name: string };
const UserContext = createContext<CurrentUser | null>(null);
export const useCurrentUser = () => useContext(UserContext)!;

export function App() {
  const navigate = useNavigate();
  useEffect(() => {
    setUnauthorizedHandler(() => navigate('/login', { replace: true }));
  }, [navigate]);

  return (
    <ToastProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireLogin />}>
          <Route path="/add" element={<AddPage />} />
          <Route path="/words" element={<WordsPage />} />
          <Route path="/import" element={<ImportPage />} />
          <Route path="/settings" element={<SettingsPage />} />
        </Route>
        <Route path="*" element={<Navigate to="/add" replace />} />
      </Routes>
    </ToastProvider>
  );
}

/** Layout for logged-in pages: checks the token once, then shows the navigation. */
function RequireLogin() {
  const [user, setUser] = useState<CurrentUser | null>(null);
  const hasToken = token.get() !== null;

  useEffect(() => {
    if (!hasToken) return;
    // A 401 here goes to /login through the unauthorized handler.
    api.me().then(setUser, () => {});
  }, [hasToken]);

  if (!hasToken) return <Navigate to="/login" replace />;
  if (!user) return null;
  return (
    <UserContext.Provider value={user}>
      <header className={styles.header}>
        <nav className={styles.nav}>
          {[
            ['/add', 'Add'],
            ['/words', 'Words'],
            ['/import', 'Import'],
            ['/settings', 'Settings'],
          ].map(([to, label]) => (
            <NavLink key={to} to={to} className={({ isActive }) => (isActive ? styles.active : undefined)}>
              {label}
            </NavLink>
          ))}
        </nav>
        <span className={styles.user}>{user.name}</span>
      </header>
      <main className={styles.main}>
        <Outlet />
      </main>
    </UserContext.Provider>
  );
}
