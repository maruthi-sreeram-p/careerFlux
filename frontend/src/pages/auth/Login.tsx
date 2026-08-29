import { useState, type FormEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';

import { AuthAlert, AuthLayout } from './AuthLayout';
import { Button, Field, TextInput } from '../../components/ui/primitives';
import { ApiError } from '../../lib/api';
import { useAuth } from '../../lib/auth';

export default function Login() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const from = (location.state as { from?: string } | null)?.from ?? '/app';

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      const user = await signIn(email, password);
      // A student who never finished setup goes back to it rather than to an
      // empty dashboard they cannot do anything with. Staff and platform
      // operators have no onboarding at all — they were being sent to the
      // resume upload screen, which is not theirs to complete.
      const needsOnboarding = user.role === 'STUDENT' && user.onboardingStage !== 'COMPLETE';
      navigate(needsOnboarding ? '/onboarding' : from, { replace: true });
    } catch (caught) {
      setError(
        caught instanceof ApiError
          ? caught.message
          : 'CareerFlux could not reach the server. Check that the API is running.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Welcome back"
      subtitle="Sign in to see what CareerFlux has found since your last visit."
      footer={
        <>
          New here? <Link to="/register">Create an account</Link>
        </>
      }
    >
      <form className="grid" style={{ gap: 'var(--space-4)' }} onSubmit={onSubmit} noValidate>
        {error && <AuthAlert message={error} />}

        <Field label="Email">
          {({ id, invalid }) => (
            <TextInput
              id={id}
              type="email"
              autoComplete="email"
              required
              aria-invalid={invalid}
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@example.com"
            />
          )}
        </Field>

        <Field label="Password">
          {({ id, invalid }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="current-password"
              required
              aria-invalid={invalid}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="••••••••••"
            />
          )}
        </Field>

        <div className="row between">
          <span />
          <Link
            to="/forgot-password"
            style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}
          >
            Forgot your password?
          </Link>
        </div>

        <Button type="submit" variant="primary" block loading={submitting}>
          Sign in
        </Button>
      </form>
    </AuthLayout>
  );
}
