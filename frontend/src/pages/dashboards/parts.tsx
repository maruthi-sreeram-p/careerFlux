import type { ReactNode } from 'react';

import { Panel } from '../../components/ui/primitives';
import type { CohortCount } from '../../lib/types';

/**
 * The pieces the three institutional dashboards share.
 *
 * <p>They ask different questions but they are all built from counts of real
 * rows, and the honesty rules are the same for each: a number that has not been
 * measured is not shown as zero, and a distribution with nothing in it says so
 * in words rather than drawing an empty chart.
 */

/** A single measured figure. Matches the student dashboard's stat treatment. */
export function Stat({
  label,
  value,
  note,
  tone,
}: {
  label: string;
  /** Null means "not measured", which is rendered as an em dash, never as 0. */
  value: number | string | null;
  note?: string;
  tone?: 'accent' | 'positive';
}) {
  const measured = value !== null && value !== undefined;
  return (
    <div className="stat">
      <p className="stat__label">{label}</p>
      <p className={`stat__value${tone && measured ? ` stat__value--${tone}` : ''}`}>
        {measured ? value : '—'}
      </p>
      {note && <p className="stat__note">{note}</p>}
    </div>
  );
}

/**
 * Coverage stated as a fraction of the cohort rather than a bare percentage.
 *
 * <p>"4 of 12 students have uploaded a resume" is a fact. "33%" invites the
 * reader to treat a twelve-person sample as a rate.
 */
export function Coverage({
  label,
  covered,
  total,
  noun,
}: {
  label: string;
  covered: number;
  total: number;
  noun: string;
}) {
  const share = total === 0 ? 0 : Math.round((covered / total) * 100);
  return (
    <div className="stat">
      <p className="stat__label">{label}</p>
      <p className="stat__value">
        {covered}
        <span className="stat__unit">of {total}</span>
      </p>
      <p className="stat__note">
        {total === 0 ? `No ${noun} in scope yet` : `${share}% of ${noun}`}
      </p>
      {total > 0 && (
        <div
          className="meter"
          role="img"
          aria-label={`${covered} of ${total} ${noun}`}
          style={{ marginTop: 'var(--space-2)' }}
        >
          <span className="meter__fill" style={{ width: `${share}%` }} />
        </div>
      )}
    </div>
  );
}

/**
 * A ranked breakdown — departments, batches, skills.
 *
 * <p>A bar per row rather than a chart: the reader wants to compare a dozen
 * labelled quantities, which a list does better than a plotted figure and
 * without implying a trend that was never measured.
 */
export function Distribution({
  title,
  rows,
  empty,
  max,
}: {
  title: string;
  rows: CohortCount[];
  empty: string;
  max?: number;
}) {
  const ceiling = Math.max(1, ...rows.map((row) => row.count));
  const shown = max ? rows.slice(0, max) : rows;
  return (
    <Panel title={title}>
      {shown.length === 0 ? (
        <p className="text-muted">{empty}</p>
      ) : (
        <ul className="distribution">
          {shown.map((row) => (
            <li className="distribution__row" key={row.label}>
              <span className="distribution__label" title={row.label}>
                {row.label}
              </span>
              <span className="distribution__bar" aria-hidden="true">
                <span style={{ width: `${(row.count / ceiling) * 100}%` }} />
              </span>
              <span className="distribution__count">{row.count}</span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** A row of stats, on the grid the student dashboard already uses. */
export function StatRow({ children }: { children: ReactNode }) {
  return <div className="grid grid--stats">{children}</div>;
}

/**
 * Said once on every institutional dashboard, because a short list of students
 * is otherwise indistinguishable from a small college.
 */
export function ScopeNote({ scopeLabel, students }: { scopeLabel: string; students: number }) {
  return (
    <p className="text-muted" style={{ marginTop: 'var(--space-2)' }}>
      Showing <strong>{scopeLabel}</strong> — {students} {students === 1 ? 'student' : 'students'}{' '}
      in your scope.
    </p>
  );
}
