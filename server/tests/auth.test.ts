import { describe, expect, it } from 'vitest';
import { makeApp } from './helpers.js';

describe('users', () => {
  it('creates a user and lists it with hasPin', async () => {
    const { call } = makeApp();
    const created = await call('POST', '/users', { body: { name: 'olli', pin: '1234' } });
    expect(created.status).toBe(201);
    expect(created.body).toEqual({ id: expect.any(Number), name: 'olli', hasPin: true });

    await call('POST', '/users', { body: { name: 'guest' } });
    const list = await call('GET', '/users');
    expect(list.status).toBe(200);
    expect(list.body).toEqual([
      { id: created.body.id, name: 'olli', hasPin: true },
      { id: expect.any(Number), name: 'guest', hasPin: false },
    ]);
  });

  it('returns 409 when the name is taken', async () => {
    const { call } = makeApp();
    await call('POST', '/users', { body: { name: 'olli' } });
    const dup = await call('POST', '/users', { body: { name: 'olli' } });
    expect(dup.status).toBe(409);
    expect(dup.body).toEqual({ error: expect.any(String) });
  });

  it('rejects an empty name with 400', async () => {
    const { call } = makeApp();
    const res = await call('POST', '/users', { body: { name: '  ' } });
    expect(res.status).toBe(400);
    expect(res.body.error).toEqual(expect.any(String));
  });

  it('never exposes the pin hash', async () => {
    const { call } = makeApp();
    const created = await call('POST', '/users', { body: { name: 'olli', pin: '1234' } });
    expect(JSON.stringify(created.body)).not.toContain('pin_hash');
    expect(created.body).not.toHaveProperty('pinHash');
  });
});

describe('auth', () => {
  it('logs in with the right PIN and returns a base64url token', async () => {
    const { call } = makeApp();
    const user = await call('POST', '/users', { body: { name: 'olli', pin: '1234' } });
    const res = await call('POST', '/auth/login', {
      body: { userId: user.body.id, pin: '1234', device: 'web' },
    });
    expect(res.status).toBe(200);
    expect(res.body.user).toEqual({ id: user.body.id, name: 'olli' });
    // 32 random bytes, base64url, no padding → 43 chars
    expect(res.body.token).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it('returns 403 on a wrong or missing PIN', async () => {
    const { call } = makeApp();
    const user = await call('POST', '/users', { body: { name: 'olli', pin: '1234' } });
    const wrong = await call('POST', '/auth/login', {
      body: { userId: user.body.id, pin: '0000', device: 'web' },
    });
    expect(wrong.status).toBe(403);
    const missing = await call('POST', '/auth/login', { body: { userId: user.body.id, device: 'web' } });
    expect(missing.status).toBe(403);
  });

  it('ignores the pin for a user without one', async () => {
    const { call } = makeApp();
    const user = await call('POST', '/users', { body: { name: 'guest' } });
    const res = await call('POST', '/auth/login', {
      body: { userId: user.body.id, pin: 'whatever', device: 'android:Pixel 8' },
    });
    expect(res.status).toBe(200);
  });

  it('returns 404 for an unknown user', async () => {
    const { call } = makeApp();
    const res = await call('POST', '/auth/login', { body: { userId: 999, device: 'web' } });
    expect(res.status).toBe(404);
  });

  it('GET /auth/me returns the current user', async () => {
    const { call, loginAs } = makeApp();
    const token = await loginAs('olli', '1234');
    const me = await call('GET', '/auth/me', { token });
    expect(me.status).toBe(200);
    expect(me.body).toEqual({ id: expect.any(Number), name: 'olli' });
  });

  it('returns 401 without a token or with an unknown token', async () => {
    const { call } = makeApp();
    expect((await call('GET', '/auth/me')).status).toBe(401);
    const bad = await call('GET', '/auth/me', { token: 'nope' });
    expect(bad.status).toBe(401);
    expect(bad.body).toEqual({ error: expect.any(String) });
  });

  it('logout invalidates the token', async () => {
    const { call, loginAs } = makeApp();
    const token = await loginAs('olli');
    const out = await call('POST', '/auth/logout', { token });
    expect(out.status).toBe(204);
    expect((await call('GET', '/auth/me', { token })).status).toBe(401);
  });
});

describe('errors', () => {
  it('unknown routes return a JSON 404', async () => {
    const { call } = makeApp();
    const res = await call('GET', '/nope');
    expect(res.status).toBe(404);
    expect(res.body).toEqual({ error: expect.any(String) });
  });

  it('malformed JSON returns 400', async () => {
    const { app } = makeApp();
    const res = await app.request('/api/users', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{not json',
    });
    expect(res.status).toBe(400);
    expect(await res.json()).toEqual({ error: expect.any(String) });
  });
});
