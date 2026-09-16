import { useState } from 'react';

import { ApiError } from '../../lib/api';
import { erasureSummary } from '../../lib/privacy';
import { useCancelStudentErasure, useRequestStudentErasure, useStudentErasure } from '../../lib/queries';
import { Badge, Button, Dialog, Panel, useToast } from '../ui/primitives';

/**
 * A placement coordinator requesting, or cancelling, erasure of a student in
 * their own college. Shown only to staff holding STUDENT_MANAGE; the server
 * checks the permission and the college again on every call.
 */
export function StudentErasurePanel({ userId }: { userId: string }) {
  const erasure = useStudentErasure(userId, true);
  const request = useRequestStudentErasure(userId);
  const cancel = useCancelStudentErasure(userId);
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const summary = erasureSummary(erasure.data);

  const fail = (caught: unknown, fallback: string) =>
    toast.show(caught instanceof ApiError ? caught.message : fallback, 'error');

  return (
    <Panel title="Account erasure" action={<Badge tone={summary.tone}>{summary.title}</Badge>}>
      <div className="grid" style={{ gap: 'var(--space-3)' }}>
        <p className="text-secondary" style={{ fontSize: 'var(--text-sm)' }}>
          {summary.detail} When carried out, the student's identity and personal data are removed and your
          college's placement records about them are kept without their name.
        </p>
        <div className="row gap-2">
          {summary.canRequest && (
            <Button variant="danger" onClick={() => setConfirming(true)}>
              Request erasure
            </Button>
          )}
          {summary.canCancel && (
            <Button
              variant="secondary"
              loading={cancel.isPending}
              onClick={() =>
                cancel.mutate(undefined, {
                  onSuccess: () => toast.show('Erasure request cancelled.', 'success'),
                  onError: (caught) => fail(caught, 'The request could not be cancelled.'),
                })
              }
            >
              Cancel erasure request
            </Button>
          )}
        </div>
      </div>

      <Dialog
        open={confirming}
        onClose={() => setConfirming(false)}
        title="Request erasure of this student's account?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>
              Do not request
            </Button>
            <Button
              variant="danger"
              loading={request.isPending}
              onClick={() =>
                request.mutate(undefined, {
                  onSuccess: () => {
                    setConfirming(false);
                    toast.show('Erasure requested. Nothing is removed during the grace period.', 'success');
                  },
                  onError: (caught) => fail(caught, 'The request could not be made.'),
                })
              }
            >
              Request erasure
            </Button>
          </>
        }
      >
        <p style={{ fontSize: 'var(--text-sm)' }}>
          Nothing is removed during a 30-day grace period, and the request can be cancelled until it is carried
          out.
        </p>
      </Dialog>
    </Panel>
  );
}
