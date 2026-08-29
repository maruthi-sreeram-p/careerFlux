import { Link } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Badge, Chip, Panel } from '../components/ui/primitives';
import { useAuth } from '../lib/auth';
import { can } from '../lib/types';
import { useRequirements } from '../lib/queries';
import type { Requirement, RequirementStatus } from '../lib/types';
import { useState } from 'react';

/**
 * What the college is currently hiring against.
 *
 * <p>A requirement is the institution-side counterpart to a job posting: a
 * company tells the placement office who they want, and this is where that
 * brief lives until students are ranked against it.
 *
 * <p>Reading is open to coordinators; only a placement officer may add one.
 * The button is hidden accordingly, and the server refuses it regardless —
 * this only avoids offering an action that would fail.
 */
const FILTERS: { id: RequirementStatus | 'ALL'; label: string }[] = [
  { id: 'ALL', label: 'All' },
  { id: 'DRAFT', label: 'Draft' },
  { id: 'OPEN', label: 'Open' },
  { id: 'CLOSED', label: 'Closed' },
];

function statusTone(status: RequirementStatus): 'positive' | 'neutral' | 'caution' {
  if (status === 'OPEN') return 'positive';
  if (status === 'DRAFT') return 'caution';
  return 'neutral';
}

function experienceLabel(requirement: Requirement): string | null {
  const { minExperienceYears: min, maxExperienceYears: max } = requirement;
  if (min === null && max === null) return null;
  if (min !== null && max !== null) return `${min}–${max} yrs`;
  if (min !== null) return `${min}+ yrs`;
  return `up to ${max} yrs`;
}

function RequirementCard({ requirement }: { requirement: Requirement }) {
  const experience = experienceLabel(requirement);
  return (
    <Panel
      title={requirement.roleTitle}
      action={<Badge tone={statusTone(requirement.status)}>{requirement.status}</Badge>}
    >
      <p className="text-secondary" style={{ marginBottom: 'var(--space-3)' }}>
        {requirement.companyName}
        {requirement.location ? ` · ${requirement.location}` : ''}
        {experience ? ` · ${experience}` : ''}
        {requirement.graduationYear ? ` · Batch ${requirement.graduationYear}` : ''}
      </p>

      {requirement.requiredSkills.length > 0 && (
        <div className="row wrap gap-2" style={{ marginBottom: 'var(--space-2)' }}>
          <span className="text-faint">Required</span>
          {requirement.requiredSkills.map((skill) => (
            <Chip key={skill.slug} selected>
              {skill.skill}
            </Chip>
          ))}
        </div>
      )}

      {requirement.preferredSkills.length > 0 && (
        <div className="row wrap gap-2" style={{ marginBottom: 'var(--space-2)' }}>
          <span className="text-faint">Preferred</span>
          {requirement.preferredSkills.map((skill) => (
            <Chip key={skill.slug}>{skill.skill}</Chip>
          ))}
        </div>
      )}

      <div className="row wrap gap-2 text-muted" style={{ marginTop: 'var(--space-3)' }}>
        {requirement.departments.length === 0 ? (
          <span>Open to every department</span>
        ) : (
          <span>{requirement.departments.map((department) => department.code).join(' · ')}</span>
        )}
        {/* Stated as the company's rule, never as a verdict about a student. */}
        {requirement.minCgpa !== null && <span>· Company asks for CGPA {requirement.minCgpa}</span>}
      </div>

      <Link className="btn btn--secondary" to={`/app/requirements/${requirement.id}`}>
        Open
      </Link>
    </Panel>
  );
}

export default function Requirements() {
  const { user } = useAuth();
  const [filter, setFilter] = useState<RequirementStatus | 'ALL'>('ALL');
  const requirements = useRequirements(filter === 'ALL' ? undefined : filter);
  const mayCreate = can(user, 'PLACEMENT_DRIVE_MANAGE');

  const list = requirements.data?.content ?? [];

  return (
    <>
      <PageHeader
        title="Company requirements"
        subtitle="What companies have asked this college to find."
        actions={
          mayCreate ? (
            <Link className="btn btn--primary" to="/app/requirements/new">
              Add requirement
            </Link>
          ) : undefined
        }
      />

      <div className="row wrap gap-2" style={{ marginBottom: 'var(--space-5)' }}>
        {FILTERS.map((entry) => (
          <Chip
            key={entry.id}
            selected={filter === entry.id}
            onClick={() => setFilter(entry.id)}
          >
            {entry.label}
          </Chip>
        ))}
      </div>

      {requirements.isLoading && (
        <Panel title="Loading requirements">
          <span className="skeleton skeleton--line" />
        </Panel>
      )}

      {requirements.isError && (
        <Panel title="Requirements could not be loaded">
          <p className="text-muted">Try again shortly. Nothing has changed.</p>
        </Panel>
      )}

      {requirements.data && list.length === 0 && (
        <Panel title={filter === 'ALL' ? 'No requirements yet' : `No ${filter.toLowerCase()} requirements`}>
          <p className="text-muted">
            {filter === 'ALL'
              ? mayCreate
                ? 'When a company gives the placement office a hiring brief, record it here so students can be matched against it.'
                : 'Nothing has been recorded for your department yet.'
              : 'Nothing in this state. Try another filter.'}
          </p>
          {filter === 'ALL' && mayCreate && (
            <Link className="btn btn--primary" to="/app/requirements/new">
              Add the first requirement
            </Link>
          )}
        </Panel>
      )}

      {list.length > 0 && (
        <div className="grid grid--two">
          {list.map((requirement) => (
            <RequirementCard key={requirement.id} requirement={requirement} />
          ))}
        </div>
      )}
    </>
  );
}
