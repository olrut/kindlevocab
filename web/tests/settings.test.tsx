import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
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

describe('/settings export', () => {
  function captureDownloads() {
    const blobs: Blob[] = [];
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: (b: Blob) => (blobs.push(b), 'blob:x'), revokeObjectURL: vi.fn() }));
    const clicked: { href: string; download: string }[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push({ href: this.href, download: this.download });
    });
    return { blobs, clicked };
  }

  it.each(['CSV', 'JSON'])('downloads the %s export under the name the server gives', async (label) => {
    const format = label.toLowerCase();
    const api = fakeApi({
      'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } },
      'GET /api/export': (req) => ({
        bytes: new TextEncoder().encode(`${req.path} content`),
        headers: { 'Content-Disposition': `attachment; filename="vocab-olli-2026-10-27.${format}"` },
      }),
    });
    const { blobs, clicked } = captureDownloads();
    const { user } = renderApp('/settings');
    await user.click(await screen.findByRole('button', { name: `Export ${label}` }));
    await waitFor(() => expect(clicked).toHaveLength(1));
    expect(clicked[0]).toEqual({ href: 'blob:x', download: `vocab-olli-2026-10-27.${format}` });
    expect(api.callsTo('GET /api/export')[0].path).toBe(`/api/export?format=${format}`);
    expect(api.callsTo('GET /api/export')[0].headers.get('Authorization')).toBe('Bearer test-token');
    expect(blobs[0].size).toBeGreaterThan(0);
  });

  it('falls back to a dated file name without Content-Disposition', async () => {
    fakeApi({
      'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } },
      'GET /api/export': { bytes: new Uint8Array([91, 93]) },
    });
    const { clicked } = captureDownloads();
    const { user } = renderApp('/settings');
    await user.click(await screen.findByRole('button', { name: 'Export JSON' }));
    await waitFor(() => expect(clicked).toHaveLength(1));
    expect(clicked[0].download).toMatch(/^vocab-\d{4}-\d{2}-\d{2}\.json$/);
  });

  it('shows an export error', async () => {
    fakeApi({
      'GET /api/obsidian/status': { body: { enabled: false, lastRun: null } },
      'GET /api/export': { status: 500, body: { error: 'internal server error' } },
    });
    const { user } = renderApp('/settings');
    await user.click(await screen.findByRole('button', { name: 'Export CSV' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('internal server error');
  });
});
