#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ($1 != empty-seed && $1 != historical-import) ]]; then
  echo 'Usage: bash scripts/postgresql/migrate.sh empty-seed|historical-import' >&2
  exit 2
fi

: "${PAMS_BACKEND_IMAGE:?Set the candidate backend image}"
: "${PAMS_PG_NETWORK:?Set the deployment network}"
: "${PAMS_PG_MIGRATION_URL:?Set the PostgreSQL migration JDBC URL}"
: "${PAMS_PG_MIGRATION_USER:?Set the PostgreSQL migration account}"
: "${PAMS_PG_MIGRATION_PASSWORD:?Set the PostgreSQL migration password}"

flock --exclusive --nonblock "${PAMS_DEPLOY_LOCK_FILE:-/var/lock/concerto-deploy.lock}" \
  docker run --rm --network "$PAMS_PG_NETWORK" \
    --env PAMS_PG_MIGRATION_URL --env PAMS_PG_MIGRATION_USER --env PAMS_PG_MIGRATION_PASSWORD \
    "$PAMS_BACKEND_IMAGE" migrate-postgresql "$1"
