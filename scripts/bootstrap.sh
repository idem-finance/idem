#!/usr/bin/env bash
# Creates the dev tenant and an ADMIN-scoped API key, then prints the raw key.
# Run via: make seed  (or directly: ./scripts/bootstrap.sh)
set -euo pipefail

PG_CONTAINER="${PG_CONTAINER:-idem-postgres}"
DB_USER="${DB_USER:-idem}"

echo "Waiting for PostgreSQL..."
until docker exec "$PG_CONTAINER" pg_isready -U "$DB_USER" -q 2>/dev/null; do
  sleep 1
done
echo "PostgreSQL ready."

./mvnw -q spring-boot:run -pl app \
  -Dspring-boot.run.profiles=dev,seed \
  -Dspring-boot.run.jvmArguments="-Dspring.docker.compose.enabled=false"
