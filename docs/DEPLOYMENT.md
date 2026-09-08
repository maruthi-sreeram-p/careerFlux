# CareerFlux — pilot deployment

How to stand CareerFlux up at a college. Written for whoever does it the first
time, and for whoever has to do it again a year later without asking anybody.

`docs/RUNBOOK.md` is the companion: this document is how to *get* there, the
runbook is how to *operate* it once it is running. Where both mention a command,
they mention the same one.

No secret values appear here.

**Two deployment shapes are supported**, and most of this document applies to
both because the application is the same either way:

| | Docker Compose on a host | Render |
|---|---|---|
| Where it runs | A VM you own | Render's managed platform |
| TLS | A reverse proxy you run | Render, automatically |
| Database | The `postgres` container | Render PostgreSQL |
| Resume files | Named volume `resume-data` | A Render disk at `/app/data/resumes` |
| Backups | `scripts/backup.sh` | Render snapshots — **`backup.sh` cannot run there** |

The Compose path is described throughout. **Render has its own section below**,
and where the two differ the Render section says so explicitly rather than
leaving you to infer it.

---

## Architecture

```
                          INTERNET
                              |
                            HTTPS
                              |
                    ┌─────────────────────┐
                    │  TLS terminates     │   NOT part of this repository.
                    │  here               │   A host reverse proxy, or a
                    └─────────────────────┘   hosting platform. See "HTTPS".
                              |
                        HTTP, loopback
                              |
   ┌──────────────────────────────────────────────────────────┐
   │  careerflux-frontend        nginx, port 80 in-container  │
   │                                                          │
   │   /            → the built SPA (try_files → index.html)  │
   │   /api/        → proxy to careerflux-backend:8080        │
   │                                                          │
   │  Published on 127.0.0.1:8081 by default.                 │
   └──────────────────────────────────────────────────────────┘
                              |
                     compose network only
                              |
   ┌──────────────────────────────────────────────────────────┐
   │  careerflux-backend         Spring Boot, port 8080       │
   │  profiles: postgres                                      │
   │  NO published port — reachable only through nginx        │
   └──────────────────────────────────────────────────────────┘
              |                    |                    |
    ┌─────────────────┐  ┌──────────────────┐  ┌─────────────────┐
    │ careerflux-     │  │ careerflux-kafka │  │ resume-data     │
    │ postgres        │  │ single broker,   │  │ named volume    │
    │ 127.0.0.1:5432  │  │ KRaft            │  │ /app/data/      │
    │ postgres-data   │  │ 127.0.0.1:9092   │  │   resumes       │
    │ named volume    │  │ kafka-data volume│  │                 │
    └─────────────────┘  └──────────────────┘  └─────────────────┘
```

Four containers, three volumes, one entrance. The frontend container is the
application's reverse proxy: the browser loads the app and calls `/api` on the
**same origin**, which is why the backend needs no published port at all.

Same origin does **not** mean CORS stops mattering. Browsers attach an `Origin`
header to every POST, PUT and DELETE, including same-origin ones, and Spring
checks it against `CAREERFLUX_CORS_ORIGINS`. Set that to anything other than the
exact origin people type and the app fails in a peculiarly misleading way: every
page loads, every list fills in, and every attempt to save anything returns a
bare `Invalid CORS request` that the frontend can only report as "That request
could not be completed".

**Everything here is single-instance.** One backend, one database, one broker.
That is a deliberate choice for a first pilot, and it has consequences that are
listed under "Known limitations" rather than buried.

---

## Prerequisites

| | |
|---|---|
| Docker Engine | 24+ with the Compose plugin (`docker compose version`) |
| Host OS | Any Docker host. The backup scheduler differs — see "Backup" |
| Disk | See "Sizing" |
| A TLS terminator | Reverse proxy or platform. Not provided here |
| An origin | The URL the college will actually use |

Nothing else. No Kubernetes, no Redis, no cloud account.

---

## Environment variables

Copy `.env.pilot.example` to `.env` beside the compose files and fill it in.
`.env` is gitignored; `.env.pilot.example` contains placeholders only.

**Required — the deployment will not start without these:**

| Variable | Notes |
|---|---|
| `CAREERFLUX_JWT_SECRET` | ≥32 chars. `openssl rand -base64 48`. The built-in fallback is published in this repository and is refused outside dev/test |
| `POSTGRES_PASSWORD` | The database password. Also supplied to the backend as `DATABASE_PASSWORD` |
| `DATABASE_USERNAME` | Usually `careerflux` |
| `CAREERFLUX_CORS_ORIGINS` | The pilot's frontend origin, **exactly** as people type it — scheme, host and port. `localhost` and `127.0.0.1` are different origins. Never `*` |

**Optional:**

| Variable | Default | Notes |
|---|---|---|
| `GEMINI_API_KEY` | unset | Absent is a supported mode, not a fault |
| `CAREERFLUX_ADMIN_EMAIL` / `_PASSWORD` | unset | First boot only, then remove |
| `CAREERFLUX_FRONTEND_BIND` | `127.0.0.1` | Only change if TLS genuinely terminates elsewhere |
| `CAREERFLUX_FRONTEND_PORT` | `8081` | Host port the TLS terminator forwards to |

The pilot overlay declares the required four with compose's `:?` form, so a
missing value stops the deployment **before any container starts**, naming the
variable. That is the intended behaviour — verify it once:

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full config
```

With nothing set it must exit non-zero and name each missing variable.

### Secrets

- Secrets live in `.env` on the host, or in the environment of whatever starts
  compose. Never in the repository, never in `application.yml`, never in this
  document.
- `.env` should be owned by the deploying user and mode `600`.
- `CAREERFLUX_ADMIN_PASSWORD` creates the platform operator on first boot.
  **Remove it from `.env` afterwards** so a restart stops re-supplying it.
- Nothing sensitive reaches the frontend bundle. The frontend calls relative
  URLs and holds no configuration at all — verified against the built assets.

---

## Deploying

Always start from the base file **plus** the pilot overlay. The base file alone
is a development stack with a known password and every port published.

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full up -d
```

The order of the `-f` flags matters; the overlay must come second.

### The procedure, in order

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full config
```

1. **Back up** — skip only on a genuinely empty first deployment.
   `scripts/backup.sh`, or the commands in the runbook.
2. **Start the database** on its own and wait for it to report healthy:
   `docker compose ... up -d postgres`
3. **Apply migrations** — nothing to run by hand. Flyway executes at backend
   startup and owns the schema; Hibernate never emits DDL (`ddl-auto: none`).
4. **Verify the migration** before trusting it (below).
5. **Start everything**: `docker compose ... --profile full up -d`
6. **Health check** (below).
7. **Create the platform operator** — set `CAREERFLUX_ADMIN_EMAIL` and
   `CAREERFLUX_ADMIN_PASSWORD` before the first start, then remove them.
8. **Onboard the college** — sign in as that operator and use
   **Institutions → Create institution** (below).
9. **Hand over to the college administrator**, who configures the college.
10. **Smoke test** (below).

### Migration

Flyway runs V1 → V12 on a clean database, and only the missing ones on an
existing database. Verify afterwards:

```bash
docker exec careerflux-postgres psql -U careerflux -d careerflux -c \
  "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;"
```

Every row must show `success = t`, ending at **V12**.

**If a migration fails the backend does not start.** That is correct and must not
be worked around. Do not edit the failed row, do not re-run with a modified
migration, and do not delete data to make it pass. Flyway here is forward-only:
there is no automated rollback, so undoing a migration means restoring the backup
taken in step 1. That is the rollback plan; there is no other one.

`baseline-on-migrate: true` is set, which is right for adopting a database that
already has tables, and wrong for a fresh one that somebody half-created by hand
— Flyway would baseline and *skip* the earlier migrations. Check
`flyway_schema_history` after any unusual first boot.

---

## Onboarding the first college

A fresh deployment has no colleges, and until one exists nobody except the
platform operator can sign in: self-registration resolves a student to a college
by their email domain, and with no colleges there is nothing to resolve against.

**This does not require SQL, and must not be done with SQL.** Creating the tenant
boundary row by hand skips validation, skips the audit record, and gets the
email domain wrong in ways that are silent — a domain stored as
`@northgate.edu` or `https://northgate.edu` never matches anything, so every
student is turned away at registration with nothing in the logs explaining why.

### The sequence

1. **Platform operator.** Set `CAREERFLUX_ADMIN_EMAIL` and
   `CAREERFLUX_ADMIN_PASSWORD` in `.env` before the first start; the account is
   created on boot. **Remove both afterwards.** This is the one account that
   still has to be bootstrapped from outside the application, because there is
   nobody to create it — noted here rather than solved with a public
   registration path, which would be a far worse answer.
2. **Sign in** as that operator and open **Institutions**.
3. **Create the college**: its name, and the email domain its students use.
   - The domain is a bare host — `northgate.edu`. Not a URL, not an address. A
     leading `@` is accepted and removed; anything else is refused with a message
     saying what was expected.
   - A college whose students do not have institutional addresses can be given a
     **registration code** instead, which the placement office hands out.
   - One or the other is required. A college with neither is one nobody can
     register against, and creation refuses it.
4. **Create the first college administrator** in the same step, if you have their
   details. Set an initial password and pass it to them over some channel that
   is not the screen you typed it on; ask them to change it. The password is
   hashed immediately and is never shown again by anything.
5. **Hand over.** The operator is *not* signed in as the college administrator
   and does not enter the college's data. The administrator signs in themselves.

### Afterwards

Students and staff at that domain can register normally — `/register` resolves
them to the college by their address. Somebody at another domain is still
refused, which is the point.

The college starts **empty**: no departments, no batches, no students, no
requirements. That is deliberate; a tenant that arrives pre-populated with
plausible fixtures is how demonstration data ends up mistaken for real records.

> **Departments and batches cannot yet be created through the application.** The
> permissions exist and a college administrator holds them, but the endpoints are
> read-only, so there is no screen for it. A college can be onboarded and its
> students can register, sign in and use CareerFlux; what does not work yet is
> targeting a company requirement at a named department or graduation year,
> because there are none to name. This is a product gap, not a deployment one.

---

## Security headers

The frontend nginx sets these on every response it serves — the SPA shell, the
hashed assets, and the fallback that makes a deep link survive a refresh:

| Header | Value |
|---|---|
| `X-Content-Type-Options` | `nosniff` |
| `X-Frame-Options` | `DENY` |
| `Referrer-Policy` | `strict-origin-when-cross-origin` |
| `Content-Security-Policy` | `default-src 'self'` plus Google Fonts, `data:` images, and nothing else |

The CSP is written against what the built bundle actually loads and was verified
by serving `dist/` with the header and watching for violations: the landing page,
a deep link and the sign-in form all render clean. It needs neither
`'unsafe-inline'` nor `'unsafe-eval'`, which is the point — the app keeps its
tokens in `localStorage`, so an injected script would be able to read them, and
`script-src 'self'` is what stops one running.

Two things will break it, both deliberate to notice:

- **Splitting the frontend and API onto different origins.** `connect-src 'self'`
  assumes the same-origin `/api` proxy in this repository. A split deployment
  must add the API origin.
- **Adding a third-party script** — analytics, a chat widget, a font host other
  than Google's. Each needs its origin in the matching directive.

`Strict-Transport-Security` is **not** set here, on purpose. Only the TLS
terminator knows whether the request was really HTTPS; see below.

---

## HTTPS

**Nothing in this repository terminates TLS, holds a certificate, or knows the
college's domain.** That is deliberate: a certificate cannot be invented and a
hardcoded domain would be wrong everywhere except one place.

The frontend container publishes on `127.0.0.1:8081` by default, so it is not
reachable from the network until something in front of it is. Choose one:

**Option A — reverse proxy on the same host** (recommended for a college server).
Caddy or nginx on the host, holding the certificate, proxying to
`http://127.0.0.1:8081`. Caddy obtains and renews a certificate from Let's
Encrypt on its own, which is the least for anybody to remember.

The proxy must:

- forward the `Host` header,
- set `X-Forwarded-Proto https`,
- **overwrite** `X-Forwarded-For` with the client address rather than appending
  to it, and
- be declared to the frontend nginx so it will believe that header. Drop a file
  into the container at `/etc/nginx/realip/trusted.conf` containing
  `set_real_ip_from <proxy CIDR>;` — mount it read-only in an overlay.

Without that last step every request appears to come from the proxy. The sign-in
rate limiter still works — it is keyed on the account as well — but it stops
telling sources apart.

**Option B — a hosting platform terminates TLS.** Set
`CAREERFLUX_FRONTEND_BIND=0.0.0.0` so the platform can reach the container, and
make sure the host firewall exposes only that port. The same `X-Forwarded-For`
requirement applies.

Either way the backend stays unpublished. It is configured to believe
`X-Forwarded-For` (`CAREERFLUX_FORWARD_HEADERS=framework`), which is safe
*because* nothing can reach it except through nginx. **If you ever publish port
8080, turn that setting off in the same change** — otherwise a caller can set the
header themselves and present a new address per request.

---

## Kafka

One broker, in KRaft mode, with a persistent volume (`kafka-data`). The backend
reaches it on the internal listener `kafka:29092`; the published
`127.0.0.1:9092` exists for an operator with the console tools.

Topics are created on demand (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`). The
consumer group is `careerflux-ingestion` and the producer uses `acks=all`.

> **This pilot Kafka deployment is not highly available.** One broker, so a
> replication factor of one, so no redundancy: if the broker is lost, unconsumed
> events in it are lost with it. It is a single point of failure and is not
> presented as anything else.

That is survivable because Kafka is not on the critical path — and the pilot no
longer takes the path at all. `SPRING_PROFILES_ACTIVE` is `postgres`, so pipeline
events go through the in-process transactional outbox, with the same topic names
and the same handlers. The outbox is the better fit at this size for a reason
beyond one less service to run: it writes the event in the same transaction as
the data it describes, so there is no window in which a job exists and the event
announcing it does not.

The broker is still defined in the base compose file and will still start, since
it declares no compose profile; nothing connects to it. To leave it stopped,
add `--scale kafka=0` to the `up` command. To go back to Kafka, add `,kafka` to
`SPRING_PROFILES_ACTIVE` and restore the backend's `depends_on`.

Check it with the command in the runbook.

---

## Resume storage

Uploaded resumes are **not** in the database. They are files in the `resume-data`
named volume, mounted at `/app/data/resumes`, laid out as
`<candidateId>/<random-uuid><extension>` — the uploaded filename is discarded and
kept in the database for display only.

The pilot overlay sets `CAREERFLUX_RESUME_DIR=/app/data/resumes` explicitly. The
default resolves to the same place, but it is derived from the container's
working directory, and naming it means a future `WORKDIR` change cannot quietly
move uploads off the volume and onto the container filesystem — where they would
vanish on the next restart, one file at a time, with nothing logged.

- **Never** bind-mount this into the frontend container. It is candidate data and
  nginx would serve it.
- The volume survives `down` and `restart`. `down -v` destroys it.
- Back it up with the database, in that order — see below.

---

## Render

The pilot's managed alternative to running Compose on a VM. Same application,
same profile, same schema; what changes is who runs the container and where the
disk comes from.

### Topology

```
                        INTERNET
                            |
                       HTTPS (Render)
                            |
        +-------------------------------------+
        |  careerflux-frontend   (web, public) |   nginx
        |    serves the built SPA at /         |   security headers + CSP
        |    proxies /api  ------------------+ |   client_max_body_size 10m
        +-----------------------------------|-+
                                            | Render private network
        +-----------------------------------v-+
        |  careerflux-backend  (pserv, private)|  no public address at all
        |    SPRING_PROFILES_ACTIVE=postgres   |  single instance
        +----+--------------------------+------+
             |                          |
   +---------v---------+     +----------v----------+
   |  careerflux-db     |     |  disk               |
   |  Render PostgreSQL |     |  /app/data/resumes  |
   +--------------------+     +---------------------+
```

The backend is a **private service**. It has no Render URL, so the only way in
is through nginx, which is the same property the Compose overlay gets by
publishing no backend port. The browser still sees one origin and `/api` is
still same-origin, so no CORS preflight and no API-URL mechanism in the bundle.

`render.yaml` in the repository root declares all three resources. It carries no
secrets — everything sensitive is `sync: false`, which makes Render ask you for
the value instead.

### What differs from Compose, and why

**The nginx proxy, and the four values it needs.** `frontend/nginx.conf` is
installed as an nginx *template*; the image defaults every variable to its
Compose value, so Compose is unaffected. Only names starting `CAREERFLUX_` are
substituted — `NGINX_ENVSUBST_FILTER` in the Dockerfile — so nginx's own
`$host`, `$remote_addr` and `$uri` are left alone. Without that filter the
default entrypoint would blank every one of them and produce a config that
starts and then misbehaves.

| Variable | Compose | Render |
|---|---|---|
| `CAREERFLUX_BACKEND_ORIGIN` | `http://backend:8080` | `https://<backend>.onrender.com` |
| `CAREERFLUX_BACKEND_HOST` | `$host` | `<backend>.onrender.com` |
| `CAREERFLUX_FORWARDED_PROTO` | `$scheme` | `https` |
| `CAREERFLUX_LISTEN_PORT` | `80` | `10000` |

**The scheme is part of the origin.** Render serves the backend only over HTTPS
and answers plain HTTP with a redirect, so `http://` cannot be hardcoded in
front of the value. It once was, and setting the variable to a full `https://`
URL then produced `http://https://...` and `invalid port in upstream`.

**The Host header is the one that bites.** Render's edge routes on Host. Leave
it as the browser's host and the edge routes the request back to the *frontend*,
which proxies it again — a loop that surfaces in the browser as
`ERR_TOO_MANY_REDIRECTS` and leaves nothing in any log naming nginx or Spring.
If you ever see that error on `/api`, check this first.

**The forwarded scheme is stated, not inherited.** TLS terminates at Render's
edge, so `$scheme` inside the container is `http`; passing it would tell the
backend the session was never encrypted. It is set as a value rather than read
from an inbound `X-Forwarded-Proto`, for the same reason `X-Forwarded-For` is
overwritten: a header a client can write is not evidence of anything.

**Free tier spins the backend down.** After a period of inactivity the first
request has to wait for a cold start — a Spring Boot boot plus Flyway, which can
take longer than a browser will wait. It is not a fault and there is nothing to
fix in the application; it is what the free tier is. A paid instance type is the
only cure.

**The listen port.** Render expects a web service on its own port, so
`CAREERFLUX_LISTEN_PORT` is set to `10000` there and stays `80` everywhere else.

**Nothing else.** No application code differs between the two.

### Setting it up

1. **Create the database first.** Its internal connection details are needed by
   the backend, and Render only shows them once the database exists. Put it in
   the same region as everything else; the private network does not cross
   regions.

2. **Deploy the backend** as a private service from `backend/Dockerfile`, with a
   disk mounted at exactly `/app/data/resumes`. Attach the disk *before* the
   first upload — a disk added later starts empty, and the database rows that
   already point into it will not find their files.

3. **Deploy the frontend** as a web service from `frontend/Dockerfile`.

4. **Set `CAREERFLUX_CORS_ORIGINS`** to the frontend's Render URL once Render has
   assigned it, then redeploy the backend. Until this is right, every GET works
   and every write fails with a bare "Invalid CORS request" — the app looks
   broken in a way that does not obviously point at CORS.

5. **Create the first administrator** with `CAREERFLUX_ADMIN_EMAIL` and
   `CAREERFLUX_ADMIN_PASSWORD`, then clear both and redeploy so the password
   stops living in the service environment.

### The database URL

Render gives you a `postgresql://` URL. **Spring does not accept that form.**
Build the JDBC one by hand from the same host, port and database name:

```
jdbc:postgresql://<internal-host>:<port>/careerflux
```

and set `DATABASE_USERNAME` and `DATABASE_PASSWORD` separately. The `postgres`
profile gives those two **no fallback**, so a deployment that forgets them fails
to start rather than quietly connecting to whatever answers.

Flyway runs V1 to V15 on first boot against the empty database. Nothing else is
required; there is no dump to load and no manual schema step.

### Backups on Render

**`scripts/backup.sh` does not work on Render and must not be scheduled there.**
It shells out to `docker exec` for `pg_dump` and mounts a named Docker volume to
archive resumes. Render gives you neither a Docker socket nor a named volume, so
the script would fail on its first line — or, worse, be believed.

Use instead:

- **Database** — Render's own PostgreSQL backups. Paid instances take daily
  snapshots and support point-in-time recovery; check the retention on the plan
  you chose and write it down, because "we have backups" and "we can restore to
  Tuesday" are different claims.
- **Resume files** — Render disk snapshots, on the disk's own page.

Two things follow from splitting the two, and both matter:

- **They are not taken at the same instant.** The Compose script dumps the
  database first on purpose, so the worst case is a file nothing references
  rather than a row whose file is missing. Independent snapshots give no such
  ordering, so after any restore expect a small window where the two disagree.
  A resume row whose file is absent surfaces as a 404 on download, which the
  application already handles as "that file is no longer stored" rather than an
  error page.
- **Restoring one without the other is a decision, not an accident.** Restore
  the database to a point *at or before* the disk snapshot if you have to choose,
  since a missing row is invisible and a missing file is not.

Neither is configured by this repository. **Turn both on in the dashboard before
the first real upload** — this is the single most consequential manual step, and
nothing in the application will warn you that it was skipped.

### Known differences in behaviour

**Sign-in rate limiting loses its per-address component.** Render's edge sets
`X-Forwarded-For`, but our nginx does not trust incoming forwarded headers by
default — it overwrites the header with the peer address, which on Render is
Render's own proxy. Every request therefore looks like it comes from one
address. The limiter still bounds attempts **per account**, which is the half
that stops password guessing; what is lost is distinguishing sources.

That default is deliberate and is the safe one: trusting a forwarded header you
cannot attribute lets an attacker present a fresh address per request and walk
straight past the limiter. To restore the per-address component, add a
`set_real_ip_from` line for Render's proxy range under `/etc/nginx/realip/` —
and only with a range you have confirmed, never a guess.

**Actuator on a public backend.** `/actuator` is not proxied by nginx, so it is
not reachable through the app's own origin. On the free tier the backend is a
public web service and therefore has an address of its own, where
`/actuator/health` and `/actuator/info` answer — both are already `permitAll`,
and `show-details: when-authorized` means neither describes the infrastructure.
`/actuator/metrics` and everything else still require a token. Making the
backend a private service on a paid plan removes even that surface.

---

## Backup

**This section is the Compose deployment.** On Render, see *Backups on Render*
above — `scripts/backup.sh` cannot run there and must not be scheduled.

`scripts/backup.sh` takes both copies, verifies the dump is complete rather than
merely present, writes `last-success.txt` as evidence, and prunes past 14 days.

**Order: database first, resumes second.** `resumes.storage_path` points at a
file in the volume. Database first means a resume uploaded mid-run is a file
nothing references — invisible, and already handled. The other order gives a row
whose file does not exist, which a student sees as their own resume failing to
open.

### Scheduling

Every six hours, which is the stated recovery point. **Until this is installed,
six hours is an intention, not a policy.** Pick the mechanism that matches the
host — do not assume:

```bash
systemctl --version >/dev/null 2>&1 && echo "systemd available" || echo "use cron"
```

- **systemd**: copy `deploy/systemd/careerflux-backup.service` and `.timer` to
  `/etc/systemd/system/`, adjust `User` and paths, then
  `systemctl enable --now careerflux-backup.timer`. Preferred where available:
  `Persistent=true` runs a backup missed while the host was off, which cron does
  not.
- **cron**: `deploy/systemd/crontab.example`. Note the explicit `PATH` — cron's
  default environment frequently cannot find `docker`, and the job then fails
  silently every six hours.

Use one or the other, never both.

Confirm it is real after the first two runs:

```bash
cat /var/backups/careerflux/last-success.txt
```

A `completed_at` older than seven hours means backups have stopped.

### What is in the archives

The dump contains student and placement data, including BCrypt password hashes.
It does **not** contain the JWT secret, the Gemini key or the database password —
those are environment values and were never written to a table. Treat the backup
directory as sensitive: restrict it to the deploying user and keep it off any
public share.

---

## Restore

The full drill, with measured timings and the verification query, is in
`docs/RUNBOOK.md`. Restore into a **fresh** database and switch to it; never
restore over a running one.

---

## Health checks

```bash
curl -s http://127.0.0.1:8081/healthz              # nginx is serving
docker inspect --format '{{.State.Health.Status}}' careerflux-backend
docker exec careerflux-backend wget -qO- http://localhost:8080/actuator/health
```

`UP` means the process is serving **and the database is reachable** — the only
dependency treated as critical, because nothing works without it.

Kafka and AI are deliberately **not** part of health, because both have working
degradation paths and neither should take the application out of a load balancer:

- **AI unavailable** → deterministic fallbacks for resume parsing and match
  narratives. Discovery, scoring and eligibility never used AI at all.
- **Kafka unavailable** → the application keeps serving; the consumer reconnects.

Component detail requires authentication; unauthenticated callers get the status
and nothing about the infrastructure.

---

## Startup and shutdown

```bash
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full up -d
docker compose -f docker-compose.yml -f docker-compose.pilot.yml --profile full stop
```

Shutdown is graceful: `server.shutdown: graceful` with a 25-second phase timeout,
so in-flight work finishes before the port closes. Candidate discovery over a
full college is the longest request the system serves, and cutting it off mid-way
leaves a placement officer unsure whether a decision was recorded.

`restart: unless-stopped` on every container, so the stack returns after a host
reboot. **`docker compose down -v` destroys the database and every uploaded
resume.** It is not a restart.

---

## Sizing

Measured on the pilot corpus (1,948 jobs, 7,598 job skills, 5,758 matches, ~2,000
students in load testing), not extrapolated from anything:

| | Observed |
|---|---|
| Database dump | 69 MB |
| Candidate discovery, warm | ~600 ms; ~1.9 s cold |
| Discovery at 16 concurrent | p95 2.25 s, 0% errors |
| Connection pool | 10, saturates at 16+ concurrent and degrades by queuing |
| Backend heap | `-XX:MaxRAMPercentage=70`, so it adapts to the limit it is given |

A reasonable starting host is **4 GB RAM and 2 vCPU** with **20 GB** of disk, and
that is a starting point rather than a measurement: it is not derived from
running the four containers together under sustained real load, because that has
not been done. Watch the first weeks and adjust.

Kafka is the most memory-hungry container for the least benefit at pilot scale.
If the host is tight, running without the `kafka` profile is a supported
configuration.

No CPU or memory limits are set in compose. Adding them without evidence would
be guessing at numbers that then look authoritative.

---

## Known limitations

Accepted for a first pilot. All of them are real:

- **Rate limiting is per instance.** Counters live in the backend's memory, so a
  second instance would double every ceiling. Restarting clears them.
- **The database is a single instance** with no replica. Recovery means restoring
  a backup.
- **Kafka is a single broker** and is not highly available.
- **The connection pool is 10** and single-instance; discovery degrades by
  queuing under load rather than failing.
- **No RTO has been agreed.** Mechanical restore takes seconds; detection and
  cutover are human and unmeasured.
- **HSTS is not set here**, and must not be until HTTPS is genuinely enforced for
  the domain — see "HTTPS". It belongs on the TLS terminator, which is the only
  component that knows the scheme was really secure. Add
  `includeSubDomains` only once every subdomain is on HTTPS, and `preload` only
  deliberately: it is effectively irreversible.
- **No monitoring or alerting.** The backup marker file is the only automatic
  evidence of anything.

---

## Troubleshooting

**Compose exits immediately naming a variable.** Working as intended. Set it.

**Backend will not start, log says the JWT secret is the development value.**
Also intended. `CAREERFLUX_JWT_SECRET` is not set, or `.env` is not where compose
is looking.

**`Could not resolve placeholder DATABASE_PASSWORD`.** There is no default. Set
it.

**Everything loads but nothing saves.** Reading works, every write fails, and
the message is only "That request could not be completed". Almost always
`CAREERFLUX_CORS_ORIGINS` not matching the origin in the address bar. Confirm it
from the browser console on the affected page:

```bash
docker exec careerflux-backend printenv CAREERFLUX_CORS_ORIGINS
```

Compare that against `window.location.origin`. They must match exactly —
`http://localhost:8081` and `http://127.0.0.1:8081` are two different origins,
as are `https://placements.college.edu` and `https://www.placements.college.edu`.

**Frontend loads but every API call fails.** nginx proxies `/api` to
`backend:8080` over the compose network. Check the backend is healthy
(`docker compose ps`) — nginx starts whether or not it is.

**A deep link 404s on refresh.** `try_files` should make `/app/discover` return
`index.html`. If it does not, the container is serving a stale config: rebuild
rather than editing inside the container.

**Everyone appears to sign in from the same address.** A proxy in front is not
declared to nginx. See "HTTPS", Option A, the `/etc/nginx/realip` step.

**Every candidate shows eligibility UNKNOWN.** Expected when no verified CGPA
exists. A student's own figure never counts. Correct behaviour, not a fault.

**A student is told their institution could not be worked out.** Either no
college claims their email domain, or the domain was entered in a form that
never matches. Check **Institutions** — the stored domain must be a bare host,
and the student's address must end in it.

Further symptoms are in `docs/RUNBOOK.md`.
