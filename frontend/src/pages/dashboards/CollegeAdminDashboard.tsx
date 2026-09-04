import { PageHeader } from '../../components/layout/AppShell';
import { Panel } from '../../components/ui/primitives';
import { useAuth } from '../../lib/auth';
import { useInstitutionOverview } from '../../lib/queries';
import { Coverage, Distribution, Stat, StatRow } from './parts';

/**
 * The college administrator's home.
 *
 * <p>This role configures the institution and manages its people. It is not a
 * placement screen and it is emphatically not a student screen — no recommended
 * jobs, no match scores, no "your profile is 0% complete", which is what this
 * account used to be shown.
 *
 * <p>The question is how the institution is set up and how well it is
 * populated: departments, batches, staff, and whether the students who exist
 * have usable records.
 */
export default function CollegeAdminDashboard() {
  const { user } = useAuth();
  const overview = useInstitutionOverview();

  if (overview.isLoading) {
    return (
      <>
        <PageHeader title="Institution" subtitle="Loading institution figures…" />
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
        <PageHeader title="Institution" />
        <Panel title="Figures unavailable">
          <p className="text-muted">The institution figures could not be loaded.</p>
        </Panel>
      </>
    );
  }

  const data = overview.data;

  return (
    <>
      <PageHeader
        title="Institution"
        subtitle={`How ${data.institutionName} is configured and populated.`}
      />

      <StatRow>
        <Stat label="Students" value={data.studentsInScope} tone="accent" />
        <Stat
          label="Departments"
          value={data.departmentCount}
          note={data.departmentCount === 0 ? 'None configured yet' : undefined}
        />
        <Stat
          label="Batches"
          value={data.batchCount}
          note={data.batchCount === 0 ? 'None configured yet' : undefined}
        />
        <Stat label="Staff accounts" value={data.staffCount} note="Coordinators, officers, admins" />
      </StatRow>

      <section className="section">
        <h2 className="section__title">Record quality</h2>
        <p className="text-muted" style={{ marginBottom: 'var(--space-4)' }}>
          How complete the student records are. Placement matching reads these, so gaps here become
          gaps in every candidate search later.
        </p>
        {data.studentsInScope === 0 ? (
          <Panel title="No students enrolled yet">
            <p className="text-muted">
              Record quality figures appear once students register with {data.institutionName}.
            </p>
          </Panel>
        ) : (
          <StatRow>
            <Coverage
              label="Has a candidate profile"
              covered={data.withCandidateProfile}
              total={data.studentsInScope}
              noun="students"
            />
            <Coverage
              label="Finished onboarding"
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
              label="Assigned to a department"
              covered={data.byDepartment.reduce((sum, row) => sum + row.count, 0)}
              total={data.studentsInScope}
              noun="students"
            />
          </StatRow>
        )}
      </section>

      <section className="section">
        <div className="grid grid--two">
          <Distribution
            title="Students by department"
            rows={data.byDepartment}
            empty="No departments have students assigned. Assign students so coordinators can support them."
          />
          <Distribution
            title="Students by batch"
            rows={data.byBatch}
            empty="No batches have students assigned."
          />
        </div>
      </section>

      <section className="section">
        <Panel title="Institution">
          <div className="grid grid--stats">
            <Stat label="Name" value={data.institutionName} />
            <Stat label="Your role" value="College administrator" />
            <Stat label="Signed in as" value={user?.email ?? '—'} />
          </div>
          <p className="text-muted">
              Departments, batches and staff accounts are managed through the institution
              API. Students appear here once they register with a college email address; put
              them in a department and a batch so company requirements can target them.
          </p>
        </Panel>
      </section>
    </>
  );
}
