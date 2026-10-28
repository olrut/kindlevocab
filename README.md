# kindlevocab

Turn your Kindle lookups into flashcards: an Android app that imports the words you looked up on your Kindle, with the sentences from your books, and reviews them one at a time with swipes. Everything stays on the phone: no server, no account. A Claude API key is optional.

- **Kindle import:** pick the Kindle's `vocab.db`; each word comes in with its book sentence, a definition, IPA and a recording from the free [Free Dictionary API](https://dictionaryapi.dev/).
- **Add your own:** look a word up, pick a meaning or write your own, add the Finnish equivalent and notes.
- **Optional Claude lookups:** add your own Anthropic API key (menu → **Claude lookups…**) and Claude gives the Finnish equivalent and, for Kindle words, the meaning that fits the book's sentence. Lookups are billed to your Anthropic account; the key stays encrypted on the phone.
- **Review:** tap to reveal, swipe sideways for the next word, swipe down when you have learned it.
- **Your data:** CSV/JSON export and import; included in Android's backup.

## Install

The app needs Android 8.0 or newer.

1. **Get the APK**, either way:
   - From GitHub: repository → **Actions** → the latest successful **android** run on `main` → **Artifacts** → `app-debug`. It downloads as a zip; unzip it to get `app-debug.apk`.
   - Or build it: `cd android && ./gradlew assembleDebug` (JDK 17 and the Android SDK). The APK is in `app/build/outputs/apk/debug/`.
2. Copy the APK to the phone and open it. Android asks you to allow installs from that app (Files, Chrome, …); allow it. Play Protect may warn about an unknown app (it is signed with a debug key); choose to install anyway.

To update, install the new APK over the old one. Your words are kept.

## Import from your Kindle

1. Connect the Kindle to a computer with USB. Copy `system/vocabulary/vocab.db` to the phone (the `system` folder may be hidden: in Windows Explorer, View → Show → Hidden items; in the macOS Finder, Cmd+Shift+.). With a USB-OTG cable you can also open it straight from the Kindle on the phone.
2. In the app: menu → **Import / export** → **Choose vocab.db**.
3. Pick the words and tap **Import**. The next time, only words new since the last import are offered.

Each word needs a dictionary lookup, so the import needs a network connection. Words the dictionary does not know come in without a definition; add one under **Words**.

## Moving from version 1

Version 1 was a self-hosted server with a web UI (tag [`server-final`](../../tree/server-final)). Export your words from its web UI (**Settings → Export CSV**), copy the file to the phone, and import it under **Import / export → Import CSV or JSON**.

## Design

[docs/SPEC.md](docs/SPEC.md)
