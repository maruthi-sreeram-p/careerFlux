import { Link, useNavigate, useParams } from 'react-router-dom';

import { SampleFlag } from '../components/job/JobCard';
import {
  HealthBadge,
  HealthSparkline,
  LifecycleRail,
  StateBadge,
} from '../components/source/SourceBits';
import { Icon } from '../components/ui/Icon';
import { Badge, ErrorState, Panel, Skeleton, cn } from '../components/ui/primitives';
import { useSource } from '../lib/queries';
import { formatDateTime, pluralize, relativeTime, titleize } from '../lib/format';
import type { PolicyView } from '../lib/types';

function DataItem({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <p className="data-item__label">{label}</p>
      <p className={`data-item__value${mono ? ' data-item__value--mono' : ''}`}>{value ?? '—'}</p>
    </div>
  );
}

type CheckStatus = 'pass' | 'warn' | 'fail';

function PolicyCheck({
  status,
  label,
  detail,
}: {
  status: CheckStatus;
  label: string;
  detail?: string | null;
}) {
  return (
    <div className="policy-check">
      <span className={`policy-check__mark policy-check__mark--${status}`}>
        {status === 'pass' ? (
          <Icon.Check size={11} />
        ) : status === 'warn' ? (
          <Icon.Warning size={11} />
        ) : (
          <Icon.Close size={11} />
        )}
      </span>
      <div>
        <p className="policy-check__label">{label}</p>
        {detail && <p className="policy-check__detail">{detail}</p>}
      </div>
    </div>
  );
}

/**
 * The access policy, rendered as the checklist a reviewer would work through.
 * Every line is either measured (robots) or attributed to a person (terms).
 */
function PolicyPanel({ policy }: { policy: PolicyView }) {
  const robotsStatus: CheckStatus =
    policy.robotsStatus === 'ALLOWED'
      ? 'pass'
      : policy.robotsStatus === 'NOT_PUBLISHED'
        ? 'warn'
        : 'fail';

  const tosStatus: CheckStatus =
    policy.tosStatus === 'PERMITTED'
      ? 'pass'
      : policy.tosStatus === 'RESTRICTED'
        ? 'warn'
        : 'fail';

  return (
    <div>
      <PolicyCheck
        status={robotsStatus}
        label={`robots.txt — ${titleize(policy.robotsStatus)}`}
        detail={
          policy.robotsRule ??
          (policy.robotsCheckedAt
            ? `Checked ${formatDateTime(policy.robotsCheckedAt)}`
            : 'Not checked yet')
        }
      />
      <PolicyCheck
        status={tosStatus}
        label={`Terms of service — ${titleize(policy.tosStatus)}`}
        detail={
          policy.tosReviewedBy
            ? `Reviewed by ${policy.tosReviewedBy} on ${formatDateTime(policy.tosReviewedAt)}${policy.tosNotes ? ` — ${policy.tosNotes}` : ''}`
            : 'No human has reviewed the terms for this source.'
        }
      />
      <PolicyCheck
        status={policy.requiresAuthentication ? 'fail' : 'pass'}
        label={
          policy.requiresAuthentication
            ? 'Requires authentication'
            : 'No authentication required'
        }
        detail={
          policy.requiresAuthentication
            ? 'CareerFlux never signs in to third-party sites, so this source cannot be activated.'
            : undefined
        }
      />
      <PolicyCheck
        status={policy.requiresCaptcha || policy.hasAntiBot ? 'fail' : 'pass'}
        label={
          policy.requiresCaptcha || policy.hasAntiBot
            ? 'Protected by anti-bot measures'
            : 'No anti-bot protection to work around'
        }
        detail={
          policy.requiresCaptcha
            ? 'A CAPTCHA guards this endpoint. Bypassing it is never acceptable.'
            : policy.hasAntiBot
              ? 'The site signals that automated access is unwanted.'
              : undefined
        }
      />
      <PolicyCheck
        status={policy.paywalled ? 'fail' : 'pass'}
        label={policy.paywalled ? 'Content is paywalled' : 'Content is publicly readable'}
      />
      <PolicyCheck
        status={
          policy.accessPolicy === 'RESTRICTED' ||
          policy.accessPolicy === 'FORBIDDEN' ||
          policy.accessPolicy === 'NOT_DETERMINED'
            ? 'fail'
            : 'pass'
        }
        label={`Access route — ${titleize(policy.accessPolicy)}`}
        detail={policy.allowedFields ? `Fields stored: ${policy.allowedFields}` : undefined}
      />
      {policy.crawlDelaySeconds ? (
        <PolicyCheck
          status="warn"
          label={`Crawl delay of ${policy.crawlDelaySeconds}s declared`}
          detail="CareerFlux paces its requests to respect this, overriding its own rate limit if that is slower."
        />
      ) : null}
    </div>
  );
}

export default function SourceDetail() {
  const { sourceId } = useParams();
  const navigate = useNavigate();
  const source = useSource(sourceId);

  if (source.isLoading) {
    return (
      <div className="page">
        <Skeleton width={140} height={14} />
        <div style={{ height: 'var(--space-6)' }} />
        <Skeleton width="50%" height={30} />
        <div style={{ height: 'var(--space-8)' }} />
        <Skeleton height={120} radius={12} />
      </div>
    );
  }

  if (source.isError || !source.data) {
    return (
      <div className="page">
        <ErrorState
          title="That source could not be loaded"
          body={(source.error as Error | undefined)?.message}
          onRetry={() => source.refetch()}
        />
      </div>
    );
  }

  const { summary, verdict, recentHealth, lifecycle, recentRuns } = source.data;

  return (
    <div className="page">
      <button
        type="button"
        className="btn btn--ghost btn--sm"
        style={{ marginBottom: 'var(--space-5)', paddingLeft: 0 }}
        onClick={() => navigate('/app/sources')}
      >
        <Icon.ChevronLeft size={14} />
        All sources
      </button>

      <div className="page-header">
        <div>
          <div className="row wrap gap-2" style={{ marginBottom: 'var(--space-3)' }}>
            <StateBadge state={summary.state} />
            <HealthBadge health={summary.healthStatus} lastCheckedAt={summary.lastHealthCheckAt} />
            {summary.sampleData && <SampleFlag />}
          </div>
          <h1 className="page-title">{summary.name}</h1>
          <p className="page-subtitle mono" style={{ fontSize: 'var(--text-xs)' }}>
            {summary.baseUrl}
          </p>
        </div>
      </div>

      <Panel title="Lifecycle" tight>
        <LifecycleRail state={summary.state} />
        <p className="text-muted" style={{ fontSize: 'var(--text-xs)', marginTop: 'var(--space-3)' }}>
          Currently {titleize(summary.state)} since {formatDateTime(summary.stateChangedAt)}.
          {summary.state === 'ACTIVE'
            ? ' A source only reaches this state once the policy gate passes.'
            : ''}
        </p>
      </Panel>

      <div className="grid grid--detail" style={{ marginTop: 'var(--space-4)' }}>
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Panel title="Identity">
            <div className="data-list data-list--cols">
              <DataItem label="Source type" value={titleize(summary.sourceType)} />
              <DataItem label="ATS provider" value={titleize(summary.atsProvider)} />
              <DataItem label="Adapter" value={summary.adapterKey ?? 'None assigned'} mono />
              <DataItem
                label="Company"
                value={
                  summary.companyName ? (
                    <span>{summary.companyName}</span>
                  ) : (
                    'Not linked to a company'
                  )
                }
              />
              <DataItem label="Rate limit" value={`${summary.rateLimitPerMinute} / minute`} mono />
              <DataItem label="Discovered" value={formatDateTime(summary.discoveredAt)} mono />
            </div>
          </Panel>

          <Panel title="Discovery">
            <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>
              CareerFlux found this source by{' '}
              <strong style={{ color: 'var(--text)', fontWeight: 500 }}>
                {titleize(summary.discoveryMethod).toLowerCase()}
              </strong>
              .
            </p>
          </Panel>

          <Panel
            title="Access policy"
            action={
              summary.policy && (
                <Badge
                  tone={
                    summary.policy.decision === 'APPROVED'
                      ? 'positive'
                      : summary.policy.decision === 'REJECTED'
                        ? 'negative'
                        : 'caution'
                  }
                  square
                >
                  {summary.policy.decision}
                </Badge>
              )
            }
          >
            <div className={cn('verdict', verdict.passed ? 'verdict--pass' : 'verdict--fail')}>
              {verdict.passed ? (
                <Icon.Shield size={16} style={{ color: 'var(--positive)', flex: 'none', marginTop: 1 }} />
              ) : (
                <Icon.Warning size={16} style={{ color: 'var(--negative)', flex: 'none', marginTop: 1 }} />
              )}
              <div>
                <p className="verdict__title">
                  {verdict.passed
                    ? 'This source satisfies every access gate'
                    : `Blocked by ${pluralize(verdict.blockers.length, 'unmet condition')}`}
                </p>
                {verdict.blockers.length > 0 && (
                  <ul className="verdict__list">
                    {verdict.blockers.map((blocker) => (
                      <li key={blocker}>{blocker}</li>
                    ))}
                  </ul>
                )}
                {verdict.warnings.length > 0 && (
                  <ul className="verdict__list">
                    {verdict.warnings.map((warning) => (
                      <li key={warning}>{warning}</li>
                    ))}
                  </ul>
                )}
              </div>
            </div>

            {summary.policy ? (
              <div style={{ marginTop: 'var(--space-4)' }}>
                <PolicyPanel policy={summary.policy} />
                {summary.policy.verifiedAt && (
                  <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-3)' }}>
                    Decision recorded by {summary.policy.decidedBy ?? 'unknown'} on{' '}
                    {formatDateTime(summary.policy.verifiedAt)}.
                    {summary.policy.decisionReason ? ` ${summary.policy.decisionReason}` : ''}
                  </p>
                )}
              </div>
            ) : (
              <p className="text-muted" style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-3)' }}>
                No access policy record exists for this source yet.
              </p>
            )}
          </Panel>

          <Panel title="Recent ingestion runs" flush>
            {recentRuns.length === 0 ? (
              <p
                className="text-muted"
                style={{ fontSize: 'var(--text-sm)', padding: 'var(--space-5)' }}
              >
                This source has never been synced.
              </p>
            ) : (
              <div className="scroll-x">
                <table className="run-table">
                  <thead>
                    <tr>
                      <th>Started</th>
                      <th>Status</th>
                      <th>Trigger</th>
                      <th className="num">Raw</th>
                      <th className="num">New</th>
                      <th className="num">Updated</th>
                      <th className="num">Dupes</th>
                      <th className="num">Closed</th>
                      <th className="num">Errors</th>
                    </tr>
                  </thead>
                  <tbody>
                    {recentRuns.map((run) => (
                      <tr key={run.id}>
                        <td>{relativeTime(run.startedAt)}</td>
                        <td>
                          <Badge
                            tone={
                              run.status === 'SUCCEEDED'
                                ? 'positive'
                                : run.status === 'FAILED'
                                  ? 'negative'
                                  : run.status === 'PARTIAL'
                                    ? 'caution'
                                    : 'neutral'
                            }
                            square
                          >
                            {run.status}
                          </Badge>
                        </td>
                        <td>{titleize(run.trigger)}</td>
                        <td className="num">{run.rawCount}</td>
                        <td className="num">{run.newCount}</td>
                        <td className="num">{run.updatedCount}</td>
                        <td className="num">{run.duplicateCount}</td>
                        <td className="num">{run.closedCount}</td>
                        <td className="num">{run.errorCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>
        </div>

        {/* ------------------------------------------------------- Side column */}
        <div className="grid" style={{ gap: 'var(--space-4)', alignContent: 'start' }}>
          <Panel title="Health">
            <HealthSparkline checks={recentHealth} />
            <div className="data-list data-list--cols" style={{ marginTop: 'var(--space-4)' }}>
              <DataItem
                label="Reliability"
                value={
                  summary.reliabilityPercent < 0
                    ? 'Not measured'
                    : `${summary.reliabilityPercent}%`
                }
                mono
              />
              <DataItem label="Consecutive failures" value={summary.consecutiveFailures} mono />
              <DataItem
                label="Last successful sync"
                value={
                  summary.lastSuccessfulSyncAt
                    ? relativeTime(summary.lastSuccessfulSyncAt)
                    : 'Never'
                }
                mono
              />
              <DataItem
                label="Last checked"
                value={
                  summary.lastHealthCheckAt ? relativeTime(summary.lastHealthCheckAt) : 'Never'
                }
                mono
              />
            </div>
            {summary.reliabilityPercent < 0 && (
              <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-3)' }}>
                Reliability is the share of sync attempts that succeeded. With no attempts yet there
                is nothing to compute, so it is shown as not measured rather than 0%.
              </p>
            )}
          </Panel>

          <Panel title="Jobs from this source">
            <div className="row between" style={{ fontSize: 'var(--text-sm)' }}>
              <span className="text-secondary">Currently listed</span>
              <span className="mono">{summary.activeJobCount}</span>
            </div>
            <div className="row between" style={{ fontSize: 'var(--text-sm)', marginTop: 'var(--space-2)' }}>
              <span className="text-secondary">Ingested in total</span>
              <span className="mono">{summary.jobsIngestedTotal}</span>
            </div>
            <Link
              to={`/app/discover?sourceId=${summary.id}`}
              className="btn btn--secondary btn--sm btn--block"
              style={{ marginTop: 'var(--space-4)' }}
            >
              Browse these jobs
              <Icon.ArrowRight size={13} />
            </Link>
          </Panel>

          <Panel title="Lifecycle history">
            <ul className="timeline">
              {lifecycle.map((entry, index) => (
                <li className="timeline__item" key={`${entry.occurredAt}-${index}`}>
                  <span
                    className={cn(
                      'timeline__marker',
                      entry.toState === 'ACTIVE' && 'timeline__marker--created',
                      entry.toState === 'BLOCKED' && 'timeline__marker--closed',
                    )}
                  >
                    <Icon.ChevronRight size={11} />
                  </span>
                  <div className="timeline__body">
                    <p className="timeline__summary">
                      {entry.fromState ? `${titleize(entry.fromState)} → ` : ''}
                      {titleize(entry.toState)}
                    </p>
                    {entry.reason && <p className="timeline__time">{entry.reason}</p>}
                    <p className="timeline__time">
                      {entry.actor} · {formatDateTime(entry.occurredAt)}
                    </p>
                  </div>
                </li>
              ))}
            </ul>
          </Panel>
        </div>
      </div>
    </div>
  );
}
