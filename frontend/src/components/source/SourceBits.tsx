import { Link } from 'react-router-dom';

import { Icon } from '../ui/Icon';
import { Badge, Tooltip, cn } from '../ui/primitives';
import { formatDateTime, relativeTime, titleize } from '../../lib/format';
import type { HealthCheckView, SourceHealthStatus, SourceState, SourceSummary } from '../../lib/types';

/** The happy path through the state machine, in order. */
export const LIFECYCLE_PATH: SourceState[] = [
  'DISCOVERED',
  'CLASSIFIED',
  'POLICY_REVIEW',
  'APPROVED',
  'ACTIVE',
];

export function stateTone(state: SourceState) {
  switch (state) {
    case 'ACTIVE':
      return 'positive' as const;
    case 'BLOCKED':
      return 'negative' as const;
    case 'DEGRADED':
    case 'PENDING_REVIEW':
    case 'POLICY_REVIEW':
      return 'caution' as const;
    case 'APPROVED':
      return 'accent' as const;
    default:
      return 'neutral' as const;
  }
}

export function healthTone(health: SourceHealthStatus) {
  switch (health) {
    case 'HEALTHY':
      return 'positive' as const;
    case 'DEGRADED':
      return 'caution' as const;
    case 'FAILING':
    case 'UNREACHABLE':
      return 'negative' as const;
    default:
      return 'neutral' as const;
  }
}

export function StateBadge({ state }: { state: SourceState }) {
  return (
    <Badge tone={stateTone(state)} square dot live={state === 'ACTIVE'}>
      {state.replace(/_/g, ' ')}
    </Badge>
  );
}

export function HealthBadge({
  health,
  lastCheckedAt,
}: {
  health: SourceHealthStatus;
  lastCheckedAt: string | null;
}) {
  // UNKNOWN means nobody has looked yet. It must never read as "fine".
  if (health === 'UNKNOWN') {
    return (
      <Tooltip label="No health probe has run against this source yet">
        <Badge square>NOT CHECKED</Badge>
      </Tooltip>
    );
  }
  return (
    <Tooltip
      label={lastCheckedAt ? `Last checked ${formatDateTime(lastCheckedAt)}` : 'Never checked'}
    >
      <Badge tone={healthTone(health)} square>
        {health}
      </Badge>
    </Tooltip>
  );
}

/**
 * The lifecycle drawn as a rail. Terminal states that sit off the happy path
 * (blocked, retired) are shown as a single node instead, because pretending
 * they are a step along the way would be misleading.
 */
export function LifecycleRail({ state }: { state: SourceState }) {
  if (state === 'BLOCKED' || state === 'RETIRED') {
    return (
      <div className="lifecycle">
        <div className="lifecycle__node lifecycle__node--current">
          <span
            className="lifecycle__dot"
            style={{
              background: state === 'BLOCKED' ? 'var(--negative)' : 'var(--line-strong)',
              borderColor: state === 'BLOCKED' ? 'var(--negative)' : 'var(--line-strong)',
              color: 'var(--bg)',
              boxShadow: 'none',
            }}
          >
            <Icon.Close size={13} />
          </span>
          <span className="lifecycle__label">{state}</span>
        </div>
      </div>
    );
  }

  // DEGRADED and PENDING_REVIEW are both "past active", so the rail shows them
  // at the ACTIVE position rather than rewinding.
  const effective: SourceState =
    state === 'DEGRADED' || state === 'PENDING_REVIEW' ? 'ACTIVE' : state;
  const currentIndex = LIFECYCLE_PATH.indexOf(effective);

  return (
    <div className="lifecycle">
      {LIFECYCLE_PATH.map((node, index) => (
        <div key={node} style={{ display: 'contents' }}>
          <div
            className={cn(
              'lifecycle__node',
              index < currentIndex && 'lifecycle__node--done',
              index === currentIndex && 'lifecycle__node--current',
            )}
          >
            <span className="lifecycle__dot">
              {index < currentIndex ? <Icon.Check size={12} /> : index + 1}
            </span>
            <span className="lifecycle__label">{node.replace(/_/g, ' ')}</span>
          </div>
          {index < LIFECYCLE_PATH.length - 1 && (
            <span className={cn('lifecycle__link', index < currentIndex && 'lifecycle__link--done')} />
          )}
        </div>
      ))}
    </div>
  );
}

/** Recent health probes as bars, oldest on the left. */
export function HealthSparkline({ checks }: { checks: HealthCheckView[] }) {
  if (checks.length === 0) {
    return (
      <p className="text-faint" style={{ fontSize: 'var(--text-xs)' }}>
        No probes recorded yet.
      </p>
    );
  }
  const ordered = [...checks].reverse();
  const maxLatency = Math.max(...ordered.map((check) => check.latencyMs ?? 0), 1);

  return (
    <div
      className="sparkline"
      role="img"
      aria-label={`${checks.length} recent health probes, most recent ${checks[0].status.toLowerCase()}`}
    >
      {ordered.map((check, index) => (
        <Tooltip
          key={`${check.checkedAt}-${index}`}
          label={`${check.status} · ${check.latencyMs ?? '?'} ms · ${relativeTime(check.checkedAt)}`}
        >
          <span
            className={`sparkline__bar sparkline__bar--${check.status.toLowerCase()}`}
            style={{
              height: `${Math.max(18, ((check.latencyMs ?? maxLatency) / maxLatency) * 100)}%`,
            }}
          />
        </Tooltip>
      ))}
    </div>
  );
}

export function SourceRow({ source }: { source: SourceSummary }) {
  return (
    <div className="source-row">
      <span className={`source-row__pip source-row__pip--${source.state.toLowerCase()}`} />

      <div className="grow">
        <Link to={`/app/sources/${source.id}`} className="source-row__name truncate">
          {source.name}
        </Link>
        <p className="source-row__url truncate">{source.baseUrl}</p>
      </div>

      <div className="source-row__meta">
        <span>{titleize(source.sourceType)}</span>
        <span className="text-faint">
          {source.lastSuccessfulSyncAt
            ? `Synced ${relativeTime(source.lastSuccessfulSyncAt)}`
            : 'Never synced'}
        </span>
      </div>

      <div className="source-row__numbers">
        <span className="source-row__number">
          {source.activeJobCount}
          <span className="source-row__number-label">jobs</span>
        </span>
        <span className="source-row__number">
          {source.reliabilityPercent < 0 ? '—' : `${source.reliabilityPercent}%`}
          <span className="source-row__number-label">reliability</span>
        </span>
      </div>

      <div className="row gap-2">
        <HealthBadge health={source.healthStatus} lastCheckedAt={source.lastHealthCheckAt} />
        <StateBadge state={source.state} />
      </div>
    </div>
  );
}
