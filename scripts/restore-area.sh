#!/usr/bin/env bash
# Puts one area of the world back from a world backup (only the chunks that cover it; the rest of the world is untouched).
#   bash anti-cheat/scripts/restore-area.sh <x1> <z1> <x2> <z2>            -> uses the newest backup
#   bash anti-cheat/scripts/restore-area.sh <x1> <z1> <x2> <z2> <backup>   -> uses that backup
# Corners are block coordinates (F3). The whole height is restored. The server is stopped while it's done.
# The region files from before are kept in <world>/region-before-restore-<date>, so it can be put back.
set -e
SERVER_DIR="$HOME/mc"
BK="$SERVER_DIR/config/vigil/backups"
if [ $# -lt 4 ]; then
  sed -n 2,6p "$0" | sed 's/^# \{0,1\}//'
  echo; echo "World backups (newest first):"; ls -t "$BK" 2>/dev/null | grep '^world-' || echo "  none yet"
  exit 1
fi
if [ -n "$5" ]; then
  F="$BK/$5"; [ -f "$F" ] || F="$BK/$5.zip"
else
  F="$BK/$(ls -t "$BK" 2>/dev/null | grep '^world-' | head -1)"
fi
[ -f "$F" ] || { echo "No world backup found (run without arguments to list them)."; exit 1; }
LEVEL=$(grep -oP '^level-name=\K.+' "$SERVER_DIR/server.properties" 2>/dev/null || echo world)
echo "Restoring blocks $1,$2 to $3,$4 from $(basename "$F")"
echo "Stopping the server..."
sudo systemctl stop minecraft
python3 - "$F" "$SERVER_DIR/$LEVEL" "$1" "$2" "$3" "$4" <<'PY'
import sys, os, zipfile, shutil, time, struct
zpath, world, x1, z1, x2, z2 = sys.argv[1], sys.argv[2], *map(int, sys.argv[3:7])
cx1, cx2 = sorted((x1 >> 4, x2 >> 4)); cz1, cz2 = sorted((z1 >> 4, z2 >> 4))
stamp = time.strftime('%Y%m%d-%H%M%S')
z = zipfile.ZipFile(zpath)
names = set(z.namelist())

def chunks(data):
    """Raw chunk records (length + type + data) by index 0..1023."""
    out = {}
    if len(data) < 8192:
        return out
    for i in range(1024):
        loc = int.from_bytes(data[i * 4:i * 4 + 3], 'big'); cnt = data[i * 4 + 3]
        if loc == 0 or cnt == 0:
            continue
        o = loc * 4096
        ln = int.from_bytes(data[o:o + 4], 'big')
        if ln <= 0 or o + 4 + ln > len(data):
            continue
        out[i] = (data[o:o + 4 + ln], int.from_bytes(data[4096 + i * 4:4096 + i * 4 + 4], 'big'))
    return out

changed = 0
for sub in ('region', 'entities'):
    keep = os.path.join(world, sub + '-before-restore-' + stamp)
    for rx in range(cx1 >> 5, (cx2 >> 5) + 1):
        for rz in range(cz1 >> 5, (cz2 >> 5) + 1):
            name = 'r.%d.%d.mca' % (rx, rz)
            live_path = os.path.join(world, sub, name)
            live = open(live_path, 'rb').read() if os.path.exists(live_path) else b''
            old = z.read(sub + '/' + name) if sub + '/' + name in names else b''
            cur, bak = chunks(live), chunks(old)
            for cx in range(max(cx1, rx * 32), min(cx2, rx * 32 + 31) + 1):
                for cz in range(max(cz1, rz * 32), min(cz2, rz * 32 + 31) + 1):
                    i = (cx & 31) + (cz & 31) * 32
                    if i in bak:
                        cur[i] = bak[i]
                        # chunks too big for the region file live next to it
                        mcc = sub + '/c.%d.%d.mcc' % (cx, cz)
                        if mcc in names:
                            os.makedirs(os.path.join(world, sub), exist_ok=True)
                            open(os.path.join(world, mcc), 'wb').write(z.read(mcc))
                    else:
                        cur.pop(i, None)  # not in the backup: the game makes it again from the seed
                    if sub == 'region':
                        changed += 1
            if live:
                os.makedirs(keep, exist_ok=True)
                shutil.copy2(live_path, os.path.join(keep, name))
            if not cur and not live:
                continue
            head, body = bytearray(8192), bytearray()
            for i, (rec, ts) in sorted(cur.items()):
                off = 2 + len(body) // 4096
                rec = rec + b'\0' * (-len(rec) % 4096)
                head[i * 4:i * 4 + 4] = struct.pack('>I', (off << 8) | (len(rec) // 4096))
                head[4096 + i * 4:4096 + i * 4 + 4] = struct.pack('>I', ts)
                body += rec
            os.makedirs(os.path.join(world, sub), exist_ok=True)
            open(live_path, 'wb').write(bytes(head) + bytes(body))
print('  %d chunks put back' % changed)
PY
echo "Starting the server..."
sudo systemctl start minecraft
echo "Done. The old region files are kept in $SERVER_DIR/$LEVEL/region-before-restore-* (delete them when you're happy)."
