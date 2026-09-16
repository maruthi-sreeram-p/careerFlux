import type { ConsentOverview, ConsentPurpose, ErasureView, PurposeState } from './types';

/**
 * How consent and erasure read on screen.
 *
 * <p>Pure functions over what the server returned. The server decides whether AI
 * processing is allowed and whether an erasure can still be cancelled; these only
 * turn that answer into words, so a screen can never show a switch as on, or a
 * request as cancellable, when the server says otherwise.
 */

export const PURPOSE_LABEL: Record<ConsentPurpose, string> = {
  PRIVACY_NOTICE: 'Privacy notice',
  RESUME_PROCESSING: 'How your resume is handled',
  AI_PROCESSING: 'AI processing of your resume',
};

export function purposeState(
  overview: ConsentOverview | undefined,
  purpose: ConsentPurpose,
): PurposeState | undefined {
  return overview?.purposes.find((state) => state.purpose === purpose);
}

export interface AiStatus {
  on: boolean;
  label: string;
  detail: string;
}

/** What the AI processing control says, from the server's answer alone. */
export function aiProcessingStatus(state: PurposeState | undefined): AiStatus {
  if (state?.active) {
    return {
      on: true,
      label: 'On',
      detail:
        'When you upload a resume, its text is sent to an AI provider with your name, contact details and links removed first.',
    };
  }
  if (state?.reconsentRequired) {
    return {
      on: false,
      label: 'Off',
      detail: 'The AI processing notice has changed. Read the new version to turn AI processing back on.',
    };
  }
  return {
    on: false,
    label: 'Off',
    detail: 'Your resume is read on CareerFlux with a simpler parser. Nothing is sent to an AI provider.',
  };
}

export interface ErasureSummary {
  tone: 'neutral' | 'caution' | 'negative' | 'positive';
  title: string;
  detail: string;
  canRequest: boolean;
  canCancel: boolean;
}

function day(iso: string): string {
  return new Date(iso).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

/** How an erasure request reads to the student it is about, or to their placement coordinator. */
export function erasureSummary(view: ErasureView | null | undefined, now: Date = new Date()): ErasureSummary {
  if (!view || view.status === 'CANCELLED') {
    return {
      tone: 'neutral',
      title: view ? 'Erasure request cancelled' : 'No erasure requested',
      detail: 'Nothing has been removed.',
      canRequest: true,
      canCancel: false,
    };
  }
  switch (view.status) {
    case 'GRACE_PERIOD':
      return new Date(view.graceEndsAt).getTime() > now.getTime()
        ? {
            tone: 'caution',
            title: 'Erasure requested',
            detail: `Nothing has been removed yet. The grace period ends on ${day(view.graceEndsAt)}, and the request can be cancelled until it is carried out.`,
            canRequest: false,
            canCancel: view.cancellable,
          }
        : {
            tone: 'caution',
            title: 'Erasure waiting to be carried out',
            detail: 'The grace period has ended. Nothing has been removed yet, and the request can still be cancelled until it is carried out.',
            canRequest: false,
            canCancel: view.cancellable,
          };
    case 'PROCESSING':
    case 'FAILED':
      return {
        tone: 'negative',
        title: 'Erasure in progress',
        detail: 'The erasure is being carried out and can no longer be cancelled.',
        canRequest: false,
        canCancel: false,
      };
    case 'COMPLETED':
      return {
        tone: 'positive',
        title: 'Erased',
        detail: `This account was erased on ${day(view.completedAt ?? view.requestedAt)}.`,
        canRequest: false,
        canCancel: false,
      };
    default:
      return { tone: 'neutral', title: 'Unknown', detail: '', canRequest: false, canCancel: false };
  }
}

/** The name a downloaded export is saved under. */
export function exportFileName(date: Date = new Date()): string {
  return `careerflux-my-data-${date.toISOString().slice(0, 10)}.json`;
}
