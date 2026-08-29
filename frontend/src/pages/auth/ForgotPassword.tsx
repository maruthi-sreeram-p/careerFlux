import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';

import { AuthAlert, AuthLayout } from './AuthLayout';
import { Button, Field, TextInput } from '../../components/ui/primitives';
import { api } from '../../lib/api';

interface ForgotResponse {
  status: string;
  message: string;
  devResetToken?: string;
  devNotice?: string;
}

export default function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [result, setResult] = useState<ForgotResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitting(true);
    try {
      setResult(
        await api.post<ForgotResponse>('/api/auth/forgot-password', { email }, { skipAuth: true }),
      );
    } catch {
      // The endpoint deliberately succeeds either way, so the only failure worth
      // reporting is not reaching the server at all.
      setResult({
        status: 'accepted',
        message: 'If that address has an account, a reset link is on its way.',
      });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Reset your password"
      subtitle="Enter the email you signed up with and we will send you a reset link."
      footer={
        <>
          Remembered it? <Link to="/login">Back to sign in</Link>
        </>
      }
    >
      {result ? (
        <div className="grid" style={{ gap: 'var(--space-4)' }}>
          <AuthAlert tone="info" message={result.message} />

          {result.devResetToken && (
            <div className="panel panel--raised">
              <div className="panel__body panel__body--tight grid" style={{ gap: 'var(--space-2)' }}>
                <p className="eyebrow">Development only</p>
                <p style={{ fontSize: 'var(--text-sm)', color: 'var(--text-secondary)' }}>
                  {result.devNotice}
                </p>
                <code
                  className="mono"
                  style={{
                    display: 'block',
                    padding: 'var(--space-2)',
                    background: 'var(--bg-subtle)',
                    border: '1px solid var(--line)',
                    borderRadius: 'var(--radius)',
                    fontSize: 'var(--text-2xs)',
                    overflowWrap: 'anywhere',
                  }}
                >
                  {result.devResetToken}
                </code>
                <Link
                  to={`/reset-password?token=${result.devResetToken}`}
                  className="btn btn--secondary btn--sm"
                >
                  Continue to reset
                </Link>
              </div>
            </div>
          )}

          <Button variant="secondary" block onClick={() => setResult(null)}>
            Use a different email
          </Button>
        </div>
      ) : (
        <form className="grid" style={{ gap: 'var(--space-4)' }} onSubmit={onSubmit} noValidate>
          <Field label="Email">
            {({ id }) => (
              <TextInput
                id={id}
                type="email"
                autoComplete="email"
                required
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                placeholder="you@example.com"
              />
            )}
          </Field>
          <Button type="submit" variant="primary" block loading={submitting}>
            Send reset link
          </Button>
        </form>
      )}
    </AuthLayout>
  );
}
