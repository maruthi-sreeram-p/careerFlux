import { Link, useNavigate } from 'react-router-dom';

import { PageHeader } from '../../components/layout/AppShell';
import { JobCard } from '../../components/job/JobCard';
import { Icon } from '../../components/ui/Icon';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  Panel,
  Skeleton,
  useToast,
} from '../../components/ui/primitives';
import { useDashboard, useJobInteraction, useRematch } from '../../lib/queries';
import { greeting, pluralize, relativeTime } from '../../lib/format';
import type { ActivityItem, JobSummary } from '../../lib/types';

/** One measured number with the sentence that says what it measures. */
function Stat({
  label,
  value,
  unit,
  note,
  tone,
}: {
  label: string;
  value: number | string;
  unit?: string;
  note?: string;
  tone?: 'accent' | 'positive';
}) {
  return (
    <div className="stat">
      <p className="stat__label">{label}</p>
      <p className={`stat__value${tone ? ` stat__value--${tone}` : ''}`}>
        {value}
        {unit && <span className="stat__unit">{unit}</span>}
      </p>
      {note && <p className="stat__note">{note}</p>}
    </div>
  );
}

function ActivityRow({ item }: { item: ActivityItem }) {
  const marker =
    item.kind === 'CREATED'
      ? 'created'
      : item.kind === 'CLOSED'
        ? 'closed'
        : 'changed';

  return (
    <li className="timeline__item">
      <span className={`timeline__marker timeline__marker--${marker}`}>
        {marker === 'created' ? (
          <Icon.Plus size={11} />
        ) : marker === 'closed' ? (
          <Icon.Close size={11} />
        ) : (
          <Icon.Pulse size={11} />
        )}
      </span>
      <div className="timeline__body">
        {item.jobId ? (
          <Link to={`/app/jobs/${item.jobId}`} className="timeline__summary">
            {item.title}
          </Link>
        ) : (
          <span className="timeline__summary">{item.title}</span>
        )}
        <span className="timeline__time">
          {item.detail} · {relativeTime(item.occurredAt)}
        </span>
      </div>
    </li>
  );
}

function FeedSkeleton() {
  return (
    <div className="grid" style={{ gap: 'var(--space-3)' }}>
      {[0, 1, 2].map((index) => (
        <div className="job-card" key={index}>
          <Skeleton width={38} height={38} radius={8} />
          <div className="grid" style={{ gap: 'var(--space-3)' }}>
            <Skeleton width="45%" height={16} />
            <Skeleton width="70%" height={12} />
            <Skeleton width="60%" height={12} />
          </div>
          <Skeleton width={56} height={56} radius={999} />
        </div>
      ))}
    </div>
  );
}

export default function StudentDashboard() {
  const dashboard = useDashboard();
  const interaction = useJobInteraction();
  const rematch = useRematch();
  const toast = useToast();
  const navigate = useNavigate();

  const onSave = (job: JobSummary) => {
    interaction.mutate(
      { jobId: job.id, action: job.interaction.saved ? 'unsave' : 'save' },
      {
        onSuccess: () => toast.show(job.interaction.saved ? 'Removed from saved' : 'Saved', 'success'),
        onError: () => toast.show('That could not be saved. Try again.', 'error'),
      },
    );
  };

  const onDismiss = (job: JobSummary) => {
    interaction.mutate(
      { jobId: job.id, action: job.interaction.dismissed ? 'undismiss' : 'dismiss' },
      { onSuccess: () => toast.show('Dismissed. It will stop appearing in your feed.') },
    );
  };

  if (dashboard.isLoading) {
    return (
      <div className="page">
        <Skeleton width={280} height={30} />
        <div style={{ height: 'var(--space-8)' }} />
        <div className="grid grid--stats">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} height={92} radius={12} />
          ))}
        </div>
        <div style={{ height: 'var(--space-10)' }} />
        <FeedSkeleton />
      </div>
    );
  }

  if (dashboard.isError) {
    return (
      <div className="page">
        <ErrorState
          title="The dashboard could not be loaded"
          body={(dashboard.error as Error).message}
          onRetry={() => dashboard.refetch()}
        />
      </div>
    );
  }

  const data = dashboard.data!;
  const { summary, recommendations, recentActivity, engagement } = data;
  const firstName = summary.greetingName?.split(' ')[0] ?? 'there';
  const onboardingIncomplete = summary.onboardingStage !== 'COMPLETE';

  return (
    <div className="page">
      <PageHeader
        title={`${greeting()}, ${firstName}.`}
        subtitle={
          summary.totalVisibleMatches > 0
            ? `CareerFlux is tracking ${pluralize(summary.openJobs, 'open role')} across ${pluralize(summary.sourcesActive, 'active source')}, and ${summary.totalVisibleMatches} of them clear your match threshold.`
            : 'CareerFlux has not found anything above your match threshold yet.'
        }
        actions={
          <Button
            variant="secondary"
            size="sm"
            loading={rematch.isPending}
            onClick={() =>
              rematch.mutate(undefined, {
                onSuccess: (result) =>
                  toast.show(
                    `Rescored ${pluralize(result.jobsScored, 'job')}. ${result.visibleMatches} above threshold.`,
                    'success',
                  ),
                onError: () => toast.show('Rematch failed. Try again.', 'error'),
              })
            }
          >
            <Icon.Refresh size={14} />
            Rematch
          </Button>
        }
      />

      {onboardingIncomplete && (
        <Panel className="panel--raised" tight>
          <div className="row between gap-4 wrap">
            <div className="row gap-3 items-start">
              <Icon.Sparkle size={16} style={{ color: 'var(--accent)', marginTop: 2 }} />
              <div>
                <p style={{ fontWeight: 500 }}>Your profile is {summary.profileCompleteness}% complete</p>
                <p className="text-muted" style={{ fontSize: 'var(--text-sm)' }}>
                  {summary.notice ??
                    'Matching gets sharper with every field you fill in — especially skills, target roles and locations.'}
                </p>
              </div>
            </div>
            <Button variant="primary" size="sm" onClick={() => navigate('/onboarding')}>
              Finish setup
              <Icon.ArrowRight size={14} />
            </Button>
          </div>
        </Panel>
      )}

      <div className="grid grid--stats" style={{ marginTop: 'var(--space-6)' }}>
        <Stat
          label="Excellent matches"
          value={summary.excellentMatches}
          tone="positive"
          note="95% and above"
        />
        <Stat
          label="Strong matches"
          value={summary.strongMatches}
          tone="accent"
          note="85–94%"
        />
        <Stat
          label="New since your last visit"
          value={summary.newSinceLastVisit}
          note={
            summary.lastMatchComputedAt
              ? `Last scored ${relativeTime(summary.lastMatchComputedAt)}`
              : 'Not scored yet'
          }
        />
        <Stat
          label="Sources monitored"
          value={summary.sourcesMonitored}
          note={`${summary.sourcesActive} actively syncing`}
        />
      </div>

      <section className="section">
        <div className="section__head">
          <h2 className="section__title">Recommended for you</h2>
          <Link to="/app/discover" className="btn btn--ghost btn--sm">
            Browse everything
            <Icon.ArrowRight size={13} />
          </Link>
        </div>

        {recommendations.length === 0 ? (
          <Panel>
            <EmptyState
              icon={<Icon.Compass size={20} />}
              title="Nothing above your match threshold yet"
              body={
                summary.notice ??
                'CareerFlux only shows roles that clear 70%. Add more skills and target roles, or wait for the next ingestion run.'
              }
              action={
                <div className="row gap-2">
                  <Button variant="primary" size="sm" onClick={() => navigate('/app/profile')}>
                    Improve your profile
                  </Button>
                  <Button variant="secondary" size="sm" onClick={() => navigate('/app/discover')}>
                    Browse all jobs
                  </Button>
                </div>
              }
            />
          </Panel>
        ) : (
          <div className="grid" style={{ gap: 'var(--space-3)' }}>
            {recommendations.map((job) => (
              <JobCard key={job.id} job={job} onSave={onSave} onDismiss={onDismiss} />
            ))}
          </div>
        )}
      </section>

      <div className="grid grid--two section">
        <Panel
          title="Recent activity"
          action={
            <span className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
              Last 14 days
            </span>
          }
        >
          {recentActivity.length === 0 ? (
            <p className="text-muted" style={{ fontSize: 'var(--text-sm)' }}>
              No job changes detected yet. Change detection compares each observation against the
              last one, so this fills up as sources are re-synced.
            </p>
          ) : (
            <ul className="timeline">
              {recentActivity.map((item, index) => (
                <ActivityRow key={`${item.jobId}-${index}`} item={item} />
              ))}
            </ul>
          )}
        </Panel>

        <div className="grid" style={{ gap: 'var(--space-4)', alignContent: 'start' }}>
          <Panel title="Your pipeline">
            <div className="grid" style={{ gap: 'var(--space-3)' }}>
              {[
                { label: 'Saved', value: engagement.saved, to: '/app/saved' },
                { label: 'Applied', value: engagement.applied, to: '/app/saved?tab=applied' },
                { label: 'Dismissed', value: engagement.dismissed, to: '/app/saved?tab=dismissed' },
              ].map((row) => (
                <Link
                  key={row.label}
                  to={row.to}
                  className="row between"
                  style={{ fontSize: 'var(--text-sm)' }}
                >
                  <span className="text-secondary">{row.label}</span>
                  <span className="mono">{row.value}</span>
                </Link>
              ))}
            </div>
          </Panel>

          <Panel title="System">
            <div className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
              <div className="row between">
                <span className="text-secondary">Resume on file</span>
                {summary.hasResume ? (
                  <Badge tone="positive" dot>
                    Parsed
                  </Badge>
                ) : (
                  <Badge tone="caution">Not uploaded</Badge>
                )}
              </div>
              <div className="row between">
                <span className="text-secondary">AI extraction</span>
                {summary.aiEnabled ? (
                  <Badge tone="accent" dot live>
                    Enabled
                  </Badge>
                ) : (
                  <Badge title="Set GEMINI_API_KEY to enable">Off</Badge>
                )}
              </div>
              <div className="row between">
                <span className="text-secondary">Open roles tracked</span>
                <span className="mono">{summary.openJobs}</span>
              </div>
            </div>
            {!summary.aiEnabled && (
              <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-3)' }}>
                Without an API key, resumes are read by a simpler parser and match explanations are
                written from the score rather than by a model. Everything else works the same.
              </p>
            )}
          </Panel>
        </div>
      </div>
    </div>
  );
}
