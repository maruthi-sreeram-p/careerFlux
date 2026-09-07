import { Link, useParams } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Badge, Chip, EmptyState, Panel, Skeleton } from '../components/ui/primitives';
import { useStudentDetail } from '../lib/queries';
import type { StudentActivityEntry } from '../lib/types';

/** Stages a company has actually reached with this student. */
const STAGE_TONE: Record<string, 'positive' | 'caution' | 'neutral' | 'negative'> = {
  SELECTED: 'positive',
  INTERESTED: 'positive',
  INVITED: 'caution',
  SHORTLISTED: 'neutral',
  NOT_PROCEEDING: 'negative',
  DECLINED: 'negative',
};

function when(value: string | null): string {
  if (!value) {
    return 'Date not recorded';
  }
  return new Date(value).toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <p className="eyebrow">{label}</p>
      <p>{value}</p>
    </div>
  );
}

function ActivityRow({ entry }: { entry: StudentActivityEntry }) {
  return (
    <li className="row gap-3" style={{ alignItems: 'baseline' }}>
      <span className="text-faint mono" style={{ minWidth: 110 }}>{when(entry.at)}</span>
      <span>{entry.summary}</span>
    </li>
  );
}

/**
 * One student, as the placement office needs to see them.
 *
 * <p>Reached from the directory, and scoped exactly like it: a student outside
 * the caller's institution or granted departments answers not-found, so arriving
 * here by editing the URL gets nothing.
 *
 * <p>Deliberately absent: the resume's contents, the student's phone number, and
 * anything resembling a credential. Opening a resume is a separate permission on
 * a separate route and has not been folded into this page.
 *
 * <p>Placement state is listed per company rather than summarised. A student can
 * be SELECTED by one employer and still SHORTLISTED by another, and collapsing
 * that into a single status would have to throw one of them away.
 */
export default function StudentDetail() {
  const { userId } = useParams<{ userId: string }>();
  const student = useStudentDetail(userId);

  if (student.isLoading) {
    return (
      <Panel title="Loading student">
        <Skeleton height={220} radius={12} />
      </Panel>
    );
  }

  if (student.isError || !student.data) {
    return (
      <Panel title="Student not available">
        <p className="text-muted">
          This student is not in your scope, or the record could not be loaded.{' '}
          <Link to="/app/students">Back to students</Link>.
        </p>
      </Panel>
    );
  }

  const detail = student.data;
  const summary = detail.summary;

  return (
    <>
      <PageHeader
        title={summary.fullName}
        subtitle={[summary.departmentName, summary.batchName].filter(Boolean).join(' · ') || undefined}
      />

      <Panel title="Profile">
        <div className="row wrap gap-4">
          <Fact label="Email" value={summary.email} />
          <Fact
            label="Roll number"
            value={summary.rollNumber ?? <span className="text-muted">Not recorded</span>}
          />
          <Fact
            label="Department"
            value={summary.departmentName ?? <span className="text-muted">Unassigned</span>}
          />
          <Fact
            label="Batch"
            value={summary.batchName ?? <span className="text-muted">Unassigned</span>}
          />
          <Fact
            label="CGPA"
            value={
              detail.cgpa ? (
                <>
                  {detail.normalisedCgpa}
                  <span className="text-faint"> ({detail.cgpa} / {detail.cgpaScale})</span>
                </>
              ) : (
                <span className="text-muted">Not recorded</span>
              )
            }
          />
          <Fact label="Profile completion" value={`${summary.profileCompleteness}%`} />
          <Fact
            label="Resume"
            value={summary.resumeUploaded ? 'Uploaded' : <span className="text-muted">None</span>}
          />
        </div>
      </Panel>

      <Panel title="Skills">
        {detail.skills.length === 0 ? (
          <p className="text-muted">No skills recorded yet.</p>
        ) : (
          <div className="row wrap gap-2">
            {detail.skills.map((skill) => (
              <Chip key={skill.id} selected>
                {skill.name}
              </Chip>
            ))}
          </div>
        )}
      </Panel>

      <Panel title="Preferences">
        {detail.preferences.length === 0 ? (
          <p className="text-muted">This student has not set preferences yet.</p>
        ) : (
          <ul className="grid" style={{ gap: 'var(--space-2)' }}>
            {detail.preferences.map((preference) => (
              <li key={preference}>{preference}</li>
            ))}
          </ul>
        )}
      </Panel>

      <Panel title="Placement">
        {detail.placements.length === 0 ? (
          <p className="text-muted">Not shortlisted for any requirement yet.</p>
        ) : (
          <div className="data-table__scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Company</th>
                  <th>Role</th>
                  <th>Stage</th>
                  <th>Since</th>
                </tr>
              </thead>
              <tbody>
                {detail.placements.map((placement) => (
                  <tr key={placement.requirementId}>
                    <td className="data-table__primary">{placement.companyName}</td>
                    <td>{placement.roleTitle}</td>
                    <td>
                      <Badge tone={STAGE_TONE[placement.stage] ?? 'neutral'}>
                        {placement.stage.replace(/_/g, ' ')}
                      </Badge>
                    </td>
                    <td>{when(placement.stageChangedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Panel>

      <Panel title="Recent activity">
        {detail.activity.length === 0 ? (
          <EmptyState
            title="Nothing recorded yet"
            body="Resume uploads, job applications and placement stage changes appear here."
          />
        ) : (
          <ul className="grid" style={{ gap: 'var(--space-2)' }}>
            {detail.activity.map((entry, index) => (
              <ActivityRow key={`${entry.type}-${entry.at}-${index}`} entry={entry} />
            ))}
          </ul>
        )}
      </Panel>
    </>
  );
}
