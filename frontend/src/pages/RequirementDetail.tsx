import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Badge, Button, Chip, Panel, useToast } from '../components/ui/primitives';
import { useAuth } from '../lib/auth';
import { useRequirement, useShortlist, useUpdateRequirement } from '../lib/queries';
import { can } from '../lib/types';
import type { Requirement, RequirementSkill } from '../lib/types';

/**
 * One company requirement in full.
 *
 * <p>Publishing lives here rather than on the form because it is a different
 * kind of act: saving records what a company said, publishing declares the
 * college is ready to search students against it.
 *
 * <p>The CGPA line is stated as the company's rule and nothing more. Student
 * CGPA is not held anywhere in CareerFlux, so no student can be judged against
 * it yet — and when they can, technical fit and formal eligibility have to stay
 * two separate answers rather than one blended score.
 */
function SkillRow({ label, skills, hint }: { label: string; skills: RequirementSkill[]; hint?: string }) {
  if (skills.length === 0) {
    return null;
  }
  return (
    <div style={{ marginBottom: 'var(--space-4)' }}>
      <p className="text-faint" style={{ marginBottom: 'var(--space-2)' }}>
        {label}
        {hint ? ` — ${hint}` : ''}
      </p>
      <div className="row wrap gap-2">
        {skills.map((skill) => (
          <Chip key={skill.slug} selected={label === 'Required'}>
            {skill.skill}
          </Chip>
        ))}
      </div>
    </div>
  );
}

function detail(label: string, value: string | number | null | undefined, absent: string) {
  return (
    <div className="stat">
      <p className="stat__label">{label}</p>
      <p className="stat__value" style={{ fontSize: 'var(--text-md)' }}>
        {value === null || value === undefined || value === '' ? (
          <span className="text-muted">{absent}</span>
        ) : (
          value
        )}
      </p>
    </div>
  );
}

export default function RequirementDetail() {
  const { requirementId } = useParams();
  const { user } = useAuth();
  const toast = useToast();
  const requirement = useRequirement(requirementId);
  const update = useUpdateRequirement();
  const shortlist = useShortlist(requirementId);
  const mayManage = can(user, 'PLACEMENT_DRIVE_MANAGE');

  if (requirement.isLoading) {
    return (
      <>
        <PageHeader title="Requirement" subtitle="Loading…" />
        <Panel title="Loading">
          <span className="skeleton skeleton--line" />
        </Panel>
      </>
    );
  }

  if (requirement.isError || !requirement.data) {
    return (
      <>
        <PageHeader title="Requirement" />
        <Panel title="Not available">
          <p className="text-muted">
            This requirement does not exist, or it is not one you are able to see.
          </p>
          <Link className="btn btn--secondary" to="/app/requirements">
            Back to requirements
          </Link>
        </Panel>
      </>
    );
  }

  const data: Requirement = requirement.data;

  const move = async (status: 'OPEN' | 'CLOSED') => {
    try {
      await update.mutateAsync({ id: data.id, patch: { status } });
      toast.show(status === 'OPEN' ? 'Requirement published.' : 'Requirement closed.', 'success');
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'The status could not be changed.',
        'error',
      );
    }
  };

  const experience =
    data.minExperienceYears === null && data.maxExperienceYears === null
      ? null
      : data.minExperienceYears !== null && data.maxExperienceYears !== null
        ? `${data.minExperienceYears}–${data.maxExperienceYears} years`
        : data.minExperienceYears !== null
          ? `${data.minExperienceYears}+ years`
          : `up to ${data.maxExperienceYears} years`;

  return (
    <>
      <PageHeader
        title={data.roleTitle}
        subtitle={`${data.companyName}${data.location ? ` · ${data.location}` : ''}`}
        actions={
          mayManage ? (
            <div className="row gap-2">
              {data.status !== 'OPEN' && (
                <Button onClick={() => move('OPEN')} disabled={update.isPending}>
                  {data.status === 'DRAFT' ? 'Publish' : 'Reopen'}
                </Button>
              )}
              {data.status === 'OPEN' && (
                <Button variant="secondary" onClick={() => move('CLOSED')} disabled={update.isPending}>
                  Close
                </Button>
              )}
            </div>
          ) : undefined
        }
      />

      <div className="row gap-2" style={{ marginBottom: 'var(--space-5)' }}>
        <Badge tone={data.status === 'OPEN' ? 'positive' : data.status === 'DRAFT' ? 'caution' : 'neutral'}>
          {data.status}
        </Badge>
        {data.status === 'DRAFT' && (
          <span className="text-muted">
            Not yet published. Candidate discovery will only consider open requirements.
          </span>
        )}
      </div>

      {data.skillsUnresolved.length > 0 && (
        <Panel title="Some skills were not recognised">
          <p className="text-muted">
            {data.skillsUnresolved.join(', ')} — these are not stored and will not be matched on.
            Edit the requirement to use a name CareerFlux knows.
          </p>
        </Panel>
      )}

      <div className="grid grid--two">
        <Panel title="What the company asked for">
          <SkillRow label="Required" skills={data.requiredSkills} hint="conditions" />
          <SkillRow label="Preferred" skills={data.preferredSkills} hint="wishes" />
          <SkillRow label="Optional" skills={data.optionalSkills} />
          {data.requiredSkills.length === 0 && data.preferredSkills.length === 0 && (
            <p className="text-muted">
              No skills recorded. A candidate search against this requirement would have nothing to
              compare, so add them before publishing.
            </p>
          )}
        </Panel>

        <Panel title="Who may be put forward">
          <div className="grid grid--stats">
            {detail('Experience', experience, 'Not stated')}
            {detail('Batch', data.graduationYear, 'Any batch')}
            {detail('Work mode', data.workMode === 'UNSPECIFIED' ? null : data.workMode, 'Not stated')}
            {detail('Drive date', data.driveDate, 'Not scheduled')}
          </div>
          <p className="text-faint" style={{ marginTop: 'var(--space-4)' }}>
            Departments
          </p>
          <p>
            {data.departments.length === 0 ? (
              <span className="text-muted">Open to every department</span>
            ) : (
              data.departments.map((department) => department.name).join(', ')
            )}
          </p>
          {data.minCgpa !== null && (
            <p className="text-muted" style={{ marginTop: 'var(--space-3)' }}>
                The company asks for a CGPA of {data.minCgpa}. This is checked against the CGPA
                the college recorded, and only that — a student's own figure never counts. It
                decides eligibility, not the technical compatibility score, and a student
                whose CGPA the college has not recorded is shown as unknown, not excluded.
            </p>
          )}
        </Panel>
      </div>

      {data.description && (
        <section className="section">
          <Panel title="Notes">
            <p style={{ whiteSpace: 'pre-wrap' }}>{data.description}</p>
          </Panel>
        </section>
      )}

      <section className="section">
        <Panel title="Candidate discovery">
          {data.status === 'OPEN' ? (
            <>
              <p className="text-muted">
                Find students whose profiles are technically relevant to this requirement. Technical
                fit and formal eligibility are shown separately, so a strong candidate is never
                hidden by a condition the college may want to make an exception to.
              </p>
              <div className="row gap-2">
                <Link className="btn btn--primary" to={`/app/requirements/${data.id}/candidates`}>
                  Find candidates
                </Link>
                <Link className="btn btn--secondary" to={`/app/requirements/${data.id}/shortlist`}>
                  {shortlist.data
                    ? `View shortlist (${shortlist.data.shortlistedCount})`
                    : 'View shortlist'}
                </Link>
              </div>
            </>
          ) : (
            <>
              <p className="text-muted">
                {data.status === 'DRAFT'
                  ? 'Open this requirement to discover candidates. A draft is not yet what the company agreed to.'
                  : 'Requirement closed. Reopen it to discover candidates again — the shortlist already built stays readable.'}
              </p>
              {data.status === 'CLOSED' && (
                <Link className="btn btn--secondary" to={`/app/requirements/${data.id}/shortlist`}>
                  {shortlist.data
                    ? `View shortlist (${shortlist.data.shortlistedCount})`
                    : 'View shortlist'}
                </Link>
              )}
            </>
          )}
        </Panel>
      </section>

      <p className="text-faint">
        Recorded by {data.createdByName ?? 'a colleague'} · last changed{' '}
        {new Date(data.updatedAt).toLocaleString()}
      </p>
    </>
  );
}
