import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';
import { useQueryClient } from '@tanstack/react-query';

import { api, setSessionExpiredHandler, tokenStore } from './api';
import type { AuthResponse, SessionUser } from './types';

interface AuthContextValue {
  user: SessionUser | null;
  /** True until the stored token has been checked against the server. */
  initialising: boolean;
  signIn: (email: string, password: string) => Promise<SessionUser>;
  register: (
    email: string,
    password: string,
    fullName: string,
    institutionCode?: string,
  ) => Promise<SessionUser>;
  signOut: () => void;
  refreshUser: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<SessionUser | null>(null);
  const [initialising, setInitialising] = useState(true);
  const queryClient = useQueryClient();

  const signOut = useCallback(() => {
    tokenStore.clear();
    setUser(null);
    queryClient.clear();
  }, [queryClient]);

  // A refresh failure anywhere in the app lands here, so the session ends in
  // one place rather than each screen inventing its own recovery.
  useEffect(() => {
    setSessionExpiredHandler(() => {
      setUser(null);
      queryClient.clear();
    });
  }, [queryClient]);

  const refreshUser = useCallback(async () => {
    if (!tokenStore.access) {
      setUser(null);
      return;
    }
    try {
      setUser(await api.get<SessionUser>('/api/auth/me'));
    } catch {
      tokenStore.clear();
      setUser(null);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      await refreshUser();
      if (!cancelled) {
        setInitialising(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [refreshUser]);

  const applySession = useCallback((response: AuthResponse) => {
    tokenStore.set(response.accessToken, response.refreshToken);
    setUser(response.user);
    return response.user;
  }, []);

  const signIn = useCallback(
    async (email: string, password: string) => {
      const response = await api.post<AuthResponse>(
        '/api/auth/login',
        { email, password },
        { skipAuth: true },
      );
      return applySession(response);
    },
    [applySession],
  );

  const register = useCallback(
    async (email: string, password: string, fullName: string, institutionCode?: string) => {
      const response = await api.post<AuthResponse>(
        '/api/auth/register',
        // Omitted rather than sent empty: the server treats a blank code as a
        // supplied-but-wrong code, and the message differs.
        { email, password, fullName, ...(institutionCode ? { institutionCode } : {}) },
        { skipAuth: true },
      );
      return applySession(response);
    },
    [applySession],
  );

  const value = useMemo(
    () => ({ user, initialising, signIn, register, signOut, refreshUser }),
    [user, initialising, signIn, register, signOut, refreshUser],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
}
