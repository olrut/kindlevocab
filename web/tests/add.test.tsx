import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { fakeApi, renderApp, word } from './fakeApi';

const lookup = {
  word: 'ubiquitous',
  ipa: '/juːˈbɪkwɪtəs/',
  audioAvailable: true,
  candidates: [
    { definition: 'present or found everywhere', example: 'Smartphones are ubiquitous.', finnish: 'kaikkialla läsnä oleva' },
    { definition: 'very common', example: 'Coffee shops are ubiquitous.', finnish: 'yleinen' },
  ],
  cached: false,
};

async function lookedUp(routes = {}) {
  const api = fakeApi({ 'POST /api/lookup': { body: lookup }, ...routes });
  const r = renderApp('/add');
  await r.user.type(await screen.findByLabelText('Word'), 'Ubiquitous');
  await r.user.click(screen.getByRole('button', { name: 'Look up' }));
  await screen.findByText('/juːˈbɪkwɪtəs/');
  return { api, ...r };
}

describe('/add', () => {
  it('looks the word up and shows IPA and the candidate cards', async () => {
    const { api } = await lookedUp();
    expect(api.callsTo('POST /api/lookup')[0].body).toEqual({ word: 'Ubiquitous' });
    const cards = screen.getAllByRole('button', { name: /Use this meaning/ });
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent('present or found everywhere');
    expect(cards[0]).toHaveTextContent('kaikkialla läsnä oleva');
  });

  it('a card fills the editable form; saving posts it, toasts, clears and refocuses the input', async () => {
    const { api, user } = await lookedUp({ 'POST /api/words': (req: any) => ({ status: 201, body: word(req.body) }) });
    await user.click(screen.getAllByRole('button', { name: /Use this meaning/ })[1]);
    const definition = screen.getByLabelText('Definition');
    expect(definition).toHaveValue('very common');
    expect(screen.getByLabelText('Example')).toHaveValue('Coffee shops are ubiquitous.');
    expect(screen.getByLabelText('Finnish')).toHaveValue('yleinen');

    await user.clear(definition);
    await user.type(definition, 'seen everywhere');
    await user.click(screen.getByRole('button', { name: 'Save' }));

    expect(api.callsTo('POST /api/words')[0].body).toEqual({
      word: 'ubiquitous',
      definition: 'seen everywhere',
      example: 'Coffee shops are ubiquitous.',
      finnish: 'yleinen',
      ipa: '/juːˈbɪkwɪtəs/',
      source: 'claude',
    });
    expect(await screen.findByRole('status')).toHaveTextContent('Saved “ubiquitous”');
    const input = screen.getByLabelText('Word');
    expect(input).toHaveValue('');
    expect(input).toHaveFocus();
    expect(screen.queryByLabelText('Definition')).not.toBeInTheDocument();
  });

  it('"Write my own" opens the form empty and saves with source manual', async () => {
    const { api, user } = await lookedUp({ 'POST /api/words': { status: 201, body: word() } });
    await user.click(screen.getByRole('button', { name: 'Write my own' }));
    expect(screen.getByLabelText('Definition')).toHaveValue('');
    await user.type(screen.getByLabelText('Definition'), 'everywhere at once');
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(api.callsTo('POST /api/words')[0].body).toMatchObject({ word: 'ubiquitous', definition: 'everywhere at once', source: 'manual', example: null, finnish: null });
  });

  it('does not save without a definition', async () => {
    const { api, user } = await lookedUp();
    await user.click(screen.getByRole('button', { name: 'Write my own' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(api.callsTo('POST /api/words')).toHaveLength(0);
  });

  it('offers the suggestion when Claude says the word is not English', async () => {
    const api = fakeApi({
      'POST /api/lookup': (req) =>
        req.body.word === 'ubiquitus'
          ? { status: 422, body: { error: 'not_english', suggestion: 'ubiquitous' } }
          : { body: lookup },
    });
    const { user } = renderApp('/add');
    await user.type(await screen.findByLabelText('Word'), 'ubiquitus{Enter}');
    await user.click(await screen.findByRole('button', { name: 'Did you mean “ubiquitous”?' }));
    expect(await screen.findByText('/juːˈbɪkwɪtəs/')).toBeInTheDocument();
    expect(api.callsTo('POST /api/lookup').at(-1)?.body).toEqual({ word: 'ubiquitous' });
    expect(screen.getByLabelText('Word')).toHaveValue('ubiquitous');
  });

  it('reports a failed lookup and still allows writing your own', async () => {
    fakeApi({ 'POST /api/lookup': { status: 502, body: { error: 'lookup failed' } } });
    const { user } = renderApp('/add');
    await user.type(await screen.findByLabelText('Word'), 'torpid{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('Lookup failed');
    await user.click(screen.getByRole('button', { name: 'Write my own' }));
    expect(screen.getByLabelText('Definition')).toBeInTheDocument();
  });

  it('says so when the word is already saved', async () => {
    const { user } = await lookedUp({ 'POST /api/words': { status: 409, body: { error: 'word already exists' } } });
    await user.click(screen.getAllByRole('button', { name: /Use this meaning/ })[0]);
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('“ubiquitous” is already in your list');
  });

  it('plays the server-side audio with the token attached', async () => {
    const play = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('Audio', vi.fn(function (this: { play: typeof play }) { this.play = play; }));
    URL.createObjectURL = vi.fn(() => 'blob:x');
    URL.revokeObjectURL = vi.fn();
    const { api, user } = await lookedUp({ 'GET /api/lookup/audio/:word': { bytes: new Uint8Array([1, 2, 3]) } });
    await user.click(screen.getByRole('button', { name: 'Play pronunciation' }));
    await waitFor(() => expect(play).toHaveBeenCalled());
    const [req] = api.callsTo('GET /api/lookup/audio/ubiquitous');
    expect(req.headers.get('Authorization')).toBe('Bearer test-token');
  });

  it('falls back to speech synthesis when there is no recording', async () => {
    const speak = vi.fn();
    vi.stubGlobal('speechSynthesis', { speak, cancel: vi.fn() });
    vi.stubGlobal('SpeechSynthesisUtterance', vi.fn(function (this: any, text: string) { this.text = text; }));
    fakeApi({ 'POST /api/lookup': { body: { ...lookup, audioAvailable: false } } });
    const { user } = renderApp('/add');
    await user.type(await screen.findByLabelText('Word'), 'ubiquitous{Enter}');
    await user.click(await screen.findByRole('button', { name: 'Play pronunciation' }));
    expect(speak).toHaveBeenCalledWith(expect.objectContaining({ text: 'ubiquitous', lang: 'en-US' }));
  });

  it('shows the looked-up word and IPA as a heading region', async () => {
    await lookedUp();
    const result = screen.getByRole('region', { name: 'ubiquitous' });
    expect(within(result).getByText('/juːˈbɪkwɪtəs/')).toBeInTheDocument();
  });
});
