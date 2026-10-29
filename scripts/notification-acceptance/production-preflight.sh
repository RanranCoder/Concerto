#!/usr/bin/env bash
# Read-only. Run on the deployment host, with Docker access.
set -euo pipefail
: "${PAMS_EXPECTED_SCHEMA:?Set the reviewed actual application schema explicitly}"
backend=${PAMS_BACKEND_CONTAINER:-pams-backend}
mysql=${PAMS_MYSQL_CONTAINER:-pams-mysql}
[[ "$PAMS_EXPECTED_SCHEMA" =~ ^[a-zA-Z0-9_]+$ ]] || exit 1
environment=$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$backend")
url=$(printf '%s\n' "$environment" | sed -n 's/^SPRING_DATASOURCE_URL=//p')
flyway=$(printf '%s\n' "$environment" | sed -n 's/^SPRING_FLYWAY_URL=//p')
[[ "$url" == jdbc:mysql://* ]] || { echo 'Explicit runtime MySQL URL required' >&2; exit 1; }
[[ -z "$flyway" || "$flyway" == "$url" ]] || { echo 'Separate Flyway target requires review' >&2; exit 1; }
schema=${url#jdbc:mysql://};schema=${schema#*/};schema=${schema%%\?*}
[[ "$schema" == "$PAMS_EXPECTED_SCHEMA" ]] || { echo 'Runtime schema differs from reviewed target' >&2; exit 1; }
printf 'Runtime schema: %s\n' "$schema"
docker exec -e PAMS_PREFLIGHT_SCHEMA="$schema" "$mysql" sh -c '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names "$PAMS_PREFLIGHT_SCHEMA" -e "
    SELECT DATABASE();
    SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
    SELECT COUNT(*) FROM sys_user;
    SELECT COUNT(*) FROM notification;"
'
