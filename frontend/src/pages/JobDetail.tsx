import { Link, useNavigate, useParams } from 'react-router-dom';

import { CompanyMark, SampleFlag } from '../components/job/JobCard';
import { MatchBreakdown, MatchExplanation, MatchRing } from '../components/job/MatchScore';
import { Icon } from '../components/ui/Icon';
import {
  Badge,
  Button,
  Chip,
  ErrorState,
  Panel,
  Skeleton,
  useToast,
} from '../components/ui/primitives';
import { useAuth } from '../lib/auth';
import { useJob, useJobInteraction } from '../lib/queries';
import {
  employmentLabel,
  experienceLabel,
  formatDateTime,
  humanize,
  relativeTime,
  salaryLabel,
  seniorityLabel,
  titleize,
  workModeLabel,
} from '../lib/format';
import { can, type ChangeEntry, type ProvenanceEntry } from '../lib/types';

function DataItem({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div>
      <p className="data-item__label">{label}</p>
      <p className={`data-item__value${mono ? ' data-item__value--mono' : ''}`}>{value ?? '—'}</p>
    </div>
  );
}

/**
 * Where one sighting came from, and everything CareerFlux knows about whether
 * it should have been there. This panel is the trust story.
 */
function ProvenanceCard({ entry }: { entry: ProvenanceEntry }) {
  // The registry behind this link is the platform operator's alone (Decisions
  // 12 and 15). Everyone else sees the name, not a link the server would refuse.
  const canOpenSource = can(useAuth().user, 'SOURCE_VIEW');
  const stateTone =
    entry.sourceState === 'ACTIVE'
      ? 'positive'
      : entry.sourceState === 'BLOCKED'
        ? 'negative'
        : entry.sourceState === 'DEGRADED' || entry.sourceState === 'PENDING_REVIEW'
          ? 'caution'
          : 'neutral';

  const healthTone =
    entry.healthStatus === 'HEALTHY'
      ? 'positive'
      : entry.healthStatus === 'UNKNOWN'
        ? 'neutral'
        : entry.healthStatus === 'DEGRADED'
          ? 'caution'
          : 'negative';

  return (
    <div className="provenance__entry">
      <div className="provenance__head">
        <div>
          {canOpenSource ? (
            <Link to={`/app/sources/${entry.sourceId}`} className="provenance__name">
              {entry.sourceName}
            </Link>
          ) : (
            <span className="provenance__name">{entry.sourceName}</span>
          )}
          <div className="row wrap gap-2" style={{ marginTop: 'var(--space-2)' }}>
            <Badge tone={stateTone} dot square>
              {entry.sourceState}
            </Badge>
            <Badge tone={healthTone} square>
              {entry.healthStatus === 'UNKNOWN' ? 'NOT CHECKED' : entry.healthStatus}
            </Badge>
            {entry.sampleData && <SampleFlag />}
            {!entry.active && <Badge tone="caution">No longer listed here</Badge>}
          </div>
        </div>
        {entry.sourceUrl && (
          <a
            href={entry.sourceUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="btn btn--ghost btn--sm btn--icon"
            aria-label="Open the original posting"
          >
            <Icon.External size={14} />
          </a>
        )}
      </div>

      <div className="provenance__grid">
        <DataItem label="Source type" value={titleize(entry.sourceType)} />
        {entry.atsProvider !== 'NONE' && entry.atsProvider !== 'UNKNOWN' && (
          <DataItem label="ATS provider" value={titleize(entry.atsProvider)} />
        )}
        <DataItem label="How we found it" value={titleize(entry.discoveryMethod)} />
        <DataItem label="Access policy" value={titleize(entry.accessPolicy ?? 'Not determined')} />
        <DataItem
          label="First observed"
          value={formatDateTime(entry.firstObservedAt)}
          mono
        />
        <DataItem label="Last observed" value={formatDateTime(entry.lastObservedAt)} mono />
        <DataItem label="Times seen" value={entry.observationCount} mono />
        <DataItem
          label="Policy verified"
          value={entry.policyVerifiedAt ? formatDateTime(entry.policyVerifiedAt) : 'Not verified'}
          mono
        />
      </div>
    </div>
  );
}

function ChangeRow({ change }: { change: ChangeEntry }) {
  const marker =
    change.changeType === 'CREATED'
      ? 'created'
      : change.changeType === 'CLOSED'
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
        <p className="timeline__summary">{change.summary}</p>
        <p className="timeline__time">
          {humanize(change.changeType)} · {formatDateTime(change.detectedAt)}
        </p>
        {(change.previousValue || change.newValue) && (
          <p className="timeline__diff">
            {change.previousValue && <del>{change.previousValue}</del>}
            {change.previousValue && change.newValue && <Icon.ArrowRight size={11} />}
            {change.newValue && <ins>{change.newValue}</ins>}
          </p>
        )}
      </div>
    </li>
  );
}

export default function JobDetail() {
  const { jobId } = useParams();
  const navigate = useNavigate();
  const toast = useToast();
  const job = useJob(jobId);
  const interaction = useJobInteraction();

  if (job.isLoading) {
    return (
      <div className="page">
        <Skeleton width={120} height={14} />
        <div style={{ height: 'var(--space-6)' }} />
        <Skeleton width="60%" height={30} />
        <div style={{ height: 'var(--space-8)' }} />
        <div className="grid grid--detail">
          <Skeleton height={420} radius={12} />
          <Skeleton height={280} radius={12} />
        </div>
      </div>
    );
  }

  if (job.isError || !job.data) {
    return (
      <div className="page">
        <ErrorState
          title="That job could not be loaded"
          body={(job.error as Error | undefined)?.message}
          onRetry={() => job.refetch()}
        />
      </div>
    );
  }

  const detail = job.data;
  const summary = detail.summary;
  const match = summary.match;
  const experience = experienceLabel(summary.experience);
  const salary = salaryLabel(summary.salary);

  const act = (action: 'save' | 'unsave' | 'dismiss' | 'applied', message: string) =>
    interaction.mutate(
      { jobId: summary.id, action },
      { onSuccess: () => toast.show(message, 'success') },
    );

  return (
    <div className="page">
      <button
        type="button"
        className="btn btn--ghost btn--sm"
        style={{ marginBottom: 'var(--space-5)', paddingLeft: 0 }}
        onClick={() => navigate(-1)}
      >
        <Icon.ChevronLeft size={14} />
        Back
      </button>

      <div className="row gap-4 items-start" style={{ marginBottom: 'var(--space-6)' }}>
        <CompanyMark name={summary.company?.name ?? summary.title} logoUrl={summary.company?.logoUrl} />
        <div className="grow">
          <h1 className="page-title">{summary.title}</h1>
          <div className="job-card__meta" style={{ marginTop: 'var(--space-2)' }}>
            <span className="job-card__company">{summary.company?.name ?? 'Company not stated'}</span>
            {summary.location && (
              <>
                <span className="job-card__meta-sep">·</span>
                <span>{summary.location}</span>
              </>
            )}
            {summary.status !== 'OPEN' && (
              <Badge tone={summary.status === 'CLOSED' ? 'negative' : 'caution'}>
                {titleize(summary.status)}
              </Badge>
            )}
            {summary.sampleData && <SampleFlag />}
          </div>
        </div>
        {match && <MatchRing score={match.overall} tier={match.tier} size={72} />}
      </div>

      <div className="row wrap gap-2" style={{ marginBottom: 'var(--space-6)' }}>
        {detail.applyUrl ? (
          <a
            href={detail.applyUrl}
            target="_blank"
            rel="noreferrer noopener"
            className="btn btn--primary"
            onClick={() => act('applied', 'Marked as applied. Good luck.')}
          >
            Apply on the employer site
            <Icon.External size={14} />
          </a>
        ) : (
          // The server returns null for a link it could not vouch for, which
          // covers both a posting that never carried one and a value that failed
          // validation. Saying so beats a button that opens a dead tab, and the
          // job itself is still worth reading.
          <span
            className="btn btn--secondary is-disabled"
            aria-disabled="true"
            title="This posting did not include a usable application link."
          >
            <Icon.Info size={14} />
            Application link unavailable
          </span>
        )}
        <Button
          variant="secondary"
          onClick={() =>
            act(
              summary.interaction.saved ? 'unsave' : 'save',
              summary.interaction.saved ? 'Removed from saved' : 'Saved',
            )
          }
        >
          {summary.interaction.saved ? (
            <Icon.BookmarkFilled size={14} style={{ color: 'var(--accent)' }} />
          ) : (
            <Icon.Bookmark size={14} />
          )}
          {summary.interaction.saved ? 'Saved' : 'Save'}
        </Button>
        <Button
          variant="ghost"
          onClick={() => act('dismiss', 'Dismissed. It will stop appearing in your feed.')}
        >
          <Icon.Close size={14} />
          Not interested
        </Button>
        {summary.interaction.applied && (
          <Badge tone="positive" dot>
            You applied
          </Badge>
        )}
      </div>

      <div className="grid grid--detail">
        {/* ------------------------------------------------------- Main column */}
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <Panel title="Overview">
            <div className="data-list data-list--cols">
              <DataItem label="Work mode" value={workModeLabel(summary.workMode)} />
              <DataItem label="Employment" value={employmentLabel(summary.employmentType)} />
              <DataItem label="Seniority" value={seniorityLabel(summary.seniority)} />
              <DataItem label="Experience" value={experience ?? 'Not stated'} />
              <DataItem label="Salary" value={salary ?? 'Not stated'} />
              <DataItem
                label="Posted"
                value={summary.postedAt ? formatDateTime(summary.postedAt) : 'Not stated by source'}
                mono
              />
              <DataItem
                label="First seen by CareerFlux"
                value={formatDateTime(summary.firstObservedAt)}
                mono
              />
              <DataItem
                label="Last seen"
                value={`${formatDateTime(summary.lastObservedAt)} (${relativeTime(summary.lastObservedAt)})`}
                mono
              />
            </div>
          </Panel>

          {detail.skills.length > 0 && (
            <Panel
              title="Skills the posting asks for"
              action={
                <span className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
                  {detail.skills[0].extractedBy === 'AI' ? 'Read by the model' : 'Matched from the dictionary'}
                </span>
              }
            >
              <div className="row wrap gap-2">
                {detail.skills
                  .filter((skill) => skill.requirement === 'REQUIRED')
                  .map((skill) => (
                    <Chip key={skill.slug} className="chip--required">
                      {skill.name}
                    </Chip>
                  ))}
                {detail.skills
                  .filter((skill) => skill.requirement === 'PREFERRED')
                  .map((skill) => (
                    <Chip key={skill.slug} title="Listed as preferred">
                      {skill.name}
                    </Chip>
                  ))}
              </div>
            </Panel>
          )}

          {detail.description && (
            <Panel title="The role">
              <div
                style={{
                  fontSize: 'var(--text-sm)',
                  lineHeight: 'var(--leading-relaxed)',
                  color: 'var(--text-secondary)',
                  whiteSpace: 'pre-wrap',
                  maxWidth: 'var(--reading-max)',
                }}
              >
                {detail.description}
              </div>
            </Panel>
          )}

          <Panel
            title="Where this came from"
            action={
              <Badge tone="neutral">
                {summary.sourceCount === 1
                  ? '1 source'
                  : `${summary.sourceCount} sources`}
              </Badge>
            }
          >
            <div className="provenance">
              {detail.provenance.map((entry) => (
                <ProvenanceCard key={entry.sourceId} entry={entry} />
              ))}
            </div>
          </Panel>
        </div>

        {/* ------------------------------------------------------ Side column */}
        <div className="grid" style={{ gap: 'var(--space-4)', position: 'sticky', top: 'calc(var(--topbar-height) + var(--space-4))' }}>
          {match ? (
            <>
              <Panel title="Match analysis">
                <MatchBreakdown match={match} />
              </Panel>
              <Panel title="Why this score">
                <MatchExplanation match={match} />
              </Panel>
            </>
          ) : (
            <Panel title="Match analysis">
              <p className="text-muted" style={{ fontSize: 'var(--text-sm)' }}>
                This job has not been scored against your profile yet. Run a rematch from the
                dashboard, or add more to your profile first.
              </p>
              <Button
                variant="secondary"
                size="sm"
                block
                style={{ marginTop: 'var(--space-3)' }}
                onClick={() => navigate('/app/profile')}
              >
                Go to your profile
              </Button>
            </Panel>
          )}

          {detail.changes.length > 0 && (
            <Panel title="What has changed">
              <ul className="timeline">
                {detail.changes.map((change, index) => (
                  <ChangeRow key={`${change.changeType}-${index}`} change={change} />
                ))}
              </ul>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}
