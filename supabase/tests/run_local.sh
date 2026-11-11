#!/usr/bin/env bash
# Runs the migration and the SQL tests against a throwaway local Postgres database.
set -euo pipefail
cd "$(dirname "$0")/.."
DB=${1:-ewe_test}
PSQL="psql -v ON_ERROR_STOP=1 -q -X"
$PSQL -d postgres -c "drop database if exists $DB" -c "create database $DB"
$PSQL -d $DB -f tests/00_local_stubs.sql
if $PSQL -d $DB -Atc "select 1 from pg_available_extensions where name='vector'" | grep -q 1; then
  $PSQL -d $DB -f migrations/0001_init.sql
else
  grep -v "^create extension if not exists vector" migrations/0001_init.sql | $PSQL -d $DB -f -
fi
$PSQL -d $DB -f tests/01_schema_test.sql
echo "SQL tests passed"
