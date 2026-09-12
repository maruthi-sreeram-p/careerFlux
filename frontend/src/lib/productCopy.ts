import type { NotificationView } from './types';

/**
 * Claims the product makes to students, kept in one place so they can be
 * checked against what the build actually does.
 *
 * Every sentence here has to be true today. When a capability ships
 * (self-service account deletion, a digest), the copy changes in the same change
 * that ships it, never ahead of it. `productCopy.test.ts` holds that line.
 */

/**
 * Said wherever a student reads about what happens to their data. Account
 * deletion does not exist yet (Decision 14), so nothing may promise it, and
 * the deletion policy itself is not decided here.
 */
const DELETION_NOT_YET_AVAILABLE = 'Self-service account deletion is not available yet.';

/** The landing page footnote. It used to promise that deletion took the derived data with it. */
export const LANDING_FOOTNOTE = `Free while in development. Your resume stays yours. ${DELETION_NOT_YET_AVAILABLE}`;

/** Under the registration form. It used to promise that deletion removed the resume. */
export const REGISTER_PRIVACY_NOTE = `Your resume and profile are treated as sensitive data. ${DELETION_NOT_YET_AVAILABLE}`;

/** What the profile screen says happens to an uploaded resume. */
export const RESUME_HANDLING: readonly string[] = [
  'The file is stored on disk under a generated name. The name you uploaded is kept for display only.',
  'The extracted text is used to build your profile, which you always get to correct.',
  DELETION_NOT_YET_AVAILABLE,
];

/**
 * How match alerts are routed. Nothing sends a digest: an alert between 70% and
 * 84% is created at once, only at a lower priority.
 */
export const ALERTS_SUBTITLE =
  'CareerFlux only tells you about a role once, and only when it clears your threshold. 95% and above is immediate, 85–94% is high priority, 70–84% is listed at lower priority, and anything below 70% is never sent.';

/** Under the immediate-alerts switch. Turning it off lowers the priority; nothing is batched. */
export const IMMEDIATE_ALERTS_HINT =
  'For matches at 95% and above. Turning this off lists them at lower priority rather than dropping them.';

/**
 * What each alert priority is called on screen. The server's DIGEST priority is
 * shown as lower priority, because no digest is ever sent.
 */
export const PRIORITY_LABEL: Record<NotificationView['priority'], string> = {
  IMMEDIATE: 'Immediate',
  HIGH: 'High priority',
  DIGEST: 'Lower priority',
  LOW: 'Low priority',
};

/**
 * Whether to offer the digest switch. Off: nothing delivers a digest, and a
 * switch would promise one. The stored preference is left as it is. Whether
 * CareerFlux has a digest at all is an open product decision (PD-2), not
 * something this flag decides.
 */
export const SHOW_DIGEST_PREFERENCE: boolean = false;
