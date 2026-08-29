import type { SessionUser } from './types';
import { can } from './types';

/**
 * Which home screen a signed-in person gets, and what their sidebar contains.
 *
 * <p>This is deliberately plain data with no React in it. The navigation is the
 * part of the shell most likely to be got wrong in a way nobody notices — a
 * coordinator quietly offered a link meant for the platform operator — and
 * keeping it as a pure function means the rules can be asserted directly
 * instead of by rendering a tree and reading it back.
 *
 * Every decision is made from a permission rather than a role name. The role
 * enum is what the backend grants permissions from; screens should ask what the
 * user may do, so a sixth role added later inherits sensible navigation instead
 * of falling through to the student view. The server re-checks all of it — this
 * only decides what is worth offering.
 */

export type DashboardKind =
  | 'student'
  | 'coordinator'
  | 'officer'
  | 'college-admin'
  | 'platform';

/** Icon keys, resolved to components by the shell. Kept as strings so this module stays pure. */
export type NavIcon =
  | 'Dashboard'
  | 'Compass'
  | 'Bookmark'
  | 'Document'
  | 'Bell'
  | 'User'
  | 'Sliders'
  | 'Radar'
  | 'Terminal'
  | 'Building'
  | 'Layers'
  | 'Pulse';

export interface NavItem {
  to: string;
  label: string;
  icon: NavIcon;
  /** Only match this route exactly, for entries whose path prefixes others. */
  end?: boolean;
}

export interface NavGroup {
  /** Omitted for the first group, which needs no heading above the home link. */
  label?: string;
  items: NavItem[];
}

/**
 * Ordered most specific first. A placement officer holds the coordinator's
 * permission as well as their own, so the broader grant has to be tested before
 * the narrower one or every officer would be shown a coordinator's screen.
 */
export function dashboardKindFor(user: SessionUser | null): DashboardKind {
  if (can(user, 'SOURCE_MANAGE')) return 'platform';
  if (can(user, 'INSTITUTION_SETTINGS_MANAGE')) return 'college-admin';
  if (can(user, 'STUDENT_READ_INSTITUTION')) return 'officer';
  if (can(user, 'STUDENT_READ_SCOPED')) return 'coordinator';
  return 'student';
}

/** What this person's dashboard is called, in their own terms. */
export function dashboardTitle(kind: DashboardKind): string {
  switch (kind) {
    case 'platform':
      return 'Platform operations';
    case 'college-admin':
      return 'Institution';
    case 'officer':
      return 'College placement';
    case 'coordinator':
      return 'Your department';
    default:
      return 'Your career';
  }
}

export function navigationFor(user: SessionUser | null): NavGroup[] {
  switch (dashboardKindFor(user)) {
    case 'platform':
      return platformNav();
    case 'college-admin':
      return collegeAdminNav();
    case 'officer':
      return officerNav();
    case 'coordinator':
      return coordinatorNav();
    default:
      return studentNav();
  }
}

/**
 * The existing student experience, unchanged in substance.
 *
 * <p>Applications and Preferences are not new screens: applications are the
 * second tab of the saved list, and preferences the second tab of the profile.
 * Naming them here gives the two things a student actually asks for their own
 * entry without inventing a page to hold them.
 */
function studentNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Dashboard', icon: 'Dashboard', end: true }] },
    {
      label: 'Find work',
      items: [
        { to: '/app/discover', label: 'Discover', icon: 'Compass' },
        { to: '/app/saved', label: 'Saved', icon: 'Bookmark', end: true },
        { to: '/app/saved?tab=applied', label: 'Applications', icon: 'Document' },
      ],
    },
    {
      label: 'You',
      items: [
        { to: '/app/notifications', label: 'Alerts', icon: 'Bell' },
        { to: '/app/profile', label: 'Career profile', icon: 'User', end: true },
        { to: '/app/profile?tab=Preferences', label: 'Preferences', icon: 'Sliders' },
      ],
    },
  ];
}

/**
 * A coordinator's screen is about their department, not their own career.
 *
 * <p>Company requirements is real. Candidate discovery and shortlists are not
 * offered yet: they are later phases, and a link to a screen that does not
 * exist is worse than no link at all.
 */
function coordinatorNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Dashboard', icon: 'Dashboard', end: true }] },
    {
      label: 'Department',
      items: [{ to: '/app/students', label: 'Students', icon: 'Layers' }],
    },
    {
      label: 'Placement',
      items: [{ to: '/app/requirements', label: 'Company requirements', icon: 'Building' }],
    },
    {
      label: 'Market',
      items: [
        { to: '/app/discover', label: 'Job market', icon: 'Compass' },
        { to: '/app/sources', label: 'Sources', icon: 'Radar' },
      ],
    },
    { label: 'You', items: [{ to: '/app/notifications', label: 'Alerts', icon: 'Bell' }] },
  ];
}

function officerNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Dashboard', icon: 'Dashboard', end: true }] },
    {
      label: 'Placement',
      items: [{ to: '/app/requirements', label: 'Company requirements', icon: 'Building' }],
    },
    {
      label: 'Institution',
      items: [{ to: '/app/students', label: 'Students', icon: 'Layers' }],
    },
    {
      label: 'Market',
      items: [
        { to: '/app/discover', label: 'Job market', icon: 'Compass' },
        { to: '/app/sources', label: 'Sources', icon: 'Radar' },
      ],
    },
    { label: 'You', items: [{ to: '/app/notifications', label: 'Alerts', icon: 'Bell' }] },
  ];
}

/**
 * The college administrator configures the institution; they do not run
 * placement and they are not a student.
 *
 * <p>There is no Students entry, and that is not an oversight. The directory
 * endpoint requires STUDENT_READ_SCOPED, which this role does not hold — the
 * dashboard shows them institution counts, which they may see, and stops there.
 */
function collegeAdminNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Dashboard', icon: 'Dashboard', end: true }] },
    { label: 'You', items: [{ to: '/app/notifications', label: 'Alerts', icon: 'Bell' }] },
  ];
}

function platformNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Operations', icon: 'Terminal', end: true }] },
    {
      label: 'Intelligence',
      items: [
        { to: '/app/admin/sources', label: 'Source registry', icon: 'Sliders' },
        { to: '/app/sources', label: 'Sources', icon: 'Radar' },
      ],
    },
    { label: 'You', items: [{ to: '/app/notifications', label: 'Alerts', icon: 'Bell' }] },
  ];
}

/** Every destination a role is offered, for assertions and for route guarding. */
export function navigationTargets(user: SessionUser | null): string[] {
  return navigationFor(user).flatMap((group) => group.items.map((item) => item.to));
}
