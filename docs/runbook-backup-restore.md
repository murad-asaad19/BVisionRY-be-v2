# Runbook — backup and restore

Closes the roadmap §11 item *"Backup/restore drill for Postgres and object
storage; documented RPO/RTO."*

The procedure below has been **rehearsed, not just written**:
`backend/tools/backup/drill.sh` performs the whole cycle — fingerprint, back up,
destroy, restore, verify — against a sandbox lane, and passes. A backup nobody
has restored is a hypothesis.

```bash
bash docker/sandbox/sandbox.sh up 2
bash backend/tools/backup/drill.sh 2      # ~1 minute; DESTROYS lane 2, then puts it back
```

---

## 1. What is protected, and what is deliberately not

| System | Holds | Protected? |
|---|---|---|
| **Postgres** | Everything transactional: users, orgs, submissions, answers, evaluations, certificates, audit log | **Yes.** The only true system of record. |
| **Object storage** (MinIO / S3, bucket `bvisionry-media`) | Lesson media, PDFs, course covers, business-card portraits, org branding logos | **Yes.** Not reconstructible — these are customer uploads. |
| **Redis** | Rate-limit windows, AI evaluation cache | **No, on purpose.** Every value in it is either disposable or actively *harmful* to restore: reinstating a stale rate-limit window would throttle real users on behalf of an abuser who left hours ago. Losing Redis costs a cold cache. |

## 2. RPO / RTO

| | Target | Status |
|---|---|---|
| **RPO** (max data loss) | **24 hours**, tightening to 1 hour once PITR is on | ⚠️ **Depends on provider configuration this repository cannot see.** See §6. |
| **RTO** (time to serving) | **2 hours** | Rehearsed at lane scale (~1 min on 1.4MB). Untested at production volume. |

Both numbers are **targets with a named gap**, not measurements, and the honest
reading is: the *procedure* is proven, the *provider retention behind it* has not
been confirmed by anyone. Stating an RPO backed by an unverified provider setting
is exactly the kind of claim this roadmap has been correcting elsewhere.

The RPO is dominated entirely by backup frequency, not by anything in this
codebase. Object storage is effectively append-only in normal operation (markers
are immutable once written; deletions are rare), so the realistic loss window is
Postgres's.

## 3. Order of operations — and why it is counter-intuitive

**Back up object storage FIRST, then the database. Restore in the same order.**

Both directions follow from one asymmetry:

- An object with no row pointing at it is **invisible garbage**. It costs storage.
- A row pointing at an object that does not exist is a **lesson that 404s for a
  paying customer**, a certificate with no PDF, an org whose logo is a broken
  image.

So always overshoot on objects. Capturing them first guarantees the backup set
can only contain objects the database does not yet reference. Restoring them
first guarantees that the instant a row exists, its target already does.

Getting this backwards produces a restore that looks completely successful —
right row counts, no errors — and is quietly broken in the parts customers see.
Step 6 of the drill is the check that catches it: every `minio://bucket/key`
marker stored anywhere in the database must resolve to an object in the bucket.

Those markers are **discovered, not listed** (`media-markers.sql` walks
`information_schema`), because the set of columns holding them grows whenever a
feature gains an image, and a hand-maintained list would go silently stale.

## 4. Restoring for real

```bash
# 0. Stop the API. A running app writes into a half-restored database and
#    Flyway may try to migrate mid-restore.

# 1. Object storage first (see §3).
mc mirror --overwrite s3-backup/bvisionry-media s3-live/bvisionry-media

# 2. Then Postgres.
pg_restore -U "$PGUSER" -d "$PGDB" --clean --if-exists --no-owner backup.dump

# 3. Do NOT restore Redis. Start it empty.

# 4. Start the API. Flyway reconciles on boot; see the constraint below.

# 5. Verify before declaring done:
#    - /actuator/health is UP, including the `scheduledJobs` indicator
#    - a certificate verifies by number at /verify/{certificateNumber}
#    - a lesson with media plays (proves markers resolve AND presigning works)
```

### Constraints specific to this application

- **Flyway is the schema owner (`ddl-auto=none`).** A dump carries
  `flyway_schema_history` with it, so restoring an OLD dump under a NEWER build
  is fine — the outstanding migrations replay forward on boot. The reverse is
  not: a dump from a newer schema under an older build fails the checksum
  validation and the app will not start. **Restore to a build at least as new as
  the dump.**
- **Certificate numbers must survive.** They are what a third party verifies
  against — deliberately preserved even through GDPR erasure (V85). A restore
  that renumbers or loses them silently invalidates every certificate ever
  issued, which is why the drill checksums them rather than merely counting rows.
- **Presigned URLs are minted per request** and expire in 60 minutes, so no
  cached URL survives a restore and none needs to. Object *keys* are what must
  match; URLs are derived.
- **`shedlock` rows may be restored holding stale locks.** Harmless: each carries
  `lock_until`, and `lockAtMostFor` (default 30 min) releases it. A scheduled job
  may skip its first tick or two after a restore.
- **Retention jobs will re-delete restored rows.** `error_events`, `ai_call_logs`
  and notifications are purged on a schedule, so a restore briefly resurrects
  rows past their retention window and the next run removes them again. Correct
  behaviour, surprising the first time you watch it.

## 5. Verifying a restore

The drill's checks are the ones to repeat by hand, in this order:

1. **Row counts** — did anything come back at all.
2. **Content checksums** over certificate numbers and user emails — the right
   *number* of wrong rows would pass a count check and fail this.
3. **Marker cross-check** — every stored `minio://` marker resolves to a real
   object. This is the only check that can catch a database and an object store
   restored to *different points in time*, which is the realistic disaster.

## 6. Operator actions this runbook cannot perform

These are outside the repository, and none of them is closed by any commit:

- [ ] **Confirm automated Postgres backups are enabled on Railway**, with the
      retention window written down. This is what the RPO number actually rests
      on, and nothing in this codebase can observe it.
- [ ] **Enable point-in-time recovery** if the 24h RPO is not acceptable to a
      customer contract.
- [ ] **Confirm object-storage backup exists at all.** Bucket versioning or
      cross-region replication on the production S3/MinIO — a bucket with neither
      has no backup, only durability, and those are different properties.
- [ ] **Store backups somewhere the production credentials cannot reach.** A
      backup deletable by the compromised account it protects is not a backup.
- [ ] **Run this drill against a production-sized restore once**, to replace the
      rehearsed-at-lane-scale RTO with a measured one.
