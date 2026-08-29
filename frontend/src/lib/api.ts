import type { ApiErrorBody } from './types';

const TOKEN_KEY = 'careerflux.access';
const REFRESH_KEY = 'careerflux.refresh';

/**
 * A failed API call, carrying the server's structured error rather than a
 * stringified response. Screens render `message` directly — it is written to be
 * shown to a person.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly violations: { field: string; message: string }[];
  readonly details: Record<string, unknown> | undefined;

  constructor(body: ApiErrorBody) {
    super(body.message);
    this.name = 'ApiError';
    this.status = body.status;
    this.code = body.code;
    this.violations = body.violations ?? [];
    this.details = body.details;
  }

  /** The message for a specific field, if the server flagged one. */
  fieldError(field: string): string | undefined {
    return this.violations.find((violation) => violation.field === field)?.message;
  }

  get isAuthError(): boolean {
    return this.status === 401 || this.status === 403;
  }
}

/** Thrown when the browser could not reach the API at all. */
export class NetworkError extends Error {
  constructor() {
    super('CareerFlux could not reach the server. Check that the API is running.');
    this.name = 'NetworkError';
  }
}

export const tokenStore = {
  get access(): string | null {
    return safeRead(TOKEN_KEY);
  },
  get refresh(): string | null {
    return safeRead(REFRESH_KEY);
  },
  set(access: string, refresh: string) {
    safeWrite(TOKEN_KEY, access);
    safeWrite(REFRESH_KEY, refresh);
  },
  clear() {
    safeRemove(TOKEN_KEY);
    safeRemove(REFRESH_KEY);
  },
};

function safeRead(key: string): string | null {
  try {
    return window.localStorage.getItem(key);
  } catch {
    // Private windows and blocked site data both throw here.
    return null;
  }
}

function safeWrite(key: string, value: string) {
  try {
    window.localStorage.setItem(key, value);
  } catch {
    // Non-fatal: the session simply will not survive a reload.
  }
}

function safeRemove(key: string) {
  try {
    window.localStorage.removeItem(key);
  } catch {
    // Non-fatal.
  }
}

/** Called when a refresh attempt fails, so the app can send the user to sign in. */
let onSessionExpired: (() => void) | null = null;

export function setSessionExpiredHandler(handler: () => void) {
  onSessionExpired = handler;
}

interface RequestOptions {
  method?: string;
  body?: unknown;
  formData?: FormData;
  signal?: AbortSignal;
  /** Set for the auth endpoints, which must not attempt a token refresh. */
  skipAuth?: boolean;
}

/**
 * A single refresh promise shared by every request that hits a 401 at once, so a
 * page with six parallel queries produces one refresh, not six.
 */
let refreshInFlight: Promise<boolean> | null = null;

async function refreshSession(): Promise<boolean> {
  const refreshToken = tokenStore.refresh;
  if (!refreshToken) {
    return false;
  }
  try {
    const response = await fetch('/api/auth/refresh', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    });
    if (!response.ok) {
      return false;
    }
    const data = (await response.json()) as { accessToken: string; refreshToken: string };
    tokenStore.set(data.accessToken, data.refreshToken);
    return true;
  } catch {
    return false;
  }
}

async function execute<T>(path: string, options: RequestOptions, isRetry: boolean): Promise<T> {
  const headers: Record<string, string> = {};
  const token = tokenStore.access;

  if (token && !options.skipAuth) {
    headers.Authorization = `Bearer ${token}`;
  }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method: options.method ?? 'GET',
      headers,
      body: options.formData ?? (options.body === undefined ? undefined : JSON.stringify(options.body)),
      signal: options.signal,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error;
    }
    throw new NetworkError();
  }

  // One transparent refresh-and-retry on an expired access token.
  if (response.status === 401 && !isRetry && !options.skipAuth && tokenStore.refresh) {
    refreshInFlight ??= refreshSession().finally(() => {
      refreshInFlight = null;
    });
    const refreshed = await refreshInFlight;
    if (refreshed) {
      return execute<T>(path, options, true);
    }
    tokenStore.clear();
    onSessionExpired?.();
  }

  if (response.status === 204) {
    return undefined as T;
  }

  const text = await response.text();
  const parsed = text ? safeParse(text) : null;

  if (!response.ok) {
    if (parsed && typeof parsed === 'object' && 'code' in parsed) {
      throw new ApiError(parsed as ApiErrorBody);
    }
    throw new ApiError({
      timestamp: new Date().toISOString(),
      status: response.status,
      code: 'UNEXPECTED',
      message:
        response.status >= 500
          ? 'Something went wrong on the server.'
          : 'That request could not be completed.',
      path,
    });
  }

  return parsed as T;
}

function safeParse(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

export const api = {
  get: <T>(path: string, signal?: AbortSignal) => execute<T>(path, { signal }, false),
  post: <T>(path: string, body?: unknown, options?: { skipAuth?: boolean }) =>
    execute<T>(path, { method: 'POST', body, skipAuth: options?.skipAuth }, false),
  put: <T>(path: string, body?: unknown) => execute<T>(path, { method: 'PUT', body }, false),
  /** Partial update: sends only the fields named, leaving the rest untouched. */
  patch: <T>(path: string, body?: unknown) => execute<T>(path, { method: 'PATCH', body }, false),
  delete: <T>(path: string) => execute<T>(path, { method: 'DELETE' }, false),
  upload: <T>(path: string, formData: FormData) =>
    execute<T>(path, { method: 'POST', formData }, false),
};

/** Builds a query string, dropping empty values so URLs stay readable. */
export function queryString(params: Record<string, unknown>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '' || value === false) {
      continue;
    }
    if (Array.isArray(value)) {
      value.forEach((entry) => search.append(key, String(entry)));
    } else {
      search.set(key, String(value));
    }
  }
  const result = search.toString();
  return result ? `?${result}` : '';
}
