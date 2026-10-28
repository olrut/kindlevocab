# kindlevocab

Turn your Kindle lookups into flashcards: an Android app that imports the words you looked up on your Kindle, with the sentences from your books, and reviews them one at a time with swipes. Everything stays on the phone: no server, no account, no API key.

- **Kindle import:** pick the Kindle's `vocab.db`; each word comes in with its book sentence, a definition, IPA and a recording from the free [Free Dictionary API](https://dictionaryapi.dev/).
- **Add your own:** look a word up, pick a meaning or write your own, add the Finnish equivalent and notes.
- **Review:** tap to reveal, swipe sideways for the next word, swipe down when you have learned it.
- **Your data:** CSV/JSON export and import; included in Android's backup.

## Install

The app needs Android 8.0 or newer.

1. On the phone, open the repository's **[Releases](../../releases/latest)** and download `kindlevocab-vX.Y.Z.apk`.
2. Open it. Android asks you to allow installs from that app (Chrome, Files, …); allow it. Play Protect may warn about an app it does not know; choose to install anyway.

To update, install the newer release over the old one. Your words are kept.

**Coming from a debug build** (an `app-debug` artifact from Actions): it is signed with a different key, so Android will not install the release over it. Export your words first (**Import / export → Export CSV**), uninstall the debug app, install the release and import the CSV.

## Import from your Kindle

1. Connect the Kindle to a computer with USB. Copy `system/vocabulary/vocab.db` to the phone (the `system` folder may be hidden: in Windows Explorer, View → Show → Hidden items; in the macOS Finder, Cmd+Shift+.). With a USB-OTG cable you can also open it straight from the Kindle on the phone.
2. In the app: menu → **Import / export** → **Choose vocab.db**.
3. Pick the words and tap **Import**. The next time, only words new since the last import are offered.

Each word needs a dictionary lookup, so the import needs a network connection. Words the dictionary does not know come in without a definition; add one under **Words**.

## Releases

A release is built by GitHub Actions when a tag `v<versionName>` is pushed (`.github/workflows/release.yml`): it runs the tests, signs the APK with the release key and attaches it to a GitHub release.

One-time setup, the release key (keep the `.jks` file and passwords safe outside the repository: without them no update can be installed over an existing install):

```sh
keytool -genkeypair -v -keystore vocab-release.jks -alias vocab -keyalg RSA -keysize 4096 -validity 10000
gh secret set KEYSTORE_BASE64 < <(base64 -w0 vocab-release.jks)   # macOS: base64 -i vocab-release.jks
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS --body vocab
gh secret set KEY_PASSWORD
```

Each release: raise `versionCode` and `versionName` in `android/app/build.gradle.kts`, merge, then:

```sh
git tag v2.0.0 && git push origin v2.0.0
```

The build fails if the tag does not match `versionName`. Pull requests and `main` still get a debug APK (`app-debug` artifact of the **android** workflow) for testing.

## Moving from version 1

Version 1 was a self-hosted server with a web UI (tag [`server-final`](../../tree/server-final)). Export your words from its web UI (**Settings → Export CSV**), copy the file to the phone, and import it under **Import / export → Import CSV or JSON**.

## Design

[docs/SPEC.md](docs/SPEC.md)
