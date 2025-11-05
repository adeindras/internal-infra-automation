#!/bin/bash
source pgpass
sed -i "/^DROP ROLE \"${PGUSER}\"/d;/^DROP ROLE IF EXISTS \"${PGUSER}\"/d;/^CREATE ROLE \"${PGUSER}\"/d; /^ALTER ROLE \"${PGUSER}\"/d;" dumpall-${TARGET_ENV}.sql 
sed -i "/^DROP ROLE ${PGUSER}/d;/^DROP ROLE IF EXISTS ${PGUSER}/d;/^CREATE ROLE ${PGUSER}/d; /^ALTER ROLE ${PGUSER}/d;" dumpall-${TARGET_ENV}.sql 
