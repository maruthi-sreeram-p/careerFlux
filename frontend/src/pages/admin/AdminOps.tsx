import { PageHeader } from '../../components/layout/AppShell';
import { Icon } from '../../components/ui/Icon';
import { Badge, ErrorState, Panel, Skeleton, Tooltip } from '../../components/ui/primitives';
import { useSystemStats } from '../../lib/queries';
import { pluralize } from '../../lib/format';

function Stat({
  label,
  value,
  note,
  tone,
}: {
  label: string;
  value: number | string;
  note?: string;
  tone?: 'accent' | 'positive';
}) {
  return (
    <div className="stat">
      <p className="stat__label">{label}</p>
      <p className={`stat__value${tone ? ` stat__value--${tone}` : ''}`}>{value}</p>
      {note && <p className="stat__note">{note}</p>}
    </div>
  );
}

/**
 * The operations console.
 *
 * Every figure here is a count of rows in the database. Nothing is sampled,
 * estimated or smoothed, because an operator has to be able to trust it when
 * deciding whether ingestion is genuinely working.
 */
export default function AdminOps() {
  const stats = useSystemStats();

  if (stats.isLoading) {
    return (
      <div className="page">
        <Skeleton width={240} height={30} />
        <div style={{ height: 'var(--space-8)' }} />
        <div className="grid grid--stats">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} height={92} radius={12} />
          ))}
        </div>
      </div>
    );
  }

  if (stats.isError || !stats.data) {
    return (
      <div className="page">
        <ErrorState
          title="System statistics could not be loaded"
          body={(stats.error as Error | undefined)?.message}
          onRetry={() => stats.refetch()}
        />
      </div>
    );
  }

  const data = stats.data;
  const topics = Object.entries(data.pipelineEventsLast24hByTopic);
  const maxTopic = Math.max(...topics.map(([, count]) => count), 1);

  return (
    <div className="page page--wide">
      <PageHeader
        title="Operations"
        subtitle="Ingestion, the event pipeline and the state of the corpus. Refreshes every 30 seconds."
        actions={
          <div className="row gap-2">
            <Tooltip label="The transport carrying pipeline events">
              <Badge square>{data.ingestionTransport}</Badge>
            </Tooltip>
            <Tooltip label={data.aiEnabled ? `Model: ${data.aiModel}` : 'No model configured'}>
              <Badge tone={data.aiEnabled ? 'accent' : 'neutral'} square dot live={data.aiEnabled}>
                AI {data.aiEnabled ? data.aiModel : 'off'}
              </Badge>
            </Tooltip>
          </div>
        }
      />

      <div className="grid grid--stats">
        <Stat
          label="Jobs tracked"
          value={data.totalJobs}
          note={`${data.openJobs} open · ${data.closedJobs} closed`}
        />
        <Stat
          label="Ingested in 24h"
          value={data.jobsIngestedLast24h}
          tone="accent"
          note={`${pluralize(data.ingestionRunsLast24h, 'run')} in the same window`}
        />
        <Stat
          label="Changes detected in 24h"
          value={data.jobChangesLast24h}
          note="Diffs against the previous observation"
        />
        <Stat
          label="Failed runs in 24h"
          value={data.failedRunsLast24h}
          tone={data.failedRunsLast24h === 0 ? 'positive' : undefined}
          note={data.failedRunsLast24h === 0 ? 'All runs completed' : 'Check the source health'}
        />
      </div>

      <div className="grid grid--two section">
        <Panel
          title="Pipeline events, last 24 hours"
          action={
            <div className="row gap-2">
              {data.pendingPipelineEvents > 0 && (
                <Badge tone="caution">{data.pendingPipelineEvents} pending</Badge>
              )}
              {data.failedPipelineEvents > 0 && (
                <Badge tone="negative">{data.failedPipelineEvents} failed</Badge>
              )}
              {data.pendingPipelineEvents === 0 && data.failedPipelineEvents === 0 && (
                <Badge tone="positive" dot>
                  Queue clear
                </Badge>
              )}
            </div>
          }
        >
          {topics.every(([, count]) => count === 0) ? (
            <p className="text-muted" style={{ fontSize: 'var(--text-sm)' }}>
              No pipeline events in the last 24 hours. Events are emitted when a source is synced.
            </p>
          ) : (
            <div>
              {topics.map(([topic, count]) => (
                <div className="topic-row" key={topic}>
                  <span className="topic-row__name">{topic}</span>
                  <span className="topic-row__track">
                    <span
                      className="topic-row__fill"
                      style={{ width: `${(count / maxTopic) * 100}%` }}
                    />
                  </span>
                  <span className="topic-row__count">{count}</span>
                </div>
              ))}
            </div>
          )}
          <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-4)' }}>
            These are the stages of the ingestion pipeline. The same topic names are used whether
            events travel through the in-process outbox or Kafka.
          </p>
        </Panel>

        <Panel title="Source registry">
          <div className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
            {Object.entries(data.sources.byState)
              .filter(([, count]) => count > 0)
              .map(([state, count]) => (
                <div className="row between" key={state}>
                  <span className="text-secondary">{state.replace(/_/g, ' ')}</span>
                  <span className="mono">{count}</span>
                </div>
              ))}
          </div>
          <div className="divider" style={{ margin: 'var(--space-4) 0' }} />
          <div className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
            <div className="row between">
              <span className="text-secondary">Needing attention</span>
              <span className="mono" style={{ color: data.sources.needingAttention > 0 ? 'var(--caution)' : undefined }}>
                {data.sources.needingAttention}
              </span>
            </div>
            <div className="row between">
              <span className="text-secondary">Job observations from active sources</span>
              <span className="mono">{data.sources.jobsFromActiveSources}</span>
            </div>
          </div>
        </Panel>
      </div>

      <div className="grid grid--two section">
        <Panel title="Health across the registry">
          <div className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
            {Object.entries(data.sources.byHealth).map(([health, count]) => (
              <div className="row between" key={health}>
                <span className="text-secondary">
                  {health === 'UNKNOWN' ? 'Not yet checked' : health}
                </span>
                <span className="mono">{count}</span>
              </div>
            ))}
          </div>
        </Panel>

        <Panel title="Accounts">
          <div className="grid" style={{ gap: 'var(--space-3)', fontSize: 'var(--text-sm)' }}>
            <div className="row between">
              <span className="text-secondary">Total users</span>
              <span className="mono">{data.users}</span>
            </div>
            <div className="row between">
              <span className="text-secondary">Administrators</span>
              <span className="mono">{data.admins}</span>
            </div>
          </div>
          <p className="text-faint" style={{ fontSize: 'var(--text-2xs)', marginTop: 'var(--space-4)' }}>
            Administrator accounts are created only when CAREERFLUX_ADMIN_EMAIL and
            CAREERFLUX_ADMIN_PASSWORD are set in the environment. There is no default credential.
          </p>
        </Panel>
      </div>

      <div className="row gap-2 section" style={{ fontSize: 'var(--text-xs)' }}>
        <Icon.Info size={13} style={{ color: 'var(--text-faint)' }} />
        <span className="text-faint">
          Every number on this page is a live count. Nothing here is a placeholder.
        </span>
      </div>
    </div>
  );
}
