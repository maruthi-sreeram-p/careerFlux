import { describe, expect, it } from 'vitest';

import {
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
    role: 'COLLEGE_ADMIN',
    permissions,
    institutionId: 'i-1',
    institutionName: 'Example College',
    candidateId: null,
    onboardingStage: null,
  };
}

const COLLEGE_ADMIN = [
  'INSTITUTION_SETTINGS_MANAGE',
  'DEPARTMENT_MANAGE',
  'BATCH_MANAGE',
  'STAFF_MANAGE',
  'STUDENT_MANAGE',
  'ANALYTICS_VIEW',
  'AUDIT_READ_INSTITUTION',
];
const OFFICER = [
  'STUDENT_READ_SCOPED',
  'STUDENT_READ_INSTITUTION',
  'PLACEMENT_DRIVE_VIEW',
  'PLACEMENT_DRIVE_MANAGE',
  'PLACEMENT_SHORTLIST_MANAGE',
];

describe('who is offered college administration', () => {
  it('offers every section to a college administrator', () => {
    const admin = userWith(COLLEGE_ADMIN);
    expect(canManageDepartments(admin)).toBe(true);
    expect(canManageBatches(admin)).toBe(true);
    expect(canManageStaff(admin)).toBe(true);
    expect(canAdministerCollege(admin)).toBe(true);
  });

  it('offers none of it to a placement officer', () => {
    // Running placement is not administering the college. The officer's
    // permissions are about drives and students, not about who works here.
    const officer = userWith(OFFICER);
    expect(canManageDepartments(officer)).toBe(false);
    expect(canManageBatches(officer)).toBe(false);
    expect(canManageStaff(officer)).toBe(false);
    expect(canAdministerCollege(officer)).toBe(false);
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
    password: 'OfficerPass!2026',
    role: 'PLACEMENT_OFFICER',
    departmentCode: '',
  };

  it('needs a name, an email and a long enough password', () => {
    expect(canSubmitStaff(EMPTY_STAFF_FORM)).toBe(false);
    expect(canSubmitStaff({ ...base, fullName: '' })).toBe(false);
    expect(canSubmitStaff({ ...base, email: '' })).toBe(false);
    expect(canSubmitStaff({ ...base, password: 'short' })).toBe(false);
    expect(canSubmitStaff(base)).toBe(true);
  });

  it('refuses a coordinator with no department', () => {
    // The failure this prevents is silent: a coordinator whose scope went
    // missing can see every student in the college.
    expect(requiresDepartment('PLACEMENT_COORDINATOR')).toBe(true);
    expect(canSubmitStaff({ ...base, role: 'PLACEMENT_COORDINATOR' })).toBe(false);
    expect(
      canSubmitStaff({ ...base, role: 'PLACEMENT_COORDINATOR', departmentCode: 'CSE' }),
    ).toBe(true);
  });

  it('does not ask an officer or an administrator for a department', () => {
    expect(requiresDepartment('PLACEMENT_OFFICER')).toBe(false);
    expect(requiresDepartment('COLLEGE_ADMIN')).toBe(false);
    expect(canSubmitStaff({ ...base, role: 'COLLEGE_ADMIN' })).toBe(true);
  });

  it('refuses a role a college may not appoint', () => {
    // A college cannot mint a platform operator. The server refuses this too;
    // the form simply never offers it.
    expect(canSubmitStaff({ ...base, role: 'PLATFORM_ADMIN' })).toBe(false);
    expect(canSubmitStaff({ ...base, role: 'STUDENT' })).toBe(false);
  });

  it('sends a department only for a coordinator', () => {
    expect(buildStaffRequest(base)).not.toHaveProperty('departmentCode');
    expect(
      buildStaffRequest({ ...base, role: 'PLACEMENT_COORDINATOR', departmentCode: ' cse ' }),
    ).toMatchObject({ role: 'PLACEMENT_COORDINATOR', departmentCode: 'CSE' });
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
  it('says whole institution when nothing narrows them', () => {
    expect(scopeLabel({ institutionWide: true, scopeLabels: [] })).toBe('Whole institution');
  });

  it('lists the departments a coordinator covers', () => {
    expect(scopeLabel({ institutionWide: false, scopeLabels: ['CSE'] })).toBe('CSE');
    expect(scopeLabel({ institutionWide: false, scopeLabels: ['CSE', 'IT'] })).toBe('CSE, IT');
  });

  it('falls back to whole institution rather than showing an empty cell', () => {
    expect(scopeLabel({ institutionWide: false, scopeLabels: [] })).toBe('Whole institution');
  });
});
