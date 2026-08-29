# CareerFlux

A career intelligence platform. You tell it what career you want; it works out
which employers and which sources matter, checks whether it is actually allowed
to read them, watches them for change, and explains why each role fits you.

It is not a job board and not a chatbot. The differentiator is the layer nobody
else shows you: **where the jobs came from, and whether we should have been there.**

---

## What it actually does

Three intelligence layers, each answering a question a job board does not ask.

**Source intelligence.** Every place CareerFlux collects from is a first-class
record with a lifecycle, an access policy and a health history. A source cannot
be read until `robots.txt` permits the exact path, a named person has reviewed
the terms, and no access control would have to be bypassed. Login walls,
CAPTCHAs, anti-bot protection and paywalls are permanent disqualifiers.

**Job intelligence.** Postings are normalized into one vocabulary, deduplicated
across sources on several signals, enriched with structured attributes, and
watched for change. A duplicate is never deleted — it becomes another
*observation* of the same canonical job, so provenance survives.

**Candidate intelligence.** Matching is deterministic and explains itself. Every
score is five weighted sub-scores plus the specific strengths and gaps behind
them. No model decides the number.

---

## Running it

### What you need

| | |
|---|---|
| Java | 21 or newer (built and tested on 22) |
| Maven | 3.9+ |
| Node | 20 or newer (tested on 22) |
| Database | Nothing — the dev profile uses embedded H2. PostgreSQL optional. |

### 1. Set the environment

```bash
cp .env.example .env
```

Then set at minimum a JWT secret:

```bash
export CAREERFLUX_JWT_SECRET="$(openssl rand -base64 48)"
```

On Windows PowerShell:

```powershell
$env:CAREERFLUX_JWT_SECRET = "at-least-32-random-characters-goes-here"
```

### 2. Start the backend

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo
```

It comes up on **http://localhost:8080**. The `demo` profile registers a bundled
sample source, walks it through the real lifecycle, and ingests ten sample
postings so there is something to look at. Everything from it is typed
`LOCAL_FIXTURE` and labelled *Sample data* throughout the UI — it is never
presented as a real employer feed.

Drop `,demo` to start with an empty registry.

### 3. Start the frontend

```bash
cd frontend && npm install && npm run dev
```

Open **http://localhost:5173**. The dev server proxies `/api` to the backend, so
the browser only ever talks to one origin.

### 4. Create an account

Register through the UI. The onboarding flow takes a resume, shows you what it
extracted so you can correct it, asks what you are looking for, and then scores
the corpus.

---

## Optional: turn AI on

```bash
export GEMINI_API_KEY="your-google-ai-studio-key"
```

That is the whole setup. The application detects the key at startup and enables
Spring AI's Gemini integration. **Never put the key in `application.yml` or in
source** — it is read from the environment only, and `.env` is gitignored.

With AI on, resumes are read by the model and match explanations are written by
it. With AI off everything still works: resume parsing falls back to a
deterministic extractor and explanations are assembled from the score. The UI
says which path produced what, on every screen where it matters.

AI is deliberately kept away from anything that must be deterministic —
authentication, source state transitions, access policy, deduplication rules,
scheduling, rate limiting, and the match arithmetic itself.

## Optional: create an administrator

```bash
export CAREERFLUX_ADMIN_EMAIL="you@example.com"
export CAREERFLUX_ADMIN_PASSWORD="a-password-of-at-least-10-characters"
```

The account is created on the next start. There is no default admin credential:
a fixed password shipped in the source would be a backdoor.

## Optional: PostgreSQL instead of H2

```sql
CREATE DATABASE careerflux;
```

```bash
export DATABASE_URL=jdbc:postgresql://localhost:5432/careerflux
export DATABASE_USERNAME=postgres
export DATABASE_PASSWORD=postgres
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=postgres,demo
```

The Flyway migrations are portable SQL that runs unchanged on both. Nothing in
the application code differs between the two.

## Optional: Kafka instead of the in-process bus

```bash
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev,demo,kafka
```

Requires a broker at `KAFKA_BOOTSTRAP_SERVERS`. The topic names, the payloads
and the handlers are identical either way — see
[docs/architecture.md](docs/architecture.md#ingestion-pipeline) for why the
abstraction exists.

---

## Tests

```bash
cd backend && mvn test
```

150 tests. They cover the things where a bug would actually matter: the source
state machine, the policy gate, `robots.txt` matching, job normalization,
deduplication across sources, the scoring rules, notification routing,
authentication, authorization and candidate data isolation.

There are no tests written to move a coverage number.

```bash
cd frontend && npm run typecheck
```

---

## Layout

```
careerFlux/
├── backend/                     Spring Boot application
│   └── src/main/java/com/careerflux/
│       ├── ai/                  The single boundary to any language model
│       ├── audit/               Append-only record of defensible decisions
│       ├── auth/                Registration, login, tokens
│       ├── candidate/           Profile, resume, preferences
│       ├── dashboard/           The home screen read model
│       ├── engagement/          Saved, dismissed, applied
│       ├── ingestion/           Pipeline, events, transports
│       ├── job/                 Canonical jobs, observations, changes
│       ├── matching/            Scoring and explanation
│       ├── notification/        Alert routing
│       ├── skill/               Canonical skill dictionary
│       └── source/              Registry, policy engine, adapters, health
├── frontend/                    React application
│   └── src/
│       ├── components/          UI primitives and domain components
│       ├── lib/                 API client, auth, queries, formatting
│       ├── pages/               One file per screen
│       └── styles/              Design tokens and component styles
└── docs/
    └── architecture.md          Why it is built this way
```

---

## Where the seams are

Honest notes on what is and is not finished, so nothing here is mistaken for
more than it is.

- **Three real source adapters** — Greenhouse, Lever and Ashby — each reading a
  documented public endpoint the provider publishes for syndication. Plus a
  local fixture adapter for development. This is deliberately a small, legitimate
  set rather than a scraper framework.
- **Email is not wired up.** Password reset issues a real token; outside
  production the token is returned to the caller rather than pretending a message
  was sent.
- **Salary is stored and displayed but is not part of the match score.** It is
  stated as a preference and shown on jobs; the scorer does not yet use it.
- **Semantic matching is not implemented.** Scoring is deterministic rules, which
  is what makes it explainable. Embeddings would be the next step, behind the
  same explanation contract.
- **Redis is not used, and is not in the stack.** Caching and rate limiting are
  in-process, which is correct for one node. The moment ingestion runs on more
  than one instance, the rate limiter needs a shared counter — that is the point
  at which Redis earns its place, and not before. It was briefly present in
  `docker-compose.yml` as a container nothing connected to, which implied an
  architecture that did not exist; it has been removed rather than given
  make-work.
- **Docker Compose runs Postgres and Kafka only.** No Kubernetes or Terraform —
  they would be diagram decoration at this size.

---

## Git

This repository has no automated commits, pushes or CI. Version control is
yours to drive.
