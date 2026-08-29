import { Link } from 'react-router-dom';

import { Icon } from '../ui/Icon';
import { Badge, Button, Chip, Tooltip, cn } from '../ui/primitives';
import { MatchExplanation, MatchRing } from './MatchScore';
import {
  employmentLabel,
  experienceLabel,
  hueFor,
  initials,
  relativeTime,
  salaryLabel,
  workModeLabel,
} from '../../lib/format';
import type { JobSummary } from '../../lib/types';

/** Marks anything that came from the bundled fixture rather than a real feed. */
export function SampleFlag() {
  return (
    <span className="sample-flag" title="Bundled sample data, not a real job posting">
      Sample data
    </span>
  );
}

export function CompanyMark({ name, logoUrl }: { name: string | null; logoUrl?: string | null }) {
  if (logoUrl) {
    return (
      <span className="job-card__logo">
        <img src={logoUrl} alt="" loading="lazy" />
      </span>
    );
  }
  const hue = hueFor(name ?? 'unknown');
  return (
    <span
      className="job-card__logo"
      style={{
        color: `hsl(${hue} 45% 72%)`,
        background: `hsl(${hue} 30% 12%)`,
        borderColor: `hsl(${hue} 25% 22%)`,
      }}
      aria-hidden="true"
    >
      {initials(name)}
    </span>
  );
}

interface JobCardProps {
  job: JobSummary;
  onSave: (job: JobSummary) => void;
  onDismiss: (job: JobSummary) => void;
  showExplanation?: boolean;
}

/**
 * The recommendation-feed card. The match explanation is deliberately part of
 * the card rather than hidden behind a click: the reasoning is the product, and
 * a candidate scanning the feed should be able to judge fit without opening
 * anything.
 */
export function JobCard({ job, onSave, onDismiss, showExplanation = true }: JobCardProps) {
  const posted = relativeTime(job.postedAt ?? job.firstObservedAt);
  const experience = experienceLabel(job.experience);
  const salary = salaryLabel(job.salary);

  return (
    <article className={cn('job-card', job.interaction.dismissed && 'job-card--dismissed')}>
      <CompanyMark name={job.company?.name ?? job.title} logoUrl={job.company?.logoUrl} />

      <div className="job-card__main">
        <div>
          <Link to={`/app/jobs/${job.id}`} className="job-card__title">
            {job.title}
          </Link>
          <div className="job-card__meta">
            <span className="job-card__company">{job.company?.name ?? 'Company not stated'}</span>
            {job.location && (
              <>
                <span className="job-card__meta-sep">·</span>
                <span>{job.location}</span>
              </>
            )}
            {job.workMode !== 'UNSPECIFIED' && (
              <>
                <span className="job-card__meta-sep">·</span>
                <span>{workModeLabel(job.workMode)}</span>
              </>
            )}
            {experience && (
              <>
                <span className="job-card__meta-sep">·</span>
                <span>{experience}</span>
              </>
            )}
            {salary && (
              <>
                <span className="job-card__meta-sep">·</span>
                <span>{salary}</span>
              </>
            )}
          </div>
        </div>

        {showExplanation && job.match && (
          <MatchExplanation match={job.match} compact maxStrengths={6} />
        )}

        {!showExplanation && job.topSkills.length > 0 && (
          <div className="row wrap gap-2">
            {job.topSkills.slice(0, 5).map((skill) => (
              <Chip key={skill.slug}>{skill.name}</Chip>
            ))}
          </div>
        )}

        <div className="job-card__source">
          <Icon.Layers size={12} />
          <span>
            {job.primarySourceName ?? 'Source not recorded'}
            {job.sourceCount > 1 && ` and ${job.sourceCount - 1} other source${job.sourceCount > 2 ? 's' : ''}`}
          </span>
          {posted && (
            <>
              <span className="job-card__meta-sep">·</span>
              <span>{posted}</span>
            </>
          )}
          {job.recentChangeCount > 0 && (
            <Badge tone="caution" title="This posting changed recently">
              Updated
            </Badge>
          )}
          {job.sampleData && <SampleFlag />}
        </div>
      </div>

      <div className="job-card__aside">
        {job.match ? (
          <MatchRing score={job.match.overall} tier={job.match.tier} />
        ) : (
          <Tooltip label="Not scored yet. Complete your profile and rematch.">
            <span className="badge">Not scored</span>
          </Tooltip>
        )}

        <div className="job-card__actions">
          <Tooltip label={job.interaction.saved ? 'Remove from saved' : 'Save'}>
            <Button
              variant="ghost"
              size="sm"
              iconOnly
              aria-label={job.interaction.saved ? 'Remove from saved' : 'Save this job'}
              aria-pressed={job.interaction.saved}
              onClick={() => onSave(job)}
            >
              {job.interaction.saved ? (
                <Icon.BookmarkFilled size={14} style={{ color: 'var(--accent)' }} />
              ) : (
                <Icon.Bookmark size={14} />
              )}
            </Button>
          </Tooltip>
          <Tooltip label={job.interaction.dismissed ? 'Undo dismiss' : 'Not interested'}>
            <Button
              variant="ghost"
              size="sm"
              iconOnly
              aria-label={job.interaction.dismissed ? 'Undo dismiss' : 'Dismiss this job'}
              onClick={() => onDismiss(job)}
            >
              <Icon.Close size={14} />
            </Button>
          </Tooltip>
        </div>
      </div>
    </article>
  );
}

/** The dense row used in list mode, where scanning many jobs matters more than detail. */
export function JobRow({ job }: { job: JobSummary }) {
  return (
    <div className="job-row">
      <CompanyMark name={job.company?.name ?? job.title} logoUrl={job.company?.logoUrl} />
      <div className="grow">
        <Link to={`/app/jobs/${job.id}`} className="job-row__title truncate">
          {job.title}
        </Link>
        <div className="job-row__meta">
          <span className="truncate">{job.company?.name ?? 'Company not stated'}</span>
          {job.location && <span className="job-card__meta-sep">·</span>}
          {job.location && <span className="truncate">{job.location}</span>}
          {job.sampleData && <SampleFlag />}
        </div>
      </div>
      <div className="job-row__tags">
        {job.workMode !== 'UNSPECIFIED' && <Chip>{workModeLabel(job.workMode)}</Chip>}
        {job.employmentType !== 'UNSPECIFIED' && <Chip>{employmentLabel(job.employmentType)}</Chip>}
      </div>
      {job.match ? (
        <MatchRing score={job.match.overall} tier={job.match.tier} size={38} showLabel={false} />
      ) : (
        <span className="text-faint" style={{ fontSize: 'var(--text-xs)' }}>
          —
        </span>
      )}
    </div>
  );
}
