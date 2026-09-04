# CareerFlux — operational runbook

For whoever is on the other end of the phone when something is wrong at a
college. Commands are copy-pasteable. No secret values appear here.

**Standing it up in the first place is `docs/DEPLOYMENT.md`.** This document
assumes it is already running. Where both mention a command, they mention the
same one — if they ever disagree, DEPLOYMENT.md is the one that was checked
against a real deployment.

---

## Required environment

The application refuses to start without these under any profile other than
`dev` or `test`. That is deliberate: every one of them has a default that is
either public or guessable, and starting anyway would be worse than not
starting.

| Variable | Required | Notes |
|---|---|---|
| `CAREERFLUX_JWT_SECRET` | **yes** | ≥32 chars. The built-in fallback is published in this repository and is refused outside dev/test. Generate: `openssl rand -base64 48` |
| `DATABASE_URL` | yes | e.g. `jdbc:postgresql://postgres:5432/careerflux` |
| `DATABASE_USERNAME` | **yes** | No fallback. |
| `DATABASE_PASSWORD` | **yes** | No fallback. |
| `CAREERFLUX_CORS_ORIGINS` | yes | Comma-separated. Never `*`. |
| `KAFKA_BOOTSTRAP_SERVERS` | only with `kafka` profile | |
| `GEMINI_API_KEY` | no | Absent = deterministic fallbacks. Not a failure. |
| `CAREERFLUX_ADMIN_EMAIL` / `_PASSWORD` | first boot only | Creates the platform operator. Unset them afterwards. |

---

## Start

**On a pilot host, always both files, overlay second.** The base file on its own
is a development stack: known database password, every port published, dev
origins.

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full up -d
```

Backing services only, while working locally:

```bash
docker compose up -d postgres kafka
```

Backend only, against an existing database:

```bash
java -jar backend/target/careerflux-backend-0.1.0.jar --spring.profiles.active=postgres
```

Add `,kafka` to route pipeline events through Kafka instead of the in-process
outbox. Both transports run the same pipeline.

## Stop

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full stop
```

Shutdown is graceful: in-flight requests get up to 25 seconds. `down -v`
**destroys the database and uploaded resumes** — it is not a restart.

---

## Health check

```bash
curl -s http://localhost:8080/actuator/health
```

`UP` means the process is serving and **the database is reachable** — that is
the only dependency treated as critical, because nothing works without it.

Component detail requires authentication (it would otherwise describe your
infrastructure to anyone who asks):

```bash
curl -s http://localhost:8080/actuator/health -H "Authorization: Bearer $TOKEN"
```

**Kafka and AI are deliberately not part of health.** Both have working
degradation paths — verified, not assumed — so neither should take the
application out of a load balancer:

- **AI unavailable** → resume parsing and match narratives use deterministic
  fallbacks. Candidate discovery, scoring and eligibility are unaffected; none
  of them ever used AI.
- **Kafka unavailable** → discovery, the student directory, requirements and
  shortlisting all keep serving. The consumer reconnects on its own.

---

## Backup

Normally you do not run these by hand — `scripts/backup.sh` does both copies in
the right order on a six-hourly schedule, and `docs/DEPLOYMENT.md` covers
installing it. What follows is the same work spelled out, for when you need one
now or need to see what the script is doing.

```bash
docker exec careerflux-postgres pg_dump -U careerflux -d careerflux > backups/careerflux-$(date +%Y%m%d-%H%M%S).sql
```

Verify before trusting it — a dump that ended early still looks like a file:

```bash
grep -c 'PostgreSQL database dump complete' backups/<file>.sql   # must be 1
grep -c '^CREATE TABLE' backups/<file>.sql                       # 37 at schema V12
```

Resumes are **not** in the dump. They live in the `resume-data` volume and need
their own copy. **Take the database dump first, then the resumes** — the order
matters and is not a style preference. `resumes.storage_path` in the database
points at a file in the volume, so:

- database first, files second: a resume uploaded between the two steps has a
  file in the archive and no row in the dump. It is an orphan file, which the
  application already handles gracefully (`That file is no longer stored.`) and
  which nobody ever asks for, because nothing references it.
- files first, database second: the same upload has a row and **no file**. A
  student's resume is listed and cannot be opened, and a placement officer
  clicking it gets an error about data that the system believes it holds.

The first failure is invisible. The second is the one somebody reports.

```bash
docker run --rm -v careerflux_resume-data:/data -v "$PWD/backups:/backup" alpine \
  tar czf /backup/resumes-$(date +%Y%m%d-%H%M%S).tar.gz -C /data .
```

## Restore

Restore into a **fresh** database and switch to it. Never restore over a
running one.

```bash
docker exec careerflux-postgres psql -U careerflux -d postgres -c "CREATE DATABASE careerflux_restored OWNER careerflux;"
docker exec -i careerflux-postgres psql -U careerflux -d careerflux_restored < backups/<file>.sql
```

Verify before cutting over:

```bash
docker exec careerflux-postgres psql -U careerflux -d careerflux_restored -c "
SELECT (SELECT count(*) FROM users) users, (SELECT count(*) FROM candidate_profiles) profiles,
       (SELECT count(*) FROM candidate_profiles WHERE cgpa IS NOT NULL) with_cgpa,
       (SELECT count(*) FROM company_requirements) reqs,
       (SELECT count(*) FROM company_requirement_shortlists) shortlists,
       (SELECT count(*) FROM jobs) jobs,
       (SELECT count(*) FROM flyway_schema_history WHERE success) migrations;"
```

Then point `DATABASE_URL` at it and restart.

## Recovery objectives

### RPO — 6 hours (proposed)

Back up every six hours. At most one working morning of profile edits, recorded
CGPAs and shortlist decisions is lost, which is recoverable by asking the people
who made them; a 24-hour point would lose a whole placement drive's worth of
decisions and nobody would remember what they were.

Both the database and the resume volume run on the same schedule, in the order
above. A resume without its row, or a row without its resume, is a worse
outcome than either copy being six hours old.

Until the schedule exists, **the real recovery point is "whenever somebody last
ran the command"**, which is not a policy. This stays proposed until the college
agrees it.

`scripts/backup.sh` does both copies in the right order, verifies the dump is
complete rather than merely present, and prunes copies older than fourteen days.
Schedule it on the pilot host:

```bash
0 */6 * * * /opt/careerflux/scripts/backup.sh >> /var/log/careerflux-backup.log 2>&1
```

**Until that entry exists, the six-hour figure is an intention, not a recovery
point.** Check the log after the first two runs; a backup job nobody reads is a
backup job nobody knows has been failing.

### RTO — measured, but NOT FORMALLY AGREED

Drilled end to end on the pilot data (1,948 jobs, 5,758 matches, 69 MB dump):

| Step | Observed |
|---|---|
| Database dump | 2.4 s |
| Restore into a fresh database | 5.8 s |
| Verification query | 0.6 s |
| **Database subtotal** | **9.9 s** |
| Resume volume archive | 1.2 s |
| Restore into a fresh volume | 1.1 s |
| Hash verification | 2.1 s |
| **Resume subtotal** | **5.5 s** |
| **Total mechanical recovery** | **~15 s** |

**That figure is the observed mechanical recovery time. It is not an RTO and
must not be quoted as one.** It measures commands running, and excludes
everything that actually dominates a real outage: noticing, deciding to fail
over, repointing `DATABASE_URL`, restarting the application, and confirming the
system is serving. Those are human steps on a deployment with no monitoring and
no on-call rota, so the honest total is unknown and mostly not technical.

**No RTO has been agreed with the operator or the college.** Do not promise one.
What can be said truthfully today: restoring from a good backup takes seconds of
machine time, and the rest depends on people who have not yet been named.

---

## Database migration

Flyway runs automatically at startup and owns the schema; Hibernate never
generates DDL (`ddl-auto: none`). Current version is **V12**.

```bash
docker exec careerflux-postgres psql -U careerflux -d careerflux -c \
  "SELECT version, description, success, installed_on FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5;"
```

**Take a backup before any deployment carrying a new migration.** There is no
automated rollback: Flyway here is forward-only, so undoing a migration means
restoring the backup taken beforehand. That is the rollback plan — there is no
other one.

`baseline-on-migrate: true` is set. On a database that already has tables but
no Flyway history, Flyway will baseline and **skip** the earlier migrations
rather than fail. That is right for adopting an existing database and wrong
for a fresh one that was half-created by hand — check `flyway_schema_history`
after any unusual first boot.

---

## Rate limiting

Server-side ceilings, counted in the backend's own memory.

| Endpoint | Keyed on | Ceiling |
|---|---|---|
| `POST /api/auth/login` | client address + login identity | 10 / 5 min |
| `POST /api/candidate/resume` | account | 10 / hour |
| `GET /api/requirements/{id}/candidates` | account | 60 / min |
| `POST /api/requirements` | account | 20 / hour |
| Shortlist add and remove | account | 120 / min |

Refusals are `429` with a `Retry-After` in seconds. Tune under
`careerflux.rate-limit.*`; `CAREERFLUX_RATE_LIMIT_ENABLED=false` turns it off
entirely, which is only correct if something in front of the application is doing
the same job.

Discovery is 60 rather than the 30 first proposed. Shortlisting a candidate
invalidates the discovery query, so the browser re-runs discovery once per
shortlist decision; at 30 an officer working down a list would have been
throttled at the thirtieth student while shortlisting itself allowed 120.

**AI is deliberately absent from that table.** AI spend is governed by the daily
per-student and per-institution quota in the database. Resume upload appears
because 8 MB files land on disk whether or not any AI runs.

Three limitations, none of them hidden:

- **One instance only.** The counters live in this JVM. A second backend behind
  a load balancer doubles every effective ceiling and makes the sign-in limit
  avoidable by landing on the other node. Horizontal scaling requires shared
  state (Redis or a database-backed counter) *first*.
- **A restart clears the counters.** Deploying mid-attack gives the attacker a
  fresh allowance. Accepted while restarts are rare and operator-driven.
- **No proxy is trusted.** The client address comes from the socket, never from
  `X-Forwarded-For`, because that header is caller-supplied and honouring it
  would let one attacker mint a new allowance per request. If a reverse proxy is
  introduced, it must strip the incoming header *and* Spring must be configured
  to trust it, in that order.

Sign-in is limited per address **and** identity rather than per address alone:
a college shares one egress address, and an address-only limit would take the
whole campus offline the first time somebody fumbled their password.

---

## Scheduler control

Two switches, because there are two different questions.

```yaml
careerflux:
  background-work-enabled: true    # master switch: may anything scheduled run?
  ingestion:
    scheduler-enabled: false       # narrower: may scheduled ingestion run?
```

| | `background-work-enabled: true` | `background-work-enabled: false` |
|---|---|---|
| `scheduler-enabled: true` | everything runs | **nothing runs** |
| `scheduler-enabled: false` | ingestion paused, other background work runs | **nothing runs** |

**The master switch wins.** Setting `background-work-enabled: false` stops all
nine scheduled workers regardless of `scheduler-enabled`. That is the setting for
a genuine freeze — a pilot, a UAT, or a corpus audit where nothing may move.

### What each switch covers

`scheduler-enabled` gates the five ingestion and source-traffic workers:
scheduled sync, stale-job expiry, the nightly match recompute, source health
monitoring, and the outbox dispatcher.

`background-work-enabled` gates those **and** the four that used to run
regardless of any switch at all:

- the rematch queue drain (every 5s)
- stalled rematch recovery (every 5min)
- pipeline-event retention pruning (03:45 daily, **deletes rows**)
- health-history retention pruning (03:30 daily, **deletes rows**)

Before Phase 14 those four ignored `scheduler-enabled` entirely, so a system an
operator believed was frozen still rescored candidates and deleted history
overnight. If you have ever set `scheduler-enabled: false` and been surprised
that `job_matches` moved, that is why.

### Which one do I want?

- **Pausing ingestion while keeping the product working** — `scheduler-enabled:
  false`. A student editing their profile still gets rescored, which is the
  intended behaviour; retention sweeps still run so tables do not grow unbounded.
- **Freezing everything** — `background-work-enabled: false`. Nothing scheduled
  writes anything.

The startup log states which of these is in effect; look for the
`BackgroundWorkGate` line if you are unsure what a deployment is doing.

`scheduler-enabled` is off deliberately while the corpus and classifier are being
validated. Turning it on starts fetching from live job boards; do that knowingly.

---

## Kafka check

```bash
docker exec careerflux-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group careerflux-ingestion
```

Healthy looks like: state `Stable`, one member, eight `job.*` /
`notification.created` partitions assigned, lag at or near zero.

Growing lag means the consumer is behind, not that events are lost. Falling
back to the in-process transport is a legitimate response: drop `,kafka` from
the active profiles and restart. The same pipeline runs through the outbox.

---

## AI configuration

Setting `GEMINI_API_KEY` is all that is needed — the application detects it at
startup and switches Spring AI on. Leaving it unset is a supported mode, not a
broken one.

Confirm which mode you are in:

```bash
grep -i "AI disabled\|chat model" <application log>
```

Per-student daily quotas are enforced server-side and are unaffected by whether
a key is present. There is no AI usage dashboard; quota state is in the
database.

---

## Demo mode

Two independent gates, both off by default, and they mean different things:

```yaml
careerflux:
  demo:
    seed-sample-jobs: false                    # demo *data*
    allow-seeding-into-populated-corpus: false # never overwrite a real corpus
```

The `demo` Spring profile enables demo **accounts**. `seed-sample-jobs` enables
demo **data**. Conflating the two once put fixture postings into a corpus of
real ones — hence two flags. **Never set either on a college's instance.**

---

## Adding an employer by name

Platform operators only. In the console: **Source registry → Add company**.

The flow is two steps and the gap between them is deliberate:

1. **Find domains.** Type one or more company names. CareerFlux proposes the
   domains it believes belong to each, and says where each belief came from —
   already in the registry, in the built-in market list, or constructed and then
   confirmed by the site answering with that name.
2. **Confirm and probe.** Choose one domain per company. Only then is a job
   board looked for, and only confirmed domains are contacted.

Nothing is pre-selected, and a resolve never registers anything. A registered
source lands at `DISCOVERED` and still has to be classified and pass robots,
terms and access review before it will sync — arriving from a company name earns
it nothing.

**Expect a modest hit rate, and read "no readable board" as a real answer.** Many
large employers run Workday, SuccessFactors or a custom system that publishes no
readable board. Those are reported and not registered, which is correct: an
empty source that looks healthy and ingests nothing is worse than none.

### When a name will not resolve

`AMBIGUOUS` means two domains both answered as that company; pick the right one.
`NOT_FOUND` means nothing CareerFlux constructed answered as that company. In
both cases the fallback is to register the source directly with the careers URL,
which is unchanged.

### Addresses CareerFlux will not fetch

Every outbound URL is checked against the address it resolves to, not its
spelling. Refused: loopback, link-local (including the cloud metadata
endpoints), RFC1918 and unique-local ranges, carrier-grade NAT, reserved and
multicast, anything that is not `https` on port 443, URLs carrying credentials,
and any host answering with a mix of public and private addresses. Redirects are
re-checked at every hop.

This applies to source registration, company resolution, careers-page probing,
ATS probing and adapter fetching alike. A refused URL is a 400 on registration
and a "no board here" during discovery.

If a legitimate public source is ever refused, do not widen the validator to fix
one source — check first that the host really is public and on 443, because that
is almost always the actual answer.

```sql
-- Which employers have a domain recorded, and how many sources each has
SELECT c.name, c.domain, count(s.id) AS sources
FROM companies c LEFT JOIN job_sources s ON s.company_id = c.id
GROUP BY c.id, c.name, c.domain ORDER BY c.name;
```


## Placement workflow

Six stages per shortlisted candidate. Staff invite and select; only the student
answers an invitation. Nothing moves out of `SELECTED`, `NOT_PROCEEDING` or
`DECLINED`, and nothing moves at all once the requirement leaves `OPEN`.

```
SHORTLISTED ──staff──▶ INVITED ──student──▶ INTERESTED ──staff──▶ SELECTED
     │                    │                      │
     └──staff──▶ NOT_PROCEEDING ◀──staff─────────┘
                          ▲
            student ──▶ DECLINED
```

The three questions support actually gets:

**"Where is this candidate?"**

```sql
SELECT u.full_name, r.company_name, r.role_title, s.stage, s.stage_changed_at
FROM company_requirement_shortlists s
JOIN candidate_profiles p ON p.id = s.candidate_id
JOIN users u ON u.id = p.user_id
JOIN company_requirements r ON r.id = s.requirement_id
WHERE u.email = 'student@college.edu';
```

**"Who invited them, and when?"**

```sql
SELECT c.occurred_at, c.from_stage, c.to_stage, c.actor_kind,
       coalesce(c.actor_label, '(account deleted)') AS actor, c.note
FROM placement_stage_changes c
JOIN company_requirement_shortlists s ON s.id = c.shortlist_id
WHERE s.id = '<shortlist-id>'
ORDER BY c.occurred_at;
```

**"How is this drive going?"**

```sql
SELECT stage, count(*) FROM company_requirement_shortlists
WHERE requirement_id = '<requirement-id>' GROUP BY stage ORDER BY stage;
```

### Two things to know before touching this data

**Never edit `stage` directly.** The transition rules live in the application,
not in a check constraint, so a manual `UPDATE` can put a row into a state no
sequence of legal moves could reach — and it writes no history, so afterwards
`placement_stage_changes` and the current stage disagree with no way to tell
which is right. If a stage genuinely has to be corrected, do it through the API
as the officer, which records who did it and why.

**Removing a candidate from a shortlist destroys their placement history.**
`placement_stage_changes` is `ON DELETE CASCADE` on the shortlist row, so taking
somebody off a shortlist after they were invited deletes the record that they
were. This is deliberate — the history describes that shortlist row and has no
meaning without it — but it means removal is not a neutral act once a drive has
started. `audit_events` still holds a `PLACEMENT_STAGE_CHANGED` line for each
move, with no foreign key to cascade, so the trail survives there:

```sql
SELECT occurred_at, actor, detail FROM audit_events
WHERE action = 'PLACEMENT_STAGE_CHANGED' AND entity_id = '<shortlist-id>'
ORDER BY occurred_at;
```

Note that `entity_id` is the shortlist row's id, which no longer resolves to
anything after the removal — capture it before deleting if the trail will matter.


## Logs

```bash
docker compose logs -f backend
docker compose logs -f postgres
```

Application logs at INFO under `com.careerflux`; SQL is at WARN so query text
does not reach the log. Authorization denials are logged with the user and the
resource. Stack traces are logged server-side only — API responses never carry
them.

---

## Troubleshooting

**Application will not start, log says the JWT secret is the development
value.** Working as intended. Set `CAREERFLUX_JWT_SECRET` to a real value.

**Will not start, "Could not resolve placeholder DATABASE_PASSWORD".** Also
intended. There is no default; set it.

**Circular reference error mentioning `kafkaEventBus`.** That was fixed by
resolving handlers lazily. If it reappears, a new `PipelineEventHandler` has
taken a constructor dependency that leads back to the bus — inject it lazily
rather than re-enabling `allow-circular-references`.

**Every candidate shows eligibility UNKNOWN.** Expected when no verified CGPA
exists. CGPA is only evaluated when the institution has recorded it; a
student's own figure never counts. This is correct behaviour, not a fault.

**A student cannot register: "we could not work out which institution you
belong to".** No college claims their email domain. Check the domain stored
against the college — it must be a bare host (`northgate.edu`), and the
student's address must end in it. Colleges are created by a platform operator
under Institutions; see `docs/DEPLOYMENT.md`. Never by hand in SQL.

**Discovery returns nobody for a real requirement.** Check the requirement's
target departments and batch against the students who exist. A requirement
naming a graduation year excludes students with no batch assigned — by design,
since an unassigned student cannot be shown to meet it.

**Discovery is slow under load.** The pool maximum is 10. At 16+ concurrent
requests the pool saturates and latency rises while throughput holds — it
degrades by queuing, not failing. Measured: p95 2.25 s at 16 concurrent, 0%
errors. Raise `maximum-pool-size` only with a measurement that justifies it.

**Resume upload rejected.** Only PDF, DOC, DOCX, ODT, RTF and TXT are accepted,
up to 8 MB, and the declared type must match the extension.

---

## Who may do what

Permissions come from the role at runtime; there is no permission table, so
changing them is a code change and a deploy, never a database edit.

| | Departments / batches / staff | Author a requirement | Shortlist | Move a placement | Discover sources | Sees |
|---|---|---|---|---|---|---|
| Student | no | no | no | **own answer only** | no | only themselves |
| Placement coordinator | no | **no** | **yes, own department only** | **yes, own department only** | no | their department |
| Placement officer | no | yes | yes | yes | **no** | the institution |
| College administrator | **yes** | no | no | no | no | institution counts |
| Platform operator | no | no | no | no | **yes** | no college's students |

Two things in that table are easy to get wrong.

**A coordinator can shortlist but cannot write a requirement.** Those used to be
one permission, `PLACEMENT_DRIVE_MANAGE`. Granting it to coordinators would have
enabled the shortlisting they needed and, in the same move, let every coordinator
create and publish company requirements for the whole college — because
requirement authoring applies no departmental scope. It was split:
`PLACEMENT_SHORTLIST_MANAGE` covers putting a candidate forward and taking them
off, `PLACEMENT_DRIVE_MANAGE` still covers authoring, and only the officer holds
both.

What confines a coordinator is not the permission but the scope check. Every
shortlist write resolves the candidate and asks `DiscoveryScope` whether the
*caller* can see that student; a request naming somebody from another department
is answered as not-found, not forbidden, so a coordinator cannot learn who exists
outside their own.

**A student moves nothing except their own answer.** The placement workflow is
the one place a student writes to a placement record, and it is deliberately not
a permission: `/api/candidate/placements` is self-access, gated only by being
signed in, and every call resolves the record from the authenticated student's
own profile. There is no candidate id in the request to tamper with. A student
asking about a drive they are not on gets not-found, which is also the answer for
a drive that does not exist, so the endpoint cannot be used to enumerate what the
college is running.

**Source discovery is platform work, not placement work.** Adding an employer
makes outbound requests from CareerFlux's own address to operator-chosen hosts,
and adds jobs visible to every student in every college. Neither consequence is
scoped to one institution, so it sits with `SOURCE_MANAGE`, which only the
platform operator holds. A placement officer who wants a specific employer
covered asks for it; granting them the permission would hand every college the
ability to point the platform's network at anything.

**A college administrator cannot shortlist and cannot read student profiles.**
They configure the college — departments, batches, staff accounts — which is not
a reason to see career data. An administrator who also needs that is given the
placement officer role as well.
