#!/usr/bin/env bash
#
# CareerFlux backup — database and resumes, in that order.
#
# Run every six hours to meet the stated recovery point. Install with cron on
# the pilot host, e.g.:
#
#   0 */6 * * * /opt/careerflux/scripts/backup.sh >> /var/log/careerflux-backup.log 2>&1
#
# The order is not arbitrary. `resumes.storage_path` in the database points at a
# file in the volume, so dumping the database first means a resume uploaded
# mid-run ends up as a file nothing references — invisible, and already handled.
# The other order produces a row whose file does not exist, which a student sees
# as their own resume failing to open.
#
# Exits non-zero on any failure, and says why. A backup job that fails quietly
# is worse than no backup job, because it is believed.
set -euo pipefail

BACKUP_DIR="${CAREERFLUX_BACKUP_DIR:-/var/backups/careerflux}"
POSTGRES_CONTAINER="${CAREERFLUX_POSTGRES_CONTAINER:-careerflux-postgres}"
RESUME_VOLUME="${CAREERFLUX_RESUME_VOLUME:-careerflux_resume-data}"
DB_NAME="${CAREERFLUX_DB_NAME:-careerflux}"
DB_USER="${CAREERFLUX_DB_USER:-careerflux}"
KEEP_DAYS="${CAREERFLUX_BACKUP_KEEP_DAYS:-14}"

stamp="$(date +%Y%m%d-%H%M%S)"
dump="${BACKUP_DIR}/careerflux-${stamp}.sql"
resumes="${BACKUP_DIR}/resumes-${stamp}.tar.gz"

mkdir -p "${BACKUP_DIR}"
echo "[$(date -Is)] backup starting -> ${BACKUP_DIR}"

# ---- 1. database ----------------------------------------------------------
echo "[$(date -Is)] dumping ${DB_NAME}"
docker exec "${POSTGRES_CONTAINER}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" > "${dump}"

# A dump that ended halfway through still looks like a perfectly good file, and
# will keep looking like one until the day somebody needs it.
if ! grep -q 'PostgreSQL database dump complete' "${dump}"; then
    echo "[$(date -Is)] FAILED: ${dump} has no completion marker; it is truncated" >&2
    exit 1
fi
tables="$(grep -c '^CREATE TABLE' "${dump}" || true)"
echo "[$(date -Is)] dump ok: $(wc -c < "${dump}") bytes, ${tables} tables"

# ---- 2. resumes -----------------------------------------------------------
echo "[$(date -Is)] archiving ${RESUME_VOLUME}"
docker run --rm -v "${RESUME_VOLUME}:/data:ro" -v "${BACKUP_DIR}:/backup" alpine \
    tar czf "/backup/$(basename "${resumes}")" -C /data .

files="$(docker run --rm -v "${RESUME_VOLUME}:/data:ro" alpine find /data -type f | wc -l)"
echo "[$(date -Is)] archive ok: $(wc -c < "${resumes}") bytes, ${files} files"

# ---- 3. prune -------------------------------------------------------------
# Only ever removes this script's own output, matched by name.
find "${BACKUP_DIR}" -maxdepth 1 -name 'careerflux-*.sql' -mtime "+${KEEP_DAYS}" -delete
find "${BACKUP_DIR}" -maxdepth 1 -name 'resumes-*.tar.gz' -mtime "+${KEEP_DAYS}" -delete

# ---- 4. evidence -----------------------------------------------------------
# One file an operator (or a monitoring check that greps it) can look at to
# answer "did this actually run, and was the result readable?" without reading
# the whole log. Rewritten only on success, so a stale timestamp is itself the
# alarm.
marker="${BACKUP_DIR}/last-success.txt"
cat > "${marker}" <<EOF
completed_at=$(date -Is)
database_dump=$(basename "${dump}")
database_bytes=$(wc -c < "${dump}")
database_tables=${tables}
resume_archive=$(basename "${resumes}")
resume_bytes=$(wc -c < "${resumes}")
resume_files=${files}
retained_dumps=$(find "${BACKUP_DIR}" -maxdepth 1 -name 'careerflux-*.sql' | wc -l)
retained_archives=$(find "${BACKUP_DIR}" -maxdepth 1 -name 'resumes-*.tar.gz' | wc -l)
keep_days=${KEEP_DAYS}
EOF

echo "[$(date -Is)] backup complete; evidence in $(basename "${marker}")"
echo "[$(date -Is)] REMINDER: a backup nobody has restored is a guess."
echo "[$(date -Is)] Drill the restore from docs/RUNBOOK.md at least once a term."
