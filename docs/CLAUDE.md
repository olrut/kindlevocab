# Vocab — personal vocabulary trainer

Self-hosted English vocabulary app. Words come mainly from the Kindle Vocabulary Builder (`vocab.db` uploaded in the web UI, real sentences from books as examples) or are typed in the web UI; the Claude API adds a short definition and the Finnish equivalent, the Free Dictionary API adds IPA and audio. A full-screen Android app reviews words with swipe gestures. Every word is also mirrored one-way into an Obsidian vault as Markdown. Single server, Docker Compose, reachable only over Tailscale. **SQLite is the source of truth; the Obsidian vault is a view.**

Full design (schema, API contract, Claude prompt, offline sync) is in `docs/SPEC.md`. Read it before touching the backend or the Android sync code. Keep SPEC.md updated when the contract changes.

## Stack

- **Backend:** Node.js 22, TypeScript, Hono, SQLite via `better-sqlite3` + Drizzle ORM. Serves the API and the built web UI from one process.
- **Web:** React 18 + Vite + TypeScript. Minimal styling (plain CSS modules); no component library.
- **Android:** Kotlin, Jetpack Compose, Room for the offline cache, Retrofit + kotlinx.serialization for the API. Min SDK 26.
- **Runtime:** Docker Compose, one container (`server`), volume `./data` holding `vocab.db` and `audio/`.
- **External APIs:** Anthropic Messages API (key from `ANTHROPIC_API_KEY` env, never shipped to clients), Free Dictionary API (`https://api.dictionaryapi.dev/api/v2/entries/en/{word}`) for IPA and audio.
- **Inputs/outputs on disk:** Kindle `vocab.db` is an uploaded SQLite file read with `better-sqlite3` in read-only mode. Obsidian vault is a mounted folder (`OBSIDIAN_DIR`); the server writes Markdown there and never reads it.

## Repository layout

```
server/         Hono backend (src/, drizzle/, tests/)
web/            Vite React app, built output copied into server/public
android/        Android Studio project
docs/SPEC.md    Design and API contract
docker-compose.yml
.env.example
```

## Commands

```
cd server && npm run dev          # backend with watch, port 3000
cd server && npm test             # vitest
cd server && npm run db:migrate   # drizzle migrations
cd web && npm run dev             # vite dev server proxying /api to :3000
cd web && npm run build
cd android && ./gradlew testDebugUnitTest assembleDebug   # JVM unit tests + debug APK (JDK 17, Android SDK 35)
docker compose up --build         # needs .env (cp .env.example .env) and a writable data/; see SPEC § 8
```

## Conventions

- All API routes under `/api`. JSON in, JSON out. Errors: `{ "error": "message" }` with proper status codes.
- Timestamps are ISO-8601 UTC strings. SQLite stores them as TEXT.
- Words are stored lowercase and trimmed; the `UNIQUE(user_id, word)` constraint is the dedupe rule.
- Never hard-delete words; set `deleted_at`. The sync endpoint depends on this.
- Claude responses are parsed as JSON; validate with zod before use. If parsing fails, return a 502 and do not cache.
- Cache every successful lookup in `lookup_cache` so a word is never sent to Claude twice.
- Audio files are downloaded server-side at save time into `data/audio/<wordId>.mp3`. Clients never call dictionaryapi.dev directly.
- Auth is a bearer token from `/api/auth/login`. Tailscale is the real perimeter; the PIN is only to keep users apart, so keep the flow simple.
- Android: all status changes go through the pending-changes queue, never directly to the API, so offline behaviour is identical to online.
- Kindle import always goes through preview → confirm; never insert straight from the uploaded file. Treat the Kindle schema as untrusted input: check tables and columns exist before querying.
- Obsidian export is one-way and idempotent. Every word write schedules an export of that word; the exporter must be safe to re-run over the whole set at any time. Only touch files under `<OBSIDIAN_DIR>/Vocab/`.
- Writes to the vault are atomic (temp file + rename). Sanitize filenames derived from book titles.
- Keep UI text in English in code; the user-facing Android app is English-only.

## Testing

- Backend: unit tests for the lookup parser, sync endpoint, status transitions, the Kindle parser (build a fixture `vocab.db` inside the test) and the Obsidian exporter (write to a temp dir, assert file contents). Mock the Anthropic and dictionary calls; never hit the network in tests.
- Add a test when fixing a bug in the sync logic.

## Build order

Follow the phases in `docs/SPEC.md` § Build order. Finish and verify a phase (run tests, curl the endpoints) before starting the next one. Do not scaffold the Android project until the backend and web UI work end to end.

## Don'ts

- Don't add Postgres, Redis, a message queue or an auth library. SQLite and a sessions table are enough.
- Don't put the Anthropic key, or any secret, in the web bundle or the Android app.
- Don't change the API contract without updating SPEC.md and the Android client in the same change.
- Don't read from the Obsidian vault or make it a second source of truth. Don't write outside the app's own subfolder in the vault.
- Don't scrape Amazon or try to reach the Kindle over the network; the uploaded `vocab.db` is the only Kindle input.
