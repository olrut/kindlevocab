import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fakeApi, renderApp } from './fakeApi';

describe('/settings', () => {
  it('says when the Obsidian export is disabled', async () => {
    fakeApi({ 'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } } });
    renderApp('/settings');
    expect(await screen.findByText(/OBSIDIAN_DIR is not set/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Re-export all' })).not.toBeInTheDocument();
  });

  it('shows the last run, warns that vault edits are overwritten, and re-exports all', async () => {
    const api = fakeApi({
      'GET /api/obsidian/status': { body: { enabled: true, lastRun: { at: '2026-10-25T10:00:00.000Z', ok: true, error: null } } },
      'POST /api/obsidian/export-all': { body: { words: 42, books: 3 } },
    });
    const { user } = renderApp('/settings');
    expect(await screen.findByText(/Last export:/)).toHaveTextContent('2026');
    expect(screen.getByText(/overwritten/)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Re-export all' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Exported 42 words and 3 books');
    expect(api.callsTo('POST /api/obsidian/export-all')).toHaveLength(1);
    expect(api.callsTo('GET /api/obsidian/status').length).toBeGreaterThan(1);
  });

  it('shows a failed last run', async () => {
    fakeApi({ 'GET /api/obsidian/status': { body: { enabled: true, lastRun: { at: '2026-10-25T10:00:00.000Z', ok: false, error: 'EACCES' } } } });
    renderApp('/settings');
    expect(await screen.findByText(/failed/)).toHaveTextContent('EACCES');
  });

  it('switch user logs out and goes to /login', async () => {
    const api = fakeApi({
      'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } },
      'POST /api/auth/logout': { status: 204 },
      'GET /api/users': { body: [{ id: 1, name: 'olli', hasPin: false }] },
    });
    const { user } = renderApp('/settings');
    await user.click(await screen.findByRole('button', { name: 'Switch user' }));
    expect(await screen.findByRole('button', { name: 'olli' })).toBeInTheDocument();
    expect(api.callsTo('POST /api/auth/logout')[0].headers.get('Authorization')).toBe('Bearer test-token');
    await waitFor(() => expect(localStorage.getItem('vocab.token')).toBeNull());
  });

  it('creates a user without switching to them', async () => {
    const api = fakeApi({
      'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } },
      'POST /api/users': { status: 201, body: { id: 2, name: 'anna', hasPin: false } },
    });
    const { user } = renderApp('/settings');
    await user.type(await screen.findByLabelText('Name'), 'anna');
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Created user “anna”');
    expect(api.callsTo('POST /api/users')[0].body).toEqual({ name: 'anna' });
    expect(api.callsTo('POST /api/auth/login')).toHaveLength(0);
  });
});
