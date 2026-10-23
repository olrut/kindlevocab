# Vocab — design spec

## 1. Purpose

A personal English vocabulary trainer.

- **Web UI**: look up a word, pick one of the suggested meanings (or write your own), save it. Manage the list, move learned words back to the learning list, switch user, export/import.
- **Android app**: shows one word at a time, full screen. Tap shows the meaning. Swipe down marks the word learned (with a short undo). Swipe left or right shows the next word. Random order; when the list is exhausted, reshuffle and start over. Empty list shows "Add words in the web app". Works offline from a local cache.
- **Server**: one Node process on a Proxmox VM, reached over Tailscale. Holds the only copy of the Anthropic API key. **The SQLite database is the source of truth.**
- **Kindle import**: the user copies `system/vocabulary/vocab.db` from the Kindle over USB and uploads it in the web UI. Words come in with the real sentence from the book as the example; Claude only adds the definition and the Finnish equivalent.
- **Obsidian export**: the server mirrors every word into an Obsidian vault folder as Markdown (one note per word, one note per book). The vault is a *view*; the server overwrites its own files and never reads them back. The folder is synced to the user's devices by Syncthing (or any sync tool) outside this app.

Owner is the only user for now, but the schema and UI support several users selectable from a simple menu.

## 2. Data model (SQLite)

```sql
CREATE TABLE users (
  id          INTEGER PRIMARY KEY,
  name        TEXT NOT NULL UNIQUE,
  pin_hash    TEXT,                       -- NULL = no PIN required
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE sessions (
  token       TEXT PRIMARY KEY,           -- 32 random bytes, base64url
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device      TEXT,                       -- 'web' | 'android:<model>'
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  last_seen   TEXT
);

CREATE TABLE words (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  word        TEXT NOT NULL,              -- lowercase, trimmed
  definition  TEXT NOT NULL,              -- short, English
  example     TEXT,
  finnish     TEXT,
  ipa         TEXT,
  audio_path  TEXT,                       -- 'audio/42.mp3' relative to data dir; NULL = use TTS
  source      TEXT NOT NULL DEFAULT 'claude',   -- 'claude' | 'manual' | 'import' | 'kindle'
  status      TEXT NOT NULL DEFAULT 'learning', -- 'learning' | 'learned'
  learned_at  TEXT,
  book_title  TEXT,                       -- from Kindle BOOK_INFO, NULL otherwise
  book_author TEXT,
  kindle_lookup_at TEXT,                  -- when the word was looked up on the Kindle
  notes       TEXT,                       -- free text, editable in web UI, exported to Obsidian
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  updated_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  deleted_at  TEXT,                       -- soft delete, needed by sync
  UNIQUE (user_id, word)
);
CREATE INDEX words_user_status  ON words(user_id, status);
CREATE INDEX words_user_updated ON words(user_id, updated_at);

CREATE TABLE lookup_cache (
  cache_key   TEXT PRIMARY KEY,           -- lowercase word, or word + '|' + sha1(example) when context given
  payload     TEXT NOT NULL,              -- JSON, the exact /lookup response
  created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now'))
);

CREATE TABLE kindle_imports (
  id          INTEGER PRIMARY KEY,
  user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  imported_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
  words_new   INTEGER NOT NULL,
  words_skipped INTEGER NOT NULL,
  last_kindle_timestamp INTEGER            -- max WORDS.timestamp seen, for incremental import
);
```

Rules:
- Every write to `words` sets `updated_at = now` and schedules an Obsidian export of that word (§ 7).
- `status = 'learned'` sets `learned_at`; `status = 'learning'` clears it.
- Random review order is not stored; the client shuffles.

## 3. API

Base path `/api`. JSON bodies and responses. Auth: `Authorization: Bearer <token>` on everything except `GET /users`, `POST /users`, `POST /auth/login`. Unknown token → 401. Errors → `{ "error": "..." }`.

### Users and auth

| Method | Path | Body | Response |
|---|---|---|---|
| GET | `/users` | – | `[{ id, name, hasPin }]` |
| POST | `/users` | `{ name, pin? }` | `{ id, name, hasPin }` (409 if name taken) |
| POST | `/auth/login` | `{ userId, pin?, device }` | `{ token, user: { id, name } }` (403 on wrong PIN) |
| POST | `/auth/logout` | – | 204 |
| GET | `/auth/me` | – | `{ id, name }` |

PIN hashed with bcrypt (cost 10). If the user has no PIN, `pin` is ignored.

### Lookup

`POST /lookup` `{ word, context? }` — `context` is an optional sentence in which the word was seen (Kindle usage). When given, Claude picks the sense that fits the sentence and returns exactly one candidate whose `example` is the given sentence.

Response:
```json
{
  "word": "ubiquitous",
  "ipa": "/juːˈbɪkwɪtəs/",
  "audioAvailable": true,
  "candidates": [
    { "definition": "present or found everywhere", "example": "Smartphones are ubiquitous these days.", "finnish": "kaikkialla läsnä oleva, yleinen" }
  ],
  "cached": false
}
```

Server behaviour:
1. Normalise the word (lowercase, trim). If in `lookup_cache`, return it with `cached: true`.
2. In parallel:
   - `GET https://api.dictionaryapi.dev/api/v2/entries/en/{word}` → first `phonetics[].text` as `ipa`, first `phonetics[].audio` that is non-empty as the audio URL. Failure or 404 → `ipa: null`, `audioAvailable: false`. Timeout 5 s.
   - Anthropic Messages API (see § 4). Failure → 502, nothing cached.
3. Store the full response plus the audio URL in `lookup_cache.payload` (the audio URL is internal; strip it from the client response).

Errors: `{ "error": "not_english", "suggestion": "..." }` with 422 when Claude reports the word is not English or is misspelled.

### Words

| Method | Path | Body / query | Response |
|---|---|---|---|
| POST | `/words` | `{ word, definition, example?, finnish?, ipa?, source }` | `201 { ...word }` (409 if exists and not deleted; if soft-deleted, revive and update) |
| GET | `/words` | `?status=learning\|learned\|all` (default `learning`) | `[ ...words ]`, excludes deleted |
| GET | `/words/:id` | – | word |
| PATCH | `/words/:id` | any of `{ status, definition, example, finnish }` | updated word |
| DELETE | `/words/:id` | – | 204, sets `deleted_at` |
| GET | `/words/changes` | `?since=<ISO>` | `{ serverTime, words: [ ... ] }` — all rows with `updated_at > since`, **including deleted ones** (`deletedAt` set) |
| GET | `/audio/:id` | – | `audio/mpeg`, 404 if `audio_path` is NULL |

Word JSON shape (camelCase):
```json
{ "id": 42, "word": "ubiquitous", "definition": "...", "example": "...", "finnish": "...",
  "ipa": "...", "hasAudio": true, "source": "claude", "status": "learning",
  "learnedAt": null, "createdAt": "...", "updatedAt": "...", "deletedAt": null }
```

`serverTime` is reported 1 ms before the server's current time, so a write later in the same millisecond is still picked up by the next pull. A row may therefore be sent twice; clients upsert, so this is harmless. `since` must be an ISO-8601 timestamp (400 otherwise).

On `POST /words`, after inserting, the server looks up the cached audio URL for that word and downloads it to `data/audio/<id>.mp3` in the background (fire-and-forget with logging). `audio_path` is set when the download finishes.

### Export / import

- `GET /export?format=json|csv` — all non-deleted words of the current user. CSV columns: `word,definition,example,finnish,ipa,status,learned_at,created_at,book_title,book_author,notes`.
- `POST /import` — multipart file (`.json` or `.csv`, same shape). Existing words (same `word`) are skipped. `source = 'import'`. Response `{ imported, skipped }`. Audio is fetched lazily for imported words: after import, enqueue a lookup of the dictionary API for each new word to fill `ipa`/audio if missing.

### Kindle import

`POST /import/kindle` — multipart upload of the Kindle's `vocab.db` (SQLite). Max 50 MB.

Kindle schema (verify against the uploaded file; column names differ slightly between firmware versions):
- `WORDS(id, word, stem, lang, category, timestamp, profileid)` — `category` 0 = learning, 1 = mastered; `timestamp` ms since epoch.
- `LOOKUPS(id, word_key, book_key, dict_key, pos, usage, timestamp)` — `usage` is the sentence from the book; `word_key` references `WORDS.id`, `book_key` references `BOOK_INFO.id`.
- `BOOK_INFO(id, asin, guid, lang, title, authors)`.

Server behaviour:
1. Open the upload read-only with `better-sqlite3`; reject if `WORDS` table is missing.
2. Select words with `lang = 'en'` and, if `onlyNew=true` (default), `timestamp > kindle_imports.last_kindle_timestamp` for this user. Use `stem` as the word (fallback `word`). Join the newest `LOOKUPS` row for the usage sentence and the book.
3. Return a **preview**, not a direct import: `{ importId, candidates: [{ word, usage, bookTitle, bookAuthor, kindleCategory, exists }] }`. The temp db file is kept under `data/tmp/<importId>.db` for 1 hour.
4. `POST /import/kindle/:importId/confirm` `{ words: ["ubiquitous", ...] , markMasteredAsLearned: true }` — for each selected word: `POST /lookup`-equivalent with `context = usage`, then insert with `source = 'kindle'`, `example = usage`, `book_title`, `book_author`, `kindle_lookup_at`, `status` from category if `markMasteredAsLearned`. Runs as a background job; progress via `GET /import/kindle/:importId` → `{ done, total, errors: [{ word, error }] }`. Words Claude rejects as `not_english` are listed in `errors` and skipped. Record a `kindle_imports` row at the end.
5. Existing words are skipped (never overwritten), except that a word with `status = 'learning'` whose Kindle category is mastered is set to `learned` when `markMasteredAsLearned` is true.

Rate limit Claude calls in the job to 5 concurrent requests.

## 4. Claude lookup prompt

Model: the current smallest Claude model (Haiku family). `max_tokens: 400`, `temperature: 0.3`.

System prompt:

```
You are a dictionary for an English learner whose native language is Finnish.
Given one English word, return ONLY a JSON object, no prose:

{"candidates":[{"definition":"...","example":"...","finnish":"..."}]}

Rules:
- 1 to 3 candidates, most common meaning first. Separate candidates only for clearly different senses.
- definition: plain English, max 12 words, no leading article, no repetition of the word.
- example: one natural sentence using the word, max 15 words.
- finnish: 1-3 Finnish equivalents separated by commas, base form.
- If the input is not an English word or is misspelled, return {"error":"not_english","suggestion":"<likely intended word or null>"}.
```

User message: the word, nothing else.

**With context** (Kindle import or `context` given to `/lookup`), append to the system prompt:

```
The user saw the word in this sentence: "<context>"
Return exactly one candidate: the sense that fits this sentence. Use the given sentence verbatim as the example.
If the word in the sentence is an inflected form, still define the base form.
```

Parse the response with zod. Strip code fences if present. Reject anything that does not match the schema. Cache key is the word alone, or `word|sha1(context)` when context was given.

## 5. Web UI

Pages (React Router):
- `/login` — list of users as buttons; PIN field appears if the user has one. New-user form below.
- `/add` — text field, "Look up" button. Shows IPA, play-audio button, candidate cards. Clicking a card fills an editable form (definition, example, finnish). "Write my own" opens the same form empty with `source: manual`. Save → toast, field cleared, focus back in input.
- `/words` — tabs *Learning* / *Learned*. Table or list with word, finnish, definition, book. Actions per row: edit (incl. notes), mark learned / restore, delete. Search box filters client-side.
- `/import` — Kindle: drop `vocab.db`, see the preview table (word, sentence, book, already-exists flag, checkbox), toggle "only new since last import" and "mark mastered as learned", confirm, watch progress. Below it the plain CSV/JSON import.
- `/settings` — export buttons, Obsidian export status (last run, "re-export all" button), switch user (logs out), create user.

Token stored in `localStorage`; attach on every fetch; 401 → go to `/login`.

## 6. Android app

Screens:
- **UserPicker** — same list as web; stores token in EncryptedSharedPreferences.
- **Review** — the only real screen.
  - Full-screen word, large centred text, keep-screen-on.
  - Tap: reveal definition, example, finnish, IPA; play button (local mp3 if cached, else Android `TextToSpeech` en-US).
  - Swipe left or right (horizontal drag > 25 % of width, velocity threshold): next word.
  - Swipe down (vertical drag > 25 % of height): mark learned, advance, show a 3-second snackbar "Marked as learned — Undo".
  - Order: shuffle the learning list on screen entry and whenever it is exhausted; then start over.
  - Empty list: "No words to learn. Add words in the web app." plus a sync button.
- Small overflow menu: Sync now, Switch user.

Local storage (Room):
```
words(id, word, definition, example, finnish, ipa, hasAudio, status, learnedAt, updatedAt, deletedAt)
pending_changes(id, wordId, status, changedAt)
sync_state(lastSync)
```

Sync (on app start, on foreground, and manually):
1. Push: for each `pending_changes` row, `PATCH /words/:id { status }`; delete the row on 2xx or 404.
2. Pull: `GET /words/changes?since=lastSync`; upsert rows; remove rows with `deletedAt`; set `lastSync = serverTime`.
3. Audio: for words with `hasAudio` and no local file, download `GET /audio/:id` into app cache dir (best effort, background).

Conflict rule: last `updatedAt` wins. Undo removes the pending row before it is pushed; if it was already pushed, push `status: learning` instead.

The "learned" gesture never calls the API directly; it writes to Room and `pending_changes`, so offline and online behave identically.

## 7. Obsidian export

One-way mirror of the database into a vault folder. The server owns everything under `<OBSIDIAN_DIR>/<user name>/`; the rest of the vault is untouched. The server never reads the vault — manual edits there are overwritten on the next export of that word, so the UI should say so (notes belong in the `notes` field, not in the Markdown).

Layout:
```
<vault>/Vocab/<user>/
  Words/<word>.md
  Books/<book title>.md
  index.md
```

`Words/<word>.md`:
```markdown
---
word: ubiquitous
status: learning          # learning | learned
finnish: "kaikkialla läsnä oleva, yleinen"
ipa: "/juːˈbɪkwɪtəs/"
source: kindle
book: "[[Vocab/<user>/Books/<book title>]]"
added: 2026-10-23
learned:                  # date or empty
tags: [vocab, vocab/learning]
---
# ubiquitous

**Definition:** present or found everywhere

**Example:** Smartphones are ubiquitous these days.

**Finnish:** kaikkialla läsnä oleva, yleinen

**Notes:** <notes field, omitted if empty>
```

`Books/<book title>.md`: frontmatter `title`, `author`, `tags: [vocab/book]`; body is a bullet list of `[[word]]` links sorted alphabetically, with the sentence under each.

`index.md`: counts per status and a Dataview-friendly note explaining the folder. Keep it static apart from the counts.

Filenames: word as-is (already lowercase ASCII-ish); for book titles replace `/ \ : * ? " < > |` with `-` and trim to 100 chars. Writes are atomic (write temp file, rename).

Trigger: a debounced (2 s) job after any word write; also `POST /obsidian/export-all` from settings. Deleted words → delete the note and remove the link from the book note. If `OBSIDIAN_DIR` is unset, the exporter is disabled and the settings page says so.

Syncing the vault to phone and laptop is out of scope (Syncthing recommended; run it on the same VM pointing at the same folder).

## 8. Deployment

`docker-compose.yml`:
```yaml
services:
  server:
    build: ./server
    ports: ["3000:3000"]
    env_file: .env
    volumes:
      - ./data:/app/data
      - ${OBSIDIAN_HOST_DIR}:/app/vault
    restart: unless-stopped
```

`.env.example`:
```
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=claude-haiku-latest
DATA_DIR=/app/data
OBSIDIAN_DIR=/app/vault          # leave empty to disable export
OBSIDIAN_HOST_DIR=/srv/obsidian  # host path mounted into the container
PORT=3000
```

Expose with `tailscale serve --bg 3000` on the VM so clients get HTTPS at `https://<host>.<tailnet>.ts.net`. Back up by copying `data/`.

## 9. Build order

1. **Backend core** — project scaffold, Drizzle schema + migration, users/auth, `/words` CRUD, `/words/changes`. Tests for status transitions and the changes endpoint. Verify with curl.
2. **Lookup** — dictionary client, Anthropic client with zod validation (with and without context), `lookup_cache`, audio download, `/audio/:id`. Tests with mocked HTTP.
3. **Kindle import** — vocab.db parser (test against a fixture db built in the test with the schema above), preview, confirm job with progress, incremental import.
4. **Obsidian export** — exporter module, debounced trigger, export-all. Tests write to a temp dir and assert the Markdown.
5. **Web UI** — login, add, words, import, settings. Served from the backend in production.
6. **CSV/JSON export/import.**
7. **Docker Compose** — build, run, verify on the VM over Tailscale; mount the vault folder.
8. **Android: online only** — user picker, review screen with gestures and reveal, audio/TTS. Reads directly from `GET /words`.
9. **Android: offline** — Room cache, pending queue, sync, undo semantics, audio cache.
10. Polish: dark theme, font size, keep-screen-on, empty state.
