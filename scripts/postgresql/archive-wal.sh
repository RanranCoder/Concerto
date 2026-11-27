#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 3 || ! $2 =~ ^([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$ ]]; then
  echo 'Usage: archive-wal.sh <WAL source path> <WAL file name> <archive directory>' >&2
  exit 2
fi

source_path=$1
name=$2
directory=$3
destination="$directory/$name"

if [[ -e "$destination" ]]; then
  cmp -- "$source_path" "$destination"
  sync -f -- "$directory"
  exit 0
fi

temporary=$(mktemp "$directory/.$name.XXXXXX")
trap 'rm -f -- "$temporary"' EXIT
cp -- "$source_path" "$temporary"
sync -d -- "$temporary"
# A successful retry may confirm the same bytes. An archive member is never overwritten.
if ! ln -- "$temporary" "$destination"; then
  cmp -- "$source_path" "$destination"
fi
sync -f -- "$directory"
