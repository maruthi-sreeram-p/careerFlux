import { describe, expect, it } from 'vitest';

import {
  APPOINTABLE_ROLES,
  EMPTY_BATCH_FORM,
  EMPTY_DEPARTMENT_FORM,
  EMPTY_STAFF_FORM,
  buildBatchRequest,
  buildDepartmentRequest,
  buildStaffRequest,
  canAdministerCollege,
  canManageBatches,
  canManageDepartments,
  canManageStaff,
  canSubmitBatch,
  canSubmitDepartment,
  canSubmitStaff,
  isValidGraduationYear,
  requiresDepartment,
  scopeLabel,
} from './administration';
import type { SessionUser } from './types';

function userWith(permissions: string[]): SessionUser {
  return {
    id: 'u-1',
    email: 'someone@example.com',
    fullName: 'Someone',
    role: 'PLACEMENT_COORDINATOR',
    permissions,
    institutionId: 'i-1',
    institutionName: 'Example College',
    candidateId: null,
    onboardingStage: null,
  };
}

/** Copied from the backend's UserRole enum, as in the navigation tests. */
const PLACEMENT_COORDINATOR = [
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
];
const DEPARTMENT_COORDINATOR = [
  'STUDENT_READ_SCOPED',
  'ANALYTICS_VIEW',
  'JOB_MARKET_VIEW',
  'PLACEMENT_DRIVE_VIEW',
  'PLACEMENT_SHORTLIST_MANAGE',
  'ANNOUNCEMENT_SEND',
];

describe('who is offered college administration', () => {
  it('offers every section to the placement coordinator', () => {
    const coordinator = userWith(PLACEMENT_COORDINATOR);
    expect(canManageDepartments(coordinator)).toBe(true);
    expect(canManageBatches(coordinator)).toBe(true);
    expect(canManageStaff(coordinator)).toBe(true);
    expect(canAdministerCollege(coordinator)).toBe(true);
  });

  it('offers none of it to a department coordinator', () => {
    // Supporting one department's students is not administering the college.
    const departmentCoordinator = userWith(DEPARTMENT_COORDINATOR);
    expect(canManageDepartments(departmentCoordinator)).toBe(false);
    expect(canManageBatches(departmentCoordinator)).toBe(false);
    expect(canManageStaff(departmentCoordinator)).toBe(false);
    expect(canAdministerCollege(departmentCoordinator)).toBe(false);
  });

  it('offers none of it to a student or to nobody', () => {
    expect(canAdministerCollege(userWith(['SELF_PROFILE_MANAGE']))).toBe(false);
    expect(canAdministerCollege(null)).toBe(false);
  });

  it('shows only the sections a person actually holds', () => {
    // Permissions are granted per role and could be recombined. A person with
    // only one of the three should see one section, not all or nothing.
    const partial = userWith(['BATCH_MANAGE']);
    expect(canManageDepartments(partial)).toBe(false);
    expect(canManageBatches(partial)).toBe(true);
    expect(canManageStaff(partial)).toBe(false);
    expect(canAdministerCollege(partial)).toBe(true);
  });
});

describe('departments', () => {
  it('needs both a name and a code', () => {
    expect(canSubmitDepartment(EMPTY_DEPARTMENT_FORM)).toBe(false);
    expect(canSubmitDepartment({ name: 'Computer Science', code: '' })).toBe(false);
    expect(canSubmitDepartment({ name: '', code: 'CSE' })).toBe(false);
    expect(canSubmitDepartment({ name: 'Computer Science', code: 'CSE' })).toBe(true);
  });

  it('treats whitespace as empty', () => {
    expect(canSubmitDepartment({ name: '   ', code: 'CSE' })).toBe(false);
  });

  it('sends the code upper-cased, so the confirmation matches what is stored', () => {
    expect(buildDepartmentRequest({ name: '  Information Technology  ', code: ' it ' })).toEqual({
      name: 'Information Technology',
      code: 'IT',
    });
  });
});

describe('batches', () => {
  it('accepts a plausible four-digit year', () => {
    expect(isValidGraduationYear('2027')).toBe(true);
    expect(isValidGraduationYear(' 2026 ')).toBe(true);
  });

  it('rejects a mistyped year rather than storing a batch nobody can explain', () => {
    expect(isValidGraduationYear('20267')).toBe(false);
    expect(isValidGraduationYear('27')).toBe(false);
    expect(isValidGraduationYear('1849')).toBe(false);
    expect(isValidGraduationYear('two thousand')).toBe(false);
    expect(isValidGraduationYear('')).toBe(false);
  });

  it('needs a name as well as a year', () => {
    expect(canSubmitBatch(EMPTY_BATCH_FORM)).toBe(false);
    expect(canSubmitBatch({ name: 'Class of 2027', graduationYear: '' })).toBe(false);
    expect(canSubmitBatch({ name: '', graduationYear: '2027' })).toBe(false);
    expect(canSubmitBatch({ name: 'Class of 2027', graduationYear: '2027' })).toBe(true);
  });

  it('sends the year as a number', () => {
    expect(buildBatchRequest({ name: ' Class of 2027 ', graduationYear: ' 2027 ' })).toEqual({
      name: 'Class of 2027',
      graduationYear: 2027,
    });
  });
});

describe('staff', () => {
  const base = {
    fullName: 'Priya Raman',
    email: 'priya@northgate.edu',
    password: 'CoordinatorPass!2026',
    role: 'PLACEMENT_COORDINATOR',
    departmentCode: '',
  };

  it('offers exactly the two staff roles, with the narrower one first and by default', () => {
    // A slip on this form should under-grant rather than hand somebody the
    // whole college.
    expect(APPOINTABLE_ROLES.map((role) => role.value)).toEqual([
      'DEPARTMENT_COORDINATOR',
      'PLACEMENT_COORDINATOR',
    ]);
    expect(EMPTY_STAFF_FORM.role).toBe('DEPARTMENT_COORDINATOR');
  });

  it('needs a name, an email and a long enough password', () => {
    expect(canSubmitStaff(EMPTY_STAFF_FORM)).toBe(false);
    expect(canSubmitStaff({ ...base, fullName: '' })).toBe(false);
    expect(canSubmitStaff({ ...base, email: '' })).toBe(false);
    expect(canSubmitStaff({ ...base, password: 'short' })).toBe(false);
    expect(canSubmitStaff(base)).toBe(true);
  });

  it('refuses a department coordinator with no department', () => {
    // Without one they would see nobody, which is not what was asked for.
    expect(requiresDepartment('DEPARTMENT_COORDINATOR')).toBe(true);
    expect(canSubmitStaff({ ...base, role: 'DEPARTMENT_COORDINATOR' })).toBe(false);
    expect(
      canSubmitStaff({ ...base, role: 'DEPARTMENT_COORDINATOR', departmentCode: 'CSE' }),
    ).toBe(true);
  });

  it('never asks the placement coordinator for a department', () => {
    // PLACEMENT_COORDINATOR once named the department role; it now covers the
    // whole institution, and the server refuses a department sent with it.
    expect(requiresDepartment('PLACEMENT_COORDINATOR')).toBe(false);
    expect(canSubmitStaff({ ...base, role: 'PLACEMENT_COORDINATOR' })).toBe(true);
    expect(
      buildStaffRequest({ ...base, role: 'PLACEMENT_COORDINATOR', departmentCode: 'CSE' }),
    ).not.toHaveProperty('departmentCode');
  });

  it('refuses a role a college may not appoint, the retired names included', () => {
    // A college cannot mint a portal administrator. The server refuses these
    // too; the form simply never offers them.
    for (const role of ['PORTAL_ADMIN', 'STUDENT', 'PLACEMENT_OFFICER', 'COLLEGE_ADMIN', 'PLATFORM_ADMIN']) {
      expect(canSubmitStaff({ ...base, role })).toBe(false);
    }
  });

  it('sends a department only for a department coordinator', () => {
    expect(buildStaffRequest(base)).not.toHaveProperty('departmentCode');
    expect(
      buildStaffRequest({ ...base, role: 'DEPARTMENT_COORDINATOR', departmentCode: ' cse ' }),
    ).toMatchObject({ role: 'DEPARTMENT_COORDINATOR', departmentCode: 'CSE' });
  });

  it('lower-cases the email and does not trim the password', () => {
    const body = buildStaffRequest({ ...base, email: '  Priya@Northgate.EDU ', password: ' keeps spaces ' });
    expect(body.email).toBe('priya@northgate.edu');
    expect(body.password).toBe(' keeps spaces ');
  });

  it('never sends a permission or an institution', () => {
    // The tenant comes from the session and the role decides the permissions.
    // A form that could name either would be a form worth attacking.
    const body = buildStaffRequest(base) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(['email', 'fullName', 'password', 'role']);
  });
});

describe('how a staff member’s reach reads', () => {
  it('says whole institution only when the server says so', () => {
    expect(scopeLabel({ institutionWide: true, scopeLabels: [] })).toBe('Whole institution');
  });

  it('lists the departments and batches a department coordinator covers', () => {
    expect(scopeLabel({ institutionWide: false, scopeLabels: ['CSE'] })).toBe('CSE');
    expect(scopeLabel({ institutionWide: false, scopeLabels: ['CSE', 'IT'] })).toBe('CSE, IT');
  });

  it('says so when a department coordinator has nothing yet, rather than claiming the college', () => {
    // A department coordinator with no grant sees nobody. Calling that the whole
    // institution was the opposite of the truth.
    expect(scopeLabel({ institutionWide: false, scopeLabels: [] })).toBe('No department or batch yet');
  });
});
