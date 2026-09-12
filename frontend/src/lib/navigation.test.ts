import { describe, expect, it } from 'vitest';

import { dashboardKindFor, navigationFor, navigationTargets } from './navigation';
import type { SessionUser, UserRole } from './types';

/**
 * Navigation is decided from permissions, and getting it wrong is quiet: a
 * department coordinator offered the portal administrator's screens, or the
 * college's placement coordinator sent to a screen they cannot use. None of
 * that throws, so it has to be asserted.
 *
 * <p>The permission sets below are copied from the backend's UserRole enum —
 * the four roles of the four-actor model. If those ever diverge, these tests are
 * where it should be noticed.
 */

function user(role: UserRole, permissions: string[]): SessionUser {
  return {
    id: 'user-1',
    email: 'person@example.com',
    fullName: 'Test Person',
    role,
    permissions,
    institutionId: 'institution-1',
    institutionName: 'Example Institute of Technology',
    candidateId: null,
    onboardingStage: null,
  };
}

const STUDENT = user('STUDENT', [
  'SELF_PROFILE_MANAGE',
  'SELF_JOBS_MANAGE',
  'SELF_AI_USE',
  'SELF_ACCOUNT_DELETE',
]);

const DEPARTMENT_COORDINATOR = user('DEPARTMENT_COORDINATOR', [
  'STUDENT_READ_SCOPED',
  'ANALYTICS_VIEW',
  'JOB_MARKET_VIEW',
  'PLACEMENT_DRIVE_VIEW',
  'PLACEMENT_SHORTLIST_MANAGE',
  'ANNOUNCEMENT_SEND',
]);

const PLACEMENT_COORDINATOR = user('PLACEMENT_COORDINATOR', [
  'STUDENT_READ_SCOPED',
  'STUDENT_READ_INSTITUTION',
  'STUDENT_RESUME_READ',
  'STUDENT_MANAGE',
  'ANALYTICS_VIEW',
  'JOB_MARKET_VIEW',
  'PLACEMENT_DRIVE_VIEW',
  'PLACEMENT_DRIVE_MANAGE',
  'PLACEMENT_SHORTLIST_MANAGE',
  'PLACEMENT_ELIGIBILITY_MANAGE',
  'ANNOUNCEMENT_SEND',
  'INSTITUTION_SETTINGS_MANAGE',
  'DEPARTMENT_MANAGE',
  'BATCH_MANAGE',
  'STAFF_MANAGE',
  'AUDIT_READ_INSTITUTION',
]);

const PORTAL_ADMIN = user('PORTAL_ADMIN', [
  'SOURCE_VIEW',
  'SOURCE_MANAGE',
  'INGESTION_MANAGE',
  'SYSTEM_HEALTH_VIEW',
  'INSTITUTION_PROVISION',
  'AUDIT_READ_PLATFORM',
  'AI_USAGE_MANAGE',
  'JOB_MARKET_VIEW',
]);

describe('dashboard resolution', () => {
  it('gives each of the four roles its own home screen', () => {
    expect(dashboardKindFor(STUDENT)).toBe('student');
    expect(dashboardKindFor(DEPARTMENT_COORDINATOR)).toBe('department-coordinator');
    expect(dashboardKindFor(PLACEMENT_COORDINATOR)).toBe('placement-coordinator');
    expect(dashboardKindFor(PORTAL_ADMIN)).toBe('platform');
  });

  it('does not mistake a placement coordinator for a department coordinator', () => {
    // The placement coordinator holds STUDENT_READ_SCOPED as well as the
    // institution-wide grant. Testing the narrow permission first would have
    // given every placement coordinator the department screen, which is the bug
    // this ordering prevents.
    expect(PLACEMENT_COORDINATOR.permissions).toContain('STUDENT_READ_SCOPED');
    expect(dashboardKindFor(PLACEMENT_COORDINATOR)).toBe('placement-coordinator');
  });

  it('decides from permissions, not from the role name', () => {
    // PLACEMENT_COORDINATOR once named the department-scoped role. A session
    // carrying that name with only department permissions must still get the
    // department screen, never the college-wide one.
    const staleName = user('PLACEMENT_COORDINATOR', DEPARTMENT_COORDINATOR.permissions);
    expect(dashboardKindFor(staleName)).toBe('department-coordinator');
  });

  it('falls back to the student view for a signed-out or unknown caller', () => {
    expect(dashboardKindFor(null)).toBe('student');
    expect(dashboardKindFor(user('STUDENT', []))).toBe('student');
  });
});

describe('student navigation', () => {
  it('is unchanged in substance and points only at student screens', () => {
    const targets = navigationTargets(STUDENT);
    expect(targets).toEqual([
      '/app',
      '/app/discover',
      '/app/saved',
      '/app/saved?tab=applied',
      '/app/placements',
      '/app/notifications',
      '/app/profile',
      '/app/profile?tab=Preferences',
      '/app/account',
    ]);
  });

  it('separates what the college put them forward for from what they applied to', () => {
    // "Applications" are jobs the student found and applied to themselves.
    // "Placements" are roles their college put them forward for. Collapsing the
    // two would tell a student they applied to something they did not.
    const items = navigationFor(STUDENT).flatMap((group) => group.items);
    const placements = items.find((item) => item.to === '/app/placements');
    const applications = items.find((item) => item.to === '/app/saved?tab=applied');
    expect(placements?.label).toBe('Placements');
    expect(applications?.label).toBe('Applications');
    expect(placements?.label).not.toBe(applications?.label);
  });

  it('never offers a student anything institutional or administrative', () => {
    const targets = navigationTargets(STUDENT);
    expect(targets).not.toContain('/app/students');
    expect(targets).not.toContain('/app/institution');
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
    expect(targets).not.toContain('/app/sources');
  });
});

describe('institutional navigation', () => {
  it('gives a department coordinator their department, not their own career', () => {
    const targets = navigationTargets(DEPARTMENT_COORDINATOR);
    expect(targets).toContain('/app/students');
    // No career profile to complete and no saved jobs.
    expect(targets).not.toContain('/app/profile');
    expect(targets).not.toContain('/app/saved');
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
  });

  it('gives the placement coordinator the college: its students, its setup and its placement', () => {
    const targets = navigationTargets(PLACEMENT_COORDINATOR);
    expect(targets).toContain('/app/students');
    expect(targets).toContain('/app/institution');
    expect(targets).toContain('/app/requirements');
    expect(targets).not.toContain('/app/saved');
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
  });

  it('offers the institution screen only to the role that can administer a college', () => {
    // Departments, batches and staff belong to the placement coordinator.
    expect(PLACEMENT_COORDINATOR.permissions).toEqual(
      expect.arrayContaining(['DEPARTMENT_MANAGE', 'BATCH_MANAGE', 'STAFF_MANAGE']),
    );
    for (const person of [STUDENT, DEPARTMENT_COORDINATOR, PORTAL_ADMIN]) {
      expect(navigationTargets(person)).not.toContain('/app/institution');
    }
  });

  it('offers the source registry to the portal administrator and nobody else', () => {
    // /api/sources is Portal Admin only (Decisions 12 and 15). A link for
    // anyone else would lead to a refusal.
    for (const person of [STUDENT, DEPARTMENT_COORDINATOR, PLACEMENT_COORDINATOR]) {
      expect(navigationTargets(person)).not.toContain('/app/sources');
    }
    expect(navigationTargets(PORTAL_ADMIN)).toContain('/app/sources');
  });

  it('keeps the portal administrator on platform concerns', () => {
    const targets = navigationTargets(PORTAL_ADMIN);
    expect(targets).toContain('/app/admin/sources');
    expect(targets).toContain('/app/sources');
    // Not a student, and not running any one college.
    expect(targets).not.toContain('/app/discover');
    expect(targets).not.toContain('/app/profile');
    expect(targets).not.toContain('/app/students');
    expect(targets).not.toContain('/app/institution');
  });
});

describe('navigation shape', () => {
  const everyone = [STUDENT, DEPARTMENT_COORDINATOR, PLACEMENT_COORDINATOR, PORTAL_ADMIN];

  it('starts every role at a home entry', () => {
    for (const person of everyone) {
      const [first] = navigationFor(person);
      expect(first.items[0].to).toBe('/app');
      expect(first.items[0].end).toBe(true);
    }
  });

  it('offers no duplicate destinations', () => {
    for (const person of everyone) {
      const targets = navigationTargets(person);
      expect(new Set(targets).size).toBe(targets.length);
    }
  });

  it('gives each of the four roles a distinct sidebar', () => {
    // Compared including group headings, because that is what the reader sees.
    const shapes = everyone.map((person) =>
      navigationFor(person)
        .map((group) => `${group.label ?? ''}:${group.items.map((item) => item.to).join(',')}`)
        .join('|'),
    );
    expect(new Set(shapes).size).toBe(everyone.length);
  });

  it('leads each coordinator with what their job starts from', () => {
    // Both reach the students and the requirements, but not in the same order,
    // and the order is the information architecture. A department coordinator
    // opens their day on their department; the placement coordinator opens it on
    // what companies have asked for.
    const departmentGroups = navigationFor(DEPARTMENT_COORDINATOR).map((group) => group.label);
    const placementGroups = navigationFor(PLACEMENT_COORDINATOR).map((group) => group.label);

    expect(departmentGroups).toContain('Department');
    expect(placementGroups).toContain('Institution');
    expect(departmentGroups.indexOf('Department')).toBeLessThan(departmentGroups.indexOf('Placement'));
    expect(placementGroups.indexOf('Placement')).toBeLessThan(placementGroups.indexOf('Institution'));
    expect(dashboardKindFor(DEPARTMENT_COORDINATOR)).not.toBe(dashboardKindFor(PLACEMENT_COORDINATOR));
  });

  it('offers company requirements to both coordinators and nobody else', () => {
    // Reading is gated on PLACEMENT_DRIVE_VIEW, which only these two hold.
    expect(navigationTargets(DEPARTMENT_COORDINATOR)).toContain('/app/requirements');
    expect(navigationTargets(PLACEMENT_COORDINATOR)).toContain('/app/requirements');

    expect(navigationTargets(STUDENT)).not.toContain('/app/requirements');
    expect(navigationTargets(PORTAL_ADMIN)).not.toContain('/app/requirements');
  });

  it('does not offer candidate discovery or shortlists as separate screens', () => {
    // Both live inside a requirement. A link to an unbuilt screen is worse than
    // no link.
    for (const person of everyone) {
      const targets = navigationTargets(person);
      expect(targets).not.toContain('/app/candidates');
      expect(targets).not.toContain('/app/shortlists');
    }
  });

  it('labels every group after the first', () => {
    for (const person of everyone) {
      const groups = navigationFor(person);
      expect(groups[0].label).toBeUndefined();
      for (const group of groups.slice(1)) {
        expect(group.label).toBeTruthy();
      }
    }
  });
});
