#!/usr/bin/env bash
# Backup/restore drill — rehearses the real disaster procedure against a sandbox
# lane, and then PROVES the restore actually restored something.
#
# A backup nobody has restored is a hypothesis. This is the experiment. It runs
# in about a minute and is the only honest way to keep the RPO/RTO numbers in
# README.md from being wishful thinking.
#
#   bash backend/tools/backup/drill.sh 1
#
# Lane only. It refuses to touch dev (:5432) or anything it did not create, and
# it DESTROYS the lane it runs against — that is the point. `sandbox.sh reset`
# puts the lane back if the drill leaves it unhappy.
#
# Lives in `backend/tools/` — NOT next to the sandbox scripts in `docker/` —
# because `docker/` sits above both git repositories and is version-controlled by
# neither. A disaster-recovery procedure that vanishes on a fresh clone is not a
# disaster-recovery procedure.
set -euo pipefail

LANE="${1:-}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="${WORK:-$HERE/../../target/backup-drill}"

PGDB="${PGDB:-bvisionry}"
PGUSER="${PGUSER:-bvisionry}"
BUCKET="${MINIO_BUCKET:-bvisionry-media}"

die() { echo "ERROR: $*" >&2; exit 1; }
step() { printf '\n\033[1m== %s\033[0m\n' "$*"; }

[ -n "$LANE" ] || die "usage: bash backend/tools/backup/drill.sh <lane 1..4>"
case "$LANE" in
  [1-4]) ;;
  # Lane 0 is the operator's and dev is on :5432. Both are off limits by policy,
  # and a drill is exactly the command you least want to run against the wrong
  # one — it wipes what it finds.
  *) die "lane must be 1..4 (never 0, never dev)" ;;
esac

PROJ="bvisionry-lane$LANE"
DB_CONTAINER="$PROJ-db-1"
MINIO_CONTAINER="$PROJ-minio-1"
NETWORK="${PROJ}_default"

docker inspect "$DB_CONTAINER"    >/dev/null 2>&1 || die "$DB_CONTAINER is not running — bash docker/sandbox/sandbox.sh up $LANE"
docker inspect "$MINIO_CONTAINER" >/dev/null 2>&1 || die "$MINIO_CONTAINER is not running — bash docker/sandbox/sandbox.sh up $LANE"

mkdir -p "$WORK"
rm -rf "${WORK:?}/media"
mkdir -p "$WORK/media"

# Host path in the form the docker CLI wants. On Git Bash / MSYS a bind mount
# spelled `/e/projects/...` is rewritten on its way to the daemon and a container
# path spelled `/backup` is helpfully "converted" to `C:/Program Files/Git/backup`
# — so the mount silently lands somewhere else and the restore reports an empty
# source. `cygpath -m` gives `E:/projects/...`, and MSYS_NO_PATHCONV leaves the
# container-side path alone. Both are no-ops on Linux and macOS.
hostpath() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else echo "$1"; fi
}

# `mc` runs as a throwaway container on the lane's own network, so the drill
# needs nothing installed on the host beyond docker itself.
mc() {
  MSYS_NO_PATHCONV=1 docker run --rm --network "$NETWORK" \
    -v "$(hostpath "$WORK/media"):/backup" --entrypoint sh minio/mc:latest -c \
    "mc alias set lane http://minio:9000 minio minio123 >/dev/null && $1"
}

psql_q() {
  docker exec -i "$DB_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -At -c "$1"
}

# ── 1. FINGERPRINT ───────────────────────────────────────────────────────────
# Taken before anything is touched, and compared at the end. Row counts alone
# would pass a restore that produced the right NUMBER of wrong rows, so the
# fingerprint also carries a content checksum of the two things whose loss is
# externally visible: issued certificates (their numbers are what a third party
# verifies against, preserved deliberately since V85) and users.
step "1/6  Fingerprint the lane"
fingerprint() {
  psql_q "
    SELECT
      (SELECT count(*) FROM users)                                        || '|' ||
      (SELECT count(*) FROM organizations)                                || '|' ||
      (SELECT coalesce(md5(string_agg(certificate_number, ',' ORDER BY certificate_number)), 'none')
         FROM certificate)                                                || '|' ||
      (SELECT coalesce(md5(string_agg(email, ',' ORDER BY email)), 'none') FROM users)
  "
}
BEFORE="$(fingerprint)"
MARKERS_BEFORE="$(docker exec -i "$DB_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -At \
  -f - < "$HERE/media-markers.sql" | wc -l | tr -d ' ')"
echo "  rows/checksums : $BEFORE"
echo "  media markers  : $MARKERS_BEFORE"

# ── 2. BACK UP ───────────────────────────────────────────────────────────────
# ORDER MATTERS, and it is the opposite of intuition. Objects are captured
# BEFORE the database so the backup set can only ever contain objects the
# database does not yet reference — never a row referencing an object that was
# never captured. An orphaned object is invisible garbage; an orphaned row is a
# lesson that 404s for a paying customer.
step "2/6  Back up object storage, then the database"
# `|| true`: a lane whose bucket has never been written to has no bucket at all,
# and mirroring from a missing source is not a drill failure — it is a lane with
# no media yet. The marker cross-check in step 6 is what would catch a genuinely
# lost object, and it stays meaningful at zero.
mc "mc mirror --overwrite --quiet lane/$BUCKET /backup || true"
OBJECTS="$(find "$WORK/media" -type f | wc -l | tr -d ' ')"
echo "  objects captured: $OBJECTS"

docker exec "$DB_CONTAINER" pg_dump -U "$PGUSER" -d "$PGDB" -Fc > "$WORK/db.dump"
echo "  dump: $(du -h "$WORK/db.dump" | cut -f1)"

# ── 3. DESTROY ───────────────────────────────────────────────────────────────
step "3/6  Destroy (this is the part that makes it a drill)"
mc "mc rm --recursive --force --quiet lane/$BUCKET || true"
# DROP SCHEMA rather than TRUNCATE: a restore that only had to refill existing
# tables would never exercise Flyway's history table, the constraints, or the
# extensions — i.e. it would not rehearse the failure we actually fear.
psql_q "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
echo "  schema dropped, bucket emptied"

# ── 4. RESTORE ───────────────────────────────────────────────────────────────
# Same order as the backup, same reason: objects first, so that the moment a row
# exists it already has something to point at.
step "4/6  Restore object storage, then the database"
mc "mc mb --ignore-existing --quiet lane/$BUCKET && mc mirror --overwrite --quiet /backup lane/$BUCKET || true"
docker exec -i "$DB_CONTAINER" pg_restore -U "$PGUSER" -d "$PGDB" --clean --if-exists --no-owner \
  < "$WORK/db.dump" >/dev/null 2>&1 || true   # --clean warns loudly on a fresh schema; exit code is not the signal
echo "  restored"

# ── 5. VERIFY THE DATA ───────────────────────────────────────────────────────
step "5/6  Verify"
AFTER="$(fingerprint)"
[ "$BEFORE" = "$AFTER" ] || die "fingerprint mismatch
  before: $BEFORE
  after : $AFTER"
echo "  ✓ rows and content checksums identical"

# ── 6. VERIFY THE TWO SYSTEMS AGREE ──────────────────────────────────────────
# The check a single-system drill cannot make, and the one that catches the
# realistic disaster: a database restored to a point in time the object store
# was not. Every marker in the database must resolve to an object that exists.
step "6/6  Cross-check every media marker against the bucket"
# `mc find` rather than `mc ls`, and no in-container text processing at all.
# Two reasons, both learned by getting it wrong: the minio/mc image ships no awk
# (so a `| awk '{print $NF}'` pipeline silently produced an EMPTY listing, and an
# empty listing makes a perfectly good restore report every marker as dangling —
# a false alarm is as bad as a missed one in a procedure people run at 3am), and
# `ls` output is columnar, so a key containing a space would be truncated at
# whitespace. `find` prints one full path per line and nothing else.
mc "mc find lane/$BUCKET" | sed "s#^lane/$BUCKET/##" | tr -d '\r' > "$WORK/objects.txt"
LISTED="$(wc -l < "$WORK/objects.txt" | tr -d ' ')"
echo "  bucket lists $LISTED object(s)"
DANGLING=0
while IFS='|' read -r _tbl _col marker; do
  [ -n "${marker:-}" ] || continue
  key="${marker#minio://$BUCKET/}"
  grep -qxF "$key" "$WORK/objects.txt" || { echo "  ✗ DANGLING: $marker"; DANGLING=$((DANGLING + 1)); }
done < <(docker exec -i "$DB_CONTAINER" psql -U "$PGUSER" -d "$PGDB" -At -F'|' -f - < "$HERE/media-markers.sql")

[ "$DANGLING" -eq 0 ] || die "$DANGLING media marker(s) point at objects that are not in the bucket"
echo "  ✓ all $MARKERS_BEFORE media markers resolve"

printf '\n\033[1;32mDRILL PASSED\033[0m — lane %s restored and verified.\n' "$LANE"
echo "Artifacts left in $WORK (delete when done)."
echo
echo "Redis was deliberately NOT backed up or restored: it holds rate-limit"
echo "windows and caches only, all of which are correct to lose. Restoring stale"
echo "rate-limit windows would be worse than dropping them."
