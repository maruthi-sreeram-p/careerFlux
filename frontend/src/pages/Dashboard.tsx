import { useAuth } from '../lib/auth';
import { dashboardKindFor } from '../lib/navigation';
import CoordinatorDashboard from './dashboards/CoordinatorDashboard';
import PlacementOfficerDashboard from './dashboards/PlacementOfficerDashboard';
import PlatformAdminDashboard from './dashboards/PlatformAdminDashboard';
import StudentDashboard from './dashboards/StudentDashboard';

/**
 * Resolves the home screen to the one this person's job needs.
 *
 * <p>Everyone keeps the same URL. A coordinator's home is still {@code /app} —
 * splitting it into one address per role would mean every link, bookmark and
 * redirect had to know the reader's role before it could point anywhere.
 *
 * <p>The choice is made from permissions rather than role names, and the server
 * decides independently what each of these screens is allowed to load. This is
 * a routing decision, not an authorization one.
 *
 * <p>The component names predate the four-role model: the placement
 * coordinator's home is the college-wide placement screen, and the department
 * coordinator's is the department screen.
 */
export default function Dashboard() {
  const { user } = useAuth();

  switch (dashboardKindFor(user)) {
    case 'platform':
      return <PlatformAdminDashboard />;
    case 'placement-coordinator':
      return <PlacementOfficerDashboard />;
    case 'department-coordinator':
      return <CoordinatorDashboard />;
    default:
      return <StudentDashboard />;
  }
}
