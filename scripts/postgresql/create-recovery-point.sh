#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ! $1 =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,62}$ ]]; then
  echo 'Usage: create-recovery-point.sh <unique restore point name>' >&2
  exit 2
fi

psql --no-psqlrc --no-password --quiet --no-align --tuples-only -v ON_ERROR_STOP=1 -v point="$1" <<'SQL'
SELECT snapshot_id, reconciliation_sha256
FROM pams_flyway.historical_import_state \gset
SELECT pg_create_restore_point(:'point') AS point_lsn, clock_timestamp() AS point_time \gset
SELECT json_build_object(
    'restorePoint', :'point',
    'lsn', :'point_lsn',
    'timestamp', :'point_time'::timestamptz,
    'walSegment', pg_walfile_name(:'point_lsn'::pg_lsn),
    'systemIdentifier', (pg_control_system()).system_identifier::text,
    'database', current_database(),
    'snapshotId', :'snapshot_id',
    'reconciliationSha256', :'reconciliation_sha256'
);
SELECT pg_switch_wal() AS switched_lsn \gset
SQL
