import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { api, errorMessage, token, type ObsidianStatus } from '../api';
import { CreateUserForm } from '../components/CreateUserForm';
import forms from '../components/forms.module.css';
import { useToast } from '../components/Toast';
import styles from './Settings.module.css';

export function SettingsPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const [status, setStatus] = useState<ObsidianStatus | null>(null);
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);

  const loadStatus = () => api.obsidianStatus().then(setStatus, (err) => setError(errorMessage(err)));
  useEffect(() => {
    void loadStatus();
  }, []);

  async function exportAll() {
    setExporting(true);
    setError(null);
    try {
      const n = await api.obsidianExportAll();
      toast(`Exported ${n.words} words and ${n.books} books`);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setExporting(false);
      void loadStatus();
    }
  }

  async function download(format: 'csv' | 'json') {
    setDownloadError(null);
    try {
      const { blob, filename } = await api.exportWords(format);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      a.click();
      // Revoke after the click has started the download.
      setTimeout(() => URL.revokeObjectURL(url), 0);
    } catch (err) {
      setDownloadError(errorMessage(err));
    }
  }

  async function switchUser() {
    try {
      await api.logout();
    } finally {
      token.clear();
      navigate('/login', { replace: true });
    }
  }

  return (
    <>
      <h1>Settings</h1>

      <section className={styles.section}>
        <h2>Export</h2>
        <p className={forms.hint}>All your words, learned ones included. Import the file again on the Import page.</p>
        <div className={forms.actions}>
          <button type="button" onClick={() => download('csv')}>
            Export CSV
          </button>
          <button type="button" onClick={() => download('json')}>
            Export JSON
          </button>
        </div>
        {downloadError && (
          <p role="alert" className={forms.error}>
            {downloadError}
          </p>
        )}
      </section>

      <section className={styles.section}>
        <h2>Obsidian</h2>
        {status && !status.enabled && (
          <p>The Obsidian export is off: OBSIDIAN_DIR is not set on the server.</p>
        )}
        {status?.enabled && (
          <>
            <p>
              {status.lastRun === null
                ? 'No export since the server started.'
                : status.lastRun.ok
                  ? `Last export: ${new Date(status.lastRun.at).toLocaleString()}`
                  : `Last export failed at ${new Date(status.lastRun.at).toLocaleString()}: ${status.lastRun.error}`}
            </p>
            <p className={forms.hint}>
              The vault is a copy: notes are overwritten on every export, so edits made in Obsidian are lost. Put your own notes in a
              word's Notes field instead.
            </p>
            <button type="button" onClick={exportAll} disabled={exporting}>
              {exporting ? 'Exporting…' : 'Re-export all'}
            </button>
          </>
        )}
        {error && (
          <p role="alert" className={forms.error}>
            {error}
          </p>
        )}
      </section>

      <section className={styles.section}>
        <h2>Users</h2>
        <button type="button" onClick={switchUser}>
          Switch user
        </button>
        <h3>Create user</h3>
        <CreateUserForm onCreated={(u) => toast(`Created user “${u.name}”`)} />
      </section>
    </>
  );
}
