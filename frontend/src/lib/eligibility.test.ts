import { describe, expect, it } from 'vitest';

import { ELIGIBILITY_FILTERS, eligibilityLabel, eligibilityTone } from './eligibility';
import type { EligibilityStatus } from './eligibility';

/**
 * The formal verdict as the staff screens show it (Phase 3B, D-2).
 *
 * <p>Formal eligibility has exactly three answers. The server stopped
 * producing ELIGIBLE_WITH_GAPS when the verdict was separated from the
 * scorer — a skill gap is a matching signal — so the screens must not offer a
 * filter for it or carry a branch that can never be reached.
 */
describe('the three formal verdicts', () => {
  const statuses: EligibilityStatus[] = ['ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN'];

  it('gives each verdict its own words and its own tone', () => {
    expect(eligibilityLabel('ELIGIBLE')).toBe('Meets stated requirements');
    expect(eligibilityTone('ELIGIBLE')).toBe('positive');

    // Never "rejected": the college decides who goes forward, not CareerFlux.
    expect(eligibilityLabel('NOT_ELIGIBLE')).toBe('Does not meet a stated requirement');
    expect(eligibilityTone('NOT_ELIGIBLE')).toBe('negative');

    expect(eligibilityLabel('UNKNOWN')).toBe('Cannot be determined');
    expect(eligibilityTone('UNKNOWN')).toBe('neutral');
  });

  it('says something specific for every verdict, and never repeats itself', () => {
    const labels = statuses.map(eligibilityLabel);
    expect(new Set(labels).size).toBe(statuses.length);
    for (const label of labels) {
      expect(label.trim()).not.toBe('');
    }
  });
});

describe('the eligibility filter chips', () => {
  it('offers all three verdicts, plus the unfiltered view', () => {
    expect(ELIGIBILITY_FILTERS.map((filter) => filter.id))
      .toEqual(['', 'ELIGIBLE', 'NOT_ELIGIBLE', 'UNKNOWN']);
  });

  it('offers no filter the server can never answer', () => {
    // A chip for a status the API no longer returns would always come back
    // empty, which reads as "nobody qualifies" rather than "this is not a
    // thing any more".
    expect(ELIGIBILITY_FILTERS.map((filter) => filter.id)).not.toContain('ELIGIBLE_WITH_GAPS');
    for (const filter of ELIGIBILITY_FILTERS) {
      expect(filter.label.toLowerCase()).not.toContain('gap');
    }
  });

  it('names every chip', () => {
    for (const filter of ELIGIBILITY_FILTERS) {
      expect(filter.label.trim()).not.toBe('');
    }
  });
});
