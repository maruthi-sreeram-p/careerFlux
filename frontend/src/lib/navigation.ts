import type { SessionUser } from './types';
import { can } from './types';

/**
 * Which home screen a signed-in person gets, and what their sidebar contains.
 *
 * <p>This is deliberately plain data with no React in it. The navigation is the
 * part of the shell most likely to be got wrong in a way nobody notices — a
 * coordinator quietly offered a link meant for the portal administrator — and
 * keeping it as a pure function means the rules can be asserted directly
 * instead of by rendering a tree and reading it back.
 *
 * Every decision is made from a permission rather than a role name. The role
 * enum is what the backend grants permissions from; screens should ask what the
 * user may do, so a role added later inherits sensible navigation instead of
 * falling through to the student view. The server re-checks all of it — this
 * only decides what is worth offering.
 */

export type DashboardKind =
  | 'student'
  | 'department-coordinator'
  | 'placement-coordinator'
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
 * Ordered most specific first. A placement coordinator holds the department
 * coordinator's permission as well as their own, so the broader grant has to be
 * tested before the narrower one or every placement coordinator would be shown a
 * department coordinator's screen.
 */
export function dashboardKindFor(user: SessionUser | null): DashboardKind {
  if (can(user, 'SOURCE_MANAGE')) return 'platform';
  if (can(user, 'STUDENT_READ_INSTITUTION')) return 'placement-coordinator';
  if (can(user, 'STUDENT_READ_SCOPED')) return 'department-coordinator';
  return 'student';
}

/** What this person's dashboard is called, in their own terms. */
export function dashboardTitle(kind: DashboardKind): string {
  switch (kind) {
    case 'platform':
      return 'Platform operations';
    case 'placement-coordinator':
      return 'College placement';
    case 'department-coordinator':
      return 'Your department';
    default:
      return 'Your career';
  }
}

export function navigationFor(user: SessionUser | null): NavGroup[] {
  switch (dashboardKindFor(user)) {
    case 'platform':
      return platformNav();
    case 'placement-coordinator':
      return placementCoordinatorNav();
    case 'department-coordinator':
      return departmentCoordinatorNav();
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
      // Distinct from "Applications". Those are jobs the student found and
      // applied to themselves; these are roles their college put them forward
      // for, and to a student those are not the same thing at all.
      label: 'Through your college',
      items: [{ to: '/app/placements', label: 'Placements', icon: 'Building' }],
    },
    {
      label: 'You',
      items: [
        { to: '/app/notifications', label: 'Alerts', icon: 'Bell' },
        { to: '/app/profile', label: 'Career profile', icon: 'User', end: true },
        { to: '/app/profile?tab=Preferences', label: 'Preferences', icon: 'Sliders' },
        { to: '/app/account', label: 'Account', icon: 'User' },
      ],
    },
  ];
}

/**
 * A department coordinator's screen is about their department, not their own
 * career.
 *
 * <p>Company requirements are readable here; authoring them is the placement
 * coordinator's, and the requirements screen offers only what this role may do.
 */
function departmentCoordinatorNav(): NavGroup[] {
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
      // No Sources entry. The registry is the portal administrator's console
      // (Decisions 12 and 15) and the server refuses it to college staff.
      label: 'Market',
      items: [{ to: '/app/discover', label: 'Job market', icon: 'Compass' }],
    },
    {
      label: 'You',
      items: [
        { to: '/app/notifications', label: 'Alerts', icon: 'Bell' },
        { to: '/app/account', label: 'Account', icon: 'User' },
      ],
    },
  ];
}

/**
 * The placement coordinator runs the college: its placement and its setup.
 *
 * <p>One sidebar for what used to be two roles. The placement work comes first
 * because it is what the role does most days; setting up departments, batches
 * and staff sits beside the student directory because both are about the
 * college as a whole.
 */
function placementCoordinatorNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Dashboard', icon: 'Dashboard', end: true }] },
    {
      label: 'Placement',
      items: [{ to: '/app/requirements', label: 'Company requirements', icon: 'Building' }],
    },
    {
      label: 'Institution',
      items: [
        { to: '/app/students', label: 'Students', icon: 'Layers' },
        { to: '/app/institution', label: 'Departments & staff', icon: 'Sliders' },
      ],
    },
    {
      // No Sources entry. The registry is the portal administrator's console
      // (Decisions 12 and 15) and the server refuses it to college staff.
      label: 'Market',
      items: [{ to: '/app/discover', label: 'Job market', icon: 'Compass' }],
    },
    {
      label: 'You',
      items: [
        { to: '/app/notifications', label: 'Alerts', icon: 'Bell' },
        { to: '/app/account', label: 'Account', icon: 'User' },
      ],
    },
  ];
}

function platformNav(): NavGroup[] {
  return [
    { items: [{ to: '/app', label: 'Operations', icon: 'Terminal', end: true }] },
    {
      // Onboarding a college is the first thing a new deployment does, and
      // until it happens nobody else can sign in at all. Its own group rather
      // than sitting under Intelligence, which is about job sources.
      label: 'Colleges',
      items: [{ to: '/app/admin/institutions', label: 'Institutions', icon: 'Building' }],
    },
    {
      label: 'Intelligence',
      items: [
        { to: '/app/admin/sources', label: 'Source registry', icon: 'Sliders' },
        { to: '/app/sources', label: 'Sources', icon: 'Radar' },
      ],
    },
    {
      label: 'You',
      items: [
        { to: '/app/notifications', label: 'Alerts', icon: 'Bell' },
        { to: '/app/account', label: 'Account', icon: 'User' },
      ],
    },
  ];
}

/** Every destination a role is offered, for assertions and for route guarding. */
export function navigationTargets(user: SessionUser | null): string[] {
  return navigationFor(user).flatMap((group) => group.items.map((item) => item.to));
}
