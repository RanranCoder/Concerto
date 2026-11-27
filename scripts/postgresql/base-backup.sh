#!/usr/bin/env bash
set -euo pipefail
umask 077

if [[ $# != 2 || ! $1 =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$ ]]; then
  echo 'Usage: base-backup.sh <backup-id> <backup root>' >&2
  exit 2
fi

backup_id=$1
root=$2
final="$root/$backup_id"
exec 9>"$root/.$backup_id.lock"
flock --exclusive --nonblock 9
if [[ -e "$final" || -L "$final" ]]; then
  echo 'Backup ID already exists' >&2
  exit 1
fi

staging=$(mktemp -d "$root/.$backup_id.XXXXXX")
echo "Writing staged base backup to $staging"
pg_basebackup --no-password --pgdata="$staging" --checkpoint=fast \
  --wal-method=stream --manifest-checksums=SHA256
pg_verifybackup "$staging"
sync -f -- "$staging"
mv --no-clobber -T -- "$staging" "$final"
[[ ! -e "$staging" ]]
sync -f -- "$root"
echo "Verified base backup: $final"
