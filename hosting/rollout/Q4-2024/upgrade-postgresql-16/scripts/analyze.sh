#!/bin/bash
source pgpass
DBS=$(psql -d postgres -At -c "SELECT datname FROM pg_database WHERE datallowconn AND datname NOT IN ('rdsadmin', 'template1', 'template0');")
for db in $DBS; do
  echo "Vacuuming database: $db"
  vacuumdb -z -d $db
done