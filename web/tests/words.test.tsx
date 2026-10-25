import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fakeApi, renderApp, word } from './fakeApi';

const learning = [
  word({ id: 1, word: 'ubiquitous', bookTitle: 'The Sensor Age' }),
  word({ id: 2, word: 'torpid', definition: 'mentally or physically inactive', finnish: 'veltto', example: null }),
];
const learned = [word({ id: 3, word: 'lucid', definition: 'expressed clearly', finnish: 'selkeä', status: 'learned', learnedAt: '2026-10-20T00:00:00.000Z' })];

function setup() {
  const api = fakeApi({
    'GET /api/words': (req) => ({ body: req.path.includes('status=learned') ? learned : learning }),
    'PATCH /api/words/:id': (req) => {
      const current = [...learning, ...learned].find((w) => w.id === Number(req.params.id))!;
      return { body: { ...current, ...req.body } };
    },
    'DELETE /api/words/:id': { status: 204 },
  });
  return { api, ...renderApp('/words') };
}

const row = (name: string) => screen.getByRole('row', { name: new RegExp(`^${name}\\b`) });

describe('/words', () => {
  it('lists learning words with word, Finnish, definition and book', async () => {
    const { api } = setup();
    await screen.findByText('ubiquitous');
    expect(api.callsTo('GET /api/words')[0].path).toBe('/api/words?status=learning');
    const r = row('ubiquitous');
    expect(r).toHaveTextContent('kaikkialla läsnä oleva');
    expect(r).toHaveTextContent('present or found everywhere');
    expect(r).toHaveTextContent('The Sensor Age');
    expect(screen.getByRole('tab', { name: 'Learning (2)', selected: true })).toBeInTheDocument();
  });

  it('switches to the Learned tab', async () => {
    const { api, user } = setup();
    await screen.findByText('ubiquitous');
    await user.click(screen.getByRole('tab', { name: /Learned/ }));
    expect(await screen.findByText('lucid')).toBeInTheDocument();
    expect(screen.queryByText('ubiquitous')).not.toBeInTheDocument();
    expect(api.callsTo('GET /api/words').at(-1)?.path).toBe('/api/words?status=learned');
  });

  it('filters the list client-side on word, Finnish and definition', async () => {
    const { api, user } = setup();
    await screen.findByText('ubiquitous');
    const search = screen.getByRole('searchbox', { name: 'Search' });
    await user.type(search, 'VELT');
    expect(screen.queryByText('ubiquitous')).not.toBeInTheDocument();
    expect(screen.getByText('torpid')).toBeInTheDocument();
    await user.clear(search);
    await user.type(search, 'everywhere');
    expect(screen.getByText('ubiquitous')).toBeInTheDocument();
    expect(screen.queryByText('torpid')).not.toBeInTheDocument();
    expect(api.callsTo('GET /api/words')).toHaveLength(1);
  });

  it('marks a word learned and removes it from the Learning tab', async () => {
    const { api, user } = setup();
    await screen.findByText('torpid');
    await user.click(within(row('torpid')).getByRole('button', { name: 'Mark learned' }));
    expect(api.callsTo('PATCH /api/words/2')[0].body).toEqual({ status: 'learned' });
    expect(screen.queryByText('torpid')).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Learning (1)' })).toBeInTheDocument();
  });

  it('restores a learned word to learning', async () => {
    const { api, user } = setup();
    await screen.findByText('ubiquitous');
    await user.click(screen.getByRole('tab', { name: /Learned/ }));
    await user.click(within(row('lucid')).getByRole('button', { name: 'Restore' }));
    expect(api.callsTo('PATCH /api/words/3')[0].body).toEqual({ status: 'learning' });
    expect(screen.queryByText('lucid')).not.toBeInTheDocument();
  });

  it('edits definition, example, Finnish and notes, warning that Obsidian edits are overwritten', async () => {
    const { api, user } = setup();
    await screen.findByText('torpid');
    await user.click(within(row('torpid')).getByRole('button', { name: 'Edit' }));
    const form = screen.getByRole('form', { name: 'Edit torpid' });
    expect(within(form).getByText(/overwritten/)).toBeInTheDocument();
    await user.clear(within(form).getByLabelText('Definition'));
    await user.type(within(form).getByLabelText('Definition'), 'sluggish');
    await user.type(within(form).getByLabelText('Notes'), 'From a Dune re-read.');
    await user.click(within(form).getByRole('button', { name: 'Save' }));

    expect(api.callsTo('PATCH /api/words/2')[0].body).toEqual({
      definition: 'sluggish',
      example: null,
      finnish: 'veltto',
      notes: 'From a Dune re-read.',
    });
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
    expect(row('torpid')).toHaveTextContent('sluggish');
  });

  it('cancelling an edit sends nothing', async () => {
    const { api, user } = setup();
    await screen.findByText('torpid');
    await user.click(within(row('torpid')).getByRole('button', { name: 'Edit' }));
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(api.callsTo('PATCH /api/words/2')).toHaveLength(0);
  });

  it('deletes after a second confirming click', async () => {
    const { api, user } = setup();
    await screen.findByText('torpid');
    await user.click(within(row('torpid')).getByRole('button', { name: 'Delete' }));
    expect(api.callsTo('DELETE /api/words/2')).toHaveLength(0);
    await user.click(within(row('torpid')).getByRole('button', { name: 'Really delete?' }));
    expect(api.callsTo('DELETE /api/words/2')).toHaveLength(1);
    expect(screen.queryByText('torpid')).not.toBeInTheDocument();
  });
});
