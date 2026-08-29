import { useEffect, useState, type ReactNode } from 'react';
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom';

import { Icon } from '../ui/Icon';
import { Button, cn } from '../ui/primitives';
import { useAuth } from '../../lib/auth';
import { navigationFor, type NavIcon, type NavItem } from '../../lib/navigation';
import { useUnreadCount } from '../../lib/queries';
import { initials } from '../../lib/format';

export function BrandMark({ size = 26 }: { size?: number }) {
  return (
    <span className="brand-mark" style={{ width: size, height: size }} aria-hidden="true">
      <svg width={size * 0.62} height={size * 0.62} viewBox="0 0 20 20" fill="none">
        {/* Three converging paths resolving into one point: sources, jobs and
            candidate signal converging on a recommendation. */}
        <path
          d="M2 4h6M2 10h9M2 16h6"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
        />
        <circle cx="15.5" cy="10" r="2.6" fill="currentColor" />
      </svg>
    </span>
  );
}

/** Icon keys live in the navigation module so it stays free of React. */
const ICONS: Record<NavIcon, ReactNode> = {
  Dashboard: <Icon.Dashboard size={16} />,
  Compass: <Icon.Compass size={16} />,
  Bookmark: <Icon.Bookmark size={16} />,
  Document: <Icon.Document size={16} />,
  Bell: <Icon.Bell size={16} />,
  User: <Icon.User size={16} />,
  Sliders: <Icon.Sliders size={16} />,
  Radar: <Icon.Radar size={16} />,
  Terminal: <Icon.Terminal size={16} />,
  Building: <Icon.Building size={16} />,
  Layers: <Icon.Layers size={16} />,
  Pulse: <Icon.Pulse size={16} />,
};

function iconFor(name: NavIcon): ReactNode {
  return ICONS[name];
}

export function AppShell({ children }: { children: ReactNode }) {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [search, setSearch] = useState('');
  const unread = useUnreadCount();

  // Navigating on a small screen should close the drawer behind you.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  const groups = navigationFor(user);

  const unreadCount = unread.data?.count ?? 0;

  const renderNav = (entries: NavItem[]) =>
    entries.map((entry) => (
      <NavLink
        key={entry.to}
        to={entry.to}
        end={entry.end}
        className={({ isActive }) => cn('nav-item', isActive && 'nav-item--active')}
      >
        <span className="nav-item__icon">{iconFor(entry.icon)}</span>
        <span>{entry.label}</span>
        {entry.label === 'Alerts' && unreadCount > 0 && (
          <span className="nav-item__badge">{unreadCount > 99 ? '99+' : unreadCount}</span>
        )}
      </NavLink>
    ));

  return (
    <div className="shell">
      <a className="skip-link" href="#main">
        Skip to content
      </a>

      {drawerOpen && (
        <div className="sidebar__scrim" onClick={() => setDrawerOpen(false)} aria-hidden="true" />
      )}

      <aside className={cn('sidebar', drawerOpen && 'sidebar--open')}>
        <Link to="/app" className="sidebar__brand">
          <BrandMark />
          <span className="brand-word">CareerFlux</span>
        </Link>

        <nav className="sidebar__nav" aria-label="Main">
          {groups.map((group, index) => (
            <div className="nav-group" key={group.label ?? `group-${index}`}>
              {group.label && <p className="nav-group__label">{group.label}</p>}
              {renderNav(group.items)}
            </div>
          ))}
        </nav>

        <div className="sidebar__footer">
          <button
            type="button"
            className="account"
            onClick={() => navigate('/app/profile')}
          >
            <span className="account__avatar">{initials(user?.fullName)}</span>
            <span className="grow truncate">
              <span className="account__name truncate" style={{ display: 'block' }}>
                {user?.fullName ?? 'Signed in'}
              </span>
              <span className="account__email truncate" style={{ display: 'block' }}>
                {user?.email}
              </span>
            </span>
          </button>
        </div>
      </aside>

      <div className="shell__main">
        <header className="topbar">
          <Button
            variant="ghost"
            size="sm"
            iconOnly
            className="topbar__menu"
            aria-label="Open navigation"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen((open) => !open)}
          >
            <Icon.Menu size={17} />
          </Button>

          <form
            className="topbar__search"
            role="search"
            onSubmit={(event) => {
              event.preventDefault();
              navigate(`/app/discover?q=${encodeURIComponent(search)}`);
            }}
          >
            <div className="search">
              <Icon.Search size={15} className="search__icon" />
              <input
                type="search"
                className="input"
                placeholder="Search roles, companies, skills"
                aria-label="Search jobs"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
            </div>
          </form>

          <div className="topbar__actions">
            <Button
              variant="ghost"
              size="sm"
              iconOnly
              className="notif-button"
              aria-label={unreadCount > 0 ? `Alerts, ${unreadCount} unread` : 'Alerts'}
              onClick={() => navigate('/app/notifications')}
            >
              <Icon.Bell size={16} />
              {unreadCount > 0 && <span className="notif-button__dot" />}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              iconOnly
              aria-label="Sign out"
              onClick={() => {
                signOut();
                navigate('/');
              }}
            >
              <Icon.Logout size={16} />
            </Button>
          </div>
        </header>

        <main id="main">{children}</main>
      </div>
    </div>
  );
}

export function PageHeader({
  title,
  subtitle,
  actions,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <header className="page-header">
      <div>
        <h1 className="page-title">{title}</h1>
        {subtitle && <p className="page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="row gap-2">{actions}</div>}
    </header>
  );
}
