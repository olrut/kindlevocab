# Vocab — design spec

## 1. Purpose

A personal English vocabulary trainer for Android, for a Finnish speaker. Everything happens on the phone; there is no server and no account. The only services it calls are the free dictionary and, if the user adds their own key, the Claude API.

- **Kindle import:** the words looked up on a Kindle (its `system/vocabulary/vocab.db`) come in with the sentence from the book as the example; the Free Dictionary API adds a definition, IPA and a recording. With the user's own Claude API key (optional), Claude gives the definition that fits the sentence and the Finnish equivalent.
- **Add your own:** look a word up, pick a meaning (or write your own), add the Finnish equivalent and notes.
- **Review:** one word at a time, full screen. Tap shows the meaning; swipe down marks the word learned (with a short undo); swipe sideways shows the next one. Random order, reshuffled when the list runs out.
- **Your data:** the phone holds the only copy (included in Android's backup); CSV/JSON export and import move it elsewhere.

Version 1 (tag `server-final`) was a self-hosted server with a web UI and Claude lookups; its CSV/JSON exports import into this app.

## 2. Data (Room)

Local storage (Room, `vocab.db`; files left by the server version, `words-*.db` and `cacheDir/audio/`, are deleted on start). Recordings in `cacheDir/recordings/<id>.mp3` (max 5 MB, written aside and renamed); an unsaved word's recording is played from a temporary file.
```
words(id autoincrement, word unique lowercase, definition?, example?, finnish?, ipa?, audioUrl?,
      source manual|dictionary|import|kindle, status learning|learned, learnedAt?, bookTitle?, bookAuthor?,
      kindleLookupAt?, notes?, createdAt, updatedAt)
kindle_imports(id, importedAt, lastKindleTimestamp)
```
Validation: word trimmed and lowercased, 1–100 characters; definition, example, Finnish at most 2000; notes at most 10 000; blank fields are stored as null. `learned` sets `learnedAt`, `learning` clears it. Timestamps are UTC with milliseconds. Deletes are real deletes (there is nothing to sync).

Android's backup is on (`allowBackup`): the phone holds the only copy, so it goes with the Google account backup and to a new phone. Caches are not backed up; recordings are downloaded again.

## 3. Lookups

A lookup takes a word and, for Kindle words, the sentence it was seen in.

- **No Claude key:** the dictionary alone (below). It has no Finnish.
- **With a Claude key:** Claude and the dictionary are asked at the same time. The senses (definition, example, Finnish) come from Claude; IPA and the recording from the dictionary, when it has them. Claude says "not English" → not found. Claude fails with a key problem (401/403: "Claude rejected the API key"; 402: billing) → the lookup fails with that message, so it gets fixed. Any other Claude failure (429, 5xx/529 overloaded, no network, a refusal, an answer cut off or not in the expected shape) → the dictionary's answer is used; if that failed too, both reasons are shown.

**Claude** (official Java SDK `com.anthropic:anthropic-java`, beta Messages endpoint; key and model from the Claude settings, § 6). Model `claude-opus-5-5` (default), `claude-sonnet-5-5` or `claude-haiku-5-5`; `max_tokens` 4096; `output_config.effort: low`; adaptive thinking (the default). Opus and Sonnet are sent with the server-side refusal fallback (`fallbacks: "default"`, beta `server-side-fallback-2026-07-01`); Haiku has none. The SDK retries 429/5xx twice; timeout 60 s. System prompt (version 1's); the user message is the lowercased word:

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

With a sentence, appended after a blank line:

```

The user saw the word in this sentence: "<sentence>"
Return exactly one candidate: the sense that fits this sentence. Use the given sentence verbatim as the example.
If the word in the sentence is an inflected form, still define the base form.
```

The reply's text (code fences stripped) must be `{"candidates": [...]}` with 1–3 candidates whose three fields are non-empty (exactly one with a sentence; its example is replaced by the sentence itself), or `{"error":"not_english",...}`. `stop_reason` `refusal` or `max_tokens` counts as a failure. Tests never call the real API (MockWebServer as the base URL).

**The dictionary:** the Free Dictionary API, `https://api.dictionaryapi.dev/api/v2/entries/en/<word>` (free, no key). IPA is the first `phonetics[].text`, else the entry's `phonetic`; the recording is the first `phonetics[].audio` ending in `-us.mp3`, else the first one (`//…` gets `https:`). Senses: the first definition of each part of speech across all entries, then the further ones, up to 3, each with its example. 404 or no definitions: not found. Timeouts 5 s connect, 10 s read. It is rate limited: a 429 is retried up to 3 times, after the `Retry-After` seconds or else 2, 4, 8 s (at most 30 s each); then "The dictionary is busy (too many requests); try again in a minute".

The app needs the network only for lookups and the first play of a recording; review, editing, the list and export work offline. A lookup without a network says so ("No connection to the dictionary"). Pronunciations filled in after a CSV/JSON import use the dictionary alone, never Claude.

## 4. Kindle import

The Kindle file (untrusted): opened read-only; tables and columns are checked with `PRAGMA table_info` before use (column sets vary by firmware); values of the wrong type are ignored. Tables used: `WORDS(id, word, stem, lang, category, timestamp)` (required: `id` and `word` or `stem`), `LOOKUPS(word_key, book_key, usage, timestamp)`, `BOOK_INFO(id, title, authors)`. Only English words (`lang` `en`, `en-*`, `en_*`, any case; all words when there is no `lang` column). The word is the `stem` (else `word`), lowercased, at most 100 characters. Rows with the same stem merge: the newest `WORDS` row gives the timestamp and mastered (`category = 1`), the newest lookup across them gives the sentence (max 2000) and book (max 500). Not a database, no `WORDS`, or no id/word columns: an error saying it is not a Kindle vocab.db.

Import, per picked word: already on the list → skipped, not looked up (a mastered word still `learning` becomes `learned` if the switch is on). Otherwise a lookup (§ 3) with the book's sentence, 2 words at a time: found → the first sense's definition and Finnish (Claude), the book's sentence as the example (else the sense's), IPA and recording; not found → imported without a definition; failed (network, busy, key) → listed as failed and not imported. Saved with `source = kindle`, the book, `kindleLookupAt`, and `learned` when mastered and the switch is on. Afterwards a `kindle_imports` row records the newest Kindle timestamp in the file, held back to just before the oldest failed word so that it is offered as new next time; "only new" means newer than the largest recorded one.

## 5. CSV/JSON export and import

The same format as version 1, so its exports import here. Columns `word, definition, example, finnish, ipa, status, learned_at, created_at, book_title, book_author, notes`. CSV is RFC 4180 with CRLF and a header; fields with `,` `"` a line break or leading/trailing space are quoted. JSON is an array of objects with those keys, `null` for no value. Import: the CSV columns may be in any order, only `word` is required; blank means no value; `status` is `learning` or `learned`; timestamps are ISO-8601 with an offset (stored as UTC); same length limits as the editor. The first bad row or item is named ("Row 3: …", counting the header as row 1; "Item 2: …") and nothing is imported. Words already on the list, or repeated in the file, are skipped. Imported words get `source = import`.

## 6. Screens

A back stack; the system back button pops it.

- **Review** — the start screen.
  - Full-screen word, large centred text, keep-screen-on.
  - Tap: reveal definition (or "No definition yet"), example, finnish, IPA; play button (the cached recording, downloaded from the word's `audioUrl` on first use, else Android `TextToSpeech` en-US).
  - Swipe left or right (horizontal drag > 25 % of width, velocity threshold): next word.
  - Swipe down (vertical drag > 25 % of height): mark learned, advance, show a 3-second snackbar "Marked as learned — Undo".
  - Order: shuffle the learning list on screen entry and whenever it is exhausted; then start over. Reading the words again (on return to the screen, after a change) does not restart the pass: words keep their place, words gone from the list drop out, new words go somewhere after the current word.
  - Empty list: "No words to learn. Add a word, or import your Kindle's vocabulary." with Add a word and Import from Kindle.
  - Overflow menu: Add a word, Words, Import / export, Claude lookups…, Display….
- **Words** — every word, newest first; filter chips Learning / Learned / All; a search over word, definition and Finnish (ignoring case); tap a word to edit it; a + button adds one.
- **Add / Edit** — the word field with Look up (also the keyboard's search key):
  - Found: up to 3 senses to pick from, plus "Write my own". Picking fills definition, the example and the Finnish (what the user already wrote stays when the sense has none), and keeps the IPA and recording URL (play button). A new word picked from a sense has `source = claude` (the sense has Finnish) or `dictionary`.
  - Not found / failed: a note; the user writes their own.
  - Fields: definition, example, Finnish, notes (all optional; a word can be saved with only its text). Learned switch. Changing the word text drops the IPA and recording, which belonged to the old text.
  - Save: a word already on the list (any case) gives "“word” is already on your list". Edit has Delete (with a confirmation; removes the word and its recording for good).
- **Import / export** — three parts, each through the system file picker (Storage Access Framework; no storage permission):
  - **From your Kindle:** pick `vocab.db` (copied from the Kindle's `system/vocabulary/` over USB, or opened from the Kindle with USB-OTG). It is copied into the cache dir (max 200 MB) and read as in § 4. Preview: every English word, newest first, with the sentence and book; "on your list" and "mastered" tags. New words are picked, existing ones not; All / None. Switches: "Only words new since the last import" (on once an import has been done) and "Words marked mastered on the Kindle are learned" (on). Import N runs the lookups (4 at a time) with a progress bar and keep-screen-on, then a summary with the words that failed. Back from the preview or summary returns to the start of the screen; an import keeps running if the screen is left.
  - **From a file:** a `.csv` or `.json` export (chosen by the file name; max 10 MB): all or nothing; "Imported N words, skipped M already on your list.", or the first problem. Afterwards, in the background (2 at a time), words without IPA or recording get the dictionary's.
  - **Export:** CSV or JSON of every word, oldest first, saved where the user picks (`vocab-<date>.csv|json`).

**Claude lookups…**: explains what a key does, that lookups are billed to the user's Anthropic account, and how the key is kept. Without a key: a password field ("API key (sk-ant-…)"; it must start with `sk-ant-` and have no spaces) and the model (Opus 5.5 default, Sonnet 5.5, Haiku 5.5), Save / Cancel. With a key: `sk-ant-…` and its last 4 characters, the model (changes apply at once), Remove key / Done. The key is stored in EncryptedSharedPreferences (`claude.xml`, keys in the Android Keystore), excluded from backup and device transfer (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`); if the file cannot be decrypted it is deleted and the key must be entered again. The key is sent only to the Anthropic API and never logged.

**Display…** (per device, plain SharedPreferences): theme — follow the system (default), light or dark; Material You wallpaper colours on Android 12+, a blue scheme before that — and word size — small, normal (default), large or extra large (0.85×, 1×, 1.2×, 1.45× of the card's text, on top of the system font size). Changes apply at once. The word stays on one line when it can: a word too wide for the screen is shrunk to fit (not below 24 sp).

## 7. Build and release

- `cd android && ./gradlew testDebugUnitTest assembleDebug` (JDK 17, Android SDK 35). Min SDK 26.
- CI (`.github/workflows/android.yml`) runs the tests and uploads the debug APK as the `app-debug` artifact on every push and pull request.
- The APK is signed with the debug key; a new one installs over the old one and keeps the words.

## 8. Build order

Phases 1–10 built version 1 (server, web UI, Kindle import, Obsidian export, Docker, Android client with offline sync; see tag `server-final`).

11. **Phone only, core** — local Room store, dictionary lookups on the phone, add/edit/delete, word list; no server.
12. **Phone only, import/export** — Kindle `vocab.db` and CSV/JSON on the phone.
13. **Remove the server side** — server, web UI, Docker, Proxmox script; docs.
