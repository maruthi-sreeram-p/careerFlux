import type { DiscoveredCandidate } from './types';

/**
 * How the staff screens say a formal eligibility verdict.
 *
 * <p>Three answers and no fourth. Formal eligibility asks whether the
 * conditions a company stated are satisfied, from the college's own record of
 * the student; a missing skill is a matching signal and never a verdict, so
 * there is no "eligible with gaps" here. The server stopped producing that
 * status when formal eligibility was separated from the scorer, and a chip
 * offering a filter that can only ever come back empty is worse than none.
 *
 * <p>Kept beside {@code placement.ts} and for the same reason: two screens
 * showed the same verdict and each carried its own copy of these words, which
 * is a thing that drifts.
 */
export type EligibilityStatus = DiscoveredCandidate['eligibility'];

export type EligibilityTone = 'neutral' | 'positive' | 'negative';

/** The filter chips, in the order the discovery screen offers them. */
export const ELIGIBILITY_FILTERS: { id: string; label: string }[] = [
  { id: '', label: 'All' },
  { id: 'ELIGIBLE', label: 'Eligible' },
  { id: 'NOT_ELIGIBLE', label: 'Not eligible' },
  { id: 'UNKNOWN', label: 'Unknown' },
];

export function eligibilityTone(status: EligibilityStatus): EligibilityTone {
  switch (status) {
    case 'ELIGIBLE':
      return 'positive';
    case 'NOT_ELIGIBLE':
      return 'negative';
    default:
      return 'neutral';
  }
}

export function eligibilityLabel(status: EligibilityStatus): string {
  switch (status) {
    case 'ELIGIBLE':
      return 'Meets stated requirements';
    case 'NOT_ELIGIBLE':
      // Never "rejected". CareerFlux does not decide who gets hired.
      return 'Does not meet a stated requirement';
    default:
      return 'Cannot be determined';
  }
}
