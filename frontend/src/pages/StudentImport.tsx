import { useRef, useState } from 'react';
import { Link } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Button, Panel, useToast } from '../components/ui/primitives';
import { ApiError, api } from '../lib/api';

interface RowResult {
  row: number;
  email: string;
  status: 'CREATED' | 'SKIPPED';
  message: string;
}

interface ImportResult {
  created: number;
  skipped: number;
  invitesEmailed: boolean;
  rows: RowResult[];
}

/**
 * Adds students from an Excel sheet. Each new student is emailed a link to
 * choose their own password; nobody is given one by the person who uploads.
 */
export default function StudentImport() {
  const toast = useToast();
  const input = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);

  const downloadTemplate = async () => {
    try {
      const blob = await api.download('/api/institution/students/import/template');
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = 'students-template.xlsx';
      link.click();
      URL.revokeObjectURL(url);
    } catch {
      toast.show('The template could not be downloaded.', 'error');
    }
  };

  const upload = async () => {
    if (!file) return;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const form = new FormData();
      form.append('file', file);
      setResult(await api.upload<ImportResult>('/api/institution/students/import', form));
      setFile(null);
      if (input.current) input.current.value = '';
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'That sheet could not be imported.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title="Add students"
        subtitle="Upload an Excel sheet and each student gets an account and an email to set their password."
        actions={
          <Link to="/students">
            <Button variant="secondary">Back to students</Button>
          </Link>
        }
      />

      <Panel title="1. Fill in the sheet">
        <p>
          Columns: <strong>Name</strong>, <strong>College Mail</strong>, <strong>Branch</strong>,{' '}
          <strong>Academic Year</strong> and Section. Branch can be its code (CSE) or full name.
          Academic Year is the year the student graduates (2026) or their batch (2022-2026), so it
          works for any programme length. Up to 500 students per sheet. The batch for that year must
          already exist; ask your placement coordinator if it does not.
        </p>
        <Button variant="secondary" onClick={downloadTemplate}>
          Download the template
        </Button>
      </Panel>

      <Panel title="2. Upload it">
        <div className="grid" style={{ gap: 'var(--space-3)' }}>
          <input
            ref={input}
            type="file"
            accept=".xlsx"
            aria-label="Student sheet"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
          <div>
            <Button onClick={upload} disabled={!file || busy}>
              {busy ? 'Adding students…' : 'Add students'}
            </Button>
          </div>
          {error && <p role="alert">{error}</p>}
        </div>
      </Panel>

      {result && (
        <Panel title="Result">
          <p role="status">
            {result.created} added, {result.skipped} skipped.{' '}
            {result.created > 0 &&
              (result.invitesEmailed
                ? 'Each new student has been emailed a link to set their password.'
                : 'No email server is configured, so no invitations were sent. Students can use "Forgot password" once it is.')}
          </p>
          {result.rows.some((row) => row.status === 'SKIPPED') && (
            <table className="table">
              <thead>
                <tr>
                  <th>Row</th>
                  <th>Email</th>
                  <th>Why it was skipped</th>
                </tr>
              </thead>
              <tbody>
                {result.rows
                  .filter((row) => row.status === 'SKIPPED')
                  .map((row) => (
                    <tr key={row.row}>
                      <td>{row.row}</td>
                      <td>{row.email}</td>
                      <td>{row.message}</td>
                    </tr>
                  ))}
              </tbody>
            </table>
          )}
        </Panel>
      )}
    </>
  );
}
