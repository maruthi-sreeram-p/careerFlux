import { describe, expect, it } from 'vitest';

import { dashboardKindFor, navigationFor, navigationTargets } from './navigation';
import type { SessionUser, UserRole } from './types';

/**
 * Navigation is decided from permissions, and getting it wrong is quiet: a
 * coordinator offered the platform operator's screens, or a college
 * administrator sent back to the student dashboard they were never meant to
 * see. None of that throws, so it has to be asserted.
 *
 * <p>The permission sets below are copied from the backend's UserRole enum. If
 * those ever diverge, these tests are where it should be noticed.
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

const COORDINATOR = user('PLACEMENT_COORDINATOR', [
  'STUDENT_READ_SCOPED',
  'ANALYTICS_VIEW',
  'JOB_MARKET_VIEW',
  'PLACEMENT_DRIVE_VIEW',
  'ANNOUNCEMENT_SEND',
]);

const OFFICER = user('PLACEMENT_OFFICER', [
  'STUDENT_READ_SCOPED',
  'STUDENT_READ_INSTITUTION',
  'STUDENT_RESUME_READ',
  'ANALYTICS_VIEW',
  'JOB_MARKET_VIEW',
  'PLACEMENT_DRIVE_VIEW',
  'PLACEMENT_DRIVE_MANAGE',
  'PLACEMENT_ELIGIBILITY_MANAGE',
  'ANNOUNCEMENT_SEND',
  'AUDIT_READ_INSTITUTION',
]);

const COLLEGE_ADMIN = user('COLLEGE_ADMIN', [
  'INSTITUTION_SETTINGS_MANAGE',
  'DEPARTMENT_MANAGE',
  'BATCH_MANAGE',
  'STAFF_MANAGE',
  'STUDENT_MANAGE',
  'ANALYTICS_VIEW',
  'AUDIT_READ_INSTITUTION',
]);

const PLATFORM_ADMIN = user('PLATFORM_ADMIN', [
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
  it('gives each role its own home screen', () => {
    expect(dashboardKindFor(STUDENT)).toBe('student');
    expect(dashboardKindFor(COORDINATOR)).toBe('coordinator');
    expect(dashboardKindFor(OFFICER)).toBe('officer');
    expect(dashboardKindFor(COLLEGE_ADMIN)).toBe('college-admin');
    expect(dashboardKindFor(PLATFORM_ADMIN)).toBe('platform');
  });

  it('does not mistake an officer for a coordinator', () => {
    // An officer holds STUDENT_READ_SCOPED as well as the institution-wide
    // grant. Testing the narrow permission first would have given every officer
    // the coordinator's screen, which is the bug this ordering prevents.
    expect(OFFICER.permissions).toContain('STUDENT_READ_SCOPED');
    expect(dashboardKindFor(OFFICER)).toBe('officer');
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
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
    expect(targets).not.toContain('/app/sources');
  });
});

describe('institutional navigation', () => {
  it('gives a coordinator their department, not their own career', () => {
    const targets = navigationTargets(COORDINATOR);
    expect(targets).toContain('/app/students');
    // A coordinator has no career profile to complete and no saved jobs.
    expect(targets).not.toContain('/app/profile');
    expect(targets).not.toContain('/app/saved');
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
  });

  it('gives an officer the student directory without platform operations', () => {
    const targets = navigationTargets(OFFICER);
    expect(targets).toContain('/app/students');
    expect(targets).not.toContain('/app/saved');
    expect(targets.some((target) => target.startsWith('/app/admin'))).toBe(false);
  });

  it('does not offer a college admin the directory their role cannot read', () => {
    // COLLEGE_ADMIN holds STUDENT_MANAGE but not STUDENT_READ_SCOPED, which is
    // what /api/institution/students requires. Offering the link would produce
    // a refusal, so it is not offered.
    expect(COLLEGE_ADMIN.permissions).not.toContain('STUDENT_READ_SCOPED');
    expect(navigationTargets(COLLEGE_ADMIN)).not.toContain('/app/students');
  });

  it('offers a college admin the institution screen their permissions back', () => {
    // Departments, batches and staff are the three things this role can
    // actually change, and until now there was no way to reach any of them:
    // every one of its permissions had an endpoint behind it and none had a
    // link.
    expect(navigationTargets(COLLEGE_ADMIN)).toContain('/app/institution');
    expect(COLLEGE_ADMIN.permissions).toContain('DEPARTMENT_MANAGE');
    expect(COLLEGE_ADMIN.permissions).toContain('BATCH_MANAGE');
    expect(COLLEGE_ADMIN.permissions).toContain('STAFF_MANAGE');
  });

  it('does not offer a college admin the placement workflow they cannot use', () => {
    // They hold neither PLACEMENT_DRIVE_MANAGE nor PLACEMENT_SHORTLIST_MANAGE,
    // so a requirements link would lead somewhere the server refuses.
    expect(COLLEGE_ADMIN.permissions).not.toContain('PLACEMENT_SHORTLIST_MANAGE');
    expect(navigationTargets(COLLEGE_ADMIN)).not.toContain('/app/requirements');
  });

  it('does not offer the institution screen to anyone who cannot administer one', () => {
    for (const person of [STUDENT, COORDINATOR, OFFICER, PLATFORM_ADMIN]) {
      expect(navigationTargets(person)).not.toContain('/app/institution');
    }
  });

  it('offers the source registry to the platform operator and nobody else', () => {
    // /api/sources is Portal Admin only (Decisions 12 and 15). A link for
    // anyone else would lead to a refusal.
    for (const person of [STUDENT, COORDINATOR, OFFICER, COLLEGE_ADMIN]) {
      expect(navigationTargets(person)).not.toContain('/app/sources');
    }
    expect(navigationTargets(PLATFORM_ADMIN)).toContain('/app/sources');
  });

  it('keeps the platform operator on platform concerns', () => {
    const targets = navigationTargets(PLATFORM_ADMIN);
    expect(targets).toContain('/app/admin/sources');
    expect(targets).toContain('/app/sources');
    // Not a student, and not running any one college.
    expect(targets).not.toContain('/app/discover');
    expect(targets).not.toContain('/app/profile');
    expect(targets).not.toContain('/app/students');
  });
});

describe('navigation shape', () => {
  const everyone = [STUDENT, COORDINATOR, OFFICER, COLLEGE_ADMIN, PLATFORM_ADMIN];

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

  it('gives every role a distinct sidebar', () => {
    // Compared including group headings, because that is what the reader sees.
    const shapes = everyone.map((person) =>
      navigationFor(person)
        .map((group) => `${group.label ?? ''}:${group.items.map((item) => item.to).join(',')}`)
        .join('|'),
    );
    expect(new Set(shapes).size).toBe(everyone.length);
  });

  it('leads each placement role with what their job starts from', () => {
    // Both roles reach the same screens, but not in the same order, and the
    // order is the information architecture. A coordinator opens their day on
    // their department; an officer opens it on what companies have asked for.
    const coordinatorGroups = navigationFor(COORDINATOR).map((group) => group.label);
    const officerGroups = navigationFor(OFFICER).map((group) => group.label);

    expect(coordinatorGroups).toContain('Department');
    expect(officerGroups).toContain('Institution');
    expect(coordinatorGroups.indexOf('Department')).toBeLessThan(
      coordinatorGroups.indexOf('Placement'),
    );
    expect(officerGroups.indexOf('Placement')).toBeLessThan(officerGroups.indexOf('Institution'));
    expect(dashboardKindFor(COORDINATOR)).not.toBe(dashboardKindFor(OFFICER));
  });

  it('offers company requirements to both placement roles and nobody else', () => {
    // Reading is gated on PLACEMENT_DRIVE_VIEW, which only these two hold.
    expect(navigationTargets(COORDINATOR)).toContain('/app/requirements');
    expect(navigationTargets(OFFICER)).toContain('/app/requirements');

    expect(navigationTargets(STUDENT)).not.toContain('/app/requirements');
    expect(navigationTargets(COLLEGE_ADMIN)).not.toContain('/app/requirements');
    expect(navigationTargets(PLATFORM_ADMIN)).not.toContain('/app/requirements');
  });

  it('does not offer candidate discovery or shortlists before they exist', () => {
    // Phases 4 and 5. A link to an unbuilt screen is worse than no link.
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
