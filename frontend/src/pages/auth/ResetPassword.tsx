import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';

import { AuthAlert, AuthLayout } from './AuthLayout';
import { Button, Field, TextInput } from '../../components/ui/primitives';
import { ApiError, api } from '../../lib/api';
import { useToast } from '../../components/ui/primitives';

export default function ResetPassword() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const toast = useToast();
  const [token, setToken] = useState(params.get('token') ?? '');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      await api.post<void>('/api/auth/reset-password', { token, password }, { skipAuth: true });
      toast.show('Password updated. Sign in with your new password.', 'success');
      navigate('/login', { replace: true });
    } catch (caught) {
      setError(
        caught instanceof ApiError ? caught.message : 'That reset could not be completed.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthLayout
      title="Choose a new password"
      subtitle="Reset links expire 30 minutes after they are issued."
      footer={
        <>
          Need a new link? <Link to="/forgot-password">Request one</Link>
        </>
      }
    >
      <form className="grid" style={{ gap: 'var(--space-4)' }} onSubmit={onSubmit} noValidate>
        {error && <AuthAlert message={error} />}

        {!params.get('token') && (
          <Field label="Reset token" hint="Paste the token from your reset link.">
            {({ id }) => (
              <TextInput
                id={id}
                required
                value={token}
                onChange={(event) => setToken(event.target.value)}
              />
            )}
          </Field>
        )}

        <Field label="New password" hint="At least 10 characters.">
          {({ id }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="new-password"
              required
              minLength={10}
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder="••••••••••"
            />
          )}
        </Field>

        <Button type="submit" variant="primary" block loading={submitting}>
          Update password
        </Button>
      </form>
    </AuthLayout>
  );
}
