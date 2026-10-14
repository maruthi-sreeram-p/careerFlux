import { Link } from 'react-router-dom';

import { BrandMark } from '../components/layout/AppShell';
import { FlowDiagram } from '../components/marketing/FlowDiagram';
import { MatchExplanation, MatchRing } from '../components/job/MatchScore';
import { Icon } from '../components/ui/Icon';
import { Badge, Chip } from '../components/ui/primitives';
import { LANDING_FOOTNOTE } from '../lib/productCopy';
import type { MatchAnalysis } from '../lib/types';

/**
 * A worked example, rendered with the real match components.
 *
 * Using the product's own components here means the landing page cannot
 * over-promise: if the explanation UI changes, this changes with it.
 */
const EXAMPLE_MATCH: MatchAnalysis = {
  overall: 92,
  tier: 'EXCELLENT',
  skills: 96,
  experience: 90,
  role: 95,
  location: 100,
  seniority: 100,
  strengths: [
    { kind: 'STRENGTH', dimension: 'SKILLS', label: 'Java', detail: null },
    { kind: 'STRENGTH', dimension: 'SKILLS', label: 'Spring Boot', detail: null },
    { kind: 'STRENGTH', dimension: 'SKILLS', label: 'REST APIs', detail: null },
    { kind: 'STRENGTH', dimension: 'SKILLS', label: 'PostgreSQL', detail: null },
    {
      kind: 'STRENGTH',
      dimension: 'EXPERIENCE',
      label: '0–2 years experience',
      detail: 'You are inside the stated range.',
    },
    { kind: 'STRENGTH', dimension: 'LOCATION', label: 'Hyderabad', detail: null },
  ],
  gaps: [
    {
      kind: 'GAP',
      dimension: 'SKILLS',
      label: 'AWS production experience',
      detail: 'Listed as a requirement and not on your profile',
    },
  ],
  context: [],
  narrative:
    'Your strongest alignment here is on skills: Java, Spring Boot and REST APIs. The main gap is AWS production experience. On the evidence in your profile, this is worth a close look.',
  narrativeEngine: 'rules',
  scorerVersion: 'rules-1',
  computedAt: new Date().toISOString(),
};

function Section({
  id,
  eyebrow,
  title,
  lead,
  children,
}: {
  id?: string;
  eyebrow?: string;
  title: string;
  lead?: string;
  children?: React.ReactNode;
}) {
  return (
    <section className="mkt-section" id={id}>
      <div className="marketing__inner">
        <div className="mkt-section__head">
          {eyebrow && <p className="eyebrow" style={{ marginBottom: 'var(--space-3)' }}>{eyebrow}</p>}
          <h2 className="mkt-section__title">{title}</h2>
          {lead && <p className="mkt-section__lead">{lead}</p>}
        </div>
        {children}
      </div>
    </section>
  );
}

export default function Landing() {
  return (
    <div className="marketing">
      <nav className="marketing-nav">
        <div className="marketing__inner marketing-nav__inner">
          <Link to="/" className="row gap-3">
            <BrandMark />
            <span className="brand-word">CareerFlux</span>
          </Link>
          <div className="marketing-nav__links">
            <a href="#how">How it works</a>
            <a href="#sources">Trusted sources</a>
            <a href="#matching">Explainable matching</a>
            <a href="#trust">Job history</a>
          </div>
          <div className="row gap-2">
            <Link to="/login" className="btn btn--ghost btn--sm">
              Sign in
            </Link>
            <Link to="/register" className="btn btn--primary btn--sm">
              Get started
            </Link>
          </div>
        </div>
      </nav>

      {/* ------------------------------------------------------------- Hero */}
      <header className="hero">
        <div className="marketing__inner hero__inner">
          <div>
            <span className="hero__eyebrow">
              <Icon.Radar size={13} style={{ color: 'var(--accent)' }} />
              Career intelligence, not another job board
            </span>
            <h1 className="hero__title">
              Tell us what career you want. We find <em>where the opportunities are.</em>
            </h1>
            <p className="hero__lead">
              Most job tools search a pile of listings somebody else assembled. CareerFlux works out
              which employers and which sources matter for the career you are aiming at, checks
              whether it is actually allowed to read them, watches them for change, and then explains
              exactly why each role fits you.
            </p>
            <div className="hero__actions">
              <Link to="/register" className="btn btn--primary btn--lg">
                Start with your resume
                <Icon.ArrowRight size={15} />
              </Link>
              <a href="#how" className="btn btn--secondary btn--lg">
                See how it works
              </a>
            </div>
            <p className="hero__footnote">{LANDING_FOOTNOTE}</p>
          </div>

          <FlowDiagram />
        </div>
      </header>

      {/* -------------------------------------------------------- How it works */}
      <Section
        id="how"
        eyebrow="The product"
        title="Three areas of support, working on the same goal"
        lead="Everything CareerFlux does falls into one of three areas. Each answers a question that an ordinary job board does not."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Radar size={16} />
            </span>
            <h3 className="mkt-card__title">Trusted sources</h3>
            <p className="mkt-card__body">
              Where should we look for this career? Are we permitted to read it? Is the source
              working properly, and when did we last confirm that? Every source has a written
              access policy and a clear record of its approval stages.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Layers size={16} />
            </span>
            <h3 className="mkt-card__title">Job insights</h3>
            <p className="mkt-card__body">
              What is this role really asking for? Have we seen it before under a different title?
              Did the requirements change since yesterday? Each job appears once, however many
              places advertise it.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Sparkle size={16} />
            </span>
            <h3 className="mkt-card__title">Personal guidance</h3>
            <p className="mkt-card__body">
              Who are you, what are you aiming at, and which of these roles genuinely fit? Scored
              across five dimensions, with the reasoning attached to every number.
            </p>
          </article>
        </div>
      </Section>

      {/* ----------------------------------------------------- Source intelligence */}
      <Section
        id="sources"
        eyebrow="What sets us apart"
        title="We show you where the jobs came from, and whether we should have been there"
        lead="Most aggregators will not tell you where a listing came from, because the answer is often embarrassing. CareerFlux treats permission to read a source as a deliberate decision that is recorded and can be reviewed."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Shield size={16} />
            </span>
            <h3 className="mkt-card__title">A permission check that is never skipped</h3>
            <p className="mkt-card__body">
              A source cannot be used until its website permits automated reading of the page, a named
              person has reviewed its terms, and no restriction would have to be worked around.
              Sign-in pages, CAPTCHAs and bot protection permanently rule a source out.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Flow size={16} />
            </span>
            <h3 className="mkt-card__title">An approval process you can review</h3>
            <p className="mkt-card__body">
              Discovered, classified, policy review, approved, active and monitored. Every change of
              stage is recorded with its reason and the person responsible, so the question "why
              are we reading this source?" always has a dated answer.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Pulse size={16} />
            </span>
            <h3 className="mkt-card__title">Reliability that is checked, not assumed</h3>
            <p className="mkt-card__body">
              Sources are checked regularly. If a source keeps failing, it is marked as unreliable and
              eventually withdrawn, because showing outdated jobs from a faulty source is worse
              than showing none.
            </p>
          </article>
        </div>
      </Section>

      {/* ---------------------------------------------------------- Matching */}
      <Section
        id="matching"
        eyebrow="Explainable matching"
        title="A score you can argue with"
        lead="Every match is five sub-scores and a list of specific reasons. Nothing is a black box, and no model gets to decide the number."
      >
        <div className="example">
          <div className="job-card" style={{ gridTemplateColumns: 'auto 1fr auto' }}>
            <span
              className="job-card__logo"
              style={{ color: 'hsl(190 45% 72%)', background: 'hsl(190 30% 12%)', borderColor: 'hsl(190 25% 22%)' }}
              aria-hidden="true"
            >
              NS
            </span>
            <div className="job-card__main">
              <div>
                <p className="job-card__title">Java Backend Developer</p>
                <div className="job-card__meta">
                  <span className="job-card__company">Northwind Systems</span>
                  <span className="job-card__meta-sep">·</span>
                  <span>Hyderabad</span>
                  <span className="job-card__meta-sep">·</span>
                  <span>Hybrid</span>
                  <span className="job-card__meta-sep">·</span>
                  <span>0–2 years</span>
                </div>
              </div>
              <MatchExplanation match={EXAMPLE_MATCH} compact maxStrengths={6} />
              <div className="job-card__source">
                <Icon.Shield size={12} style={{ color: 'var(--positive)' }} />
                <span>Verified company career page</span>
                <span className="job-card__meta-sep">·</span>
                <span>first seen 4 days ago</span>
              </div>
            </div>
            <div className="job-card__aside">
              <MatchRing score={92} tier="EXCELLENT" />
            </div>
          </div>

          <div className="mkt-card">
            <h3 className="mkt-card__title">Why this matters</h3>
            <p className="mkt-card__body">{EXAMPLE_MATCH.narrative}</p>
            <div className="row wrap gap-2" style={{ marginTop: 'var(--space-2)' }}>
              <Chip>Skills 40%</Chip>
              <Chip>Experience 20%</Chip>
              <Chip>Role 20%</Chip>
              <Chip>Location 10%</Chip>
              <Chip>Seniority 10%</Chip>
            </div>
            <p className="mkt-card__body">
              Those weights are fixed, published and applied identically to every candidate. When a
              dimension cannot be evaluated — the posting lists no skills, say — it scores neutral
              and says so, rather than being silently counted as a failure.
            </p>
          </div>
        </div>
      </Section>

      {/* -------------------------------------------------------------- Trust */}
      <Section
        id="trust"
        eyebrow="Job history and changes"
        title="Every job shows where it came from and what it used to say"
        lead="A duplicate listing is never deleted. When two sources advertise the same opening, both are kept as records of a single job, so you can see how widely a role is advertised and when it changed."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Clock size={16} />
            </span>
            <h3 className="mkt-card__title">First seen, last seen</h3>
            <p className="mkt-card__body">
              Not "posted 2 days ago" copied from somewhere. The dates CareerFlux shows are when it
              saw the posting itself, for each source.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Pulse size={16} />
            </span>
            <h3 className="mkt-card__title">Change tracking</h3>
            <p className="mkt-card__body">
              A new required skill added. The location moved. The salary band updated. The job
              closed and reopened. Every change is recorded and shown on the role.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Layers size={16} />
            </span>
            <h3 className="mkt-card__title">Duplicate listings combined</h3>
            <p className="mkt-card__body">
              Matched using the job reference number, application link and description similarity,
              not just on whether two titles are spelled the same way.
            </p>
          </article>
        </div>

        <div className="mkt-numbers" style={{ marginTop: 'var(--space-6)' }}>
          <div>
            <p className="mkt-number__value">5</p>
            <p className="mkt-number__label">scoring dimensions behind every match</p>
          </div>
          <div>
            <p className="mkt-number__value">9</p>
            <p className="mkt-number__label">approval stages a source moves through</p>
          </div>
          <div>
            <p className="mkt-number__value">0</p>
            <p className="mkt-number__label">restrictions bypassed, by design</p>
          </div>
          <div>
            <p className="mkt-number__value">70%</p>
            <p className="mkt-number__label">the floor below which nothing is shown to you</p>
          </div>
        </div>
      </Section>

      {/* ---------------------------------------------------------------- CTA */}
      <section className="mkt-section" style={{ borderTop: 'none' }}>
        <div className="marketing__inner">
          <div className="cta">
            <Badge tone="accent" dot live>
              Currently in development
            </Badge>
            <h2 className="cta__title">Start with your resume. CareerFlux does the looking.</h2>
            <p className="mkt-section__lead" style={{ maxWidth: '54ch', marginTop: 0 }}>
              Upload it once, correct anything we read wrongly, tell us the roles you are
              aiming at, and let CareerFlux do the searching.
            </p>
            <Link to="/register" className="btn btn--primary btn--lg">
              Create your profile
              <Icon.ArrowRight size={15} />
            </Link>
          </div>
        </div>
      </section>

      <footer className="marketing-footer">
        <div className="marketing__inner marketing-footer__inner">
          <div className="row gap-3">
            <BrandMark size={22} />
            <span>CareerFlux — career intelligence platform</span>
          </div>
          <div className="row gap-6">
            <span>Spring Boot · PostgreSQL · React</span>
            <Link to="/login">Sign in</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}
