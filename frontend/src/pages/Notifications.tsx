import { Link } from 'react-router-dom';

import { PageHeader } from '../components/layout/AppShell';
import { Icon } from '../components/ui/Icon';
import {
  Badge,
  Button,
  EmptyState,
  ErrorState,
  Panel,
  Skeleton,
  cn,
} from '../components/ui/primitives';
import { useMarkNotificationsRead, useNotifications } from '../lib/queries';
import { relativeTime, titleize } from '../lib/format';
import { ALERTS_SUBTITLE, PRIORITY_LABEL } from '../lib/productCopy';
import type { NotificationView } from '../lib/types';

const PRIORITY_TONE = {
  IMMEDIATE: 'positive',
  HIGH: 'accent',
  DIGEST: 'neutral',
  LOW: 'neutral',
} as const;

function NotificationRow({
  notification,
  onRead,
}: {
  notification: NotificationView;
  onRead: (id: string) => void;
}) {
  const unread = !notification.readAt;
  return (
    <div
      className="job-row"
      style={{ gridTemplateColumns: 'auto 1fr auto', alignItems: 'flex-start' }}
    >
      <span
        className={cn('source-row__pip')}
        style={{
          marginTop: 7,
          background: unread ? 'var(--accent)' : 'var(--line-strong)',
        }}
        aria-hidden="true"
      />
      <div className="grow">
        <div className="row wrap gap-2" style={{ marginBottom: 2 }}>
          <Badge tone={PRIORITY_TONE[notification.priority]} square>
            {PRIORITY_LABEL[notification.priority]}
          </Badge>
          <span className="text-faint" style={{ fontSize: 'var(--text-2xs)' }}>
            {titleize(notification.category)}
          </span>
        </div>
        {notification.jobId ? (
          <Link
            to={`/app/jobs/${notification.jobId}`}
            className="job-row__title"
            onClick={() => unread && onRead(notification.id)}
          >
            {notification.title}
          </Link>
        ) : (
          <span className="job-row__title">{notification.title}</span>
        )}
        {notification.body && (
          <p
            className="text-muted clamp-2"
            style={{ fontSize: 'var(--text-sm)', marginTop: 2, lineHeight: 'var(--leading-snug)' }}
          >
            {notification.body}
          </p>
        )}
        <p className="timeline__time" style={{ marginTop: 4 }}>
          {relativeTime(notification.createdAt)}
        </p>
      </div>
      {unread && (
        <Button variant="ghost" size="sm" onClick={() => onRead(notification.id)}>
          Mark read
        </Button>
      )}
    </div>
  );
}

export default function Notifications() {
  const notifications = useNotifications(0);
  const markRead = useMarkNotificationsRead();

  const unreadCount = notifications.data?.content.filter((entry) => !entry.readAt).length ?? 0;

  return (
    <div className="page">
      <PageHeader
        title="Alerts"
        subtitle={ALERTS_SUBTITLE}
        actions={
          unreadCount > 0 && (
            <Button
              variant="secondary"
              size="sm"
              loading={markRead.isPending}
              onClick={() => markRead.mutate(undefined)}
            >
              Mark all read
            </Button>
          )
        }
      />

      {notifications.isLoading && (
        <Panel flush>
          {[0, 1, 2].map((index) => (
            <div key={index} style={{ padding: 'var(--space-4)' }}>
              <Skeleton height={44} />
            </div>
          ))}
        </Panel>
      )}

      {notifications.isError && (
        <Panel>
          <ErrorState
            title="Alerts could not be loaded"
            body={(notifications.error as Error).message}
            onRetry={() => notifications.refetch()}
          />
        </Panel>
      )}

      {notifications.data && notifications.data.content.length === 0 && (
        <Panel>
          <EmptyState
            icon={<Icon.Bell size={20} />}
            title="No alerts yet"
            body="Alerts are created when a newly ingested job clears your match threshold. Existing jobs you were matched against during setup do not generate alerts, because that would be fifty notifications on your first day."
          />
        </Panel>
      )}

      {notifications.data && notifications.data.content.length > 0 && (
        <Panel flush>
          {notifications.data.content.map((notification) => (
            <NotificationRow
              key={notification.id}
              notification={notification}
              onRead={(id) => markRead.mutate(id)}
            />
          ))}
        </Panel>
      )}
    </div>
  );
}
