import { useState } from 'react';

import { Badge, Button, Field, Panel, TextInput, useToast } from '../ui/primitives';
import { useUpdateOwnAcademics } from '../../lib/queries';
import type { CandidateProfile } from '../../lib/types';

/**
 * The student's academic standing.
 *
 * <p>Small on purpose. A CGPA is one fact a company may ask about, and it
 * decides nothing about how well a student's skills fit a role — so it sits
 * beside their department and batch rather than being given a screen of its own.
 *
 * <p>Two things this is careful about. An absent CGPA reads as "not provided",
 * never as a zero, because a zero looks measured and would be a lie about a
 * student who simply has not entered one. And a figure a student types is
 * labelled as theirs: it is shown back to them and is not what a company's
 * stated minimum is judged against, which the panel says plainly rather than
 * letting them assume otherwise.
 */
export function AcademicPanel({ profile }: { profile: CandidateProfile }) {
  const scale = profile.cgpaScale ?? 10;
  const save = useUpdateOwnAcademics();
  const toast = useToast();
  const [value, setValue] = useState(profile.cgpa === null || profile.cgpa === undefined
    ? ''
    : String(profile.cgpa));

  const submit = async () => {
    const trimmed = value.trim();
    const parsed = trimmed === '' ? null : Number(trimmed);
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0 || parsed > scale)) {
      toast.show(`A CGPA must be a number between 0 and ${scale}.`, 'error');
      return;
    }
    try {
      await save.mutateAsync(parsed);
      toast.show(parsed === null ? 'CGPA cleared.' : 'CGPA saved.', 'success');
    } catch (error) {
      toast.show(
        error instanceof Error ? error.message : 'Your CGPA could not be saved.',
        'error',
      );
    }
  };

  return (
    <Panel
      title="Academic information"
      action={
        profile.cgpaVerified ? (
          <Badge tone="positive">Verified by your college</Badge>
        ) : profile.cgpa !== null && profile.cgpa !== undefined ? (
          <Badge tone="neutral">Entered by you</Badge>
        ) : null
      }
    >
      <Field
        label={`CGPA (out of ${scale})`}
        hint="Leave empty if you would rather not enter one. An empty field is not a zero."
      >
        {({ id }) => (
          <TextInput
            id={id}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder="7.42"
            inputMode="decimal"
          />
        )}
      </Field>

      <p className="text-muted">
        {profile.cgpaVerified
          ? 'Your college has recorded this as your official CGPA, and companies asking for a minimum will be checked against it.'
          : 'A CGPA you enter yourself appears on your profile. Where a company states a minimum, your college has to record the official figure before it can be checked — until then it shows as unknown rather than counting against you.'}
      </p>

      <Button onClick={submit} disabled={save.isPending}>
        {save.isPending ? 'Saving…' : 'Save CGPA'}
      </Button>
    </Panel>
  );
}
