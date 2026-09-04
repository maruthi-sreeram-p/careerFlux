# CareerFlux — deployment smoke test

Run after every deployment, before telling anyone it is ready.

```bash
BASE_URL=https://your-pilot-origin ./scripts/smoke-test.sh
```

That script covers the checks a machine can make honestly. The rest need a
person, and are marked **manual** — not because automating them is hard, but
because a script clicking through a shortlist would report success without
anybody having looked at whether the screen made sense. A deployment is not
verified until somebody has signed in.

Add `SMOKE_EMAIL` and `SMOKE_PASSWORD` to include an authenticated round trip.
Use an operator account, not a student's.

---

## Automated — `scripts/smoke-test.sh`

| # | Check | What a failure means |
|---|---|---|
| 1 | Frontend responds and serves the SPA shell | nginx is down, or the build did not land in the image |
| 2 | `/healthz` responds | Container healthcheck will flap |
| 3 | Deep links (`/app`, `/app/discover`, `/app/profile`) return the shell | `try_files` is missing — every browser refresh 404s |
| 4 | `/api/auth/me` returns 401 | 404 = nginx is not proxying; 502 = backend down; 401 = the whole path works |
| 5 | Unknown API path returns a clean 404 | Error handling is leaking or the proxy is misrouted |
| 6 | Actuator, Swagger, `/v3/api-docs`, H2 console unreachable from the public origin | Developer tooling is exposed |
| 7 | `.map` files refused | Frontend source is downloadable |
| 8 | Security headers present, no version in `Server` | Header inheritance was dropped in an nginx block |
| 9 | Backend actuator health `UP`, no component detail unauthenticated | Database unreachable, or health is describing the infrastructure |
| 10 | Active profiles exclude `demo` and `test` | The pilot is running development configuration |
| 11 | Seeding and scheduler flags are `false` | Sample jobs may appear in a real corpus |
| 12 | Sign-in is rate limited | The limiter is not wired |
| 13 | Optional authenticated round trip | Sign-in or JWT handling is broken |
| 14 | Schema at V12, no failed migrations | Flyway did not complete |

The rate-limit check deliberately probes an address at `example.invalid`, so
smoke-testing a deployment can never lock a real student out of their account.

---

## Manual

Sign in as each role and confirm the screen is right. Roughly ten minutes.

### As the platform operator — do this first

On a brand-new deployment nothing else can be checked until a college exists.

- [ ] **Sign in** as the operator created from `CAREERFLUX_ADMIN_EMAIL`.
- [ ] **Institutions** lists the colleges, or says there are none yet.
- [ ] **Create institution** with a name and an email domain. It appears in the
      list, ACTIVE, with the domain stored as a bare host.
- [ ] **A bad domain is refused** — try `https://example.edu`. The message should
      say what shape was expected rather than failing silently.
- [ ] **A duplicate domain is refused** — try the same domain twice.
- [ ] **First college administrator** created alongside the college, if you have
      their details. You are *not* signed in as them afterwards.
- [ ] **Confirm the college starts empty**: no departments, batches or students.

### As a student

- [ ] **Sign in** — the dashboard loads and greets the right person.
- [ ] **Profile** — edit and save; reload and confirm it persisted.
- [ ] **Resume upload** — upload a PDF. It appears in the list. Download it back
      and confirm it is the same file.
- [ ] **Resume persistence** — restart the stack (`stop` then `up -d`, *not*
      `down -v`) and confirm the resume is still there and still downloads. This
      is the check that catches resume storage having been mounted somewhere
      ephemeral, which is silent until it is not.
- [ ] **Job discovery** — matches are listed with scores that look sane.
- [ ] **Apply link** — opens the employer's real posting in a new tab, and the
      URL is the employer's, not CareerFlux's.
- [ ] **AI fallback** — with no `GEMINI_API_KEY` set, resume parsing and match
      narratives still work and the UI says which path it used. Absence of a key
      is a supported mode; if anything errors, that is a bug.

### As a placement officer

- [ ] **Sign in** — the officer dashboard loads, not the student one.
- [ ] **Create a company requirement** — it saves and appears in the list.
- [ ] **Candidate discovery** — students are listed against that requirement,
      with technical compatibility and eligibility shown separately.
- [ ] **Shortlist** — add a candidate, then remove them. The count updates. No
      candidate was ever shortlisted without somebody clicking.
- [ ] **Scope** — a coordinator sees only their own department's students; an
      officer sees the institution. Changing an id in the URL returns 404, not
      somebody else's data.

### As a coordinator

- [ ] **Sign in** — the requirements list loads.
- [ ] **Open a requirement** in scope, then **Find candidates**. Only their own
      department's students appear.
- [ ] **Shortlist** one of them. It succeeds — a coordinator holds
      `PLACEMENT_SHORTLIST_MANAGE`.
- [ ] **Remove** them again. The count updates.
- [ ] **No authoring** — there is no way to create, edit, publish or close a
      requirement. That needs `PLACEMENT_DRIVE_MANAGE`, which a coordinator does
      not hold, and the server refuses it regardless of what the screen shows.

### As a college admin

- [ ] **Sign in** — the admin dashboard loads.
- [ ] **Institution → Departments & staff** is in the sidebar and opens.
- [ ] **Departments** — the list loads; adding one succeeds and it appears.
- [ ] **Batches** — the list loads; adding one succeeds. A mistyped year such as
      `20267` is refused before it is sent.
- [ ] **Staff** — the list shows this college's staff with the scope each covers,
      and **no password hash or reset token**. Adding a coordinator asks for a
      department and refuses without one; adding an officer does not ask.
- [ ] **No placement workflow** — there is no Company requirements link, and this
      role cannot shortlist. That is deliberate: a college admin holds neither
      `PLACEMENT_DRIVE_MANAGE` nor `PLACEMENT_SHORTLIST_MANAGE`.
- [ ] **Academic data** — recording a CGPA is refused for this role; it belongs
      to the placement officer.

### As a platform admin

- [ ] **Sign in** and reach the platform console.
- [ ] **Sources** are listed. **Do not enable the scheduler** to test this.

### Operations

- [ ] **Kafka event flow** — the consumer group is `Stable` with lag at or near
      zero (command in `docs/RUNBOOK.md`).
- [ ] **Backup** — run `scripts/backup.sh` once by hand. Confirm
      `last-success.txt` appears with a current timestamp, a plausible byte
      count, and the retention counts you expect.
- [ ] **Restore** — restore into a scratch database and compare row counts. Do
      this at least once before real students depend on the system, and once a
      term after. A backup nobody has restored is a guess.
- [ ] **Restart** — `stop` then `up -d`. Everything returns, resumes intact,
      database intact, no migration re-runs.
- [ ] **Graceful shutdown** — the backend log shows shutdown, not a kill. No
      request is cut mid-response.
- [ ] **Logs contain no secrets** — grep the backend log for the JWT secret, the
      database password and the Gemini key. All three must be absent.

---

## After the first week

- [ ] `last-success.txt` timestamp is under seven hours old. If it is not, the
      schedule was never installed or has been failing silently — which is the
      single most likely way this deployment quietly stops being recoverable.
