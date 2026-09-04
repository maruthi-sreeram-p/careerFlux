import { describe, expect, it } from 'vitest';

import {
  EMPTY_ONBOARDING_FORM,
  buildProvisionRequest,
  canSubmit,
  isAdminComplete,
  isReachable,
} from './onboarding';
import { dashboardKindFor, navigationTargets } from './navigation';
import type { SessionUser, UserRole } from './types';

/**
 * College onboarding, as far as the browser is responsible for it.
 *
 * <p>Two things are asserted here. The form's rules, because a form that
 * submits when it should not produces a server error the operator has to
 * interpret, and one that refuses when it should not is simply broken. And who
 * is offered the screen at all — the server is what actually refuses, but a
 * college administrator being shown a link to a platform operation is a bug
 * whether or not the request behind it succeeds.
 */

function user(role: UserRole, permissions: string[]): SessionUser {
  return {
    id: 'user-1',
    email: 'person@example.com',
    fullName: 'Test Person',
    role,
    permissions,
    institutionId: role === 'PLATFORM_ADMIN' ? null : 'institution-1',
    institutionName: role === 'PLATFORM_ADMIN' ? null : 'Example Institute of Technology',
    candidateId: null,
    onboardingStage: null,
  };
}

// Copied from the backend's UserRole enum, as the existing navigation tests do.
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
const COLLEGE_ADMIN = user('COLLEGE_ADMIN', [
  'INSTITUTION_SETTINGS_MANAGE',
  'DEPARTMENT_MANAGE',
  'BATCH_MANAGE',
  'STAFF_MANAGE',
  'STUDENT_MANAGE',
  'ANALYTICS_VIEW',
  'AUDIT_READ_INSTITUTION',
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
const STUDENT = user('STUDENT', [
  'SELF_PROFILE_MANAGE',
  'SELF_JOBS_MANAGE',
  'SELF_AI_USE',
  'SELF_ACCOUNT_DELETE',
]);

const ONBOARDING_ROUTE = '/app/admin/institutions';

describe('who is offered college onboarding', () => {
  it('offers it to the platform operator', () => {
    expect(dashboardKindFor(PLATFORM_ADMIN)).toBe('platform');
    expect(navigationTargets(PLATFORM_ADMIN)).toContain(ONBOARDING_ROUTE);
  });

  it('offers it to nobody inside a college', () => {
    // Including the college administrator, who administers one college and is
    // not thereby able to create another.
    for (const person of [COLLEGE_ADMIN, OFFICER, STUDENT]) {
      expect(navigationTargets(person)).not.toContain(ONBOARDING_ROUTE);
    }
  });

  it('offers it to nobody who is signed out', () => {
    expect(navigationTargets(null)).not.toContain(ONBOARDING_ROUTE);
  });
});

describe('when the form may be submitted', () => {
  const named = { ...EMPTY_ONBOARDING_FORM, name: 'Northgate Institute of Technology' };

  it('refuses an empty form', () => {
    expect(canSubmit(EMPTY_ONBOARDING_FORM)).toBe(false);
  });

  it('refuses a college with a name but no way in', () => {
    // No domain and no code means nobody can ever register against it.
    expect(isReachable(named)).toBe(false);
    expect(canSubmit(named)).toBe(false);
  });

  it('accepts a college reachable by domain', () => {
    const form = { ...named, emailDomains: 'northgate.edu' };
    expect(isReachable(form)).toBe(true);
    expect(canSubmit(form)).toBe(true);
  });

  it('accepts a college reachable by registration code alone', () => {
    const form = { ...named, registrationCode: 'NORTHGATE-2026' };
    expect(isReachable(form)).toBe(true);
    expect(canSubmit(form)).toBe(true);
  });

  it('treats whitespace as empty', () => {
    expect(canSubmit({ ...EMPTY_ONBOARDING_FORM, name: '   ', emailDomains: 'a.edu' })).toBe(false);
    expect(isReachable({ ...named, emailDomains: '   ' })).toBe(false);
  });

  it('refuses a half-filled administrator', () => {
    const base = { ...named, emailDomains: 'northgate.edu', withAdmin: true };
    expect(isAdminComplete(base)).toBe(false);
    expect(canSubmit({ ...base, adminName: 'Anita Rao' })).toBe(false);
    expect(canSubmit({ ...base, adminName: 'Anita Rao', adminEmail: 'anita@northgate.edu' }))
      .toBe(false);
  });

  it('refuses a password the server would refuse', () => {
    const base = {
      ...named,
      emailDomains: 'northgate.edu',
      withAdmin: true,
      adminName: 'Anita Rao',
      adminEmail: 'anita@northgate.edu',
    };
    expect(canSubmit({ ...base, adminPassword: 'short' })).toBe(false);
    expect(canSubmit({ ...base, adminPassword: '123456789' })).toBe(false);
    expect(canSubmit({ ...base, adminPassword: '1234567890' })).toBe(true);
  });

  it('ignores an incomplete administrator that was not asked for', () => {
    // Unticking the box should not leave the form unsubmittable because of
    // text still sitting in hidden inputs.
    const form = {
      ...named,
      emailDomains: 'northgate.edu',
      withAdmin: false,
      adminName: 'Half',
      adminEmail: '',
      adminPassword: '',
    };
    expect(canSubmit(form)).toBe(true);
  });
});

describe('what gets sent', () => {
  it('sends only the name when nothing else was filled in', () => {
    // Empty strings are omitted rather than sent blank: '' for an email domain
    // is not the same as claiming none.
    const request = buildProvisionRequest({
      ...EMPTY_ONBOARDING_FORM,
      name: '  Northgate Institute of Technology  ',
      registrationCode: 'NORTHGATE-2026',
    });
    expect(request).toEqual({
      name: 'Northgate Institute of Technology',
      registrationCode: 'NORTHGATE-2026',
    });
    expect(request.emailDomains).toBeUndefined();
    expect(request.city).toBeUndefined();
    expect(request.initialAdmin).toBeUndefined();
  });

  it('trims every field the operator typed', () => {
    const request = buildProvisionRequest({
      ...EMPTY_ONBOARDING_FORM,
      name: '  Northgate  ',
      emailDomains: '  northgate.edu  ',
      city: '  Hyderabad  ',
      registrationCode: '  NG-2026  ',
    });
    expect(request).toEqual({
      name: 'Northgate',
      emailDomains: 'northgate.edu',
      city: 'Hyderabad',
      registrationCode: 'NG-2026',
    });
  });

  it('includes the administrator only when one was asked for', () => {
    const base = {
      ...EMPTY_ONBOARDING_FORM,
      name: 'Northgate',
      emailDomains: 'northgate.edu',
      adminName: '  Anita Rao  ',
      adminEmail: '  anita@northgate.edu  ',
      adminPassword: 'HandoverAdmin!2026',
    };
    expect(buildProvisionRequest({ ...base, withAdmin: false }).initialAdmin).toBeUndefined();
    expect(buildProvisionRequest({ ...base, withAdmin: true }).initialAdmin).toEqual({
      fullName: 'Anita Rao',
      email: 'anita@northgate.edu',
      password: 'HandoverAdmin!2026',
    });
  });

  it('does not trim the password', () => {
    // A password is whatever was typed. Trimming it would hand the operator a
    // credential that no longer signs in.
    const request = buildProvisionRequest({
      ...EMPTY_ONBOARDING_FORM,
      name: 'Northgate',
      emailDomains: 'northgate.edu',
      withAdmin: true,
      adminName: 'Anita Rao',
      adminEmail: 'anita@northgate.edu',
      adminPassword: '  spaced password  ',
    });
    expect(request.initialAdmin?.password).toBe('  spaced password  ');
  });

  it('never proposes an identifier for the tenant', () => {
    // The slug and the id are the server's to derive. A client that sent them
    // would be choosing the identity of a tenant.
    const request = buildProvisionRequest({
      ...EMPTY_ONBOARDING_FORM,
      name: 'Northgate',
      emailDomains: 'northgate.edu',
    });
    expect(Object.keys(request)).not.toContain('slug');
    expect(Object.keys(request)).not.toContain('id');
    expect(Object.keys(request)).not.toContain('status');
  });
});
