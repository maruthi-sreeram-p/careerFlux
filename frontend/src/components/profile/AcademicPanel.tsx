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
 * <p>Two figures, shown apart. The one the student types is theirs: it appears
 * on their profile and never replaces the college's. The college's is the
 * official record and the only one a company's stated minimum is checked
 * against. An absent CGPA reads as "not provided", never as a zero, because a
 * zero looks measured and would be a lie about a student who simply has not
 * entered one.
 */
export function AcademicPanel({ profile }: { profile: CandidateProfile }) {
  const scale = profile.cgpaScale ?? 10;
  const verified = profile.verifiedCgpa ?? null;
  const save = useUpdateOwnAcademics();
  const toast = useToast();
  const [value, setValue] = useState(
    profile.reportedCgpa === null || profile.reportedCgpa === undefined
      ? ''
      : String(profile.reportedCgpa),
  );

  const submit = async () => {
    const trimmed = value.trim();
    const parsed = trimmed === '' ? null : Number(trimmed);
    if (parsed !== null && (!Number.isFinite(parsed) || parsed < 0 || parsed > scale)) {
      toast.show(`A CGPA must be a number between 0 and ${scale}.`, 'error');
      return;
    }
    try {
      await save.mutateAsync(parsed);
      toast.show(parsed === null ? 'Your CGPA was cleared.' : 'Your CGPA was saved.', 'success');
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
      action={verified !== null ? <Badge tone="positive">Verified by your college</Badge> : null}
    >
      <p className="text-secondary">
        {verified !== null
          ? `Your college has recorded ${verified} out of ${scale} as your official CGPA. Companies that state a minimum are checked against that figure.`
          : 'Your college has not recorded an official CGPA for you yet. Where a company states a minimum, you show as unknown until it does — never as failing.'}
      </p>

      <Field
        label={`Your own CGPA (out of ${scale})`}
        hint="Shown on your profile as the figure you gave. It never replaces your college's record and is not used to check eligibility. Leave it empty if you would rather not enter one — an empty field is not a zero."
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

      <Button onClick={submit} disabled={save.isPending}>
        {save.isPending ? 'Saving…' : 'Save your CGPA'}
      </Button>
    </Panel>
  );
}
