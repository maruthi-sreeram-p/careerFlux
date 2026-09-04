import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Badge, Chip, Panel } from '../components/ui/primitives';
import {
  useAddToShortlist,
  useCandidateDiscovery,
  useRemoveFromShortlist,
} from '../lib/queries';
import { useAuth } from '../lib/auth';
import { can } from '../lib/types';
import type { DiscoveredCandidate } from '../lib/types';
import { Button, useToast } from '../components/ui/primitives';
import { CgpaLine } from '../components/profile/CgpaLine';
import { StageBadge } from '../components/placement/StageControls';

/**
 * The placement team's workspace for one company requirement.
 *
 * <p>Built to be worked through rather than admired: a dense, comparable row
 * per student, with the two answers side by side. Technical compatibility says
 * how well the profile lines up with what the company described. Eligibility
 * says whether the conditions the company stated are met. They are never
 * multiplied together, and a student who fails a condition is never removed —
 * they appear ranked on merit with the condition named, which is the entire
 * reason this screen exists.
 *
 * <p>Every number and every phrase comes from the server's scorer. Nothing here
 * composes an explanation of its own.
 */
const ELIGIBILITY_FILTERS = [
  { id: '', label: 'All' },
  { id: 'ELIGIBLE', label: 'Eligible' },
  { id: 'ELIGIBLE_WITH_GAPS', label: 'Eligible with gaps' },
  { id: 'NOT_ELIGIBLE', label: 'Not eligible' },
  { id: 'UNKNOWN', label: 'Unknown' },
];

const SORTS = [
  { id: 'match', label: 'Best match' },
  { id: 'eligibility', label: 'Eligibility' },
  { id: 'experience', label: 'Experience' },
];

const SHORTLIST_FILTERS: { id: string; label: string; value: boolean | undefined }[] = [
  { id: 'all', label: 'All', value: undefined },
  { id: 'yes', label: 'Shortlisted', value: true },
  { id: 'no', label: 'Not shortlisted', value: false },
];

const SCORE_FLOORS = [
  { id: 0, label: 'Any' },
  { id: 60, label: '60+' },
  { id: 70, label: '70+' },
  { id: 80, label: '80+' },
];

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
      // Never "rejected". CareerFlux does not decide who gets hired.
      return 'Does not meet a stated requirement';
    default:
      return 'Cannot be determined';
  }
}

function SkillList({
  label,
  matched,
  missing,
}: {
  label: string;
  matched: string[];
  missing: string[];
}) {
  if (matched.length === 0 && missing.length === 0) {
    return null;
  }
  return (
    <div className="candidate__skills">
      <span className="candidate__skills-label">{label}</span>
      <span className="row wrap gap-2">
        {matched.map((skill) => (
          <span className="skill-tick skill-tick--has" key={`m-${skill}`}>
            ✓ {skill}
          </span>
        ))}
        {missing.map((skill) => (
          <span className="skill-tick skill-tick--lacks" key={`x-${skill}`}>
            ✕ {skill}
          </span>
        ))}
      </span>
    </div>
  );
}

function ShortlistAction({
  candidate,
  requirementId,
  editable,
}: {
  candidate: DiscoveredCandidate;
  requirementId: string;
  editable: boolean;
}) {
  const toast = useToast();
  const add = useAddToShortlist();
  const remove = useRemoveFromShortlist();
  const busy = add.isPending || remove.isPending;

  if (!editable) {
    // A coordinator can read a shortlist but not change one, and the
    // requirement must be open. State without a control, rather than a button
    // that would be refused.
    return candidate.shortlisted ? (
      <StageBadge stage={candidate.placementStage ?? 'SHORTLISTED'} />
    ) : null;
  }

  const act = async (shortlisting: boolean) => {
    const mutation = shortlisting ? add : remove;
    try {
      await mutation.mutateAsync({ requirementId, candidateId: candidate.candidateId });
      toast.show(
        shortlisting ? 'Candidate shortlisted.' : 'Candidate removed from shortlist.',
        'success',
      );
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'That decision could not be recorded.',
        'error',
      );
    }
  };

  // No optimistic state. The server decides, and only then does this change.
  if (candidate.shortlisted) {
    const stage = candidate.placementStage ?? 'SHORTLISTED';
    // Once a drive has actually started moving for somebody, the place to work
    // is the shortlist screen, where the stage and its history are. Removing
    // them from here would discard that trail without showing it first.
    const started = stage !== 'SHORTLISTED';
    return (
      <span className="row gap-2">
        <StageBadge stage={stage} />
        {started ? (
          <Link className="btn btn--ghost" to={`/app/requirements/${requirementId}/shortlist`}>
            Open
          </Link>
        ) : (
          <Button variant="ghost" onClick={() => act(false)} disabled={busy}>
            {remove.isPending ? 'Removing…' : 'Remove'}
          </Button>
        )}
      </span>
    );
  }
  return (
    <Button variant="secondary" onClick={() => act(true)} disabled={busy}>
      {add.isPending ? 'Shortlisting…' : 'Shortlist'}
    </Button>
  );
}

function CandidateRow({
  candidate,
  requirementId,
  editable,
  minCgpa,
}: {
  candidate: DiscoveredCandidate;
  requirementId: string;
  editable: boolean;
  minCgpa: number | null;
}) {
  const [open, setOpen] = useState(false);
  const scored = candidate.compatibility !== null;
  const lowConfidence =
    candidate.confidence === 'LOW' || candidate.confidence === 'INSUFFICIENT';

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
            {scored ? (
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

      {lowConfidence && (
        <p className="candidate__caveat">
          {candidate.confidence === 'INSUFFICIENT'
            ? 'Too little profile information to score. Review this student directly before deciding.'
            : `Limited profile information (${candidate.confidenceCoverage}% of factors known). Review before making a decision.`}
        </p>
      )}

      {candidate.eligibilityReasons.length > 0 && (
        <ul className="candidate__reasons">
          {candidate.eligibilityReasons.map((reason) => (
            <li key={reason}>{reason}</li>
          ))}
        </ul>
      )}

      <SkillList
        label="Required"
        matched={candidate.matchedRequiredSkills}
        missing={candidate.missingRequiredSkills}
      />
      <SkillList
        label="Preferred"
        matched={candidate.matchedPreferredSkills}
        missing={candidate.missingPreferredSkills}
      />
      <CgpaLine candidate={candidate} minCgpa={minCgpa} />

      <div className="candidate__foot">
        <button type="button" className="candidate__toggle" onClick={() => setOpen((was) => !was)}>
          {open ? 'Hide breakdown' : 'Why this score?'}
        </button>
        <ShortlistAction
          candidate={candidate}
          requirementId={requirementId}
          editable={editable}
        />
      </div>

      {open && (
        <div className="candidate__breakdown">
          <div className="row wrap gap-2">
            {candidate.dimensions.map((dimension) => (
              <span className="dimension" key={dimension.dimension}>
                <span className="dimension__name">{dimension.dimension.replace('_', ' ')}</span>
                <span className="dimension__value">
                  {dimension.score === null ? 'unknown' : dimension.score}
                </span>
              </span>
            ))}
          </div>
          {candidate.strengths.length > 0 && (
            <div className="candidate__reasons-block">
              <p className="text-faint">Strengths</p>
              <ul>
                {candidate.strengths.map((reason, index) => (
                  <li key={`s-${index}`}>
                    {reason.label}
                    {reason.detail ? <span className="text-muted"> — {reason.detail}</span> : null}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {candidate.gaps.length > 0 && (
            <div className="candidate__reasons-block">
              <p className="text-faint">Gaps</p>
              <ul>
                {candidate.gaps.map((reason, index) => (
                  <li key={`g-${index}`}>
                    {reason.label}
                    {reason.detail ? <span className="text-muted"> — {reason.detail}</span> : null}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </article>
  );
}

export default function CandidateDiscovery() {
  const { requirementId } = useParams();
  const [eligibility, setEligibility] = useState('');
  const [sort, setSort] = useState('match');
  const [minScore, setMinScore] = useState(0);
  const [shortlistFilter, setShortlistFilter] = useState('all');
  const { user } = useAuth();

  const discovery = useCandidateDiscovery(requirementId, {
    eligibility: eligibility || undefined,
    minScore: minScore || undefined,
    sort,
    shortlisted: SHORTLIST_FILTERS.find((entry) => entry.id === shortlistFilter)?.value,
  });

  if (discovery.isError) {
    return (
      <>
        <PageHeader title="Candidate discovery" />
        <Panel title="Candidates could not be found">
          <p className="text-muted">
            This requirement may be a draft, closed, or not one you are able to open. Candidates can
            only be discovered for an open requirement.
          </p>
          <Link className="btn btn--secondary" to="/app/requirements">
            Back to requirements
          </Link>
        </Panel>
      </>
    );
  }

  const data = discovery.data;
  // Shortlisting is a placement write, and the requirement has to be open.
  // Shortlisting, not authoring. A coordinator holds this and not
  // PLACEMENT_DRIVE_MANAGE, which is what lets them put their own
  // department's students forward without being able to write the
  // requirement. The server enforces both the permission and the scope;
  // this only decides whether the button is worth showing.
  const mayDecide = can(user, 'PLACEMENT_SHORTLIST_MANAGE')
      && data?.requirementStatus === 'OPEN';

  return (
    <>
      <PageHeader
        title="Candidate discovery"
        subtitle={data ? `${data.companyName} · ${data.roleTitle}` : 'Loading…'}
        actions={
          requirementId ? (
            <Link className="btn btn--secondary" to={`/app/requirements/${requirementId}`}>
              Requirement
            </Link>
          ) : undefined
        }
      />

      {data && (
        <div className="discovery__brief">
          <div>
            <span className="text-faint">Required</span>
            <span className="row wrap gap-2">
              {data.requiredSkills.length === 0 ? (
                <span className="text-muted">None stated</span>
              ) : (
                data.requiredSkills.map((skill) => (
                  <Chip key={skill} selected>
                    {skill}
                  </Chip>
                ))
              )}
            </span>
          </div>
          {data.preferredSkills.length > 0 && (
            <div>
              <span className="text-faint">Preferred</span>
              <span className="row wrap gap-2">
                {data.preferredSkills.map((skill) => (
                  <Chip key={skill}>{skill}</Chip>
                ))}
              </span>
            </div>
          )}
          <div>
            <span className="text-faint">Target</span>
            <span className="text-secondary">
              {[
                data.targetDepartments.length > 0
                  ? data.targetDepartments.join(' · ')
                  : 'Every department',
                data.targetGraduationYear ? `Batch ${data.targetGraduationYear}` : 'Any batch',
              ].join(' · ')}
            </span>
          </div>
        </div>
      )}

      {data && !data.cgpaAvailable && data.minCgpa !== null && (
        <Panel title="CGPA cannot be checked">
          <p className="text-muted">
            This company asks for a CGPA of {data.minCgpa}. CareerFlux does not hold a verified
            numeric CGPA for any student, so that condition shows as unknown rather than being
            guessed from a grade field. Technical compatibility below is unaffected.
          </p>
        </Panel>
      )}

      <div className="discovery__filters">
        <div className="row wrap gap-2">
          <span className="text-faint">Eligibility</span>
          {ELIGIBILITY_FILTERS.map((filter) => (
            <Chip
              key={filter.id || 'all'}
              selected={eligibility === filter.id}
              onClick={() => setEligibility(filter.id)}
            >
              {filter.label}
            </Chip>
          ))}
        </div>
        <div className="row wrap gap-2">
          <span className="text-faint">Minimum match</span>
          {SCORE_FLOORS.map((floor) => (
            <Chip key={floor.id} selected={minScore === floor.id} onClick={() => setMinScore(floor.id)}>
              {floor.label}
            </Chip>
          ))}
        </div>
        <div className="row wrap gap-2">
          <span className="text-faint">Shortlist</span>
          {SHORTLIST_FILTERS.map((filter) => (
            <Chip
              key={filter.id}
              selected={shortlistFilter === filter.id}
              onClick={() => setShortlistFilter(filter.id)}
            >
              {filter.label}
            </Chip>
          ))}
        </div>
        <div className="row wrap gap-2">
          <span className="text-faint">Sort</span>
          {SORTS.map((option) => (
            <Chip key={option.id} selected={sort === option.id} onClick={() => setSort(option.id)}>
              {option.label}
            </Chip>
          ))}
        </div>
      </div>

      {discovery.isLoading && (
        <Panel title="Searching your students">
          <span className="skeleton skeleton--line" />
        </Panel>
      )}

      {data && (
        <>
          <p className="text-muted discovery__count">
            {data.totalElements} of {data.consideredStudents}{' '}
            {data.consideredStudents === 1 ? 'student' : 'students'} in scope
            {eligibility || minScore || shortlistFilter !== 'all' ? ' match these filters' : ''} ·{' '}
            {data.scopeLabel}
            {' · '}
            <strong>{data.shortlistedCount}</strong> shortlisted
          </p>

          {data.content.length === 0 ? (
            <Panel title="No candidates match">
              <p className="text-muted">
                {data.consideredStudents === 0
                  ? 'No students in your scope fall within the departments and batch this requirement targets.'
                  : 'No student in scope matches these filters. Try widening them — a student who does not meet a stated condition is still worth seeing.'}
              </p>
            </Panel>
          ) : (
            <div className="discovery__list">
              {data.content.map((candidate) => (
                <CandidateRow
                  key={candidate.candidateId}
                  candidate={candidate}
                  requirementId={requirementId!}
                  editable={mayDecide}
                  minCgpa={data.minCgpa}
                />
              ))}
            </div>
          )}

          <p className="text-faint discovery__footnote">
            CareerFlux surfaces students whose profiles are technically relevant to what the company
            described, and shows whether the conditions the company stated are met. Deciding who to
            put forward is the placement team's call.
          </p>
        </>
      )}
    </>
  );
}
