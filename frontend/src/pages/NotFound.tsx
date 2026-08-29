import { Link } from 'react-router-dom';

import { Icon } from '../components/ui/Icon';
import { EmptyState } from '../components/ui/primitives';

export default function NotFound({ inApp = false }: { inApp?: boolean }) {
  const content = (
    <EmptyState
      icon={<Icon.Compass size={20} />}
      title="There is nothing at this address"
      body="The page you were looking for does not exist, or it moved."
      action={
        <Link to={inApp ? '/app' : '/'} className="btn btn--primary btn--sm">
          {inApp ? 'Back to dashboard' : 'Back to home'}
        </Link>
      }
    />
  );

  if (inApp) {
    return <div className="page">{content}</div>;
  }
  return <div style={{ display: 'grid', placeItems: 'center', minHeight: '100dvh' }}>{content}</div>;
}
