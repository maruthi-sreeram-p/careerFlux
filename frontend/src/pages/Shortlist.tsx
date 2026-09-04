import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Badge, Button, Panel, useToast } from '../components/ui/primitives';
import { useAuth } from '../lib/auth';
import { useRemoveFromShortlist, useShortlist } from '../lib/queries';
import { can } from '../lib/types';
import type { DiscoveredCandidate } from '../lib/types';
import { CgpaLine } from '../components/profile/CgpaLine';
import { StageControls } from '../components/placement/StageControls';

/**
 * Who the placement team decided to put forward.
 *
 * <p>Short by design. This is a working list to take into a drive, not a
 * report: the students, why each was relevant, whether the company's stated
 * conditions are met, and a way to take somebody off again.
 *
 * <p>The figures are today's. Nothing was frozen when the decision was made, so
 * a student whose profile has improved shows the better number and one whose
 * eligibility has changed shows that too — <em>without</em> being removed. A
 * person put them on this list and only a person takes them off.
 */
function eligibilityTone(status: DiscoveredCandidate['eligibility']) {
  switch (status) {
    case 'ELIGIBLE':
      return 'positive' as const;
    case 'ELIGIBLE_WITH_GAPS':
      return 'info' as const;
    case 'NOT_ELIGIBLE':
      return 'negative' as const;
    default:
      return 'neutral' as const;
  }
}

function eligibilityLabel(status: DiscoveredCandidate['eligibility']) {
  switch (status) {
    case 'ELIGIBLE':
      return 'Meets stated requirements';
    case 'ELIGIBLE_WITH_GAPS':
      return 'Meets requirements, with gaps';
    case 'NOT_ELIGIBLE':
      return 'Does not meet a stated requirement';
    default:
      return 'Cannot be determined';
  }
}

function ShortlistedRow({
  candidate,
  requirementId,
  requirementStatus,
  editable,
  minCgpa,
}: {
  candidate: DiscoveredCandidate;
  requirementId: string;
  requirementStatus: string;
  editable: boolean;
  minCgpa: number | null;
}) {
  const toast = useToast();
  const remove = useRemoveFromShortlist();

  const withdraw = async () => {
    try {
      await remove.mutateAsync({ requirementId, candidateId: candidate.candidateId });
      toast.show('Candidate removed from shortlist.', 'success');
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'That candidate could not be removed.',
        'error',
      );
    }
  };

  return (
    <article className="candidate">
      <header className="candidate__head">
        <div>
          <h3 className="candidate__name">{candidate.fullName}</h3>
          <p className="text-muted">
            {[candidate.department, candidate.batch].filter(Boolean).join(' · ') || 'Unassigned'}
            {candidate.primaryRole ? ` · ${candidate.primaryRole}` : ''}
            {candidate.yearsExperience !== null ? ` · ${candidate.yearsExperience} yrs` : ''}
          </p>
        </div>
        <div className="candidate__verdicts">
          <div className="candidate__score">
            {candidate.compatibility !== null ? (
              <>
                <span className="candidate__score-value">{candidate.compatibility}%</span>
                <span className="candidate__score-label">technical compatibility</span>
              </>
            ) : (
              <>
                <span className="candidate__score-value candidate__score-value--none">—</span>
                <span className="candidate__score-label">not enough information</span>
              </>
            )}
          </div>
          <Badge tone={eligibilityTone(candidate.eligibility)}>
            {eligibilityLabel(candidate.eligibility)}
          </Badge>
        </div>
      </header>

      {candidate.eligibilityReasons.length > 0 && (
        <ul className="candidate__reasons">
          {candidate.eligibilityReasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}

      {candidate.matchedRequiredSkills.length > 0 && (
        <div className="candidate__skills">
          <span className="candidate__skills-label">Strong</span>
          <span className="row wrap gap-2">
            {candidate.matchedRequiredSkills.map((skill) => (
              <span className="skill-tick skill-tick--has" key={skill}>
                ✓ {skill}
              </span>
            ))}
          </span>
        </div>
      )}

      {candidate.missingRequiredSkills.length > 0 && (
        <div className="candidate__skills">
          <span className="candidate__skills-label">Gap</span>
          <span className="row wrap gap-2">
            {candidate.missingRequiredSkills.map((skill) => (
              <span className="skill-tick skill-tick--lacks" key={skill}>
                ✕ {skill}
              </span>
            ))}
          </span>
        </div>
      )}

      <CgpaLine candidate={candidate} minCgpa={minCgpa} />

      <StageControls
        requirementId={requirementId}
        candidateId={candidate.candidateId}
        candidateName={candidate.fullName}
        stage={candidate.placementStage ?? 'SHORTLISTED'}
        requirementStatus={requirementStatus}
        editable={editable}
      />

      {editable && (
        <div className="candidate__foot">
          <span className="text-faint">
            Removing takes them off the list entirely; the workflow above records what happened.
          </span>
          <Button variant="ghost" onClick={withdraw} disabled={remove.isPending}>
            {remove.isPending ? 'Removing…' : 'Remove from shortlist'}
          </Button>
        </div>
      )}
    </article>
  );
}

export default function Shortlist() {
  const { requirementId } = useParams();
  const { user } = useAuth();
  const shortlist = useShortlist(requirementId);

  const data = shortlist.data;
  // Changing a shortlist is a placement write, and only while the drive is open.
  // Same split as the discovery screen: a coordinator may take somebody off
  // their own shortlist without being able to close the drive.
  const editable = can(user, 'PLACEMENT_SHORTLIST_MANAGE') && data?.requirementStatus === 'OPEN';

  if (shortlist.isError) {
    return (
      <>
        <PageHeader title="Shortlist" />
        <Panel title="Shortlist unavailable">
          <p className="text-muted">
            This requirement does not exist, or it is not one you are able to open.
          </p>
          <Link className="btn btn--secondary" to="/app/requirements">
            Back to requirements
          </Link>
        </Panel>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Shortlist"
        subtitle={data ? `${data.companyName} · ${data.roleTitle}` : 'Loading…'}
        actions={
          requirementId ? (
            <div className="row gap-2">
              <Link className="btn btn--secondary" to={`/app/requirements/${requirementId}`}>
                Requirement
              </Link>
              {data?.requirementStatus === 'OPEN' && (
                <Link
                  className="btn btn--primary"
                  to={`/app/requirements/${requirementId}/candidates`}
                >
                  Find more candidates
                </Link>
              )}
            </div>
          ) : undefined
        }
      />

      {shortlist.isLoading && (
        <Panel title="Loading shortlist">
          <span className="skeleton skeleton--line" />
        </Panel>
      )}

      {data && (
        <>
          <p className="text-muted discovery__count">
            <strong>{data.shortlistedCount}</strong>{' '}
            {data.shortlistedCount === 1 ? 'candidate' : 'candidates'} shortlisted
            {data.requirementStatus !== 'OPEN'
              ? ` · requirement ${data.requirementStatus.toLowerCase()}, this list is read-only`
              : ''}
          </p>

          {data.content.length === 0 ? (
            <Panel title="No candidates shortlisted yet">
              <p className="text-muted">
                {data.shortlistedCount > 0
                  ? 'Candidates have been shortlisted for this requirement, but none of them are within the students you are able to see.'
                  : 'Open candidate discovery, review who is relevant, and shortlist the students you want to put forward.'}
              </p>
              {data.requirementStatus === 'OPEN' && requirementId && (
                <Link
                  className="btn btn--primary"
                  to={`/app/requirements/${requirementId}/candidates`}
                >
                  Find candidates
                </Link>
              )}
            </Panel>
          ) : (
            <div className="discovery__list">
              {data.content.map((candidate) => (
                <ShortlistedRow
                  key={candidate.candidateId}
                  candidate={candidate}
                  requirementId={requirementId!}
                  requirementStatus={data.requirementStatus}
                  editable={editable}
                  minCgpa={data.minCgpa}
                />
              ))}
            </div>
          )}

          <p className="text-faint discovery__footnote">
            Everyone here was chosen by a person. CareerFlux does not add or remove candidates from
            a shortlist on its own, and a student whose score or eligibility changes later stays on
            the list until someone decides otherwise.
          </p>
        </>
      )}
    </>
  );
}
