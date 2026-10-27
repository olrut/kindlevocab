import { describe, expect, it } from 'vitest';
import { makeApp } from './helpers.js';

describe('GET /health', () => {
  it('answers without a token once the database is open', async () => {
    const { call } = makeApp();
    expect(await call('GET', '/health')).toEqual({ status: 200, body: { ok: true } });
  });
});
