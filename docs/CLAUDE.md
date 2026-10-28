# Vocab — personal vocabulary trainer

An Android app for learning English words. Words come mainly from the Kindle Vocabulary Builder (`vocab.db`, picked with the system file picker; the real sentences from books become the examples) or are typed in; the Free Dictionary API adds definitions, IPA and recordings. A full-screen review shows one word at a time with swipe gestures. **Everything is on the phone: no server, no account, no API keys.** Version 1 (tag `server-final`) had a server, a web UI and Claude lookups.

Full design (Room schema, dictionary rules, Kindle import, CSV/JSON format, screens) is in `docs/SPEC.md`. Read it before changing behaviour, and keep it updated in the same change.

## Stack

- Kotlin, Jetpack Compose (Material 3), Room (the only copy of the words), coroutines.
- OkHttp + kotlinx.serialization for the Free Dictionary API (`https://api.dictionaryapi.dev/api/v2/entries/en/{word}`).
- Android's own SQLite (read-only) for the Kindle file. Storage Access Framework for every file; no storage permission.
- Min SDK 26, target/compile 35, JDK 17.

## Repository layout

```
android/        Android Studio project (app/src/main/java/io/github/olrut/vocab/)
  data/         Room entities, DAO, WordRepository
  dictionary/   Free Dictionary API client
  edit/         Add/edit logic (WordEditor)
  kindle/       vocab.db reader and importer
  transfer/     CSV/JSON format, import/export logic
  review/       Deck order, swipe decisions, ReviewController
  audio/        Recording cache, playback with TextToSpeech fallback
  ui/           Compose screens, theme, display settings
docs/SPEC.md    Design
```

## Commands

```
cd android && ./gradlew testDebugUnitTest assembleDebug   # JVM unit tests + debug APK
```

## Conventions

- All word changes go through `WordRepository` (validation, lowercase unique words, timestamps); screens never write to Room directly.
- Timestamps are ISO-8601 UTC strings with milliseconds (`WordRepository.ISO`).
- Words are stored lowercase and trimmed; the unique index on `word` is the dedupe rule.
- Logic lives in plain classes (controllers, repository, readers) with JVM tests; composables only render state and forward events.
- Lookups must fail gracefully ("not found" / "failed") without a network; review, editing, the list and export never need one.
- Kindle import always goes preview → pick → import. Treat the Kindle file as untrusted input: open it read-only, check tables and columns exist before querying, type-check every value.
- CSV/JSON import is all or nothing and keeps the version 1 format, so old exports keep importing.
- Keep UI text in English in code; the app is English-only.

## Testing

- JVM tests with Robolectric for Room and SQLite (`@Config(sdk = [34])`), MockWebServer for HTTP. Never hit the network in tests.
- Test-first; run a mutation check on new logic before a PR.
- Smoke test on the emulator before a PR: no server or key is needed.

## Don'ts

- Don't add a backend, an account system or analytics; the phone is the whole app.
- Don't put any secret or API key in the app.
- Don't ask for storage permissions; use the system file picker.
- Don't scrape Amazon or try to reach the Kindle over the network; the `vocab.db` file is the only Kindle input.
