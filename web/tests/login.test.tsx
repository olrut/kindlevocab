import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fakeApi, renderApp } from './fakeApi';

const users = [
  { id: 1, name: 'olli', hasPin: false },
  { id: 2, name: 'anna', hasPin: true },
];

describe('/login', () => {
  it('lists users; a user without a PIN logs in with one click and lands on /add', async () => {
    const api = fakeApi({
      'GET /api/users': { body: users },
      'POST /api/auth/login': { body: { token: 'tok-olli', user: { id: 1, name: 'olli' } } },
    });
    const { user } = renderApp('/login', { token: null });

    await user.click(await screen.findByRole('button', { name: 'olli' }));
    expect(api.callsTo('POST /api/auth/login')[0].body).toEqual({ userId: 1, device: 'web' });
    expect(await screen.findByRole('heading', { name: 'Add a word' })).toBeInTheDocument();
    expect(localStorage.getItem('vocab.token')).toBe('tok-olli');
    expect(api.callsTo('GET /api/auth/me').at(-1)?.headers.get('Authorization')).toBe('Bearer tok-olli');
  });

  it('asks for the PIN of a user who has one, and reports a wrong PIN', async () => {
    const api = fakeApi({
      'GET /api/users': { body: users },
      'POST /api/auth/login': (req) =>
        req.body.pin === '1234'
          ? { body: { token: 'tok-anna', user: { id: 2, name: 'anna' } } }
          : { status: 403, body: { error: 'wrong PIN' } },
    });
    const { user } = renderApp('/login', { token: null });

    await user.click(await screen.findByRole('button', { name: 'anna' }));
    expect(api.callsTo('POST /api/auth/login')).toHaveLength(0);
    const pin = screen.getByLabelText('PIN for anna');
    await user.type(pin, '0000{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong PIN');

    await user.clear(pin);
    await user.type(pin, '1234{Enter}');
    expect(await screen.findByRole('heading', { name: 'Add a word' })).toBeInTheDocument();
    expect(api.callsTo('POST /api/auth/login').at(-1)?.body).toEqual({ userId: 2, pin: '1234', device: 'web' });
  });

  it('creates a new user and logs in as them', async () => {
    const api = fakeApi({
      'GET /api/users': { body: users },
      'POST /api/users': (req) =>
        req.body.name === 'olli'
          ? { status: 409, body: { error: 'name already taken' } }
          : { status: 201, body: { id: 3, name: req.body.name, hasPin: !!req.body.pin } },
      'POST /api/auth/login': { body: { token: 'tok-new', user: { id: 3, name: 'mia' } } },
    });
    const { user } = renderApp('/login', { token: null });

    await user.type(await screen.findByLabelText('Name'), 'olli');
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('name already taken');

    await user.clear(screen.getByLabelText('Name'));
    await user.type(screen.getByLabelText('Name'), 'mia');
    await user.type(screen.getByLabelText('PIN (optional)'), '42');
    await user.click(screen.getByRole('button', { name: 'Create user' }));
    expect(await screen.findByRole('heading', { name: 'Add a word' })).toBeInTheDocument();
    expect(api.callsTo('POST /api/users').at(-1)?.body).toEqual({ name: 'mia', pin: '42' });
    expect(api.callsTo('POST /api/auth/login').at(-1)?.body).toEqual({ userId: 3, pin: '42', device: 'web' });
  });
});

describe('auth guard', () => {
  it('sends a visitor without a token to /login', async () => {
    const api = fakeApi({ 'GET /api/users': { body: users } });
    renderApp('/words', { token: null });
    expect(await screen.findByRole('button', { name: 'olli' })).toBeInTheDocument();
    expect(api.callsTo('GET /api/auth/me')).toHaveLength(0);
  });

  it('a 401 from any request clears the token and goes to /login', async () => {
    fakeApi({
      'GET /api/users': { body: users },
      'GET /api/auth/me': { body: { id: 1, name: 'olli' } },
      'GET /api/words': { status: 401, body: { error: 'invalid token' } },
    });
    renderApp('/words');
    expect(await screen.findByRole('button', { name: 'olli' })).toBeInTheDocument();
    await waitFor(() => expect(localStorage.getItem('vocab.token')).toBeNull());
  });

  it('shows the user name and the navigation when logged in', async () => {
    fakeApi({ 'GET /api/words': { body: [] } });
    renderApp('/add');
    expect(await screen.findByText('olli')).toBeInTheDocument();
    for (const name of ['Add', 'Words', 'Import', 'Settings']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument();
    }
  });
});
