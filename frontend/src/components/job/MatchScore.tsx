import { useEffect, useRef, useState } from 'react';

import { Icon } from '../ui/Icon';
import { cn } from '../ui/primitives';
import { tierLabel } from '../../lib/format';
import type { MatchAnalysis, MatchComponentView } from '../../lib/types';

/**
 * The score ring.
 *
 * The arc animates from zero on first paint, which is the one place motion in
 * CareerFlux carries meaning: it reads as the number being *computed* rather
 * than looked up. It animates once, on mount, and never on re-render.
 */
export function MatchRing({
  score,
  tier,
  size = 56,
  showLabel = true,
}: {
  score: number;
  tier: string;
  size?: number;
  showLabel?: boolean;
}) {
  const [drawn, setDrawn] = useState(0);
  const animated = useRef(false);

  useEffect(() => {
    if (animated.current) {
      setDrawn(score);
      return;
    }
    animated.current = true;
    const prefersReduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    if (prefersReduced) {
      setDrawn(score);
      return;
    }
    const frame = window.requestAnimationFrame(() => setDrawn(score));
    return () => window.cancelAnimationFrame(frame);
  }, [score]);

  const stroke = size >= 64 ? 4 : 3;
  const radius = (size - stroke) / 2;
  const circumference = 2 * Math.PI * radius;
  const offset = circumference - (drawn / 100) * circumference;

  return (
    <div
      className={cn('match-ring', `match-ring--${tier.toLowerCase()}`)}
      style={{ width: size, height: size }}
      role="img"
      aria-label={`${score} percent match. ${tierLabel(tier)}.`}
    >
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} aria-hidden="true">
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="var(--line)"
          strokeWidth={stroke}
        />
        <circle
          className="match-ring__arc"
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="currentColor"
          strokeWidth={stroke}
          strokeLinecap="round"
          strokeDasharray={circumference}
          strokeDashoffset={offset}
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <span className="match-ring__value mono" style={{ fontSize: size >= 64 ? 17 : 14 }}>
        {score}
        {showLabel && <span className="match-ring__unit">%</span>}
      </span>
    </div>
  );
}

/** A compact inline score for dense list rows, where a ring would be too heavy. */
export function MatchPill({ score, tier }: { score: number; tier: string }) {
  return (
    <span
      className={cn('match-pill', `match-pill--${tier.toLowerCase()}`)}
      title={tierLabel(tier)}
    >
      <span className="mono">{score}</span>
      <span className="match-pill__unit">%</span>
    </span>
  );
}

const DIMENSIONS: { key: keyof MatchAnalysis; label: string }[] = [
  { key: 'skills', label: 'Skills' },
  { key: 'experience', label: 'Experience' },
  { key: 'role', label: 'Role' },
  { key: 'location', label: 'Location' },
  { key: 'seniority', label: 'Seniority' },
];

/** The five sub-scores that add up to the overall number. */
export function MatchBreakdown({ match }: { match: MatchAnalysis }) {
  return (
    <div className="breakdown">
      <div className="breakdown__row breakdown__row--total">
        <span className="breakdown__label">Overall match</span>
        <div className="breakdown__track">
          <span className="breakdown__fill breakdown__fill--total" style={{ width: `${match.overall}%` }} />
        </div>
        <span className="breakdown__value mono">{match.overall}%</span>
      </div>
      {DIMENSIONS.map(({ key, label }) => {
        const value = match[key] as number;
        return (
          <div className="breakdown__row" key={key}>
            <span className="breakdown__label">{label}</span>
            <div className="breakdown__track">
              <span className="breakdown__fill" style={{ width: `${value}%` }} />
            </div>
            <span className="breakdown__value mono">{value}%</span>
          </div>
        );
      })}
    </div>
  );
}

function ComponentList({
  items,
  kind,
}: {
  items: MatchComponentView[];
  kind: 'strength' | 'gap' | 'context';
}) {
  if (items.length === 0) {
    return null;
  }
  return (
    <ul className={cn('reasons', `reasons--${kind}`)}>
      {items.map((item, index) => (
        <li className="reason" key={`${item.label}-${index}`}>
          <span className="reason__mark" aria-hidden="true">
            {kind === 'strength' ? (
              <Icon.Check size={11} />
            ) : kind === 'gap' ? (
              <Icon.Triangle size={9} />
            ) : (
              <span className="reason__dash" />
            )}
          </span>
          <span className="reason__body">
            <span className="reason__label">{item.label}</span>
            {item.detail && <span className="reason__detail">{item.detail}</span>}
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * The full explanation. A score is never shown on its own anywhere in
 * CareerFlux — this component is what makes the number answerable.
 */
export function MatchExplanation({
  match,
  compact = false,
  maxStrengths,
}: {
  match: MatchAnalysis;
  compact?: boolean;
  maxStrengths?: number;
}) {
  const strengths = maxStrengths ? match.strengths.slice(0, maxStrengths) : match.strengths;
  const hiddenStrengths = match.strengths.length - strengths.length;

  return (
    <div className={cn('explanation', compact && 'explanation--compact')}>
      {strengths.length > 0 && (
        <div className="explanation__group">
          {!compact && <p className="eyebrow">Strong matches</p>}
          <ComponentList items={strengths} kind="strength" />
          {hiddenStrengths > 0 && (
            <p className="explanation__more">+{hiddenStrengths} more</p>
          )}
        </div>
      )}

      {match.gaps.length > 0 && (
        <div className="explanation__group">
          {!compact && <p className="eyebrow">Potential gaps</p>}
          <ComponentList items={compact ? match.gaps.slice(0, 2) : match.gaps} kind="gap" />
        </div>
      )}

      {!compact && match.context.length > 0 && (
        <div className="explanation__group">
          <p className="eyebrow">Worth knowing</p>
          <ComponentList items={match.context} kind="context" />
        </div>
      )}

      {!compact && match.narrative && (
        <div className="explanation__narrative">
          <p className="eyebrow">Why this matters</p>
          <p>{match.narrative}</p>
          <p className="explanation__provenance mono">
            Scored by {match.scorerVersion}
            {match.narrativeEngine && match.narrativeEngine !== 'rules'
              ? ` · explained by ${match.narrativeEngine}`
              : ' · explanation written from the score, not by a model'}
          </p>
        </div>
      )}
    </div>
  );
}
