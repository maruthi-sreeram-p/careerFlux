import { useState } from 'react';

import { PageHeader } from '../components/layout/AppShell';
import { Button, Panel } from '../components/ui/primitives';
import { useInstitutionOverview, useInstitutionStudents } from '../lib/queries';

/**
 * The students a staff member is responsible for.
 *
 * <p>The list is whatever the server returns and nothing is filtered here. A
 * coordinator granted one department receives that department; asking for a
 * page of students they do not cover returns an empty page rather than a
 * refusal, and requesting one such student by id returns not-found. Filtering
 * in the browser would mean the data had already been sent.
 *
 * <p>The scope is stated above the list for the same reason the dashboards
 * state it: a two-row list and a two-student college look identical otherwise.
 */
export default function Students() {
  const [page, setPage] = useState(0);
  const students = useInstitutionStudents(page, 25);
  const overview = useInstitutionOverview();

  const scopeLabel = overview.data?.scopeLabel;
  const data = students.data;

  return (
    <>
      <PageHeader
        title="Students"
        subtitle={
          scopeLabel
            ? scopeLabel === 'Whole institution'
              ? 'Every student in your college.'
              : `Students in ${scopeLabel}.`
            : undefined
        }
      />

      {students.isLoading && (
        <Panel title="Loading students">
          <span className="skeleton skeleton--line" />
        </Panel>
      )}

      {students.isError && (
        <Panel title="Students could not be loaded">
          <p className="text-muted">Try again shortly. Nothing has changed.</p>
        </Panel>
      )}

      {data && data.content.length === 0 && (
        <Panel title="No students in your scope">
          <p className="text-muted">
            {scopeLabel === 'No scope granted'
              ? 'You have not been granted a department or batch yet. A college administrator assigns this.'
              : 'No students have been enrolled into your scope yet.'}
          </p>
        </Panel>
      )}

      {data && data.content.length > 0 && (
        <>
          <div className="data-table__scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Department</th>
                  <th>Batch</th>
                  <th>Profile</th>
                  <th>Resume</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((student) => (
                  <tr key={student.userId}>
                    <td>
                      <span className="data-table__primary">{student.fullName}</span>
                      <span className="text-faint">{student.email}</span>
                    </td>
                    <td>{student.departmentName ?? <span className="text-muted">Unassigned</span>}</td>
                    <td>{student.batchName ?? <span className="text-muted">Unassigned</span>}</td>
                    <td>
                      {student.profileCompleteness === null ? (
                        <span className="text-muted">Not started</span>
                      ) : (
                        `${student.profileCompleteness}%`
                      )}
                    </td>
                    <td>
                      {student.resumeUploaded ? 'Uploaded' : <span className="text-muted">None</span>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {data.totalPages > 1 && (
            <div className="row gap-2" style={{ marginTop: 'var(--space-4)' }}>
              <Button
                variant="secondary"
                disabled={page === 0}
                onClick={() => setPage((current) => Math.max(0, current - 1))}
              >
                Previous
              </Button>
              <span className="text-muted">
                Page {data.page + 1} of {data.totalPages} · {data.totalElements} students
              </span>
              <Button
                variant="secondary"
                disabled={page + 1 >= data.totalPages}
                onClick={() => setPage((current) => current + 1)}
              >
                Next
              </Button>
            </div>
          )}
        </>
      )}
    </>
  );
}
