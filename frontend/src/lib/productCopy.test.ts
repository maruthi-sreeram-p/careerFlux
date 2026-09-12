import { describe, expect, it } from 'vitest';

import {
  ALERTS_SUBTITLE,
  IMMEDIATE_ALERTS_HINT,
  LANDING_FOOTNOTE,
  PRIORITY_LABEL,
  REGISTER_PRIVACY_NOTE,
  RESUME_HANDLING,
  SHOW_DIGEST_PREFERENCE,
} from './productCopy';

/**
 * The product must not promise what it cannot do.
 *
 * These fail if a false claim comes back: the deletion promise Decision 14
 * found on the landing page (and twice more, under registration and on the
 * profile), or a digest nothing sends (PD-2). Restoring either should be a
 * decision made together with the feature, not a sentence that drifts back in.
 */
const DATA_PROMISES = [LANDING_FOOTNOTE, REGISTER_PRIVACY_NOTE, ...RESUME_HANDLING];
const ALERT_COPY = [ALERTS_SUBTITLE, IMMEDIATE_ALERTS_HINT, ...Object.values(PRIORITY_LABEL)];

describe('account deletion', () => {
  it('is never promised, because it does not exist yet', () => {
    for (const sentence of DATA_PROMISES) {
      expect(sentence).not.toMatch(/delete your account|deleting your account/i);
      expect(sentence).not.toMatch(/derived (data|from it)|goes with it/i);
    }
  });

  it('is described as unavailable wherever a student reads about their data', () => {
    expect(LANDING_FOOTNOTE).toMatch(/deletion is not available yet/i);
    expect(REGISTER_PRIVACY_NOTE).toMatch(/deletion is not available yet/i);
    expect(RESUME_HANDLING.join(' ')).toMatch(/deletion is not available yet/i);
  });

  it('keeps the privacy messaging that is true', () => {
    expect(LANDING_FOOTNOTE).toMatch(/your resume stays yours/i);
    expect(REGISTER_PRIVACY_NOTE).toMatch(/treated as sensitive data/i);
  });
});

describe('the digest', () => {
  it('has no switch while nothing sends one', () => {
    expect(SHOW_DIGEST_PREFERENCE).toBe(false);
  });

  it('is not promised anywhere a student reads about alerts', () => {
    for (const sentence of ALERT_COPY) {
      expect(sentence).not.toMatch(/digest/i);
    }
  });

  it('still names every priority the server can send', () => {
    expect(Object.keys(PRIORITY_LABEL).sort()).toEqual(['DIGEST', 'HIGH', 'IMMEDIATE', 'LOW']);
  });
});
