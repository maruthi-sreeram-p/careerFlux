import { describe, expect, it } from 'vitest';

import {
  ALERTS_SUBTITLE,
  ERASURE_EXPLANATION,
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
 * These fail if a false claim comes back: a deletion promise stronger than the
 * erasure Phase 2B ships (Decision 14 found one on the landing page and twice
 * more), an AI promise the consent gate does not keep, or a digest nothing
 * sends (PD-2). Changing any of them should be a decision made together with the
 * feature, not a sentence that drifts in.
 */
const DATA_PROMISES = [LANDING_FOOTNOTE, REGISTER_PRIVACY_NOTE, ...RESUME_HANDLING, ...ERASURE_EXPLANATION];
const ALERT_COPY = [ALERTS_SUBTITLE, IMMEDIATE_ALERTS_HINT, ...Object.values(PRIORITY_LABEL)];

describe('account erasure', () => {
  it('is offered wherever a student reads about their data, with the grace period stated', () => {
    for (const sentence of [LANDING_FOOTNOTE, REGISTER_PRIVACY_NOTE]) {
      expect(sentence).toMatch(/erased/i);
      expect(sentence).toMatch(/30 days/i);
      expect(sentence).toMatch(/change your mind/i);
    }
    expect(ERASURE_EXPLANATION.join(' ')).toMatch(/30-day grace period/i);
    expect(ERASURE_EXPLANATION.join(' ')).toMatch(/cancel/i);
  });

  it('never promises more than it does: no instant removal, no date, nothing about backups disappearing', () => {
    for (const sentence of DATA_PROMISES) {
      expect(sentence).not.toMatch(/immediately|instantly|right away|permanently deleted/i);
      expect(sentence).not.toMatch(/derived (data|from it)|goes with it|everything is (deleted|removed)/i);
      expect(sentence).not.toMatch(/after 30 days (it|your account) (is|will be)/i);
    }
    expect(ERASURE_EXPLANATION.join(' ')).toMatch(/backups expire/i);
  });

  it('says the college keeps its placement records, without the name', () => {
    expect(ERASURE_EXPLANATION.join(' ')).toMatch(/placement records about you, with your name removed/i);
  });
});

describe('resume handling', () => {
  it('promises AI processing only with consent, and only after identifiers are removed', () => {
    const copy = RESUME_HANDLING.join(' ');
    expect(copy).toMatch(/only if you turn AI processing on/i);
    expect(copy).toMatch(/name, contact details and links removed/i);
  });

  it('does not promise limits the retention worker enforces, since it may be paused', () => {
    const copy = RESUME_HANDLING.join(' ');
    expect(copy).not.toMatch(/30 days|two (most recent|previous)/i);
    expect(copy).toMatch(/delete any of them/i);
  });
});

describe('privacy messaging', () => {

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
