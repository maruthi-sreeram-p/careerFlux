import type { ProvisionInstitutionInput } from './types';

/**
 * The decisions the college onboarding form makes, with no React in them.
 *
 * <p>Separated so they can be tested. This project renders nothing in its
 * frontend tests — there is no DOM testing library — so logic that only exists
 * inside a component is logic nothing asserts. What lives here is the part
 * worth asserting: when the form may be submitted, and exactly what is sent.
 *
 * <p>The payload rules matter more than they look. An empty text input is
 * {@code ''}, not {@code undefined}, and sending {@code ''} for an email domain
 * is not the same as omitting it — the server would try to normalise an empty
 * claim rather than treating the college as code-only.
 */

export interface OnboardingFormState {
  name: string;
  emailDomains: string;
  city: string;
  registrationCode: string;
  withAdmin: boolean;
  adminName: string;
  adminEmail: string;
  adminPassword: string;
}

export const EMPTY_ONBOARDING_FORM: OnboardingFormState = {
  name: '',
  emailDomains: '',
  city: '',
  registrationCode: '',
  withAdmin: false,
  adminName: '',
  adminEmail: '',
  adminPassword: '',
};

/** The server's own minimum for an initial administrator's password. */
export const MIN_PASSWORD_LENGTH = 10;

/**
 * Whether anybody could ever join this college.
 *
 * <p>With no domain to match and no code to hand out, registration has nothing
 * to resolve against. The server refuses this too; saying so in the form means
 * an operator finds out while they are still typing rather than from a
 * placement office three weeks later.
 */
export function isReachable(form: OnboardingFormState): boolean {
  return form.emailDomains.trim().length > 0 || form.registrationCode.trim().length > 0;
}

/** Whether the optional administrator block is complete enough to send. */
export function isAdminComplete(form: OnboardingFormState): boolean {
  if (!form.withAdmin) {
    return true;
  }
  return (
    form.adminName.trim().length > 0 &&
    form.adminEmail.trim().length > 0 &&
    form.adminPassword.length >= MIN_PASSWORD_LENGTH
  );
}

export function canSubmit(form: OnboardingFormState): boolean {
  return form.name.trim().length > 0 && isReachable(form) && isAdminComplete(form);
}

/**
 * Builds the request body.
 *
 * <p>Whitespace is trimmed and anything left empty is omitted rather than sent
 * blank. The administrator block is present only when it was asked for — never
 * as an empty object, which the server would read as an attempt to create an
 * account with no name.
 *
 * <p>Nothing here sends a slug or an id. Those are the server's to decide; a
 * client that proposed them would be proposing the identity of a tenant.
 */
export function buildProvisionRequest(form: OnboardingFormState): ProvisionInstitutionInput {
  const request: ProvisionInstitutionInput = { name: form.name.trim() };

  const domains = form.emailDomains.trim();
  if (domains) {
    request.emailDomains = domains;
  }
  const city = form.city.trim();
  if (city) {
    request.city = city;
  }
  const code = form.registrationCode.trim();
  if (code) {
    request.registrationCode = code;
  }
  if (form.withAdmin) {
    request.initialAdmin = {
      fullName: form.adminName.trim(),
      email: form.adminEmail.trim(),
      // Not trimmed. A password is whatever was typed; stripping the ends would
      // silently change the credential from the one the operator will pass on.
      password: form.adminPassword,
    };
  }
  return request;
}
