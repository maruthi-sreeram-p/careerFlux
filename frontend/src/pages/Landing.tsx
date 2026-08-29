import { Link } from 'react-router-dom';

import { BrandMark } from '../components/layout/AppShell';
import { FlowDiagram } from '../components/marketing/FlowDiagram';
import { MatchExplanation, MatchRing } from '../components/job/MatchScore';
import { Icon } from '../components/ui/Icon';
import { Badge, Chip } from '../components/ui/primitives';
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
            <a href="#sources">Source intelligence</a>
            <a href="#matching">Explainable matching</a>
            <a href="#trust">Provenance</a>
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
            <p className="hero__footnote">
              Free while in development. Your resume stays yours — delete your account and the
              derived data goes with it.
            </p>
          </div>

          <FlowDiagram />
        </div>
      </header>

      {/* -------------------------------------------------------- How it works */}
      <Section
        id="how"
        eyebrow="The product"
        title="Three intelligence layers, working on the same problem"
        lead="Everything CareerFlux does sits in one of three layers. Each answers a question a job board never asks."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Radar size={16} />
            </span>
            <h3 className="mkt-card__title">Source intelligence</h3>
            <p className="mkt-card__body">
              Where should we look for this career? Can we legitimately access it? Is the endpoint
              healthy, and when did we last confirm any of that? Every source carries a policy
              record and a lifecycle you can read.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Layers size={16} />
            </span>
            <h3 className="mkt-card__title">Job intelligence</h3>
            <p className="mkt-card__body">
              What is this role really asking for? Have we seen it before under a different title?
              Did the requirements change since yesterday? One canonical job, however many places
              list it.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Sparkle size={16} />
            </span>
            <h3 className="mkt-card__title">Candidate intelligence</h3>
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
        eyebrow="The differentiator"
        title="We show you where the jobs came from, and whether we should have been there"
        lead="Most aggregators will not tell you where a listing came from, because the answer is often embarrassing. CareerFlux treats source access as a first-class, auditable decision."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Shield size={16} />
            </span>
            <h3 className="mkt-card__title">A policy gate nothing bypasses</h3>
            <p className="mkt-card__body">
              A source cannot become active until robots.txt permits the exact path, a named person
              has reviewed the terms, and no access control would have to be worked around. Login
              walls, CAPTCHAs and anti-bot protection are permanent disqualifiers, not obstacles.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Flow size={16} />
            </span>
            <h3 className="mkt-card__title">A lifecycle you can audit</h3>
            <p className="mkt-card__body">
              Discovered, classified, policy review, approved, active, health monitored. Every
              transition is recorded with its reason and its actor, so the question "why are we
              reading this?" always has an answer with a timestamp on it.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Pulse size={16} />
            </span>
            <h3 className="mkt-card__title">Health that is measured, not assumed</h3>
            <p className="mkt-card__body">
              Sources are probed on a schedule. Sustained failure degrades a source and eventually
              pulls it out of service, because quietly serving stale jobs from a broken feed is
              worse than serving none.
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
        eyebrow="Provenance and change detection"
        title="Every job knows where it came from, and what it used to say"
        lead="A duplicate is never deleted. When two sources list the same opening, both sightings survive as observations of one canonical job — so you can see how widely a role is being advertised, and when it moved."
      >
        <div className="mkt-grid">
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Clock size={16} />
            </span>
            <h3 className="mkt-card__title">First seen, last seen</h3>
            <p className="mkt-card__body">
              Not "posted 2 days ago" copied from somewhere. The dates CareerFlux shows are when it
              observed the posting itself, per source.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Pulse size={16} />
            </span>
            <h3 className="mkt-card__title">Change detection</h3>
            <p className="mkt-card__body">
              A Spring Boot requirement added. The location moved. The salary band updated. The job
              closed and reopened. All of it recorded and shown on the role.
            </p>
          </article>
          <article className="mkt-card">
            <span className="mkt-card__icon">
              <Icon.Layers size={16} />
            </span>
            <h3 className="mkt-card__title">Multi-source deduplication</h3>
            <p className="mkt-card__body">
              Matched on requisition id, application URL, canonical key and description similarity —
              not on whether two titles happen to be spelled the same way.
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
            <p className="mkt-number__label">lifecycle states a source moves through</p>
          </div>
          <div>
            <p className="mkt-number__value">0</p>
            <p className="mkt-number__label">access controls bypassed, by design</p>
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
              Upload it once, correct anything the parser got wrong, tell us the roles you are
              aiming at, and let the pipeline run.
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
