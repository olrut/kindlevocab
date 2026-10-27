import { fireEvent, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { timing } from '../src/pages/Import';
import { fakeApi, renderApp } from './fakeApi';

const preview = {
  importId: 'abc',
  candidates: [
    { word: 'torpid', usage: 'A torpid afternoon.', bookTitle: 'Dune', bookAuthor: 'Frank Herbert', kindleCategory: 'mastered', exists: false },
    { word: 'ubiquitous', usage: 'Spice was ubiquitous.', bookTitle: 'Dune', bookAuthor: 'Frank Herbert', kindleCategory: 'learning', exists: true },
    { word: 'lucid', usage: null, bookTitle: null, bookAuthor: null, kindleCategory: 'learning', exists: false },
  ],
};

const vocabFile = () => new File([new Uint8Array([83, 81, 76])], 'vocab.db');

function setup(routes = {}) {
  timing.pollMs = 5;
  const api = fakeApi({ 'POST /api/import/kindle': { body: preview }, ...routes });
  return { api, ...renderApp('/import') };
}

async function upload(user: ReturnType<typeof setup>['user']) {
  await user.upload(await screen.findByLabelText('Kindle vocab.db'), vocabFile());
  return screen.findByRole('table', { name: 'Kindle words' });
}

describe('/import (Kindle)', () => {
  it('uploads the file and shows the preview; new words are pre-selected, existing ones not', async () => {
    const { api, user } = setup();
    const table = await upload(user);
    const sent = api.callsTo('POST /api/import/kindle')[0].body as FormData;
    expect(sent.get('file')).toBeInstanceOf(File);
    expect(sent.get('onlyNew')).toBe('true');

    const torpid = within(table).getByRole('row', { name: /torpid/ });
    expect(torpid).toHaveTextContent('A torpid afternoon.');
    expect(torpid).toHaveTextContent('Dune');
    expect(torpid).toHaveTextContent('mastered');
    expect(within(torpid).getByRole('checkbox')).toBeChecked();
    const existing = within(table).getByRole('row', { name: /ubiquitous/ });
    expect(existing).toHaveTextContent('already saved');
    expect(within(existing).getByRole('checkbox')).not.toBeChecked();
    expect(screen.getByRole('button', { name: 'Import 2 words' })).toBeEnabled();
  });

  it('accepts a dropped file', async () => {
    const { api } = setup();
    fireEvent.drop(await screen.findByText(/Drop vocab.db here/), { dataTransfer: { files: [vocabFile()] } });
    await screen.findByRole('table', { name: 'Kindle words' });
    expect(api.callsTo('POST /api/import/kindle')).toHaveLength(1);
  });

  it('sends onlyNew=false when "only new" is off, and re-reads the file when it is toggled', async () => {
    const { api, user } = setup();
    await upload(user);
    await user.click(screen.getByLabelText('Only words new since the last import'));
    await screen.findByRole('table', { name: 'Kindle words' });
    const calls = api.callsTo('POST /api/import/kindle');
    expect(calls).toHaveLength(2);
    expect((calls[1].body as FormData).get('onlyNew')).toBe('false');
  });

  it('select all / none', async () => {
    const { user } = setup();
    await upload(user);
    await user.click(screen.getByRole('button', { name: 'Select none' }));
    expect(screen.getByRole('button', { name: 'Import 0 words' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Select all' }));
    expect(screen.getByRole('button', { name: 'Import 3 words' })).toBeEnabled();
  });

  it('confirms the selection and follows progress until finished, listing errors', async () => {
    let polls = 0;
    const { api, user } = setup({
      'POST /api/import/kindle/:id/confirm': { status: 202, body: { state: 'running', done: 0, total: 2, imported: 0, skipped: 0, errors: [] } },
      'GET /api/import/kindle/:id': () =>
        ++polls < 2
          ? { body: { state: 'running', done: 1, total: 2, imported: 1, skipped: 0, errors: [] } }
          : { body: { state: 'finished', done: 2, total: 2, imported: 1, skipped: 0, errors: [{ word: 'lucid', error: 'not_english' }] } },
    });
    await upload(user);
    await user.click(within(screen.getByRole('row', { name: /torpid/ })).getByRole('checkbox'));
    await user.click(within(screen.getByRole('row', { name: /torpid/ })).getByRole('checkbox'));
    await user.click(screen.getByLabelText('Mark mastered words as learned'));
    await user.click(screen.getByRole('button', { name: 'Import 2 words' }));

    expect(api.callsTo('POST /api/import/kindle/abc/confirm')[0].body).toEqual({ words: ['torpid', 'lucid'], markMasteredAsLearned: false });
    expect(await screen.findByText('Done: 1 imported, 0 skipped, 1 failed.')).toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '2');
    expect(screen.getByText('lucid')).toBeInTheDocument();
    expect(screen.getByText(/not English/)).toBeInTheDocument();
    expect(polls).toBe(2);
  });

  it('shows the server error for a file that is not a Kindle vocab.db', async () => {
    const { user } = setup({ 'POST /api/import/kindle': { status: 400, body: { error: 'WORDS table is missing' } } });
    await user.upload(await screen.findByLabelText('Kindle vocab.db'), vocabFile());
    expect(await screen.findByRole('alert')).toHaveTextContent('WORDS table is missing');
  });

  it('says when there is nothing new', async () => {
    const { user } = setup({ 'POST /api/import/kindle': { body: { importId: 'x', candidates: [] } } });
    await user.upload(await screen.findByLabelText('Kindle vocab.db'), vocabFile());
    expect(await screen.findByText(/No new words/)).toBeInTheDocument();
  });
});

describe('/import (CSV / JSON)', () => {
  const csvFile = () => new File(['word,definition\nlucid,clear\n'], 'words.csv', { type: 'text/csv' });

  it('uploads the file and reports what was imported', async () => {
    const api = fakeApi({ 'POST /api/import': { body: { imported: 3, skipped: 1 } } });
    const { user } = renderApp('/import');
    await user.upload(await screen.findByLabelText('CSV or JSON file'), csvFile());
    expect(await screen.findByText('Imported 3 words; 1 already existed and was skipped.')).toBeInTheDocument();
    const sent = api.callsTo('POST /api/import')[0].body as FormData;
    expect((sent.get('file') as File).name).toBe('words.csv');
  });

  it('pluralises the skipped count', async () => {
    fakeApi({ 'POST /api/import': { body: { imported: 1, skipped: 0 } } });
    const { user } = renderApp('/import');
    await user.upload(await screen.findByLabelText('CSV or JSON file'), csvFile());
    expect(await screen.findByText('Imported 1 word; 0 already existed and were skipped.')).toBeInTheDocument();
  });

  it('shows why the server rejected the file', async () => {
    fakeApi({ 'POST /api/import': { status: 400, body: { error: 'row 3: definition is required' } } });
    const { user } = renderApp('/import');
    await user.upload(await screen.findByLabelText('CSV or JSON file'), csvFile());
    expect(await screen.findByRole('alert')).toHaveTextContent('row 3: definition is required');
  });
});
