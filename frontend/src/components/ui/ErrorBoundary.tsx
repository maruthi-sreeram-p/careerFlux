import { Component, type ErrorInfo, type ReactNode } from 'react';

import { Icon } from './Icon';
import { Button } from './primitives';

interface Props {
  children: ReactNode;
  /** Changing this resets the boundary — used to recover on navigation. */
  resetKey?: string;
  fallbackTitle?: string;
}

interface State {
  error: Error | null;
}

/**
 * Catches render errors so one broken component cannot blank the application.
 *
 * <p>A rendering bug is still a bug and still gets logged, but the person using
 * CareerFlux should see a page with a way forward rather than a white screen.
 * The boundary resets when {@code resetKey} changes, so navigating away from a
 * broken screen recovers without a reload.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidUpdate(previous: Props) {
    if (this.state.error && previous.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Left in deliberately: a swallowed render error is worse than a noisy one.
    console.error('Render error caught by boundary', error, info.componentStack);
  }

  render() {
    if (!this.state.error) {
      return this.props.children;
    }
    return (
      <div className="page">
        <div className="state state--error" role="alert">
          <div className="state__icon">
            <Icon.Warning size={20} />
          </div>
          <p className="state__title">{this.props.fallbackTitle ?? 'This screen could not be displayed'}</p>
          <p className="state__body">
            Something went wrong rendering this page. The rest of CareerFlux is unaffected — try
            again, or move to another screen.
          </p>
          <div className="row gap-2">
            <Button variant="primary" size="sm" onClick={() => this.setState({ error: null })}>
              <Icon.Refresh size={14} />
              Try again
            </Button>
            <Button variant="secondary" size="sm" onClick={() => window.location.assign('/app')}>
              Back to dashboard
            </Button>
          </div>
        </div>
      </div>
    );
  }
}
