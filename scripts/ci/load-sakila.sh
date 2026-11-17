#!/usr/bin/env bash
# MySQL·PostgreSQL에 Sakila 샘플 DB를 넣는다. CI(GitHub Actions)와 로컬 확인에서 같이 쓴다.
#
#   scripts/ci/load-sakila.sh            둘 다
#   scripts/ci/load-sakila.sh mysql      MySQL만
#   scripts/ci/load-sakila.sh postgres   PostgreSQL만
#
# 접속 정보(기본값):
#   MySQL      MYSQL_HOST=127.0.0.1 MYSQL_PORT=3306 MYSQL_USER=root MYSQL_PASSWORD=(없음)
#   PostgreSQL PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGPASSWORD=(없음), 데이터베이스 sakila
# 클라이언트 경로: MYSQL_BIN(기본 mysql), PSQL_BIN(기본 psql)
#
# 스크립트는 jOOQ/sakila(BSD-2-Clause)의 고정 커밋에서 받아 SHA-256으로 확인한다(SQLite판과 같은 커밋).
set -euo pipefail

COMMIT=e089a5b1ec9af0df7a9c6a5d47d49fa1736a4e84
BASE="https://raw.githubusercontent.com/jOOQ/sakila/$COMMIT"
WORK="${SAKILA_DIR:-$(mktemp -d)}"
MYSQL_BIN="${MYSQL_BIN:-mysql}"
PSQL_BIN="${PSQL_BIN:-psql}"
TARGET="${1:-all}"

# 받은 파일이 고정한 해시와 같은지 확인한다. 이미 받아 둔 같은 파일이 있으면 다시 받지 않는다.
fetch() {
  local path=$1 sha=$2
  local out="$WORK/$(basename "$path")"
  if [ ! -f "$out" ] || ! echo "$sha  $out" | sha256sum -c --status; then
    curl -fsSL "$BASE/$path" -o "$out"
    echo "$sha  $out" | sha256sum -c --quiet
  fi
  echo "$out"
}

if [[ "$TARGET" == all || "$TARGET" == mysql ]]; then
  schema=$(fetch mysql-sakila-db/mysql-sakila-schema.sql f2c41c3bf6d6c239941b4f98fb37afad21f6be12b82bf586202529a793ccc2ee)
  data=$(fetch mysql-sakila-db/mysql-sakila-insert-data.sql 353ef858e4d2d1a60549969283434da15b905aef3ca7099d82b649b75f8de99f)
  export MYSQL_PWD="${MYSQL_PASSWORD:-}"
  mysql_args=(-h "${MYSQL_HOST:-127.0.0.1}" -P "${MYSQL_PORT:-3306}" -u "${MYSQL_USER:-root}" --protocol=TCP)
  echo "MySQL: Sakila 스키마·데이터 넣는 중..."
  "$MYSQL_BIN" "${mysql_args[@]}" < "$schema"
  "$MYSQL_BIN" "${mysql_args[@]}" < "$data"
  # SQL 안에는 한글을 넣지 않는다(Windows에서 클라이언트 인코딩이 달라질 수 있음)
  echo "MySQL: actor $("$MYSQL_BIN" "${mysql_args[@]}" -N -e "SELECT COUNT(*) FROM sakila.actor")행"
fi

if [[ "$TARGET" == all || "$TARGET" == postgres ]]; then
  schema=$(fetch postgres-sakila-db/postgres-sakila-schema.sql eaa8cfc2fd358e70617d31ed3638196982944749929f02e90b980457855bfa48)
  data=$(fetch postgres-sakila-db/postgres-sakila-insert-data.sql 5abfb27721f56443dcd67b0fe4313c66c2ceca5e552fc1e31b2fb289157ff2e9)
  export PGHOST="${PGHOST:-127.0.0.1}" PGPORT="${PGPORT:-5432}" PGUSER="${PGUSER:-postgres}"
  if ! "$PSQL_BIN" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname = 'sakila'" | grep -q 1; then
    "$PSQL_BIN" -d postgres -qc "CREATE DATABASE sakila"
  fi
  echo "PostgreSQL: Sakila 스키마·데이터 넣는 중..."
  "$PSQL_BIN" -d sakila -q -v ON_ERROR_STOP=1 -f "$schema" > /dev/null
  "$PSQL_BIN" -d sakila -q -v ON_ERROR_STOP=1 -f "$data" > /dev/null
  "$PSQL_BIN" -d sakila -qc "ANALYZE" # 행 수 추정치(list_tables)가 나오도록 통계를 만든다
  echo "PostgreSQL: actor $("$PSQL_BIN" -d sakila -tAc "SELECT COUNT(*) FROM actor")행"
fi
