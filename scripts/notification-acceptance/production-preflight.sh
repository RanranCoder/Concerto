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
endpoint=${url#jdbc:mysql://}
authority=${endpoint%%/*}
[[ "$endpoint" == */* && "$authority" =~ ^([a-zA-Z0-9_.-]+)(:([0-9]+))?$ ]] || {
  echo 'An explicit single-host MySQL URL is required' >&2; exit 1;
}
db_host=${BASH_REMATCH[1]}
db_port=${BASH_REMATCH[3]:-3306}
schema=${endpoint#*/};schema=${schema%%\?*}
[[ "$schema" == "$PAMS_EXPECTED_SCHEMA" ]] || { echo 'Runtime schema differs from reviewed target' >&2; exit 1; }

if ! resolved=$(docker exec "$backend" getent ahostsv4 "$db_host" | awk '{print $1}' | sort -u); then
  echo 'Cannot resolve the runtime MySQL host from the backend container' >&2; exit 1
fi
mysql_addresses=$(docker inspect --format '{{range .NetworkSettings.Networks}}{{println .IPAddress}}{{end}}' "$mysql")
if [[ -z "$resolved" || "$resolved" == *$'\n'* ]] || ! grep -Fxq -- "$resolved" <<< "$mysql_addresses"; then
  echo 'Runtime MySQL host does not resolve to the selected MySQL container' >&2; exit 1
fi
actual_port=$(docker exec "$mysql" sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names -e "SELECT @@port"')
[[ "$actual_port" == "$db_port" ]] || { echo 'Runtime MySQL port differs from the selected server port' >&2; exit 1; }
printf 'Runtime schema: %s\n' "$schema"
docker exec -e PAMS_PREFLIGHT_SCHEMA="$schema" "$mysql" sh -c '
  MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names "$PAMS_PREFLIGHT_SCHEMA" -e "
    SELECT DATABASE();
    SELECT version,success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
    SELECT COUNT(*) FROM sys_user;
    SELECT COUNT(*) FROM notification;"
'
