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

## 7. On Proxmox VE

Run the app in a small **Debian VM**, not in an LXC container. Proxmox recommends a VM for Docker, Tailscale needs no extra device setup in a VM, and a VM avoids the user-ID mapping of folders mounted into unprivileged containers.

### With the script

`deploy/proxmox/create-vm.sh` does sections 2 and 3 for you. Run it as root on the Proxmox host (version 7.2 or newer):

```sh
curl -fsSLO https://raw.githubusercontent.com/olrut/kindlevocab/main/deploy/proxmox/create-vm.sh
bash create-vm.sh
```

It asks for the Anthropic API key and a Tailscale auth key (admin console → Settings → Keys); either can be left empty. It then:
1. downloads the Debian 12 cloud image;
2. creates the VM: 2 vCPU, 2 GB RAM, 16 GB disk, QEMU guest agent, start at boot;
3. installs Docker, Tailscale and the app in the VM with cloud-init;
4. writes `.env` with `PORT=127.0.0.1:3000`, starts the container and runs `tailscale serve`.

The keys are not stored on the Proxmox host. They go into the VM over the QEMU guest agent, so only the VM's `.env` holds them. The cloud-init snippet contains no secrets.

Change the defaults with environment variables, e.g. `VMID=120 STORAGE=local-zfs BRIDGE=vmbr1 VAULT=yes bash create-vm.sh`:

| Variable | Default | |
|---|---|---|
| `VMID` | next free id | |
| `NAME` | `vocab` | VM name, VM hostname and tailnet name (`https://vocab.<tailnet>.ts.net`) |
| `STORAGE` | `local-lvm` | storage for the VM disk |
| `SNIPPETS` | `local` | storage for the cloud-init file; it must allow the *Snippets* content type (Datacenter → Storage → Edit) |
| `BRIDGE` | `vmbr0` | network bridge (the VM gets its address by DHCP) |
| `CORES`, `MEMORY`, `DISK` | `2`, `2048`, `16G` | |
| `SSH_KEY_FILE` | root's `id_ed25519.pub` or `id_rsa.pub` | key for `ssh vocab@<vm>`; the VM user has no password |
| `VAULT` | `no` | `yes` turns on the Obsidian export to `/srv/obsidian` in the VM |

The run takes about 10 minutes: package installation, then the image build. It ends by printing the VM's address.

### By hand

1. Create a VM:
   - Debian 12;
   - 2 vCPU, 2 GB RAM (the first build needs about 1.5 GB; running needs about 150 MB);
   - 16 GB disk, VirtIO SCSI with *Discard*;
   - under *Options*, turn on *QEMU Guest Agent* and *Start at boot*.
2. In the VM:
   ```sh
   apt install qemu-guest-agent git curl sqlite3
   curl -fsSL https://get.docker.com | sh
   usermod -aG docker <you>
   curl -fsSL https://tailscale.com/install.sh | sh
   tailscale up
   ```
3. Continue from section 2.

### Proxmox notes

- **Backups:** add the VM to a Proxmox backup job in snapshot mode. With the guest agent installed, Proxmox briefly freezes the VM's file system during the snapshot, so the copy of the database is consistent. The `sqlite3 .backup` in section 5 is still handy for restoring just the words.
- **Obsidian vault:** it has to be reachable inside the VM. Any of these work:
  - sync it into the VM (e.g. Syncthing to `/srv/obsidian`);
  - mount it from a NAS over NFS/SMB;
  - on Proxmox VE 8.4 or newer, share a host folder into the VM with *Hardware → Add → Virtiofs*.

  The folder must be writable by `APP_UID`.
- **No firewall rules needed:** with `PORT=127.0.0.1:3000` the VM exposes nothing to the LAN.
- **If you must use LXC** (unprivileged Debian 12):
  - set `features: nesting=1,keyctl=1`;
  - for Tailscale, add to `/etc/pve/lxc/<id>.conf`:
    ```
    lxc.cgroup2.devices.allow: c 10:200 rwm
    lxc.mount.entry: /dev/net/tun dev/net/tun none bind,create=file
    ```
  - then follow sections 2–3. Docker in LXC can break on upgrades or with ZFS storage.
