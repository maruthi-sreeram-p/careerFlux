import { Link } from 'react-router-dom';
import type { ReactNode } from 'react';

import { BrandMark } from '../../components/layout/AppShell';
import { FlowDiagram } from '../../components/marketing/FlowDiagram';
import { Icon } from '../../components/ui/Icon';

/**
 * Shared frame for sign in, register and password reset.
 *
 * The right-hand panel carries the product argument so the auth screens are not
 * a dead end visually, but it drops out entirely below 940px rather than being
 * squeezed — a form on a phone should be a form.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
  aside,
}: {
  title: string;
  subtitle?: string;
  children: ReactNode;
  footer?: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <div className="auth">
      <div className="auth__panel">
        <div className="auth__form">
          <div>
            <Link to="/" className="row gap-3" style={{ marginBottom: 'var(--space-8)' }}>
              <BrandMark />
              <span className="brand-word">CareerFlux</span>
            </Link>
            <h1 className="auth__title">{title}</h1>
            {subtitle && <p className="auth__subtitle">{subtitle}</p>}
          </div>
          {children}
          {footer && <div className="auth__footer">{footer}</div>}
        </div>
      </div>

      <aside className="auth__aside">
        {aside ?? (
          <>
            <div>
              <p className="eyebrow" style={{ marginBottom: 'var(--space-4)' }}>
                What happens next
              </p>
              <p className="auth__quote">
                CareerFlux reads your resume, works out which sources matter for the career you
                want, and explains every match it finds.
              </p>
            </div>
            <FlowDiagram />
            <p className="text-faint row gap-2" style={{ fontSize: 'var(--text-xs)' }}>
              <Icon.Shield size={13} />
              Your resume and profile are treated as sensitive data.
            </p>
          </>
        )}
      </aside>
    </div>
  );
}

export function AuthAlert({ message, tone = 'error' }: { message: string; tone?: 'error' | 'info' }) {
  return (
    <div className={`auth__alert${tone === 'info' ? ' auth__alert--info' : ''}`} role="alert">
      {tone === 'error' ? (
        <Icon.Warning size={15} style={{ color: 'var(--negative)', flex: 'none', marginTop: 1 }} />
      ) : (
        <Icon.Info size={15} style={{ color: 'var(--info)', flex: 'none', marginTop: 1 }} />
      )}
      <span>{message}</span>
    </div>
  );
}
