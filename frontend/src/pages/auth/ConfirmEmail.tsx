import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';

import { AuthAlert, AuthLayout } from './AuthLayout';
import { ApiError, api } from '../../lib/api';

type State = { kind: 'working' } | { kind: 'done' } | { kind: 'failed'; message: string };

/** Opened from the link in the confirmation email; the token in the URL is the proof. */
export default function ConfirmEmail() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [state, setState] = useState<State>(
    token ? { kind: 'working' } : { kind: 'failed', message: 'That confirmation link is not valid.' },
  );
  // Strict mode runs effects twice in development; the token is single use.
  const started = useRef(false);

  useEffect(() => {
    if (!token || started.current) return;
    started.current = true;
    api
      .post<void>('/api/public/personal-email/confirm', { token }, { skipAuth: true })
      .then(() => setState({ kind: 'done' }))
      .catch((caught) =>
        setState({
          kind: 'failed',
          message: caught instanceof ApiError ? caught.message : 'That email could not be confirmed.',
        }),
      );
  }, [token]);

  return (
    <AuthLayout
      title="Confirm your email"
      subtitle="Job alerts are sent to a personal email only after you confirm it."
      footer={<Link to="/profile">Back to your profile</Link>}
    >
      {state.kind === 'working' && <p>Confirming…</p>}
      {state.kind === 'done' && (
        <p>Thanks, your email is confirmed. Matching jobs will now be sent to it.</p>
      )}
      {state.kind === 'failed' && <AuthAlert message={state.message} />}
    </AuthLayout>
  );
}
