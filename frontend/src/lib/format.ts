/** Presentation helpers. Nothing here invents a value it was not given. */

const RELATIVE = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

/** "3 days ago", "just now". Returns null for a null input rather than a guess. */
export function relativeTime(iso: string | null | undefined): string | null {
  if (!iso) {
    return null;
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return null;
  }
  const seconds = Math.round((then - Date.now()) / 1000);
  const absolute = Math.abs(seconds);

  if (absolute < 45) {
    return 'just now';
  }
  const units: [Intl.RelativeTimeFormatUnit, number][] = [
    ['minute', 60],
    ['hour', 3600],
    ['day', 86400],
    ['week', 604800],
    ['month', 2629800],
    ['year', 31557600],
  ];
  let chosen: [Intl.RelativeTimeFormatUnit, number] = units[0];
  for (const unit of units) {
    if (absolute >= unit[1]) {
      chosen = unit;
    }
  }
  return RELATIVE.format(Math.round(seconds / chosen[1]), chosen[0]);
}

export function formatDate(iso: string | null | undefined): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
}

export function formatDateTime(iso: string | null | undefined): string | null {
  if (!iso) {
    return null;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date.toLocaleString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Turns SCREAMING_SNAKE_CASE into "Screaming snake case". */
export function humanize(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  const lower = value.toLowerCase().replace(/_/g, ' ');
  return lower.charAt(0).toUpperCase() + lower.slice(1);
}

/** Title Case for labels where every word should be capitalised. */
export function titleize(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  return value
    .toLowerCase()
    .replace(/_/g, ' ')
    .split(' ')
    .filter(Boolean)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

const WORK_MODE_LABELS: Record<string, string> = {
  ONSITE: 'On-site',
  HYBRID: 'Hybrid',
  REMOTE: 'Remote',
  UNSPECIFIED: 'Not stated',
};

const EMPLOYMENT_LABELS: Record<string, string> = {
  FULL_TIME: 'Full-time',
  PART_TIME: 'Part-time',
  CONTRACT: 'Contract',
  INTERNSHIP: 'Internship',
  TEMPORARY: 'Temporary',
  UNSPECIFIED: 'Not stated',
};

const SENIORITY_LABELS: Record<string, string> = {
  INTERN: 'Intern',
  ENTRY: 'Entry level',
  JUNIOR: 'Junior',
  MID: 'Mid level',
  SENIOR: 'Senior',
  LEAD: 'Lead',
  PRINCIPAL: 'Principal',
  UNSPECIFIED: 'Not stated',
};

export const workModeLabel = (value: string) => WORK_MODE_LABELS[value] ?? humanize(value);
export const employmentLabel = (value: string) => EMPLOYMENT_LABELS[value] ?? humanize(value);
export const seniorityLabel = (value: string) => SENIORITY_LABELS[value] ?? humanize(value);

export const WORK_MODE_OPTIONS = ['ONSITE', 'HYBRID', 'REMOTE'];
export const EMPLOYMENT_OPTIONS = [
  'FULL_TIME',
  'PART_TIME',
  'CONTRACT',
  'INTERNSHIP',
  'TEMPORARY',
];
export const SENIORITY_OPTIONS = [
  'INTERN',
  'ENTRY',
  'JUNIOR',
  'MID',
  'SENIOR',
  'LEAD',
  'PRINCIPAL',
];

/**
 * "1–3 years", "5+ years", or null when the posting did not say.
 *
 * Every check here is `== null` on purpose, so a field that arrives as null and
 * one that is absent are treated identically. Anything narrower crashes the
 * first time a posting states no experience range, which most of them do.
 */
export function experienceLabel(
  range: { min?: number | null; max?: number | null } | null | undefined,
): string | null {
  const min = range?.min ?? null;
  const max = range?.max ?? null;
  if (min === null && max === null) {
    return null;
  }
  const trim = (value: number) => (Number.isInteger(value) ? String(value) : value.toFixed(1));
  if (min !== null && max !== null) {
    return `${trim(min)}–${trim(max)} years`;
  }
  if (min !== null) {
    return `${trim(min)}+ years`;
  }
  return `Up to ${trim(max as number)} years`;
}

const CURRENCY_SYMBOLS: Record<string, string> = {
  USD: '$',
  INR: '₹',
  EUR: '€',
  GBP: '£',
};

/** Salary is only ever shown when a source stated it. */
export function salaryLabel(
  salary:
    | {
        min?: number | null;
        max?: number | null;
        currency?: string | null;
        period?: string | null;
      }
    | null
    | undefined,
): string | null {
  const min = salary?.min ?? null;
  const max = salary?.max ?? null;
  if (min === null && max === null) {
    return null;
  }
  const symbol = salary?.currency
    ? (CURRENCY_SYMBOLS[salary.currency] ?? `${salary.currency} `)
    : '';
  const compact = (value: number) =>
    value >= 1000 ? `${Math.round(value / 1000)}k` : String(Math.round(value));

  const range =
    min !== null && max !== null
      ? `${symbol}${compact(min)}–${symbol}${compact(max)}`
      : `${symbol}${compact((min ?? max) as number)}`;

  const period =
    salary?.period === 'HOURLY' ? ' / hour' : salary?.period === 'MONTHLY' ? ' / month' : '';
  return range + period;
}

/** The word CareerFlux uses for each score band, matching the backend tiers. */
export function tierLabel(tier: string): string {
  switch (tier) {
    case 'EXCELLENT':
      return 'Excellent match';
    case 'STRONG':
      return 'Strong match';
    case 'MODERATE':
      return 'Moderate match';
    case 'WEAK':
      return 'Weak match';
    default:
      return 'Below threshold';
  }
}

export function initials(name: string | null | undefined): string {
  if (!name) {
    return '?';
  }
  const parts = name.trim().split(/\s+/).slice(0, 2);
  return parts.map((part) => part.charAt(0).toUpperCase()).join('') || '?';
}

/**
 * Deterministic hue from a string, used to give companies without a logo a
 * stable colour instead of a random one that changes on every render.
 */
export function hueFor(seed: string): number {
  let hash = 0;
  for (let index = 0; index < seed.length; index += 1) {
    hash = (hash << 5) - hash + seed.charCodeAt(index);
    hash |= 0;
  }
  return Math.abs(hash) % 360;
}

export function pluralize(count: number, singular: string, plural?: string): string {
  return `${count} ${count === 1 ? singular : (plural ?? `${singular}s`)}`;
}

/** Greeting keyed off local time. Cosmetic, but it makes the dashboard feel addressed to you. */
export function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) {
    return 'Good morning';
  }
  if (hour < 18) {
    return 'Good afternoon';
  }
  return 'Good evening';
}
