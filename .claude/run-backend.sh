#!/usr/bin/env bash
# Starts the backend against the local dev database backend/data/careerflux-run.
# Accounts already exist there, so the seeders do nothing and need no passwords.
cd "$(dirname "$0")/../backend" || exit 1
export SPRING_DATASOURCE_URL="jdbc:h2:file:./data/careerflux-run;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE"
exec mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
