
import { PageHeader } from '../../components/layout/AppShell';
import { Panel } from '../../components/ui/primitives';
import { useInstitutionOverview } from '../../lib/queries';
import { Coverage, Distribution, ScopeNote, Stat, StatRow } from './parts';

/**
 * The department coordinator's home.
 *
 * <p>Answers one question: how are the students I support progressing towards
 * placement? Everything here is a count of rows the server allowed this caller
 * to count — a coordinator granted Computer Science sees Computer Science, and
 * the scope note says so, because a short list is otherwise indistinguishable
 * from a small college.
 *
 * <p>There is no placement rate and no success percentage. CareerFlux does not
 * model placement outcomes, and a zero would read as a measurement instead of
 * an absence.
 */
export default function CoordinatorDashboard() {
  const overview = useInstitutionOverview();

  if (overview.isLoading) {
    return (
      <>
        <PageHeader title="Your department" subtitle="Loading department figures…" />
        <div className="grid grid--stats" aria-hidden="true">
          {[0, 1, 2, 3].map((key) => (
            <div className="stat" key={key}>
              <span className="skeleton skeleton--line" />
            </div>
          ))}
        </div>
      </>
    );
  }

  if (overview.isError || !overview.data) {
    return (
      <>
        <PageHeader title="Your department" />
        <Panel title="Figures unavailable">
          <p className="text-muted">
            The department figures could not be loaded. Nothing has changed — try again shortly.
          </p>
        </Panel>
      </>
    );
  }

  const data = overview.data;
  const noStudents = data.studentsInScope === 0;

  return (
    <>
      <PageHeader
        title="Your department"
        subtitle={`Placement readiness across ${data.scopeLabel.toLowerCase()}.`}
      />

      {noStudents ? (
        <Panel title="No students in your scope">
          <p className="text-muted">
            {data.scopeLabel === 'No scope granted'
              ? 'You have not been granted a department or batch yet. Your placement coordinator assigns this, and until then there is nothing for you to see — which is different from your department being empty.'
              : 'No students have been enrolled into your scope yet.'}
          </p>
        </Panel>
      ) : (
        <>
          <StatRow>
            <Stat label="Students" value={data.studentsInScope} tone="accent" />
            <Stat
              label="Active accounts"
              value={data.activeStudents}
              note={
                data.activeStudents === data.studentsInScope
                  ? 'All accounts active'
                  : `${data.studentsInScope - data.activeStudents} disabled`
              }
            />
            <Stat
              label="Average profile completeness"
              value={
                data.averageProfileCompleteness === null
                  ? null
                  : `${data.averageProfileCompleteness}%`
              }
              note={
                data.averageProfileCompleteness === null
                  ? 'No profiles started yet'
                  : 'Across students with a profile'
              }
            />
          </StatRow>

          <ScopeNote scopeLabel={data.scopeLabel} students={data.studentsInScope} />

          <section className="section">
            <h2 className="section__title">Readiness</h2>
            <StatRow>
              <Coverage
                label="Career profile complete"
                covered={data.withCareerProfile}
                total={data.studentsInScope}
                noun="students"
              />
              <Coverage
                label="Resume uploaded"
                covered={data.withResume}
                total={data.studentsInScope}
                noun="students"
              />
              <Coverage
                label="Skills recorded"
                covered={data.withSkills}
                total={data.studentsInScope}
                noun="students"
              />
            </StatRow>
          </section>

          <section className="section">
            <h2 className="section__title">Needs attention</h2>
            <div className="grid grid--two">
              <Panel title="Students without a resume">
                <p className="stat__value">{data.studentsInScope - data.withResume}</p>
                <p className="text-muted">
                  A resume is what CareerFlux reads skills from. Without one, a student is matched
                  only on what they entered by hand.
                </p>
              </Panel>
            </div>
          </section>

          <section className="section">
            <div className="grid grid--two">
              <Distribution
                title="Skills in your department"
                rows={data.topSkills}
                empty="No skills recorded yet. Skills appear once students upload a resume or add them by hand."
              />
              <Distribution
                title="Students by batch"
                rows={data.byBatch}
                empty="No students have been assigned to a batch yet."
              />
            </div>
          </section>
        </>
      )}
    </>
  );
}
