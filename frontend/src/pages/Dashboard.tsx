import { useAuth } from '../lib/auth';
import { dashboardKindFor } from '../lib/navigation';
import CoordinatorDashboard from './dashboards/CoordinatorDashboard';
import CollegeAdminDashboard from './dashboards/CollegeAdminDashboard';
import PlacementOfficerDashboard from './dashboards/PlacementOfficerDashboard';
import PlatformAdminDashboard from './dashboards/PlatformAdminDashboard';
import StudentDashboard from './dashboards/StudentDashboard';

/**
 * Resolves the home screen to the one this person's job needs.
 *
 * <p>Everyone keeps the same URL. A coordinator's home is still {@code /app} —
 * splitting it into five addresses would mean every link, bookmark and redirect
 * had to know the reader's role before it could point anywhere.
 *
 * <p>The choice is made from permissions rather than role names, and the server
 * decides independently what each of these screens is allowed to load. This is
 * a routing decision, not an authorization one.
 */
export default function Dashboard() {
  const { user } = useAuth();

  switch (dashboardKindFor(user)) {
    case 'platform':
      return <PlatformAdminDashboard />;
    case 'college-admin':
      return <CollegeAdminDashboard />;
    case 'officer':
      return <PlacementOfficerDashboard />;
    case 'coordinator':
      return <CoordinatorDashboard />;
    default:
      return <StudentDashboard />;
  }
}
