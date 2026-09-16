import { useState } from 'react';

import { ApiError } from '../../lib/api';
import { formatDateTime } from '../../lib/format';
import { ERASURE_EXPLANATION } from '../../lib/productCopy';
import { PURPOSE_LABEL, aiProcessingStatus, erasureSummary, purposeState } from '../../lib/privacy';
import {
  downloadMyData,
  useAcceptConsent,
  useCancelErasure,
  useConsents,
  useErasure,
  useRequestErasure,
  useWithdrawConsent,
} from '../../lib/queries';
import type { PurposeState } from '../../lib/types';
import { Badge, Button, Dialog, ErrorState, Panel, Skeleton, useToast } from '../ui/primitives';

function errorText(caught: unknown, fallback: string): string {
  return caught instanceof ApiError ? caught.message : fallback;
}

/** The notice text as published, marked when it is engineering placeholder wording. */
function NoticeText({ state }: { state: PurposeState }) {
  return (
    <div className="grid" style={{ gap: 'var(--space-2)' }}>
      <div className="row gap-2 wrap">
        <Badge square>Version {state.currentNotice.version}</Badge>
        {state.currentNotice.placeholder && (
          <Badge tone="caution" square title="Engineering placeholder text, not approved wording">
            Placeholder wording
          </Badge>
        )}
      </div>
      <pre
        className="text-secondary"
        style={{
          whiteSpace: 'pre-wrap',
          fontFamily: 'inherit',
          fontSize: 'var(--text-sm)',
          maxHeight: 180,
          overflowY: 'auto',
          margin: 0,
          padding: 'var(--space-3)',
          border: '1px solid var(--line-faint)',
          borderRadius: 8,
        }}
      >
        {state.currentNotice.body}
      </pre>
    </div>
  );
}

function AiProcessingPanel({ state }: { state: PurposeState }) {
  const accept = useAcceptConsent();
  const withdraw = useWithdrawConsent();
  const toast = useToast();
  const status = aiProcessingStatus(state);

  const turnOn = () =>
    accept.mutate(
      { purpose: 'AI_PROCESSING', noticeVersionId: state.currentNotice.id },
      {
        onSuccess: () => toast.show('AI processing is on for future uploads.', 'success'),
        onError: (caught) => toast.show(errorText(caught, 'AI processing could not be turned on.'), 'error'),
      },
    );
  const turnOff = () =>
    withdraw.mutate('AI_PROCESSING', {
      onSuccess: () =>
        toast.show('AI processing is off. Unanswered AI suggestions were removed.', 'success'),
      onError: (caught) => toast.show(errorText(caught, 'AI processing could not be turned off.'), 'error'),
    });

  return (
    <Panel
      title="AI processing of your resume"
      action={<Badge tone={status.on ? 'positive' : 'neutral'}>{status.label}</Badge>}
    >
      <div className="grid" style={{ gap: 'var(--space-3)' }}>
        <p className="text-secondary" style={{ fontSize: 'var(--text-sm)' }}>
          {status.detail}
        </p>
        <NoticeText state={state} />
        <div className="row gap-2">
          {status.on ? (
            <Button variant="secondary" loading={withdraw.isPending} onClick={turnOff}>
              Turn AI processing off
            </Button>
          ) : (
            <Button variant="primary" loading={accept.isPending} onClick={turnOn}>
              {state.reconsentRequired ? 'Agree to the new notice' : 'Turn AI processing on'}
            </Button>
          )}
        </div>
      </div>
    </Panel>
  );
}

function NoticePanel({ state }: { state: PurposeState }) {
  const accept = useAcceptConsent();
  const toast = useToast();
  const acknowledged = state.active;

  return (
    <Panel
      title={PURPOSE_LABEL[state.purpose]}
      action={<Badge tone={acknowledged ? 'positive' : 'neutral'}>{acknowledged ? 'Acknowledged' : 'Not yet'}</Badge>}
    >
      <div className="grid" style={{ gap: 'var(--space-3)' }}>
        <NoticeText state={state} />
        {!acknowledged && (
          <div>
            <Button
              variant="secondary"
              loading={accept.isPending}
              onClick={() =>
                accept.mutate(
                  { purpose: state.purpose, noticeVersionId: state.currentNotice.id },
                  {
                    onError: (caught) => toast.show(errorText(caught, 'That could not be recorded.'), 'error'),
                  },
                )
              }
            >
              I have read this
            </Button>
          </div>
        )}
      </div>
    </Panel>
  );
}

function MyDataPanel() {
  const toast = useToast();
  const [busy, setBusy] = useState(false);

  const download = async () => {
    setBusy(true);
    try {
      await downloadMyData();
    } catch (caught) {
      toast.show(errorText(caught, 'Your data could not be downloaded.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel title="Your data">
      <div className="grid" style={{ gap: 'var(--space-3)' }}>
        <p className="text-secondary" style={{ fontSize: 'var(--text-sm)' }}>
          Download everything CareerFlux holds about you that you can see: your profile, skills, both CGPA
          figures, resumes, saved and applied jobs, placements, consent history and resume suggestions. Your
          resume files download separately from the Resume tab.
        </p>
        <div>
          <Button variant="secondary" loading={busy} onClick={download}>
            Download my data
          </Button>
        </div>
      </div>
    </Panel>
  );
}

function ErasurePanel() {
  const erasure = useErasure();
  const request = useRequestErasure();
  const cancel = useCancelErasure();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const summary = erasureSummary(erasure.data);

  return (
    <Panel title="Erase your account" action={<Badge tone={summary.tone}>{summary.title}</Badge>}>
      <div className="grid" style={{ gap: 'var(--space-3)' }}>
        <p className="text-secondary" style={{ fontSize: 'var(--text-sm)' }}>
          {summary.detail}
        </p>
        <ul className="grid" style={{ gap: 'var(--space-2)', fontSize: 'var(--text-sm)' }}>
          {ERASURE_EXPLANATION.map((line) => (
            <li key={line} className="text-secondary">
              {line}
            </li>
          ))}
        </ul>
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
                  onSuccess: () => toast.show('Erasure request cancelled. Nothing was removed.', 'success'),
                  onError: (caught) => toast.show(errorText(caught, 'The request could not be cancelled.'), 'error'),
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
        title="Request erasure of your account?"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirming(false)}>
              Keep my account
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
                  onError: (caught) => toast.show(errorText(caught, 'The request could not be made.'), 'error'),
                })
              }
            >
              Request erasure
            </Button>
          </>
        }
      >
        <ul className="grid" style={{ gap: 'var(--space-2)', fontSize: 'var(--text-sm)' }}>
          {ERASURE_EXPLANATION.map((line) => (
            <li key={line}>{line}</li>
          ))}
        </ul>
      </Dialog>
    </Panel>
  );
}

function ConsentHistoryPanel() {
  const consents = useConsents();
  const history = consents.data?.history ?? [];
  return (
    <Panel title="Consent history">
      {history.length === 0 ? (
        <p className="text-muted">Nothing recorded yet.</p>
      ) : (
        <ul className="grid" style={{ gap: 'var(--space-2)', fontSize: 'var(--text-sm)' }}>
          {history.map((event) => (
            <li key={event.id} className="row between wrap gap-2">
              <span>
                {event.action === 'ACCEPTED' ? 'Agreed to' : 'Withdrew'}{' '}
                {PURPOSE_LABEL[event.purpose]}
                <span className="text-muted"> · version {event.noticeVersion}</span>
              </span>
              <span className="text-muted">{formatDateTime(event.at)}</span>
            </li>
          ))}
        </ul>
      )}
    </Panel>
  );
}

/** The Privacy tab: AI processing, notices, the student's data, erasure and history. */
export function PrivacyTab() {
  const consents = useConsents();

  if (consents.isLoading) {
    return <Skeleton height={240} radius={12} />;
  }
  if (consents.isError || !consents.data) {
    return <ErrorState title="Privacy settings could not be loaded" body="Reload the page to try again." />;
  }

  const ai = purposeState(consents.data, 'AI_PROCESSING');
  const privacy = purposeState(consents.data, 'PRIVACY_NOTICE');
  const resume = purposeState(consents.data, 'RESUME_PROCESSING');

  return (
    <div className="grid" style={{ gap: 'var(--space-4)' }}>
      {ai && <AiProcessingPanel state={ai} />}
      {privacy && <NoticePanel state={privacy} />}
      {resume && <NoticePanel state={resume} />}
      <MyDataPanel />
      <ErasurePanel />
      <ConsentHistoryPanel />
    </div>
  );
}
