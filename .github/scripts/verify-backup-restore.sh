#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ $# -eq 2 ]] || { echo 'Usage: verify-backup-restore.sh ENV_FILE TEMP_DIRECTORY' >&2; exit 2; }
[[ ${CI:-} == true ]] || { echo 'This script creates disposable CI projects only.' >&2; exit 2; }
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)
env_file=$(realpath -- "$1")
temporary=$(realpath -- "$2")
source_project=order-stock-backup-source-ci
target_project=order-stock-backup-target-ci
source_compose=(docker compose --project-name "$source_project" --env-file "$env_file" -f "$repo_root/deploy/compose.prod.yaml" -f "$repo_root/.github/compose.backup-source.yaml")
target_compose=(docker compose --project-name "$target_project" --env-file "$env_file" -f "$repo_root/deploy/compose.prod.yaml" -f "$repo_root/.github/compose.https-test.yaml")
db_tool=(bash "$repo_root/scripts/production-db.sh")

for project in "$source_project" "$target_project"; do
    existing=$(docker ps --all --quiet --filter "label=com.docker.compose.project=$project")
    [[ -z $existing ]] || { echo 'Existing CI project found; no cleanup or mutation performed.' >&2; exit 1; }
    existing_volumes=$(docker volume ls --quiet --filter "label=com.docker.compose.project=$project")
    [[ -z $existing_volumes ]] || { echo 'Existing CI volume found; no cleanup or mutation performed.' >&2; exit 1; }
done
cleanup() {
    status=$?
    trap - EXIT
    if [[ $status -ne 0 ]]; then
        "${source_compose[@]}" logs --no-color --tail 80 || true
        "${target_compose[@]}" logs --no-color --tail 80 || true
    fi
    "${source_compose[@]}" down --volumes --remove-orphans || true
    "${target_compose[@]}" down --volumes --remove-orphans || true
    exit "$status"
}
trap cleanup EXIT
expect_rejection() {
    reason=$1
    shift
    if "$@" > "$temporary/rejected.stdout" 2> "$temporary/rejected.stderr"; then
        echo 'Unsafe restore was unexpectedly accepted.' >&2
        exit 1
    fi
    grep -Fq "$reason" "$temporary/rejected.stderr" || { echo 'Unexpected restore rejection reason.' >&2; exit 1; }
}

"${source_compose[@]}" build --pull
"${source_compose[@]}" up --no-build --wait --wait-timeout 240 app
APP_PORT=18082 python3 "$repo_root/.github/scripts/compose-smoke.py" prepare --state-file "$temporary/state.json"
APP_PORT=18082 python3 "$repo_root/.github/scripts/verify-media-backup.py" prepare --state-file "$temporary/state.json" --env-file "$env_file" --project "$source_project"
archive=$("${db_tool[@]}" backup "$env_file" "$temporary/archives" "$source_project")
# Verify the backup source really contains a live login; a recovery test with no session would prove nothing.
# shellcheck disable=SC2016
source_sessions=$(printf 'SELECT COUNT(*) FROM app_sessions WHERE PRINCIPAL_NAME IS NOT NULL;\n' |
    "${source_compose[@]}" exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names')
[[ $source_sessions -gt 0 ]] || { echo 'No authenticated session in backup source.' >&2; exit 1; }
[[ $(stat -c %a "$archive") == 700 && $(stat -c %a "$archive/database.sql.gz") == 600 ]]
expect_rejection 'Stop app and proxy' "${db_tool[@]}" restore "$env_file" "$archive" "$source_project"

"${target_compose[@]}" build --pull
"${target_compose[@]}" up --no-build --wait --wait-timeout 240 db
cp -a -- "$archive" "$temporary/corrupt"
printf 'damaged' >> "$temporary/corrupt/database.sql.gz"
expect_rejection 'checksum verification failed' "${db_tool[@]}" restore "$env_file" "$temporary/corrupt" "$target_project"
touch "$archive/INCOMPLETE"
expect_rejection 'Backup is incomplete' "${db_tool[@]}" restore "$env_file" "$archive" "$target_project"
rm -- "$archive/INCOMPLETE"
# shellcheck disable=SC2016 # Resolve credentials inside the DB container.
table_count=$(printf 'SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE();\n' |
    "${target_compose[@]}" exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names')
[[ $table_count == 0 ]] || { echo 'Rejected restores changed the empty target.' >&2; exit 1; }

"${db_tool[@]}" restore "$env_file" "$archive" "$target_project"
# shellcheck disable=SC2016
restored_sessions=$(printf 'SELECT (SELECT COUNT(*) FROM app_sessions)+(SELECT COUNT(*) FROM app_sessions_ATTRIBUTES);\n' |
    "${target_compose[@]}" exec -T db sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names')
[[ $restored_sessions == 0 ]] || { echo 'Restored session rows or attributes survived invalidation.' >&2; exit 1; }
expect_rejection 'Target database must be empty' "${db_tool[@]}" restore "$env_file" "$archive" "$target_project"
"${target_compose[@]}" up --no-build --wait --wait-timeout 240
[[ $("${target_compose[@]}" exec -T app printenv SPRING_PROFILES_ACTIVE) == prod ]]
"${target_compose[@]}" cp proxy:/data/caddy/pki/authorities/local/root.crt "$temporary/root.crt"
SMOKE_BASE_URL=https://localhost:18443 SMOKE_CA_FILE="$temporary/root.crt" \
    python3 "$repo_root/.github/scripts/compose-smoke.py" verify --state-file "$temporary/state.json" --expect-session-invalidated
SMOKE_BASE_URL=https://localhost:18443 SMOKE_CA_FILE="$temporary/root.crt" \
    python3 "$repo_root/.github/scripts/verify-media-backup.py" verify --state-file "$temporary/state.json" --env-file "$env_file" --project "$target_project"
printf '%s\n' 'PASS: backup restored into a different empty MySQL volume; Flyway, login, orders, request keys, stock and payment/refund history verified over HTTPS'
printf '%s\n' 'PASS: running app, incomplete/corrupt archives and nonempty targets rejected; backup permissions restricted'
