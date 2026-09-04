import { Navigate, Route, Routes, useLocation } from 'react-router-dom';

import { AppShell } from './components/layout/AppShell';
import { ErrorBoundary } from './components/ui/ErrorBoundary';
import { can } from './lib/types';
import { useAuth } from './lib/auth';

import Landing from './pages/Landing';
import Login from './pages/auth/Login';
import Register from './pages/auth/Register';
import ForgotPassword from './pages/auth/ForgotPassword';
import ResetPassword from './pages/auth/ResetPassword';
import Onboarding from './pages/onboarding/Onboarding';
import Dashboard from './pages/Dashboard';
import Discover from './pages/Discover';
import JobDetail from './pages/JobDetail';
import Saved from './pages/Saved';
import CandidateDiscovery from './pages/CandidateDiscovery';
import RequirementDetail from './pages/RequirementDetail';
import RequirementNew from './pages/RequirementNew';
import Requirements from './pages/Requirements';
import Shortlist from './pages/Shortlist';
import Students from './pages/Students';
import Sources from './pages/Sources';
import SourceDetail from './pages/SourceDetail';
import Profile from './pages/Profile';
import Notifications from './pages/Notifications';
import MyPlacements from './pages/MyPlacements';
import AdminOps from './pages/admin/AdminOps';
import AdminInstitutions from './pages/admin/AdminInstitutions';
import CollegeAdministration from './pages/admin/CollegeAdministration';
import AdminSources from './pages/admin/AdminSources';
import NotFound from './pages/NotFound';

/** Blocks a route until the session is known, then sends you where you belong. */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, initialising } = useAuth();
  const location = useLocation();

  if (initialising) {
    return (
      <div style={{ display: 'grid', placeItems: 'center', minHeight: '100dvh' }}>
        <span className="spinner" aria-label="Loading" />
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return <>{children}</>;
}

/**
 * Onboarding belongs to students.
 *
 * <p>Staff and platform operators have no candidate profile to build, and
 * without this they land on the resume upload screen after signing in.
 */
function RequireStudent({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (user && user.role !== 'STUDENT') {
    return <Navigate to="/app" replace />;
  }
  return <>{children}</>;
}

/**
 * Gates the platform console.
 *
 * <p>Asks for the permission rather than the role. This is a convenience for the
 * person browsing, not a security boundary — the server refuses these routes on
 * its own, and it is the only refusal that counts.
 */
function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (!can(user, 'SOURCE_MANAGE')) {
    return <Navigate to="/app" replace />;
  }
  return <>{children}</>;
}

/**
 * Staff-only screens. Mirrors the permission the directory endpoint requires,
 * so a student who types the URL is sent home instead of watching a request
 * fail. The server refuses it either way; this only avoids the dead end.
 */
function RequireStaff({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (!can(user, 'STUDENT_READ_SCOPED')) {
    return <Navigate to="/app" replace />;
  }
  return <>{children}</>;
}

/**
 * A screen behind one permission.
 *
 * <p>Generalises the staff guard: the route names the permission its endpoint
 * requires, so the two cannot drift. The server refuses regardless — this only
 * spares the user a screen that would fail to load.
 */
function RequirePermission({
  permission,
  children,
}: {
  permission: string;
  children: React.ReactNode;
}) {
  const { user } = useAuth();
  if (!can(user, permission)) {
    return <Navigate to="/app" replace />;
  }
  return <>{children}</>;
}

/** Sends an already-signed-in visitor away from the marketing and auth pages. */
function RedirectIfSignedIn({ children }: { children: React.ReactNode }) {
  const { user, initialising } = useAuth();
  if (initialising) {
    return null;
  }
  if (user) {
    return <Navigate to="/app" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  const location = useLocation();
  return (
    <ErrorBoundary resetKey={location.pathname}>
      <Routes>
      <Route path="/" element={<Landing />} />
      <Route
        path="/login"
        element={
          <RedirectIfSignedIn>
            <Login />
          </RedirectIfSignedIn>
        }
      />
      <Route
        path="/register"
        element={
          <RedirectIfSignedIn>
            <Register />
          </RedirectIfSignedIn>
        }
      />
      <Route path="/forgot-password" element={<ForgotPassword />} />
      <Route path="/reset-password" element={<ResetPassword />} />

      <Route
        path="/onboarding"
        element={
          <RequireAuth>
            <RequireStudent>
              <Onboarding />
            </RequireStudent>
          </RequireAuth>
        }
      />

      <Route
        path="/app/*"
        element={
          <RequireAuth>
            <AppShell>
              <Routes>
                <Route index element={<Dashboard />} />
                <Route path="discover" element={<Discover />} />
                <Route path="jobs/:jobId" element={<JobDetail />} />
                <Route path="saved" element={<Saved />} />
                <Route
                  path="placements"
                  element={
                    <RequireStudent>
                      <MyPlacements />
                    </RequireStudent>
                  }
                />
                <Route
                  path="students"
                  element={
                    <RequireStaff>
                      <Students />
                    </RequireStaff>
                  }
                />
                <Route
                  path="requirements"
                  element={
                    <RequirePermission permission="PLACEMENT_DRIVE_VIEW">
                      <Requirements />
                    </RequirePermission>
                  }
                />
                <Route
                  path="requirements/new"
                  element={
                    <RequirePermission permission="PLACEMENT_DRIVE_MANAGE">
                      <RequirementNew />
                    </RequirePermission>
                  }
                />
                <Route
                  path="requirements/:requirementId"
                  element={
                    <RequirePermission permission="PLACEMENT_DRIVE_VIEW">
                      <RequirementDetail />
                    </RequirePermission>
                  }
                />
                <Route
                  path="requirements/:requirementId/candidates"
                  element={
                    <RequirePermission permission="STUDENT_READ_SCOPED">
                      <CandidateDiscovery />
                    </RequirePermission>
                  }
                />
                <Route
                  path="requirements/:requirementId/shortlist"
                  element={
                    <RequirePermission permission="PLACEMENT_DRIVE_VIEW">
                      <Shortlist />
                    </RequirePermission>
                  }
                />
                <Route path="sources" element={<Sources />} />
                <Route path="sources/:sourceId" element={<SourceDetail />} />
                <Route path="profile" element={<Profile />} />
                <Route path="notifications" element={<Notifications />} />
                <Route
                  path="admin"
                  element={
                    <RequireAdmin>
                      <AdminOps />
                    </RequireAdmin>
                  }
                />
                <Route
                  path="admin/sources"
                  element={
                    <RequireAdmin>
                      <AdminSources />
                    </RequireAdmin>
                  }
                />
                <Route
                  path="institution"
                  element={
                    <RequirePermission permission="DEPARTMENT_MANAGE">
                      <CollegeAdministration />
                    </RequirePermission>
                  }
                />
                <Route
                  path="admin/institutions"
                  element={
                    <RequireAdmin>
                      <AdminInstitutions />
                    </RequireAdmin>
                  }
                />
                <Route path="*" element={<NotFound inApp />} />
              </Routes>
            </AppShell>
          </RequireAuth>
        }
      />

      <Route path="*" element={<NotFound />} />
      </Routes>
    </ErrorBoundary>
  );
}
