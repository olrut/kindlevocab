#!/usr/bin/env bash
# Creates a Debian 12 VM on this Proxmox VE host and deploys kindlevocab in it (docs/DEPLOY.md).
#
# Run as root on the Proxmox host:
#   bash create-vm.sh
# Settings come from environment variables (defaults below); secrets are asked for when not set:
#   ANTHROPIC_API_KEY   for word lookups (empty: lookups fail, the rest works)
#   TS_AUTHKEY          a Tailscale auth key (admin console → Settings → Keys); empty: log in by hand later
#
# Secrets never touch the host's disk: cloud-init (stored as a snippet) only installs packages, and
# the .env and Tailscale login go into the VM over stdin through the QEMU guest agent.
set -euo pipefail

VMID="${VMID:-$(pvesh get /cluster/nextid)}"
NAME="${NAME:-vocab}"
STORAGE="${STORAGE:-local-lvm}"          # VM disk
SNIPPETS="${SNIPPETS:-local}"            # storage with the "snippets" content type, for cloud-init
BRIDGE="${BRIDGE:-vmbr0}"
CORES="${CORES:-2}"
MEMORY="${MEMORY:-2048}"                 # MiB; the first build needs ~1.5 GB, running needs ~150 MB
DISK="${DISK:-16G}"
VM_USER="${VM_USER:-vocab}"
SSH_KEY_FILE="${SSH_KEY_FILE:-$(ls /root/.ssh/id_ed25519.pub /root/.ssh/id_rsa.pub 2>/dev/null | head -1 || true)}"
REPO="${REPO:-https://github.com/olrut/kindlevocab.git}"
VAULT="${VAULT:-no}"                     # yes: export to Obsidian under /srv/obsidian in the VM
IMAGE_URL="${IMAGE_URL:-https://cloud.debian.org/images/cloud/bookworm/latest/debian-12-genericcloud-amd64.qcow2}"

die() { echo "error: $*" >&2; exit 1; }
step() { echo; echo "==> $*"; }

[ "$(id -u)" = 0 ] || die "run as root on the Proxmox host"
command -v qm >/dev/null || die "qm not found: this is not a Proxmox VE host"
qm status "$VMID" >/dev/null 2>&1 && die "VM $VMID already exists (set VMID=…)"
pvesm status --content snippets 2>/dev/null | awk 'NR > 1 { print $1 }' | grep -qx "$SNIPPETS" \
  || die "storage '$SNIPPETS' does not allow snippets. Enable it: pvesm set $SNIPPETS --content <its current content>,snippets  (see: pvesm status)"
SNIPPET_DIR="$(pvesm path "$SNIPPETS:snippets/x" | xargs dirname)"
mkdir -p "$SNIPPET_DIR"

if [ -z "${ANTHROPIC_API_KEY+set}" ]; then read -rsp "Anthropic API key (empty to skip): " ANTHROPIC_API_KEY; echo; fi
if [ -z "${TS_AUTHKEY+set}" ]; then read -rsp "Tailscale auth key (empty to log in by hand later): " TS_AUTHKEY; echo; fi
for v in "$ANTHROPIC_API_KEY" "$TS_AUTHKEY"; do
  [[ "$v" =~ ^[A-Za-z0-9_-]*$ ]] || die "keys may contain only letters, digits, - and _"
done
SSH_KEY=""
if [ -n "$SSH_KEY_FILE" ]; then SSH_KEY="$(cat "$SSH_KEY_FILE")"; else echo "note: no SSH key found; use 'qm guest exec' or set SSH_KEY_FILE"; fi

step "Downloading the Debian 12 cloud image"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fL --progress-bar -o "$TMP/debian.qcow2" "$IMAGE_URL"

step "Writing cloud-init to $SNIPPET_DIR/$NAME-$VMID.yaml (no secrets in it)"
cat > "$SNIPPET_DIR/$NAME-$VMID.yaml" <<EOF
#cloud-config
hostname: $NAME
groups: [docker]
users:
  - name: $VM_USER
    groups: [sudo, docker]
    shell: /bin/bash
    sudo: ALL=(ALL) NOPASSWD:ALL
    lock_passwd: true
$( [ -z "$SSH_KEY" ] || printf '    ssh_authorized_keys:\n      - %s\n' "$SSH_KEY" )
package_update: true
package_upgrade: true
packages: [qemu-guest-agent, git, curl, ca-certificates, sqlite3]
runcmd:
  - systemctl enable --now qemu-guest-agent
  - curl -fsSL https://get.docker.com | sh
  - curl -fsSL https://tailscale.com/install.sh | sh
  - sudo -u $VM_USER git clone $REPO /home/$VM_USER/kindlevocab
  - sudo -u $VM_USER mkdir -p /home/$VM_USER/kindlevocab/data /home/$VM_USER/kindlevocab/backup
EOF

step "Creating VM $VMID ($NAME)"
qm create "$VMID" --name "$NAME" --ostype l26 --cpu host --cores "$CORES" --memory "$MEMORY" \
  --net0 "virtio,bridge=$BRIDGE" --scsihw virtio-scsi-single \
  --scsi0 "$STORAGE:0,import-from=$TMP/debian.qcow2,discard=on,ssd=1" \
  --ide2 "$STORAGE:cloudinit" --boot order=scsi0 --serial0 socket --vga serial0 \
  --agent enabled=1 --onboot 1 --ipconfig0 ip=dhcp \
  --cicustom "user=$SNIPPETS:snippets/$NAME-$VMID.yaml"
qm resize "$VMID" scsi0 "$DISK"
qm start "$VMID"

step "Waiting for the VM to boot and install packages (a few minutes)"
for _ in $(seq 1 240); do qm agent "$VMID" ping >/dev/null 2>&1 && break; sleep 5; done
qm agent "$VMID" ping >/dev/null 2>&1 || die "the guest agent did not answer in 20 minutes; check the console: qm terminal $VMID"

# Runs a script in the VM (as root) from stdin, prints its output, fails if it fails.
guest() {
  qm guest exec "$VMID" --timeout 0 --pass-stdin 1 -- bash -s | perl -MJSON::PP -0777 -ne '
    my $r = decode_json($_);
    print $r->{"out-data"} // "";
    print STDERR $r->{"err-data"} // "";
    exit($r->{exitcode} // 1);'
}

echo 'cloud-init status --wait >/dev/null; cloud-init status --long' | guest \
  || die "cloud-init failed; see /var/log/cloud-init-output.log in the VM"

step "Configuring and starting kindlevocab (the first build takes a few minutes)"
# Values are checked above to contain no shell or .env special characters.
guest <<EOF
set -euo pipefail
cd /home/$VM_USER/kindlevocab
if [ "$VAULT" = yes ]; then mkdir -p /srv/obsidian; chown $VM_USER:$VM_USER /srv/obsidian; OBS=/app/vault; else OBS=; fi
umask 077
cat > .env <<ENV
ANTHROPIC_API_KEY=$ANTHROPIC_API_KEY
ANTHROPIC_MODEL=claude-haiku-5-5
OBSIDIAN_DIR=\$OBS
OBSIDIAN_HOST_DIR=/srv/obsidian
PORT=127.0.0.1:3000
APP_UID=\$(id -u $VM_USER)
APP_GID=\$(id -g $VM_USER)
ENV
chown $VM_USER:$VM_USER .env
sudo -u $VM_USER docker compose up -d --build --quiet-pull 2>&1 | tail -n 5
for i in \$(seq 1 30); do curl -fs http://127.0.0.1:3000/api/health && break; sleep 2; done
echo
if [ -n "$TS_AUTHKEY" ]; then
  tailscale up --authkey=$TS_AUTHKEY --hostname=$NAME
  tailscale serve --bg 3000
  tailscale serve status
fi
EOF

step "Done"
IP="$(echo "hostname -I | cut -d' ' -f1" | guest || true)"
echo "VM $VMID ($NAME), LAN address ${IP:-unknown}; the app listens only inside the VM and on the tailnet."
if [ -z "$TS_AUTHKEY" ]; then
  echo "Tailscale is installed but not logged in. In the VM run:"
  echo "  sudo tailscale up --hostname=$NAME && sudo tailscale serve --bg 3000"
fi
echo "Open https://$NAME.<your tailnet>.ts.net (the first visit may take a few seconds for the certificate)."
[ -n "$SSH_KEY" ] && echo "SSH: ssh $VM_USER@${IP:-<vm ip>}"
[ "$VAULT" = yes ] && echo "Obsidian export goes to /srv/obsidian in the VM; sync it to your devices (e.g. Syncthing)."
echo "Next: docs/DEPLOY.md § 4 (first use) and § 5 (updates, backups). Proxmox backup jobs can include VM $VMID."
