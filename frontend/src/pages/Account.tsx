import { PageHeader } from '../components/layout/AppShell';
import { PasswordPanel } from '../components/profile/PasswordPanel';
import { Badge, Panel } from '../components/ui/primitives';
import { useAuth } from '../lib/auth';

const ROLE_LABELS: Record<string, string> = {
  STUDENT: 'Student',
  PLACEMENT_COORDINATOR: 'Placement coordinator',
  PLACEMENT_OFFICER: 'Placement officer',
  COLLEGE_ADMIN: 'College administrator',
  PLATFORM_ADMIN: 'Platform administrator',
};

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div>
      <p className="eyebrow">{label}</p>
      <p>{value}</p>
    </div>
  );
}

/**
 * Your account, as distinct from your career profile.
 *
 * <p>Staff have no candidate profile, so the profile page has nothing to show
 * them and they had nowhere to change a password — which matters most for the
 * people who are handed an initial one by an administrator. This page belongs to
 * every signed-in role for that reason.
 *
 * <p>It shows who you are signed in as and lets you replace your password. It
 * does not let you change your own role, institution or email: those are
 * decisions somebody else makes about you, and a page that appeared to offer
 * them would be lying about what the server will accept.
 */
export default function Account() {
  const { user } = useAuth();

  return (
    <div className="page">
      <PageHeader
        title="Account"
        subtitle="How you sign in. Your role and college are set by your institution."
      />

      <Panel title="Account information">
        <div className="row wrap gap-4">
          <Fact label="Name" value={user?.fullName ?? '—'} />
          <Fact label="Email" value={user?.email ?? '—'} />
          <Fact
            label="Role"
            value={
              <Badge>{user ? (ROLE_LABELS[user.role] ?? user.role) : '—'}</Badge>
            }
          />
        </div>
      </Panel>

      <PasswordPanel />
    </div>
  );
}
