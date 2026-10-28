# Deploying kindlevocab

The server runs in Docker on a Linux machine (a VM, a home server) on your Tailscale tailnet. The web UI and the Android app reach it over the tailnet at `https://<host>.<tailnet>.ts.net`. Nothing is exposed to the internet.

```
phone (Android app) ─┐
                     ├─ tailnet (HTTPS via tailscale serve) ─▶ VM: Docker container :3000
browser (web UI) ────┘                                          ├─ data/   SQLite db + audio
                                                                └─ vault/  Obsidian export (optional)
```

## 1. What you need

- **A Linux host** with Docker Engine and the Compose plugin (`docker compose version` works), plus git.
- **Tailscale** on the host, and on every phone or computer that will use the app.
- **HTTPS certificates** enabled for the tailnet: admin console → DNS → turn on MagicDNS and HTTPS Certificates.
- **An Anthropic API key** for word lookups (console.anthropic.com).
  - Lookups and Kindle imports call Claude, which is billed to that key.
  - Words already looked up are cached on the server and cost nothing to look up again.
  - Without a key, everything else still works: manual words, CSV/JSON import, review. Only lookups fail, with an error.
- **Optional: an Obsidian vault folder** on the host, if you want each word mirrored there as Markdown. Get the files to your devices however you already sync the vault (Syncthing, Obsidian Sync, …).

## 2. Install the server

Run these as the user that should own the data (not root):

```sh
git clone https://github.com/olrut/kindlevocab.git
cd kindlevocab
cp .env.example .env
mkdir -p data          # create it yourself: Docker would create it owned by root
id -u; id -g           # your UID and GID, for .env
```

Edit `.env`:

| Setting | Value |
|---|---|
| `ANTHROPIC_API_KEY` | your key |
| `ANTHROPIC_MODEL` | leave the default unless you want another model |
| `OBSIDIAN_DIR` | `/app/vault` to export to Obsidian; **empty** to turn the export off |
| `OBSIDIAN_HOST_DIR` | the host folder mounted as the vault, e.g. `/home/me/Obsidian/MyVault` (the vault root or a folder inside it) |
| `PORT` | `127.0.0.1:3000` (recommended, see below) or `3000` |
| `APP_UID`, `APP_GID` | the numbers `id -u` and `id -g` printed |

**`PORT=127.0.0.1:3000`** makes the server listen only on the host itself. Tailscale then serves it to the tailnet, and nobody else on the local network can reach it. Plain `3000` also opens it to the local network without HTTPS. Because the user list and logins have no PIN unless you set one, that is only fine on a network you trust.

The container runs as `APP_UID:APP_GID`, so `data/` and the vault folder must be writable by that user. If you export to Obsidian, check `ls -ld "$OBSIDIAN_HOST_DIR"`.

Start it:

```sh
docker compose up -d --build     # the first build takes a few minutes
docker compose ps                # STATUS should become "healthy"
curl http://127.0.0.1:3000/api/health    # {"ok":true}
```

## 3. Put it on the tailnet

```sh
sudo tailscale serve --bg 3000
tailscale serve status            # shows https://<host>.<tailnet>.ts.net
```

`--bg` keeps the setting across reboots. Open `https://<host>.<tailnet>.ts.net` in a browser on a device in the tailnet; the first request can take a few seconds while the certificate is issued.

## 4. First use

### Web

1. Open the address. On the login page, use **Create user**: a name and an optional PIN. A PIN is worth setting if other people share the tailnet.
2. On **Add**, look up a word. If it shows a definition, the API key works.
3. **Import** takes a Kindle vocabulary file:
   1. connect the Kindle by USB;
   2. copy `system/vocabulary/vocab.db` from it (the `system` folder may be hidden);
   3. upload it, pick the words and confirm.

   Each new word is one Claude lookup. CSV/JSON import is on the same page.
4. **Settings** shows the state of the Obsidian export, and has a button that rebuilds the whole export. Words go to `<vault>/Vocab/<user>/`. Do not edit those files: put your notes in a word's Notes field in the web app instead.

### Android

The app needs Android 8.0 or newer, with Tailscale installed and connected on the phone.

1. **Get the APK**, either way:
   - From GitHub: repository → Actions → the latest successful **android** run on `main` → Artifacts → `app-debug`. It downloads as a zip; unzip it to get `app-debug.apk`.
   - Or build it: `cd android && ./gradlew assembleDebug` (needs JDK 17 and the Android SDK). The APK is in `app/build/outputs/apk/debug/`.
2. Copy the APK to the phone and open it. Android asks you to allow installs from that app (Files, Chrome, …); allow it.
3. **Start the app:**
   1. enter the server address, e.g. `vocab-vm.tail1234.ts.net` (`https://` is assumed);
   2. pick your name and type the PIN if you set one.
4. **Review:**
   - tap to reveal the definition;
   - swipe sideways for the next word;
   - swipe down when you have learned a word (Undo is offered for 3 s).
5. **Menu:** Sync now, Display… (theme, word size), Switch user.

The app keeps a copy of your words and works offline. Changes made offline are sent the next time it reaches the server; until then "Not synced" shows at the top.

The APK is signed with a debug key. To update, install the new APK over the old one. Your words and login are kept.

## 5. Day to day

Update to the latest version:

```sh
cd kindlevocab
git pull
docker compose up -d --build
docker image prune -f          # removes the old image
```

Database migrations run automatically on start.

Logs and restarts:

```sh
docker compose logs -f --tail=100
docker compose restart
docker compose down            # stop (data/ is kept)
```

The container restarts by itself after a crash or reboot (`restart: unless-stopped`).

### Backups

Everything is in `data/`:
- `vocab.db`: users, words and the lookup cache;
- `audio/`: downloaded pronunciations.

The Obsidian folder can be rebuilt from the database (Settings → re-export all).

The database is in WAL mode, so do not copy `vocab.db` alone while the server runs. Use either:

```sh
# a consistent copy while running (needs the sqlite3 package on the host)
sqlite3 data/vocab.db ".backup 'backup/vocab-$(date +%F).db'"
cp -r data/audio backup/
```

or stop the container, copy `data/`, and start it again:

```sh
docker compose stop && cp -a data "backup/data-$(date +%F)" && docker compose start
```

Restore by putting the files back into `data/` while the container is stopped.

Export from **Settings → Export CSV/JSON** is also a portable copy of a user's words.

## 6. Troubleshooting

| Symptom | Check |
|---|---|
| `docker compose ps` never reaches healthy; the logs show a permission error | `data/` (or the vault) is not writable by `APP_UID`. Fix with `sudo chown -R $(id -u):$(id -g) data`. |
| Lookups fail with a 502 | `ANTHROPIC_API_KEY` is missing or wrong, or the account has no credit. After editing `.env`, run `docker compose up -d` to apply it. |
| No pronunciation, only the phone's voice | The Free Dictionary API has no recording for the word, or was down when the word was added. Text-to-speech is the expected fallback. |
| The browser cannot open the `ts.net` address | Is Tailscale up on that device (`tailscale status`)? Are HTTPS certificates enabled in the admin console? Does `tailscale serve status` on the host show the proxy? |
| Android: "Cannot reach the server" | Tailscale is off on the phone, or the address is mistyped. The app works offline from its own copy meanwhile. Use "Change server" on the user list to fix the address. |
| Android: "Signed out by the server" | The session was removed, for example because the database was replaced. Pick your name again; changes not yet sent are kept for that user. |
| Obsidian files do not appear | `OBSIDIAN_DIR` must be `/app/vault` (not the host path) and `OBSIDIAN_HOST_DIR` the host path. Settings shows the last export error. |
