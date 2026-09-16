import { useState } from 'react';

import { ApiError } from '../../lib/api';
import { relativeTime } from '../../lib/format';
import { downloadResumeFile, useDeleteResume, useResumes } from '../../lib/queries';
import type { ResumeSummary } from '../../lib/types';
import { Badge, Button, Dialog, Panel, useToast } from '../ui/primitives';

/**
 * Every resume the student has uploaded, with a way to download or delete each.
 *
 * <p>Deleting asks first, and says what goes and what stays: the file, its text
 * and suggestions read from it go; the profile, including anything already
 * accepted from it, stays.
 */
export function ResumeVersionsPanel({ activeId }: { activeId: string | null }) {
  const resumes = useResumes();
  const remove = useDeleteResume();
  const toast = useToast();
  const [deleting, setDeleting] = useState<ResumeSummary | null>(null);

  const list = resumes.data ?? [];
  if (list.length === 0) {
    return null;
  }

  const download = (resume: ResumeSummary) =>
    downloadResumeFile(resume).catch(() => toast.show('That file could not be downloaded.', 'error'));

  return (
    <Panel title="Your resumes">
      <ul className="grid" style={{ gap: 'var(--space-3)' }}>
        {list.map((resume) => (
          <li key={resume.id} className="row between wrap gap-3">
            <div>
              <p style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>{resume.originalFilename}</p>
              <p className="text-muted" style={{ fontSize: 'var(--text-xs)' }}>
                Uploaded {relativeTime(resume.uploadedAt)}
              </p>
            </div>
            <div className="row gap-2">
              {resume.id === activeId && (
                <Badge tone="positive" square>
                  Current
                </Badge>
              )}
              <Button size="sm" variant="ghost" onClick={() => download(resume)}>
                Download
              </Button>
              <Button size="sm" variant="ghost" onClick={() => setDeleting(resume)}>
                Delete
              </Button>
            </div>
          </li>
        ))}
      </ul>

      <Dialog
        open={deleting !== null}
        onClose={() => setDeleting(null)}
        title="Delete this resume?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeleting(null)}>
              Keep it
            </Button>
            <Button
              variant="danger"
              loading={remove.isPending}
              onClick={() =>
                deleting &&
                remove.mutate(deleting.id, {
                  onSuccess: () => {
                    setDeleting(null);
                    toast.show('Resume deleted.', 'success');
                  },
                  onError: (caught) =>
                    toast.show(
                      caught instanceof ApiError ? caught.message : 'That resume could not be deleted.',
                      'error',
                    ),
                })
              }
            >
              Delete resume
            </Button>
          </>
        }
      >
        <p style={{ fontSize: 'var(--text-sm)' }}>
          The file, its extracted text and any suggestions read from it are removed. Your profile, including
          anything you already accepted from it, and your placement records stay as they are.
        </p>
      </Dialog>
    </Panel>
  );
}
