import { useState } from 'react';

import { PageHeader } from '../components/layout/AppShell';
import { SourceRow } from '../components/source/SourceBits';
import { Icon } from '../components/ui/Icon';
import {
  Badge,
  EmptyState,
  ErrorState,
  Panel,
  Skeleton,
  Tooltip,
  cn,
} from '../components/ui/primitives';
import { useAdapters, useSourceStats, useSources } from '../lib/queries';
import { pluralize, titleize } from '../lib/format';
import type { SourceState } from '../lib/types';

/** The states worth surfacing as filter tiles, in the order an operator scans them. */
const BOARD: { state: SourceState; label: string; kind?: string }[] = [
  { state: 'ACTIVE', label: 'Active', kind: 'active' },
  { state: 'POLICY_REVIEW', label: 'Policy review', kind: 'attention' },
  { state: 'PENDING_REVIEW', label: 'Needs review', kind: 'attention' },
  { state: 'DEGRADED', label: 'Degraded', kind: 'attention' },
  { state: 'BLOCKED', label: 'Blocked', kind: 'blocked' },
  { state: 'DISCOVERED', label: 'Discovered' },
  { state: 'CLASSIFIED', label: 'Classified' },
  { state: 'APPROVED', label: 'Approved' },
];

export default function Sources() {
  const [selected, setSelected] = useState<SourceState[]>([]);
  const stats = useSourceStats();
  const sources = useSources(selected);
  const adapters = useAdapters();

  const toggle = (state: SourceState) =>
    setSelected((current) =>
      current.includes(state) ? current.filter((entry) => entry !== state) : [...current, state],
    );

  return (
    <div className="page page--wide">
      <PageHeader
        title="Source intelligence"
        subtitle="Every place CareerFlux collects jobs from, what it is permitted to read there, and whether that source is currently working."
        actions={
          stats.data && (
            <div className="row gap-2">
              <Tooltip label="The transport carrying pipeline events">
                <Badge square>{stats.data.ingestionTransport}</Badge>
              </Tooltip>
              <Tooltip
                label={
                  stats.data.aiEnabled
                    ? 'A language model is configured for enrichment and explanations'
                    : 'No model configured — deterministic paths only'
                }
              >
                <Badge tone={stats.data.aiEnabled ? 'accent' : 'neutral'} square dot>
                  AI {stats.data.aiEnabled ? 'on' : 'off'}
                </Badge>
              </Tooltip>
            </div>
          )
        }
      />

      {stats.isLoading && (
        <div className="state-board">
          {BOARD.map((entry) => (
            <Skeleton key={entry.state} height={78} radius={8} />
          ))}
        </div>
      )}

      {stats.isError && (
        <Panel>
          <ErrorState
            title="Source statistics could not be loaded"
            body={(stats.error as Error).message}
            onRetry={() => stats.refetch()}
          />
        </Panel>
      )}

      {stats.data && (
        <>
          <div className="state-board">
            {BOARD.map((entry) => {
              const count = stats.data.byState[entry.state] ?? 0;
              const isSelected = selected.includes(entry.state);
              return (
                <button
                  type="button"
                  key={entry.state}
                  className={cn(
                    'state-tile',
                    entry.kind && `state-tile--${entry.kind}`,
                    isSelected && 'state-tile--selected',
                  )}
                  aria-pressed={isSelected}
                  onClick={() => toggle(entry.state)}
                >
                  <span className="state-tile__label">{entry.label}</span>
                  <span className="state-tile__count">{count}</span>
                </button>
              );
            })}
          </div>

          {stats.data.needingAttention > 0 && (
            <div
              className="row gap-2"
              style={{ marginTop: 'var(--space-4)', fontSize: 'var(--text-sm)' }}
            >
              <Icon.Warning size={14} style={{ color: 'var(--caution)' }} />
              <span className="text-secondary">
                {pluralize(stats.data.needingAttention, 'source')} waiting on a human decision.
              </span>
            </div>
          )}
        </>
      )}

      <section className="section">
        <div className="section__head">
          <h2 className="section__title">
            Registry
            {selected.length > 0 && (
              <span className="text-muted" style={{ fontWeight: 400, fontSize: 'var(--text-sm)' }}>
                {' '}
                · filtered to {selected.map((state) => titleize(state)).join(', ')}
              </span>
            )}
          </h2>
          {sources.data && (
            <span className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
              {pluralize(sources.data.totalElements, 'source')}
            </span>
          )}
        </div>

        <Panel flush>
          {sources.isLoading &&
            [0, 1, 2].map((index) => (
              <div key={index} style={{ padding: 'var(--space-4)' }}>
                <Skeleton height={38} />
              </div>
            ))}

          {sources.isError && (
            <ErrorState
              title="The registry could not be loaded"
              body={(sources.error as Error).message}
              onRetry={() => sources.refetch()}
            />
          )}

          {sources.data && sources.data.content.length === 0 && (
            <EmptyState
              icon={<Icon.Radar size={20} />}
              title={selected.length > 0 ? 'No sources in those states' : 'No sources registered yet'}
              body={
                selected.length > 0
                  ? 'Clear the state filter to see the whole registry.'
                  : 'An administrator registers a source, CareerFlux classifies it, and it only becomes active once the access policy is satisfied.'
              }
            />
          )}

          {sources.data?.content.map((source) => <SourceRow key={source.id} source={source} />)}
        </Panel>
      </section>

      <section className="section">
        <div className="section__head">
          <h2 className="section__title">Adapters in this build</h2>
        </div>
        <p className="page-subtitle" style={{ marginBottom: 'var(--space-4)' }}>
          A source can only be activated if one of these knows how to read it. Each targets a
          documented public endpoint that its provider publishes for exactly this purpose.
        </p>

        <div className="mkt-grid">
          {adapters.isLoading &&
            [0, 1, 2].map((index) => <Skeleton key={index} height={140} radius={12} />)}

          {adapters.data?.map((adapter) => (
            <article className="mkt-card" key={adapter.key}>
              <div className="row between">
                <h3 className="mkt-card__title">{adapter.displayName}</h3>
                <Badge square>{adapter.key}</Badge>
              </div>
              <p className="mkt-card__body">{adapter.description}</p>
              <div className="row wrap gap-2">
                <Badge>{titleize(adapter.sourceType)}</Badge>
                <Badge tone="accent">{titleize(adapter.intendedAccessPolicy)}</Badge>
              </div>
              {adapter.documentationUrl && (
                <a
                  href={adapter.documentationUrl}
                  target="_blank"
                  rel="noreferrer noopener"
                  className="row gap-2"
                  style={{ fontSize: 'var(--text-xs)', color: 'var(--accent)' }}
                >
                  Provider documentation
                  <Icon.External size={12} />
                </a>
              )}
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
