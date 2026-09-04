import { Link } from 'react-router-dom';

import { PageHeader } from '../../components/layout/AppShell';
import { Panel } from '../../components/ui/primitives';
import { useInstitutionOverview } from '../../lib/queries';
import { Coverage, Distribution, Stat, StatRow } from './parts';

/**
 * The placement officer's home.
 *
 * <p>Same facts as the coordinator's screen, asked across the whole college:
 * where does the institution stand, and which departments are behind? The
 * department comparison is the part a coordinator does not get and the officer
 * exists to act on.
 *
 * <p>Deliberately absent: placement rate, offers, packages, company counts.
 * None of those are modelled yet. They arrive with the company requirements
 * work, and inventing them here would put a number on a screen that no row in
 * the database supports.
 */
export default function PlacementOfficerDashboard() {
  const overview = useInstitutionOverview();

  if (overview.isLoading) {
    return (
      <>
        <PageHeader title="College placement" subtitle="Loading institutional figures…" />
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
        <PageHeader title="College placement" />
        <Panel title="Figures unavailable">
          <p className="text-muted">
            The institutional figures could not be loaded. Try again shortly.
          </p>
        </Panel>
      </>
    );
  }

  const data = overview.data;

  return (
    <>
      <PageHeader
        title="College placement"
        subtitle={`Placement readiness across ${data.institutionName}.`}
      />

      {data.studentsInScope === 0 ? (
        <Panel title="No students enrolled yet">
          <p className="text-muted">
            {data.institutionName} has no student accounts. Readiness figures appear once students
            register and begin their profiles.
          </p>
        </Panel>
      ) : (
        <>
          <StatRow>
            <Stat label="Students" value={data.studentsInScope} tone="accent" />
            <Stat
              label="Departments"
              value={data.departmentCount}
              note={`${data.batchCount} ${data.batchCount === 1 ? 'batch' : 'batches'}`}
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
            <Stat
              label="Applications recorded"
              value={data.totalApplications}
              note={
                data.totalApplications === 0
                  ? 'No application activity yet'
                  : `From ${data.studentsWhoApplied} of ${data.studentsInScope} students`
              }
            />
          </StatRow>

          <section className="section">
            <h2 className="section__title">Institution readiness</h2>
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
              <Coverage
                label="Applied at least once"
                covered={data.studentsWhoApplied}
                total={data.studentsInScope}
                noun="students"
              />
            </StatRow>
          </section>

          <section className="section">
            <h2 className="section__title">Across the college</h2>
            <div className="grid grid--two">
              <Distribution
                title="Students by department"
                rows={data.byDepartment}
                empty="No students have been assigned to a department yet."
              />
              <Distribution
                title="Students by batch"
                rows={data.byBatch}
                empty="No students have been assigned to a batch yet."
              />
            </div>
          </section>

          <section className="section">
            <div className="grid grid--two">
              <Distribution
                title="Skill supply across the college"
                rows={data.topSkills}
                empty="No skills recorded yet. Skills appear once students upload a resume or add them by hand."
              />
              <Panel title="Company requirements">
                <p className="text-muted">
                    Write down what a company asked for, then find the students who match
                    it. Technical fit and formal eligibility are shown separately, and
                    nobody is shortlisted until a person decides to.
                  </p>
                  <Link className="btn btn--secondary" to="/app/requirements">
                    Open company requirements
                  </Link>
              </Panel>
            </div>
          </section>
        </>
      )}
    </>
  );
}
