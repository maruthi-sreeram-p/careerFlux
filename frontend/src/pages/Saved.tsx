import { useSearchParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { JobCard } from '../components/job/JobCard';
import { Icon } from '../components/ui/Icon';
import { EmptyState, ErrorState, Panel, Skeleton, cn, useToast } from '../components/ui/primitives';
import { useAppliedJobs, useJobInteraction, useSavedJobs } from '../lib/queries';
import type { JobSummary } from '../lib/types';

const TABS = [
  { id: 'saved', label: 'Saved' },
  { id: 'applied', label: 'Applied' },
] as const;

type TabId = (typeof TABS)[number]['id'];

export default function Saved() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as TabId) ?? 'saved';
  const toast = useToast();
  const interaction = useJobInteraction();

  const saved = useSavedJobs();
  const applied = useAppliedJobs();
  const active = tab === 'applied' ? applied : saved;

  const onSave = (job: JobSummary) =>
    interaction.mutate(
      { jobId: job.id, action: job.interaction.saved ? 'unsave' : 'save' },
      { onSuccess: () => toast.show(job.interaction.saved ? 'Removed from saved' : 'Saved') },
    );

  const onDismiss = (job: JobSummary) =>
    interaction.mutate(
      { jobId: job.id, action: job.interaction.dismissed ? 'undismiss' : 'dismiss' },
      { onSuccess: () => toast.show('Dismissed') },
    );

  return (
    <div className="page">
      <PageHeader
        title="Your pipeline"
        subtitle="Roles you kept, and the ones you told CareerFlux you applied to."
      />

      <div className="tabs" role="tablist" style={{ marginBottom: 'var(--space-5)' }}>
        {TABS.map((entry) => {
          const count =
            entry.id === 'saved' ? saved.data?.length : applied.data?.length;
          return (
            <button
              key={entry.id}
              role="tab"
              type="button"
              className={cn('tab')}
              aria-selected={tab === entry.id}
              onClick={() => setParams(entry.id === 'saved' ? {} : { tab: entry.id })}
            >
              {entry.label}
              {count !== undefined && <span className="tab__count">{count}</span>}
            </button>
          );
        })}
      </div>

      {active.isLoading && (
        <div className="grid" style={{ gap: 'var(--space-3)' }}>
          {[0, 1].map((index) => (
            <Skeleton key={index} height={132} radius={12} />
          ))}
        </div>
      )}

      {active.isError && (
        <Panel>
          <ErrorState
            title="That list could not be loaded"
            body={(active.error as Error).message}
            onRetry={() => active.refetch()}
          />
        </Panel>
      )}

      {active.data && active.data.length === 0 && (
        <Panel>
          <EmptyState
            icon={tab === 'saved' ? <Icon.Bookmark size={20} /> : <Icon.Check size={20} />}
            title={tab === 'saved' ? 'Nothing saved yet' : 'No applications recorded'}
            body={
              tab === 'saved'
                ? 'Save a role from your feed or from discovery and it will wait for you here.'
                : 'CareerFlux never applies on your behalf. When you apply somewhere, mark it here so it stops competing for your attention.'
            }
          />
        </Panel>
      )}

      {active.data && active.data.length > 0 && (
        <div className="grid" style={{ gap: 'var(--space-3)' }}>
          {active.data.map((job) => (
            <JobCard
              key={job.id}
              job={job}
              onSave={onSave}
              onDismiss={onDismiss}
              showExplanation={Boolean(job.match)}
            />
          ))}
        </div>
      )}
    </div>
  );
}
