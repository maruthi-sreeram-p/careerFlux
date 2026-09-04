import type { SessionUser } from './types';
import { can } from './types';

/**
 * The decisions the college administration screens make, with no React in them.
 *
 * <p>Separated for the same reason as the onboarding rules: this project has no
 * DOM testing library, so logic that lives inside a component is logic nothing
 * asserts. What is here is the part worth asserting — when a form may be
 * submitted, exactly what is sent, and which sections a signed-in person should
 * be offered at all.
 *
 * <p>None of it is a security boundary. Every one of these checks exists again
 * on the server, where it is the one that counts; hiding a button an
 * unauthorised caller could still reach by hand would be theatre.
 */

export interface DepartmentFormState {
  name: string;
  code: string;
}

export interface BatchFormState {
  name: string;
  graduationYear: string;
}

export interface StaffFormState {
  fullName: string;
  email: string;
  password: string;
  role: string;
  departmentCode: string;
}

export const EMPTY_DEPARTMENT_FORM: DepartmentFormState = { name: '', code: '' };
export const EMPTY_BATCH_FORM: BatchFormState = { name: '', graduationYear: '' };
export const EMPTY_STAFF_FORM: StaffFormState = {
  fullName: '',
  email: '',
  password: '',
  role: 'PLACEMENT_OFFICER',
  departmentCode: '',
};

/** Mirrors the server's minimum. Catching it here saves a round trip, nothing more. */
export const MIN_PASSWORD_LENGTH = 10;

/** The roles a college may appoint. Deliberately no platform operator. */
export const APPOINTABLE_ROLES = [
  { value: 'PLACEMENT_OFFICER', label: 'Placement officer' },
  { value: 'PLACEMENT_COORDINATOR', label: 'Placement coordinator' },
  { value: 'COLLEGE_ADMIN', label: 'College administrator' },
] as const;

/** Which of the three sections this person is offered. */
export function canManageDepartments(user: SessionUser | null): boolean {
  return can(user, 'DEPARTMENT_MANAGE');
}

export function canManageBatches(user: SessionUser | null): boolean {
  return can(user, 'BATCH_MANAGE');
}

export function canManageStaff(user: SessionUser | null): boolean {
  return can(user, 'STAFF_MANAGE');
}

/** Whether the college administration screen is worth showing anybody. */
export function canAdministerCollege(user: SessionUser | null): boolean {
  return canManageDepartments(user) || canManageBatches(user) || canManageStaff(user);
}

export function canSubmitDepartment(form: DepartmentFormState): boolean {
  return form.name.trim().length > 0 && form.code.trim().length > 0;
}

export function buildDepartmentRequest(form: DepartmentFormState) {
  // Upper-cased here as well as on the server so the confirmation a person sees
  // is the code that was actually stored, rather than what they typed.
  return { name: form.name.trim(), code: form.code.trim().toUpperCase() };
}

/**
 * A graduation year has to be a plausible four-digit year.
 *
 * <p>The server bounds this too. The reason to repeat it is that a mistyped year
 * produces a batch nobody can explain later rather than an error anybody notices
 * at the time.
 */
export function isValidGraduationYear(value: string): boolean {
  if (!/^\d{4}$/.test(value.trim())) {
    return false;
  }
  const year = Number(value.trim());
  return year >= 1950 && year <= 2100;
}

export function canSubmitBatch(form: BatchFormState): boolean {
  return form.name.trim().length > 0 && isValidGraduationYear(form.graduationYear);
}

export function buildBatchRequest(form: BatchFormState) {
  return { name: form.name.trim(), graduationYear: Number(form.graduationYear.trim()) };
}

/** A coordinator is responsible for one department; nobody else is. */
export function requiresDepartment(role: string): boolean {
  return role === 'PLACEMENT_COORDINATOR';
}

export function canSubmitStaff(form: StaffFormState): boolean {
  if (
    form.fullName.trim().length === 0 ||
    form.email.trim().length === 0 ||
    form.password.length < MIN_PASSWORD_LENGTH
  ) {
    return false;
  }
  if (!APPOINTABLE_ROLES.some((role) => role.value === form.role)) {
    return false;
  }
  // Refused rather than quietly dropped: a coordinator whose scope went missing
  // can see every student in the college, which is the opposite of the request.
  if (requiresDepartment(form.role)) {
    return form.departmentCode.trim().length > 0;
  }
  return true;
}

export function buildStaffRequest(form: StaffFormState) {
  const body: {
    fullName: string;
    email: string;
    password: string;
    role: string;
    departmentCode?: string;
  } = {
    fullName: form.fullName.trim(),
    email: form.email.trim().toLowerCase(),
    // Not trimmed. Leading or trailing spaces are part of a password, and
    // silently removing them would lock somebody out of the account being made.
    password: form.password,
    role: form.role,
  };
  // Sent only where it means something. The server refuses a department on a
  // role that covers the whole institution rather than ignoring it.
  if (requiresDepartment(form.role) && form.departmentCode.trim().length > 0) {
    body.departmentCode = form.departmentCode.trim().toUpperCase();
  }
  return body;
}

/** How a staff member's reach reads in the list. */
export function scopeLabel(staff: { institutionWide: boolean; scopeLabels: string[] }): string {
  if (!staff.institutionWide && staff.scopeLabels.length > 0) {
    return staff.scopeLabels.join(', ');
  }
  return 'Whole institution';
}
