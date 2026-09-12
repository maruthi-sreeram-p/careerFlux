import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';

import { AuthAlert, AuthLayout } from './AuthLayout';
import { Button, Field, TextInput, cn } from '../../components/ui/primitives';
import { ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';
import { REGISTER_PRIVACY_NOTE } from '../../lib/productCopy';

const MIN_PASSWORD = 10;

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [institutionCode, setInstitutionCode] = useState('');
  // Most students register with their college address and never see this field.
  // It appears when they ask for it, and appears on its own when the server says
  // it could not work out which college they belong to.
  const [showCode, setShowCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [submitting, setSubmitting] = useState(false);

  // Three segments, tied to the one rule the server actually enforces. No
  // invented "strength" adjectives — just how close you are to the minimum.
  const filledSegments = Math.min(3, Math.floor((password.length / MIN_PASSWORD) * 3));

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setFieldErrors({});
    setSubmitting(true);
    try {
      await register(email, password, fullName, institutionCode.trim() || undefined);
      navigate('/onboarding', { replace: true });
    } catch (caught) {
      if (caught instanceof ApiError) {
        if (caught.code === 'INSTITUTION_UNRESOLVED') {
          setShowCode(true);
        }
        setError(caught.violations.length > 0 ? null : caught.message);
        setFieldErrors(
          Object.fromEntries(caught.violations.map((v) => [v.field, v.message])),
        );
      } else {
        setError('CareerFlux could not reach the server. Check that the API is running.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Create your profile"
      subtitle="One resume upload, a few preferences, and CareerFlux starts looking."
      footer={
        <>
          Already have an account? <Link to="/login">Sign in</Link>
        </>
      }
    >
      <form className="grid" style={{ gap: 'var(--space-4)' }} onSubmit={onSubmit} noValidate>
        {error && <AuthAlert message={error} />}

        <Field label="Full name" error={fieldErrors.fullName}>
          {({ id, invalid, describedBy }) => (
            <TextInput
              id={id}
              autoComplete="name"
              required
              aria-invalid={invalid}
              aria-describedby={describedBy}
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              placeholder="Maruthi Sreeram"
            />
          )}
        </Field>

        <Field label="Email" error={fieldErrors.email}>
          {({ id, invalid, describedBy }) => (
            <TextInput
              id={id}
              type="email"
              autoComplete="email"
              required
              aria-invalid={invalid}
              aria-describedby={describedBy}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@example.com"
            />
          )}
        </Field>

        {showCode ? (
          <Field
            label="Registration code"
            hint="Your placement office issues this. Leave it blank if you used your college email."
            error={fieldErrors.institutionCode}
          >
            {({ id, invalid, describedBy }) => (
              <TextInput
                id={id}
                autoComplete="off"
                autoCapitalize="characters"
                aria-invalid={invalid}
                aria-describedby={describedBy}
                value={institutionCode}
                onChange={(event) => setInstitutionCode(event.target.value)}
                placeholder="NORTHGATE-2026"
              />
            )}
          </Field>
        ) : (
          <button
            type="button"
            className="link-button"
            onClick={() => setShowCode(true)}
            style={{ justifySelf: 'start', fontSize: 'var(--text-xs)' }}
          >
            Not using a college email address?
          </button>
        )}

        <Field
          label="Password"
          hint={`At least ${MIN_PASSWORD} characters.`}
          error={fieldErrors.password}
        >
          {({ id, invalid, describedBy }) => (
            <>
              <TextInput
                id={id}
                type="password"
                autoComplete="new-password"
                required
                minLength={MIN_PASSWORD}
                aria-invalid={invalid}
                aria-describedby={describedBy}
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                placeholder="••••••••••"
              />
              <div className="strength" aria-hidden="true">
                {[0, 1, 2].map((index) => (
                  <span
                    key={index}
                    className={cn('strength__seg', index < filledSegments && 'strength__seg--on')}
                  />
                ))}
              </div>
            </>
          )}
        </Field>

        <Button type="submit" variant="primary" block loading={submitting}>
          Create account
        </Button>

        <p className="text-faint" style={{ fontSize: 'var(--text-xs)', textAlign: 'center' }}>
          {REGISTER_PRIVACY_NOTE}
        </p>
      </form>
    </AuthLayout>
  );
}
