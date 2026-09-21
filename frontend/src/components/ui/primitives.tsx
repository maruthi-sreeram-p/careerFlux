import {
  createContext,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type ButtonHTMLAttributes,
  type InputHTMLAttributes,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from 'react';

import { Icon } from './Icon';

export function cn(...parts: (string | false | null | undefined)[]): string {
  return parts.filter(Boolean).join(' ');
}

/* ---------------------------------------------------------------- Button */

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: 'sm' | 'md' | 'lg';
  block?: boolean;
  iconOnly?: boolean;
  loading?: boolean;
}

export function Button({
  variant = 'secondary',
  size = 'md',
  block,
  iconOnly,
  loading,
  disabled,
  className,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type="button"
      className={cn(
        'btn',
        `btn--${variant}`,
        size !== 'md' && `btn--${size}`,
        block && 'btn--block',
        iconOnly && 'btn--icon',
        className,
      )}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? <span className="spinner" aria-hidden="true" /> : children}
    </button>
  );
}

/* ----------------------------------------------------------------- Badge */

interface BadgeProps {
  tone?: 'neutral' | 'accent' | 'positive' | 'caution' | 'negative' | 'info';
  square?: boolean;
  dot?: boolean;
  live?: boolean;
  children: ReactNode;
  className?: string;
  title?: string;
}

export function Badge({ tone = 'neutral', square, dot, live, children, className, title }: BadgeProps) {
  return (
    <span
      className={cn('badge', tone !== 'neutral' && `badge--${tone}`, square && 'badge--square', className)}
      title={title}
    >
      {dot && <span className={cn('badge__dot', live && 'badge__dot--live')} />}
      {children}
    </span>
  );
}

/* ------------------------------------------------------------------ Chip */

interface ChipProps {
  selected?: boolean;
  onClick?: () => void;
  children: ReactNode;
  className?: string;
  title?: string;
}

export function Chip({ selected, onClick, children, className, title }: ChipProps) {
  if (!onClick) {
    return (
      <span className={cn('chip', className)} title={title}>
        {children}
      </span>
    );
  }
  return (
    <button
      type="button"
      className={cn('chip', 'chip--interactive', selected && 'chip--selected', className)}
      aria-pressed={selected}
      onClick={onClick}
      title={title}
    >
      {children}
    </button>
  );
}

/* ----------------------------------------------------------------- Panel */

export function Panel({
  title,
  action,
  children,
  flush,
  raised,
  className,
  tight,
}: {
  title?: ReactNode;
  action?: ReactNode;
  children: ReactNode;
  flush?: boolean;
  raised?: boolean;
  className?: string;
  tight?: boolean;
}) {
  return (
    <section className={cn('panel', raised && 'panel--raised', className)}>
      {(title || action) && (
        <header className="panel__header">
          {typeof title === 'string' ? <h2 className="panel__title">{title}</h2> : title}
          {action}
        </header>
      )}
      <div className={cn('panel__body', flush && 'panel__body--flush', tight && 'panel__body--tight')}>
        {children}
      </div>
    </section>
  );
}

/* ----------------------------------------------------------------- Field */

interface FieldProps {
  label?: string;
  hint?: string;
  error?: string;
  children: (props: { id: string; describedBy?: string; invalid: boolean }) => ReactNode;
}

/**
 * Wires label, hint and error to the control via ids so screen readers announce
 * the whole story, not just the input.
 */
export function Field({ label, hint, error, children }: FieldProps) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ');

  return (
    <div className="field">
      {label && (
        <label className="field__label" htmlFor={id}>
          {label}
        </label>
      )}
      {children({ id, describedBy: describedBy || undefined, invalid: Boolean(error) })}
      {hint && !error && (
        <p className="field__hint" id={hintId}>
          {hint}
        </p>
      )}
      {error && (
        <p className="field__error" id={errorId} role="alert">
          <Icon.Warning size={12} />
          {error}
        </p>
      )}
    </div>
  );
}

export function TextInput({
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={cn('input', className)} {...props} />;
}

export function TextArea({
  className,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return <textarea className={cn('textarea', className)} {...props} />;
}

export function Select({
  className,
  children,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cn('select', className)} {...props}>
      {children}
    </select>
  );
}

export function SearchInput({
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <div className={cn('search', className)}>
      <Icon.Search size={15} className="search__icon" />
      <input type="search" className="input" {...props} />
    </div>
  );
}

export function Checkbox({
  label,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode }) {
  return (
    <label className="checkbox">
      <input type="checkbox" {...props} />
      <span>{label}</span>
    </label>
  );
}

export function Switch({
  label,
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: ReactNode }) {
  return (
    <label className="switch">
      <input type="checkbox" role="switch" {...props} />
      <span>{label}</span>
    </label>
  );
}

/* ---------------------------------------------------------------- States */

export function EmptyState({
  icon,
  title,
  body,
  action,
}: {
  icon?: ReactNode;
  title: string;
  body?: string;
  action?: ReactNode;
}) {
  return (
    <div className="state">
      {icon && <div className="state__icon">{icon}</div>}
      <p className="state__title">{title}</p>
      {body && <p className="state__body">{body}</p>}
      {action}
    </div>
  );
}

export function ErrorState({
  title = 'Something went wrong',
  body,
  onRetry,
}: {
  title?: string;
  body?: string;
  onRetry?: () => void;
}) {
  return (
    <div className="state state--error" role="alert">
      <div className="state__icon">
        <Icon.Warning size={20} />
      </div>
      <p className="state__title">{title}</p>
      {body && <p className="state__body">{body}</p>}
      {onRetry && (
        <Button variant="secondary" size="sm" onClick={onRetry}>
          <Icon.Refresh size={14} />
          Try again
        </Button>
      )}
    </div>
  );
}

export function Skeleton({
  width,
  height = 12,
  radius,
  className,
}: {
  width?: number | string;
  height?: number | string;
  radius?: number;
  className?: string;
}) {
  return (
    <span
      className={cn('skeleton', className)}
      style={{
        display: 'block',
        width: width ?? '100%',
        height,
        borderRadius: radius ?? undefined,
      }}
      aria-hidden="true"
    />
  );
}

/* --------------------------------------------------------------- Tooltip */

export function Tooltip({ label, children }: { label: string; children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <span
      className="tooltip-host"
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onFocus={() => setOpen(true)}
      onBlur={() => setOpen(false)}
    >
      {children}
      {open && <span className="tooltip" role="tooltip">{label}</span>}
    </span>
  );
}

/* ---------------------------------------------------------------- Dialog */

export function Dialog({
  open,
  onClose,
  title,
  children,
  footer,
}: {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const titleId = useId();

  // Callers usually pass a fresh onClose on every render. Kept in a ref so the
  // effect below runs when the dialog opens, not on every keystroke inside it:
  // re-running it moved focus back to the dialog after each character typed.
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open) {
      return;
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onCloseRef.current();
      }
    };
    document.addEventListener('keydown', onKeyDown);
    // Focus moves into the dialog so the keyboard does not stay behind it.
    ref.current?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [open]);

  if (!open) {
    return null;
  }

  return (
    <div
      className="dialog-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        ref={ref}
      >
        <header className="panel__header">
          <h2 className="panel__title" id={titleId}>
            {title}
          </h2>
          <Button variant="ghost" size="sm" iconOnly onClick={onClose} aria-label="Close">
            <Icon.Close size={15} />
          </Button>
        </header>
        <div className="panel__body">{children}</div>
        {footer && (
          <footer className="panel__header" style={{ borderBottom: 'none', borderTop: '1px solid var(--line-faint)' }}>
            <span />
            <div className="row gap-2">{footer}</div>
          </footer>
        )}
      </div>
    </div>
  );
}

/* ----------------------------------------------------------------- Toast */

interface ToastMessage {
  id: number;
  text: string;
  tone: 'default' | 'success' | 'error';
}

interface ToastContextValue {
  show: (text: string, tone?: ToastMessage['tone']) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [messages, setMessages] = useState<ToastMessage[]>([]);
  const nextId = useRef(1);

  const show = (text: string, tone: ToastMessage['tone'] = 'default') => {
    const id = nextId.current++;
    setMessages((current) => [...current, { id, text, tone }]);
    window.setTimeout(() => {
      setMessages((current) => current.filter((message) => message.id !== id));
    }, 5000);
  };

  return (
    <ToastContext.Provider value={{ show }}>
      {children}
      <div className="toast-region" aria-live="polite" aria-atomic="false">
        {messages.map((message) => (
          <div
            key={message.id}
            className={cn('toast', message.tone !== 'default' && `toast--${message.tone}`)}
          >
            {message.tone === 'success' && (
              <Icon.Check size={15} style={{ color: 'var(--positive)', marginTop: 2 }} />
            )}
            {message.tone === 'error' && (
              <Icon.Warning size={15} style={{ color: 'var(--negative)', marginTop: 2 }} />
            )}
            <span>{message.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error('useToast must be used inside ToastProvider');
  }
  return context;
}
