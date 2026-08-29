# CareerFlux architecture

Why the system is built the way it is. Every section here is a decision someone
could reasonably have made differently, with the reasoning attached.

---

## The shape

```
React (Vite)
     │  /api  — proxied in dev, one origin in production
     ▼
Spring Boot ────────────────► PostgreSQL (or H2 in dev)
     │
     ├── Source adapters ───► public ATS APIs
     │
     └── Pipeline event bus ─► in-process outbox  (default)
                             └ Apache Kafka       (profile: kafka)
```

One deployable backend. No microservices, no separate Python service, no
container orchestration. Those would be architecture theatre at this size, and
the brief asked specifically not to build them.

---

## Source intelligence

This is the part that makes CareerFlux something other than a job board, so it
gets the most structure.

### The state machine

```
DISCOVERED → CLASSIFIED → POLICY_REVIEW → APPROVED → ACTIVE
                                                       │
                                          DEGRADED ◄───┤
                                     PENDING_REVIEW ◄──┘

BLOCKED and RETIRED sit off the path. BLOCKED can be re-reviewed;
RETIRED is terminal.
```

Transitions are enumerated in `SourceState.allowedTransitions()` rather than
implied by scattered conditionals. `SourceLifecycleService` is the only way a
source changes state, and it refuses anything the machine does not permit.

**Why a state machine at all.** The question "why is CareerFlux reading this
site?" needs an answer with a timestamp and a person's name on it. A boolean
`is_active` column cannot produce that. Every transition writes a
`source_lifecycle_events` row with its reason and its actor.

### The policy gate

`SourcePolicyEngine` decides whether a source may be read. Deterministic, no
model involved, and conservative by construction: a fact that has not been
established counts against the source, not for it.

| Gate | Rule |
|---|---|
| Access controls | Authentication, CAPTCHA, anti-bot, paywall — any one is an absolute disqualifier |
| robots.txt | Must permit the exact path we would fetch. Unreadable is treated as refusal |
| Terms of service | Must be reviewed by a named person and must not prohibit automated access. Ambiguous counts as prohibited |
| Access route | Must be a public API, a syndication feed, or a permitted public page |
| Adapter | Must exist, otherwise "active" would be a lie |
| Rate limit | Must be configured, and must respect any declared crawl-delay |

The gate is enforced in `SourceLifecycleService.transition()` on the way into
`ACTIVE`, and re-checked in `IngestionService` before any adapter runs. That
second check is redundant by design — ingestion is the last place it can be
stopped.

**Why "unclear terms" is a refusal.** The alternative is a system that reads
things nobody decided it could read. Blocking a source that turns out to be fine
costs a review; reading one that turns out not to be costs rather more.

### robots.txt

`RobotsTxtService` implements the parts of the exclusion protocol that decide
this question: user-agent group selection with a fallback to `*`, `Allow` and
`Disallow` with longest-match-wins, the `*` and `$` wildcards, and `Crawl-delay`.

It errs toward refusal. A file that cannot be fetched or parsed blocks the
source rather than being ignored.

### Adapters

```java
public interface JobSourceAdapter {
    SourceMetadata getMetadata();
    boolean supports(SourceConfiguration configuration);
    List<RawJobPosting> fetchJobs(SourceConfiguration configuration);
    SourceHealthResult checkHealth(SourceConfiguration configuration);
    default String probeUrl(SourceConfiguration configuration) { ... }
}
```

Four registered: Greenhouse, Lever, Ashby, and a local fixture. Each real one
reads a documented, unauthenticated endpoint its provider publishes so employers
can syndicate their own postings.

Adapters do the least possible interpretation — they map the source's fields onto
common names and stop. Title normalization, seniority inference, skill
extraction and location parsing all happen later, so they are applied
consistently across every source rather than reimplemented per adapter.

Adapters deliberately cannot decide whether they are allowed to run. They receive
a `SourceConfiguration` value object rather than the JPA entity, so they cannot
mutate persistent state or wander into lazy associations.

`probeUrl` exists because robots.txt has to be evaluated against the path we
would actually request, which is usually not the source's display URL.

### Health

Health is always the result of a real probe. A source that has never been checked
stays `UNKNOWN` and the UI says *not yet checked* — never optimistically healthy.

Sustained failure has consequences: three consecutive failures degrade a source,
eight pull it out of `ACTIVE` into `PENDING_REVIEW`. Quietly serving stale jobs
from a broken feed is worse than serving none.

`reliabilityPercent` returns `-1` when nothing has been attempted, and the UI
renders that as *not measured*. Zero and "unmeasured" mean very different things
to someone deciding whether to trust a source.

---

## Ingestion pipeline

```
adapter.fetchJobs
   → normalize          deterministic field mapping
   → deduplicate        find or create the canonical job
   → detect changes     diff against what we held
   → enrich             skills and structured attributes
   → persist + emit
   → close vanished     postings the source stopped listing
```

Each stage emits an event on `PipelineEventBus`:

```
job.raw            job.enriched      job.changed
job.normalized     job.classified    job.expired
job.deduplicated                     notification.created
```

### Why an event bus abstraction

The brief asked for Kafka-based ingestion. Kafka is genuinely the right shape for
this — it is a multi-stage pipeline over a stream of external observations — but
requiring a broker to run the application on a laptop is a real cost.

So the port is `PipelineEventBus` with two implementations:

- **`InProcessEventBus`** (default) writes each event to a `pipeline_events`
  table in the same transaction as the data it describes — a transactional
  outbox — and `OutboxDispatcher` drains it, retrying failures up to five times
  before parking them as `FAILED`. That gives the same at-least-once delivery and
  idempotency a broker would.
- **`KafkaEventBus`** (profile `kafka`) publishes the identical events to the
  identical topics through Spring Kafka.

Handlers implement `PipelineEventHandler` and are registered the same way for
both. Switching transports changes the deployment shape and nothing about the
pipeline's behaviour.

The alternative — a hard Kafka dependency — would have meant the pipeline could
not be demonstrated or tested without infrastructure. The alternative in the
other direction — no abstraction, just method calls — would have thrown away an
architectural idea the product actually needs at scale.

### Deduplication

Title equality is nowhere near enough: the same opening is routinely listed as
"Backend Engineer", "Backend Engineer (Remote)" and "Backend Engineer II" across
three boards. `JobDeduplicator` uses several signals in descending confidence:

1. **Same source, same external id** — certain; the same posting seen again.
2. **Same requisition id at the same company** — the employer's own identifier.
   Searched company-wide, not within matching titles.
3. **Same normalized apply URL** — two listings sending you to one application
   form are one opening. Also company-wide. The normalized form is stored in an
   indexed `apply_url_key` column with query strings and trailing slashes
   removed, because boards append their own tracking parameters.
4. **Canonical key** — company plus normalized title plus city.
5. **Description similarity** — same company, same city, Jaccard token overlap
   above 0.72.

That threshold is set high on purpose. Merging two genuinely different openings
is a worse failure than showing a candidate a near-duplicate.

**Nothing is ever deleted as a duplicate.** A match produces another
`job_observations` row against the existing canonical job. That is what lets the
job detail page say "this appears on the company career page and on two boards,
first seen here on the 4th".

**Which record wins.** Only the source that owns an observation may rewrite the
canonical job's fields — that is the only case where a difference means the
employer actually edited something. A different source describing a job we
already hold fills genuine gaps (a salary the original omitted) but never
overwrites. Otherwise a job's title would flap between wordings depending on
which board happened to be ingested last.

### Change detection

`JobChangeDetector` diffs each observation against the stored job and records
title, location, work mode, salary and content changes, plus creation, closure,
reopening and new-source sightings. Each carries a summary written to read on its
own: *"Spring Boot requirement added"*, not a field name and two values.

`CREATED` and `NEW_SOURCE_OBSERVED` are recorded as history but excluded from the
"recently updated" count, because neither means the posting changed.

---

## Matching

Deterministic, weighted, and explained. `MatchScorer` has no model in it and no
learned weight.

| Dimension | Weight | Compares |
|---|---|---|
| Skills | 40% | Canonical skill slugs, required weighted over preferred |
| Experience | 20% | Candidate years against the stated range |
| Role | 20% | Token similarity of target roles against the normalized title |
| Location | 10% | City and work mode against stated preferences |
| Seniority | 10% | Distance on the seniority ladder |

Two properties matter more than the specific numbers:

**A dimension that cannot be evaluated scores neutral (70) and says so.** A
posting that lists no skills is not a candidate failing a skills test. The UI
shows the reason rather than an unexplained mid-range number.

**Every score carries its components.** `match_components` rows hold the
individual strengths and gaps — *"Java"*, *"0–2 years experience"*, *"AWS
production experience"* — so the question "why 92%?" is answered by data, not by
re-running the scorer. `scorer_version` records which rules produced a row, so a
rules change is identifiable rather than looking like unexplained drift.

**Where AI is allowed.** `MatchNarrator` is handed the already-computed
strengths and gaps and asked to phrase them. It never sees the inputs and cannot
change the score, so the explanation cannot drift from the arithmetic. Without a
model, the rules-based narrative is used and labelled as such.

### Concurrency

Rematches are serialised per candidate with a `ReentrantLock`. Without it, the
profile-changed listener and an explicit request from the UI — which commonly
arrive together — both miss on the find-then-insert in `scoreAndStore` and
collide on the unique `(candidate_id, job_id)` constraint. This was a real bug,
found by driving the actual UI.

### Notification routing

From the product spec, applied in `NotificationService`:

```
95–100  immediate
85–94   high priority
70–84   daily digest
below   never sent
```

Two rules keep it from becoming noise: a candidate is never told about the same
job twice, and switching off immediate alerts downgrades them to the digest
rather than dropping them.

Notifications fire from `job.classified` — a job finishing the pipeline is the
trigger. A candidate who joins later gets matches through a rematch but no
notification backlog, because fifty alerts on day one is a worse experience than
none.

---

## AI boundary

Everything a model touches goes through `AiClient`. Two implementations:
`SpringAiClient` when a `ChatModel` bean exists, `UnavailableAiClient` otherwise.
The unavailable one fails loudly rather than fabricating output, so callers are
forced onto their deterministic path.

`AiConfig` resolves the model through `ObjectProvider` rather than
`@ConditionalOnBean`, so the decision happens at bean-creation time, after Spring
AI's auto-configuration has had its chance to register one.

`AiEnablementEnvironmentPostProcessor` exists because Spring AI's Gemini
auto-configuration is gated on `spring.ai.model.chat=google-genai` with
`matchIfMissing=true` — meaning the application would fail to start with no API
key. The base configuration pins that selector to `none`, and the post-processor
flips it back on when, and only when, a key is present. The result is that
setting one environment variable turns AI on, and setting nothing runs the whole
product without it.

**What AI is used for:** reading resumes, extracting structured attributes from
job descriptions, and phrasing match explanations.

**What it is never used for:** authentication, authorization, source state
transitions, access policy decisions, deduplication rules, scheduling, rate
limiting, health assessment, or the match arithmetic. Those are the parts that
must be reproducible and defensible.

---

## Data model

Twenty-six tables. Some deliberate compressions to avoid a sprawl of near-identical ones:

- `candidate_preference_values` holds every multi-valued preference behind a
  `value_type` discriminator, instead of six parallel tables.
- `job_interactions` holds saved, dismissed, applied and viewed behind an
  `interaction_type`, instead of four.

The important structural choice is the split between `jobs` (one row per real
opening) and `job_observations` (one row per source sighting). That is what makes
provenance and multi-source deduplication possible at all.

Flyway owns the schema; Hibernate never generates DDL. The migrations are
portable SQL that runs unchanged on PostgreSQL and on H2 in PostgreSQL
compatibility mode — no `jsonb`, no arrays, no partial indexes, no `ON CONFLICT`.
That is what lets the dev profile need no database installation while the
PostgreSQL profile stays a configuration change rather than a code change.

UUID primary keys are generated by the application, so inserts do not depend on
database sequences.

---

## Security

- Spring Security with stateless JWT. Access and refresh tokens are distinct
  types and are not interchangeable — presenting one where the other is required
  is rejected.
- The user record is re-read from the database on every request rather than
  trusted from the token body, so a disabled account stops working immediately
  instead of at token expiry.
- BCrypt at cost 12.
- Login gives the same answer for a wrong password and an unknown account.
- Password reset tokens are 256 bits of `SecureRandom`, single use, 30 minutes.
- Candidate data isolation is enforced in the service layer, and a resource
  belonging to another account returns 404 rather than 403 — a 403 confirms the
  id exists.
- Resume files are stored under generated names; the uploaded filename is kept
  for display only, which closes the path-traversal hole.
- Uploads are validated by content, not by the browser-supplied content type.
- Errors return one structured shape. Stack traces never reach the client.

---

## Frontend

React with Vite and TypeScript, TanStack Query for server state, React Router for
routing. No component library and no utility-CSS framework: the design system is
hand-written CSS with a token layer, because the brief was specifically about not
looking assembled.

**Dark-first.** One palette, near-black surfaces with a faint cool cast, and a
single teal accent spent only where it carries meaning — a match score, an active
source, a primary action. Inter for anything a person reads, JetBrains Mono for
anything the system measured. That split is what makes the interface read as an
instrument rather than a brochure.

**Accessibility.** All four text steps clear WCAG AA against the panel surface
(roughly 15.7, 8.4, 6.9 and 4.7 to one). Form control borders meet the 3:1
required by 1.4.11 — visibly heavier than the decorative panel edges, which is
the correct trade. Semantic landmarks, a skip link, labelled controls, a single
visible focus treatment, and `prefers-reduced-motion` honoured throughout.

**Motion** is used where it communicates state: the score ring draws itself once
on mount so the number reads as computed, the pipeline diagram advances a signal
through its stages, source states transition. Nothing bounces, spins or blocks
input.

---

## Things deliberately not built

| Not built | Why |
|---|---|
| Docker / Kubernetes / Terraform | Diagram decoration at this size |
| A separate Python AI service | Spring AI does this in-process, as the brief required |
| OpenSearch | PostgreSQL search over a pre-built `search_text` column is enough for this volume |
| Redis | In-process caching and rate limiting are correct for one node. Redis earns its place when ingestion runs on more than one instance, and not before |
| Dozens of adapters | Three legitimate public APIs beat a hundred sources of dubious provenance |
| Embedding-based matching | Deterministic scoring first, because it is explainable. Embeddings would sit behind the same explanation contract |
