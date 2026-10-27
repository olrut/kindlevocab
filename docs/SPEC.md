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

Base path `/api`. JSON bodies and responses. Auth: `Authorization: Bearer <token>` on everything except `GET /health`, `GET /users`, `POST /users`, `POST /auth/login`. Unknown token → 401. Errors → `{ "error": "..." }`.

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

Errors: `{ "error": "not_english", "suggestion": "..." }` with 422 when Claude reports the word is not English or is misspelled. `not_english` replies are not cached. Any failure to get a usable answer from Claude (API or connection error, missing credentials, refusal, reply cut off at `max_tokens`, reply failing the schema) → 502, nothing cached.

### Words

| Method | Path | Body / query | Response |
|---|---|---|---|
| POST | `/words` | `{ word, definition, example?, finnish?, ipa?, source }` | `201 { ...word }` (409 if exists and not deleted; if soft-deleted, revive and update) |
| GET | `/words` | `?status=learning\|learned\|all` (default `learning`) | `[ ...words ]`, excludes deleted |
| GET | `/words/:id` | – | word |
| PATCH | `/words/:id` | any of `{ status, definition, example, finnish, notes }` | updated word (`notes`: up to 10 000 chars, trimmed, blank → null) |
| DELETE | `/words/:id` | – | 204, sets `deleted_at` |
| GET | `/words/changes` | `?since=<ISO>` | `{ serverTime, words: [ ... ] }` — all rows with `updated_at > since`, **including deleted ones** (`deletedAt` set) |
| GET | `/audio/:id` | – | `audio/mpeg`, 404 if `audio_path` is NULL |
| GET | `/lookup/audio/:word` | – | `audio/mpeg` of a looked-up word that may not be saved yet (the /add page). The server fetches the cached dictionary audio URL; 404 if there is none or it cannot be fetched. Clients then use speech synthesis. |
| GET | `/obsidian/status` | – | `{ enabled, lastRun: { at, ok, error } \| null }` — `enabled` is false when `OBSIDIAN_DIR` is unset; `lastRun` is this user's last export since the server started |
| POST | `/obsidian/export-all` | – | `{ words, books }` — rebuilds the current user's folder (§ 7); 503 when disabled, 500 if writing fails |

Word JSON shape (camelCase):
```json
{ "id": 42, "word": "ubiquitous", "definition": "...", "example": "...", "finnish": "...",
  "ipa": "...", "hasAudio": true, "source": "claude", "status": "learning",
  "learnedAt": null, "bookTitle": null, "bookAuthor": null, "notes": null,
  "createdAt": "...", "updatedAt": "...", "deletedAt": null }
```

`serverTime` is reported 1 ms before the server's current time, so a write later in the same millisecond is still picked up by the next pull. A row may therefore be sent twice; clients upsert, so this is harmless. `since` must be an ISO-8601 timestamp (400 otherwise).

On `POST /words`, after inserting, the server looks up the cached audio URL for that word and downloads it to `data/audio/<id>.mp3` in the background (fire-and-forget with logging). `audio_path` is set when the download finishes. The cached URL may come from a lookup with or without context (`word` or `word|…` key; newest wins). Setting `audio_path` is a write to `words`: it bumps `updated_at`, so sync clients see `hasAudio` change.

### Export / import

- `GET /export?format=json|csv` (default `json`; anything else → 400) — all non-deleted words of the current user, ordered by id, as a download (`Content-Disposition: attachment; filename="vocab-<user>-<YYYY-MM-DD>.<ext>"`). CSV columns: `word,definition,example,finnish,ipa,status,learned_at,created_at,book_title,book_author,notes`. JSON is an array of objects with exactly those keys (snake_case, `null` for no value). CSV is RFC 4180: header row, CRLF line ends, empty cell for no value, quotes around fields containing `,` `"` a line break or leading/trailing whitespace; it starts with a UTF-8 BOM so spreadsheet apps read ä, ö and IPA correctly.
- `POST /import` — multipart `file` (`.csv` or `.json` by file name, case-insensitive; max 10 MB → 413), same shape as the export. CSV: the header names the columns, in any order; unknown columns are ignored; `word` and `definition` are required columns; every row must have as many fields as the header; a BOM and blank lines are ignored. JSON: an array of objects; missing keys mean no value. Per word: text is trimmed and blank means no value; `word` (lowercased, ≤ 100 chars) and `definition` are required; `status` is `learning` (also when blank) or `learned`; `learned_at` is kept only for `learned` (default now); `created_at` is kept (default now); timestamps must be ISO-8601; `notes` ≤ 10 000 chars. Any invalid word → 400 `{ error }` naming it (`row N` counts the CSV header as row 1; `item N` is 1-based in JSON) and nothing is imported. Otherwise all words go in one transaction: existing live words (same normalised `word`, also repeats within the file) are skipped, soft-deleted ones are revived as with `POST /words`, the rest are inserted with `source = 'import'`. Response `{ imported, skipped }`. Audio is fetched lazily for imported words: after import each new word is queued for a Free Dictionary API lookup (no Claude call, at most 2 at a time, not cached in `lookup_cache`) that fills `ipa` if it is missing and downloads the audio to `data/audio/<id>.mp3` if there is none (a cached `/lookup` audio URL is used first). A word deleted while queued is skipped. Filling either is a write to `words` (bumps `updated_at`).

### Kindle import

`POST /import/kindle` — multipart upload of the Kindle's `vocab.db` (SQLite). Max 50 MB.

Kindle schema (verify against the uploaded file; column names differ slightly between firmware versions):
- `WORDS(id, word, stem, lang, category, timestamp, profileid)` — `category` 0 = learning, 1 = mastered; `timestamp` ms since epoch.
- `LOOKUPS(id, word_key, book_key, dict_key, pos, usage, timestamp)` — `usage` is the sentence from the book; `word_key` references `WORDS.id`, `book_key` references `BOOK_INFO.id`.
- `BOOK_INFO(id, asin, guid, lang, title, authors)`.

Server behaviour:
1. Open the upload read-only with `better-sqlite3`; reject if `WORDS` table is missing. The multipart field is `file`; an optional field `onlyNew` is `"true"` (default) or `"false"`. A file that is not SQLite, or whose `WORDS` table is missing or has no `id`/`word` columns, gets 400; over 50 MB gets 413. Columns are detected per table: `LOOKUPS`, `BOOK_INFO` and the optional columns (`stem`, `lang`, `category`, `usage`, `authors`, …) may be missing, and then the corresponding values are `null`. Every value is type-checked; rows with an unusable word are skipped, `usage` is capped at 2000 chars.
2. Select words with `lang = 'en'` (also `en-GB` etc.) and, if `onlyNew=true` (default), `timestamp > kindle_imports.last_kindle_timestamp` for this user (the max over all of the user's imports). Use `stem` as the word (fallback `word`), normalised like any word. Join the newest `LOOKUPS` row for the usage sentence and the book. Several `WORDS` rows with the same stem ("ran", "running") become one candidate: the newest row gives the category, the newest lookup across them gives the sentence and book. Candidates are sorted newest first.
3. Return a **preview**, not a direct import: `{ importId, candidates: [{ word, usage, bookTitle, bookAuthor, kindleCategory, exists }] }`. `kindleCategory` is `"learning"` or `"mastered"`; `exists` is true for a live (not soft-deleted) word. The temp db file is kept under `data/tmp/<importId>.db` for 1 hour; an unconfirmed import then expires (404). Import state lives in memory, so `data/tmp/` is emptied at server start.
4. `POST /import/kindle/:importId/confirm` `{ words: ["ubiquitous", ...] , markMasteredAsLearned: true }` (`markMasteredAsLearned` defaults to true; 1–10 000 words) — for each selected word: `POST /lookup`-equivalent with `context = usage`, then insert with `source = 'kindle'`, `example = usage`, `book_title`, `book_author`, `kindle_lookup_at`, `status` from category if `markMasteredAsLearned`. A word without a sentence is looked up without context and keeps Claude's example. A soft-deleted word is revived, as with `POST /words`. Responds 202 with the progress; 404 for an unknown, expired or other user's import; 409 if already confirmed. Runs as a background job; progress via `GET /import/kindle/:importId` → `{ state, done, total, imported, skipped, errors: [{ word, error }] }`, where `state` is `preview` | `running` | `finished` and `error` is `not_english`, `lookup failed`, `not in upload` or `internal error`. Words Claude rejects as `not_english` are listed in `errors` and skipped. Record a `kindle_imports` row at the end (`words_new` = imported, `words_skipped` = existing words, `last_kindle_timestamp` = max `WORDS.timestamp` of the upload: everything in it was seen in the preview, selected or not. If any word failed with `lookup failed` or `internal error`, it is instead one less than the oldest failed word's timestamp, so the next "only new" preview offers those words again; `not_english` is final and does not hold it back). The temp file is deleted when the job finishes; the progress stays readable for 1 hour after that.
5. Existing words are skipped (never overwritten), except that a word with `status = 'learning'` whose Kindle category is mastered is set to `learned` when `markMasteredAsLearned` is true. A word added by hand while the job runs counts as existing.

Rate limit Claude calls in the job to 5 concurrent requests.

## 4. Claude lookup prompt

Model: the current smallest Claude model (Haiku family): `claude-haiku-5-5`, overridable with `ANTHROPIC_MODEL`. `max_tokens: 1024`, `output_config.effort: "low"`, no sampling parameters.

- No `temperature`: API version `2026-10-21` (the SDK default) rejects `temperature`/`top_p`/`top_k` on every model, and Haiku 5.5 rejects non-default values on older versions too. The JSON format is held by the prompt and the zod schema instead.
- Haiku 5.5 thinks by default and thinking counts toward `max_tokens`, so the cap leaves room for it; low effort keeps it short. Read the answer from the `text` blocks (a reply may start with an empty `thinking` block).
- `stop_reason: "refusal"` or `"max_tokens"` → treat as a failed lookup (502).

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

Parse the response with zod. Strip code fences if present. Reject anything that does not match the schema. With context, a reply with more than one candidate is rejected, and the candidate's `example` is set to the given sentence (the server does not rely on the model copying it verbatim). Cache key is the word alone, or `word|sha1(context)` when context was given.

## 5. Web UI

Pages (React Router):
- `/login` — list of users as buttons; PIN field appears if the user has one. New-user form below.
- `/add` — text field, "Look up" button. Shows IPA, play-audio button, candidate cards. Clicking a card fills an editable form (definition, example, finnish). "Write my own" opens the same form empty with `source: manual`. Save → toast, field cleared, focus back in input.
- `/words` — tabs *Learning* / *Learned*. Table or list with word, finnish, definition, book. Actions per row: edit (incl. notes), mark learned / restore, delete. Search box filters client-side.
- `/import` — Kindle: drop `vocab.db`, see the preview table (word, sentence, book, already-exists flag, checkbox), toggle "only new since last import" and "mark mastered as learned", confirm, watch progress. Below it the plain CSV/JSON import.
- `/settings` — export buttons, Obsidian export status (last run, "re-export all" button), switch user (logs out), create user.

Token stored in `localStorage`; attach on every fetch; 401 → go to `/login`.

Details:
- Audio needs the token, so it is fetched as a blob and played from an object URL. Without a recording (or if it fails) the browser's speech synthesis (`en-US`) speaks the word.
- `/login`: creating a user there logs in as them; on `/settings` it does not.
- `/words`: delete asks for a second click; editing shows that Obsidian notes are overwritten and that notes belong in the Notes field.
- `/import`: words that already exist start unselected; changing "only new" re-reads the file; progress is polled every second.
- Production: `cd web && npm run build` writes to `server/public/`, which the server serves (override with `PUBLIC_DIR`). Hashed `/assets/*` are cached for a year, everything else is `no-cache`. A path without a file extension that is not under `/api` gets `index.html`; a missing file is a 404. In development `npm run dev` in `web/` proxies `/api` to port 3000.
- `/settings`: "Export CSV" / "Export JSON" fetch `/export` with the token and save it under the server's file name.
- `/import`: the CSV/JSON import uploads as soon as a file is chosen and shows `{ imported, skipped }` or the server's error.

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

One-way mirror of the database into a vault folder. The server owns everything under `<OBSIDIAN_DIR>/Vocab/<user name>/`; the rest of the vault, including other files directly in `Vocab/`, is untouched. The server never reads the vault — manual edits there are overwritten on the next export of that word, so the UI should say so (notes belong in the `notes` field, not in the Markdown).

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

The `#` comments in the template are documentation, not output. Empty fields are written as `key:` with no value; the `Example`, `Finnish` and `Notes` lines are omitted when empty. Free-text values are YAML double-quoted; `word` is quoted only when it would not read as a plain string (`yes`, `a: b`, …). Line breaks in text become spaces. `added`/`learned` are the UTC dates of `created_at`/`learned_at`.

`Books/<book title>.md`: frontmatter `title`, `author`, `tags: [vocab/book]`; body is a bullet list of `[[word]]` links sorted alphabetically, with the sentence under each as a nested bullet. Links are written as `[[Vocab/<user>/Words/<word>|<word>]]` because two users can have the same word. A book with no live words left has no note. Words with the same sanitised title share one note; the first non-empty author wins.

`index.md`: counts per status and a Dataview-friendly note explaining the folder. Keep it static apart from the counts.

Filenames: word as-is (already lowercase ASCII-ish); for book titles replace `/ \ : * ? " < > |` with `-` and trim to 100 chars. The same rule applies to words and user names (a word like `../x` must not leave the folder), and also replaces `[ ] # ^`, which break wiki links; control characters become spaces, leading dots and trailing dots/spaces are removed, and an empty result becomes `_`. If two words of a user sanitise to the same name, the older word owns the file. Writes are atomic (write temp file, rename).

Trigger: a debounced (2 s) job after any word write; also `POST /obsidian/export-all` from settings. Deleted words → delete the note and remove the link from the book note. If `OBSIDIAN_DIR` is unset, the exporter is disabled and the settings page says so.

Details: the 2 s wait restarts on every write, but a pending export runs at most 10 s after the first unexported write, so a long Kindle import is mirrored while it runs. Each run rewrites the written words' notes and all of that user's book notes and `index.md`. `export-all` (and a full export of every user at server start, which catches changes a restart lost) also rewrites every word note and removes `.md` files in `Words/` and `Books/` that no longer belong to a word or book; this directory listing is the only time the server looks at the vault, and other files are left alone. Exports run one at a time; a failed export is logged and never fails the API request that triggered it.

Syncing the vault to phone and laptop is out of scope (Syncthing recommended; run it on the same VM pointing at the same folder).

## 8. Deployment

`docker-compose.yml` (build context is the repo root, because the image also builds `web/`):
```yaml
services:
  server:
    build:
      context: .
      dockerfile: server/Dockerfile
    ports: ["${PORT:-3000}:3000"]
    env_file: .env
    environment:
      DATA_DIR: /app/data
      PORT: "3000"
    user: "${APP_UID:-1000}:${APP_GID:-1000}"
    volumes:
      - ./data:/app/data
      - ${OBSIDIAN_HOST_DIR:-./vault}:/app/vault
    restart: unless-stopped
```

`.env.example` (comments on their own lines; compose reads the file both for the container and for `${…}` in the compose file):
```
ANTHROPIC_API_KEY=
ANTHROPIC_MODEL=claude-haiku-5-5
# leave empty to disable the Obsidian export
OBSIDIAN_DIR=/app/vault
# host folder mounted at /app/vault
OBSIDIAN_HOST_DIR=/srv/obsidian
# host port
PORT=3000
# host user owning data/ and the vault files (id -u / id -g)
APP_UID=1000
APP_GID=1000
```

Image (`server/Dockerfile`, Node 22 bookworm-slim, three stages): build the web UI into `public/`; `npm ci --omit=dev` for the server (with python3/make/g++ only in that stage, for better-sqlite3); the runtime stage runs `node --import tsx src/index.ts` as the given user. `tsx` is therefore a runtime dependency. Migrations run at startup (`openDb`). `GET /api/health` → `{ ok: true }` (no auth) backs the image's `HEALTHCHECK`. On SIGTERM/SIGINT the server stops accepting requests, writes the pending Obsidian export and exits, so `docker stop` is immediate.

First run on the VM:
```
git clone … && cd kindlevocab
cp .env.example .env            # set ANTHROPIC_API_KEY, OBSIDIAN_HOST_DIR, APP_UID/APP_GID
mkdir -p data                   # must be writable by APP_UID; Docker would create it as root
docker compose up -d --build
tailscale serve --bg 3000
```
Clients then get HTTPS at `https://<host>.<tailnet>.ts.net`. Update with `git pull && docker compose up -d --build`. Back up by copying `data/` (stop the container first, or use `sqlite3 data/vocab.db ".backup …"`, since the database is in WAL mode).

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
