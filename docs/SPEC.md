# Vocab — design spec

## 1. Purpose

A personal English vocabulary trainer for Android, for a Finnish speaker. Everything happens on the phone; there is no server and no account.

- **Kindle import:** the words looked up on a Kindle (its `system/vocabulary/vocab.db`) come in with the sentence from the book as the example; the Free Dictionary API adds a definition, IPA and a recording.
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

## 3. Dictionary

The Free Dictionary API, `https://api.dictionaryapi.dev/api/v2/entries/en/<word>` (free, no key). IPA is the first `phonetics[].text`, else the entry's `phonetic`; the recording is the first `phonetics[].audio` ending in `-us.mp3`, else the first one (`//…` gets `https:`). Senses: the first definition of each part of speech across all entries, then the further ones, up to 3, each with its example. 404 or no definitions: not found. Timeouts 5 s connect, 10 s read.

The app needs the network only for lookups and the first play of a recording; review, editing, the list and export work offline. A lookup without a network says so ("No connection to the dictionary").

## 4. Kindle import

The Kindle file (untrusted): opened read-only; tables and columns are checked with `PRAGMA table_info` before use (column sets vary by firmware); values of the wrong type are ignored. Tables used: `WORDS(id, word, stem, lang, category, timestamp)` (required: `id` and `word` or `stem`), `LOOKUPS(word_key, book_key, usage, timestamp)`, `BOOK_INFO(id, title, authors)`. Only English words (`lang` `en`, `en-*`, `en_*`, any case; all words when there is no `lang` column). The word is the `stem` (else `word`), lowercased, at most 100 characters. Rows with the same stem merge: the newest `WORDS` row gives the timestamp and mastered (`category = 1`), the newest lookup across them gives the sentence (max 2000) and book (max 500). Not a database, no `WORDS`, or no id/word columns: an error saying it is not a Kindle vocab.db.

Import, per picked word: already on the list → skipped, not looked up (a mastered word still `learning` becomes `learned` if the switch is on). Otherwise a dictionary lookup: found → the first sense's definition, the book's sentence as the example (else the sense's), IPA and recording; not found → imported without a definition; failed (network) → listed as failed and not imported. Saved with `source = kindle`, the book, `kindleLookupAt`, and `learned` when mastered and the switch is on. Afterwards a `kindle_imports` row records the newest Kindle timestamp in the file, held back to just before the oldest failed word so that it is offered as new next time; "only new" means newer than the largest recorded one.

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
  - Overflow menu: Add a word, Words, Import / export, Display….
- **Words** — every word, newest first; filter chips Learning / Learned / All; a search over word, definition and Finnish (ignoring case); tap a word to edit it; a + button adds one.
- **Add / Edit** — the word field with Look up (also the keyboard's search key):
  - Found: up to 3 senses to pick from, plus "Write my own". Picking fills definition and the example (an example the user already wrote stays when the sense has none), and keeps the IPA and recording URL (play button). A new word picked from the dictionary has `source = dictionary`.
  - Not found / failed: a note; the user writes their own.
  - Fields: definition, example, Finnish, notes (all optional; a word can be saved with only its text). Learned switch. Changing the word text drops the IPA and recording, which belonged to the old text.
  - Save: a word already on the list (any case) gives "“word” is already on your list". Edit has Delete (with a confirmation; removes the word and its recording for good).
- **Import / export** — three parts, each through the system file picker (Storage Access Framework; no storage permission):
  - **From your Kindle:** pick `vocab.db` (copied from the Kindle's `system/vocabulary/` over USB, or opened from the Kindle with USB-OTG). It is copied into the cache dir (max 200 MB) and read as in § 4. Preview: every English word, newest first, with the sentence and book; "on your list" and "mastered" tags. New words are picked, existing ones not; All / None. Switches: "Only words new since the last import" (on once an import has been done) and "Words marked mastered on the Kindle are learned" (on). Import N runs the lookups (4 at a time) with a progress bar and keep-screen-on, then a summary with the words that failed. Back from the preview or summary returns to the start of the screen; an import keeps running if the screen is left.
  - **From a file:** a `.csv` or `.json` export (chosen by the file name; max 10 MB): all or nothing; "Imported N words, skipped M already on your list.", or the first problem. Afterwards, in the background (2 at a time), words without IPA or recording get the dictionary's.
  - **Export:** CSV or JSON of every word, oldest first, saved where the user picks (`vocab-<date>.csv|json`).

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
