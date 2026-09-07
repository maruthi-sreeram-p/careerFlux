import { useState } from 'react';

import { ApiError, api } from '../../lib/api';
import { Button, Field, Panel, TextInput, useToast } from '../ui/primitives';

/** The policy the server enforces; stated here so the message arrives before the request does. */
const MIN_LENGTH = 10;

/**
 * Changing your own password.
 *
 * <p>The same panel for every role, because it is the same endpoint for every
 * role: the account being changed comes from the token, and there is no field
 * on this form that could name somebody else. A student, a placement officer and
 * a college administrator all rotate an initial password the same way.
 *
 * <p>Confirmation is checked here and nowhere else. The server takes one new
 * password and has no opinion about whether the person typed it twice — that is
 * a typing aid, not a security control, so it belongs in the browser.
 *
 * <p>Nothing is logged, nothing goes in a URL, and the fields are cleared on
 * success so a shared screen does not keep the value around.
 */
export function PasswordPanel() {
  const toast = useToast();
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [fieldError, setFieldError] = useState<'current' | 'next' | 'confirm' | null>(null);
  const [saving, setSaving] = useState(false);

  const reset = () => {
    setCurrent('');
    setNext('');
    setConfirm('');
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError(null);
    setFieldError(null);

    if (next.length < MIN_LENGTH) {
      setFieldError('next');
      setError(`Use at least ${MIN_LENGTH} characters.`);
      return;
    }
    if (next !== confirm) {
      setFieldError('confirm');
      setError('The two new passwords do not match.');
      return;
    }
    if (next === current) {
      setFieldError('next');
      setError('Choose a password you have not used here before.');
      return;
    }

    setSaving(true);
    try {
      await api.post<void>('/api/auth/change-password', {
        currentPassword: current,
        newPassword: next,
      });
      reset();
      toast.show('Password updated. Use the new one next time you sign in.', 'success');
    } catch (failure) {
      if (failure instanceof ApiError) {
        // The server says "Your current password is incorrect" for the case
        // that matters; anything else is shown as it was written rather than
        // replaced with a guess about what went wrong.
        setFieldError(failure.status === 400 ? 'current' : null);
        setError(failure.violations[0]?.message ?? failure.message);
      } else {
        setError('Something went wrong. Your password has not been changed.');
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Panel title="Update password">
      <form onSubmit={submit} className="grid" style={{ gap: 'var(--space-4)', maxWidth: 420 }}>
        <Field
          label="Current password"
          error={fieldError === 'current' ? (error ?? undefined) : undefined}
        >
          {({ id, describedBy, invalid }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="current-password"
              value={current}
              aria-describedby={describedBy}
              aria-invalid={invalid}
              onChange={(event) => setCurrent(event.target.value)}
            />
          )}
        </Field>

        <Field
          label="New password"
          hint={`At least ${MIN_LENGTH} characters.`}
          error={fieldError === 'next' ? (error ?? undefined) : undefined}
        >
          {({ id, describedBy, invalid }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="new-password"
              value={next}
              aria-describedby={describedBy}
              aria-invalid={invalid}
              onChange={(event) => setNext(event.target.value)}
            />
          )}
        </Field>

        <Field
          label="Confirm new password"
          error={fieldError === 'confirm' ? (error ?? undefined) : undefined}
        >
          {({ id, describedBy, invalid }) => (
            <TextInput
              id={id}
              type="password"
              autoComplete="new-password"
              value={confirm}
              aria-describedby={describedBy}
              aria-invalid={invalid}
              onChange={(event) => setConfirm(event.target.value)}
            />
          )}
        </Field>

        {error && fieldError === null && (
          <p className="field__error" role="alert">
            {error}
          </p>
        )}

        <div>
          <Button type="submit" loading={saving} disabled={!current || !next || !confirm}>
            Update password
          </Button>
        </div>
      </form>
    </Panel>
  );
}
