/**
 * api.ts — centralised HTTP + JWT helpers
 *
 * Token lifecycle:
 *   setAuth()            → called after successful login
 *   clearAuth()          → called on logout / 401
 *   getToken()           → read token for Authorization header
 *   refreshToken()       → silently issues a new JWT from a still-valid one
 *   isTokenExpiringSoon()→ true when token expires within the given window
 */

// ─── Storage keys ────────────────────────────────────────────────────────────
const TOKEN_KEY = 'mcp_jwt_token';
const REFRESH_TOKEN_KEY = 'mcp_refresh_token';
const USER_KEY  = 'mcp_user';
export const SIMPLE_ERROR_MESSAGE = 'Something went wrong. Please try again.';

// ─── Types ───────────────────────────────────────────────────────────────────
export interface AuthUser {
  username: string;
  fullName?: string;
  role: string;
  department?: string;
  tenantId: string;
  tenantName?: string;
  token: string;
  expiresIn: number;
  refreshToken?: string;
  refreshExpiresIn?: number;
  /** Set by the server when the account still carries a password an admin handed over. */
  mustChangePassword?: boolean;
}

export interface LoginResponse {
  token?: string;
  accessToken?: string;
  refreshToken?: string;
  username: string;
  fullName?: string;
  role: string;
  department?: string;
  tenantId: string;
  tenantName?: string;
  expiresIn: number;
  refreshExpiresIn?: number;
  mustChangePassword?: boolean;
}

// ─── Token storage ───────────────────────────────────────────────────────────
export function setAuth(user: AuthUser): void {
  localStorage.setItem(TOKEN_KEY, user.token);
  if (user.refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, user.refreshToken);
  else localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw) as AuthUser;
    // A record with no username is not a session. Six components read this shape and render
    // from it — initials, "requested by", the assignee filter — so a half-written or stale
    // record has to present as signed-out here, at the one reader, rather than white-screening
    // the app inside whichever component touches it first. Every caller already handles null.
    return parsed && parsed.username ? parsed : null;
  } catch {
    return null;
  }
}

export function isAuthenticated(): boolean {
  return !!getToken();
}

// ─── Token expiry helpers ─────────────────────────────────────────────────────

/** Decode the JWT `exp` claim and return its value in milliseconds. */
export function getTokenExpiry(): number | null {
  const token = getToken();
  if (!token) return null;
  try {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(atob(b64));
    return typeof payload.exp === 'number' ? payload.exp * 1000 : null;
  } catch {
    return null;
  }
}

/**
 * Returns true when the stored token expires within `thresholdMs` milliseconds.
 * Default threshold: 5 minutes (proactive per-request refresh window).
 */
export function isTokenExpiringSoon(thresholdMs = 5 * 60 * 1000): boolean {
  const exp = getTokenExpiry();
  if (!exp) return false;
  return exp - Date.now() < thresholdMs;
}

// Single in-flight refresh promise — prevents stampede when many requests fire simultaneously
let _refreshPromise: Promise<boolean> | null = null;

/**
 * Silently exchange the stored refresh token for a fresh access token.
 * Calls POST /api/auth/refresh — no password required.
 *
 * The replacement refresh token the server returns carries the SAME expiry as the one sent,
 * so rotating does not extend the session: the 7 days (or 1 day without "keep me signed in")
 * are counted from password entry and end there regardless of how often this runs.
 *
 * Returns false when there is nothing to refresh with, or the window has closed — the caller
 * signs the user out.
 */
export async function refreshToken(): Promise<boolean> {
  if (_refreshPromise) return _refreshPromise;
  const refreshTokenValue = localStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshTokenValue) return false;

  _refreshPromise = (async (): Promise<boolean> => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: refreshTokenValue }),
      });
      if (!res.ok) return false;
      const data = await res.json();
      const accessToken = data.accessToken || data.token;
      if (accessToken) {
        const stored = getStoredUser();
        if (stored) {
          const updated: AuthUser = {
            ...stored,
            token: accessToken,
            expiresIn: data.expiresIn ?? stored.expiresIn,
            refreshToken: data.refreshToken || stored.refreshToken,
            refreshExpiresIn: data.refreshExpiresIn ?? stored.refreshExpiresIn,
          };
          setAuth(updated);
        } else {
          localStorage.setItem(TOKEN_KEY, accessToken);
          if (data.refreshToken) localStorage.setItem(REFRESH_TOKEN_KEY, data.refreshToken);
        }
        return true;
      }
      return false;
    } catch {
      return false;
    } finally {
      _refreshPromise = null;
    }
  })();

  return _refreshPromise;
}

// ─── Login ───────────────────────────────────────────────────────────────────
export async function login(username: string, password: string, rememberMe = false, role?: 'VIEWER' | 'ANALYST' | 'ADMIN'): Promise<AuthUser> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password, rememberMe, ...(role ? { role } : {}) }),
  });

  if (res.status === 401) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || 'Invalid username or password');
  }
  if (!res.ok) {
    throw new Error(SIMPLE_ERROR_MESSAGE);
  }

  const data = (await res.json()) as LoginResponse;
  const user: AuthUser = {
    ...data,
    token: data.accessToken || data.token || '',
    refreshToken: data.refreshToken,
  };
  if (!user.token) throw new Error(SIMPLE_ERROR_MESSAGE);
  setAuth(user);
  return user;
}

// ─── Authenticated fetch ──────────────────────────────────────────────────────
/**
 * Drop-in replacement for `fetch()` that:
 *   1. Injects `Authorization: Bearer <token>` header
 *   2. On 401 → clears stored token and reloads the page (login redirect)
 */
export async function authFetch(input: string, init: RequestInit = {}): Promise<Response> {
  // ── Proactively refresh if token expires within the next 5 minutes ──────
  if (isTokenExpiringSoon(5 * 60 * 1000)) {
    await refreshToken();
  }

  const token = getToken();
  const headers = new Headers(init.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (!headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(input, { ...init, headers });

  // 401 = token rejected by backend; 403 = Spring Security stateless default for
  // unauthenticated requests (expired/missing JWT). Both require re-login.
  if (res.status === 401 || res.status === 403) {
    // Dispatch a custom event instead of calling window.location.reload() so that
    // App.tsx can handle logout via React state (prevents white-page flash from
    // tearing down the component tree during an in-progress render).
    const token = getToken();
    if (!token) {
      clearAuth();
      window.dispatchEvent(new CustomEvent('mcp:auth-expired'));
      return res;
    }
    if (res.status === 401) {
      clearAuth();
      window.dispatchEvent(new CustomEvent('mcp:auth-expired'));
    }
  }

  return res;
}

export async function extractApiError(res: Response): Promise<string> {
  try {
    const data = await res.json();
    if (data && typeof data.error === 'string' && data.error.trim()) {
      return data.error;
    }
  } catch {
    // Ignore invalid/non-JSON bodies and use the shared fallback.
  }
  return SIMPLE_ERROR_MESSAGE;
}

/**
 * Convenience wrapper — GETs JSON via authFetch.
 */
export async function apiGet<T>(url: string): Promise<T> {
  const res = await authFetch(url);
  if (!res.ok) throw new Error(await extractApiError(res));
  return res.json() as Promise<T>;
}

/**
 * Convenience wrapper — POSTs JSON via authFetch.
 */
export async function apiPost<T>(url: string, body: unknown): Promise<T> {
  const res = await authFetch(url, { method: 'POST', body: JSON.stringify(body) });
  if (!res.ok) throw new Error(await extractApiError(res));
  return res.json() as Promise<T>;
}

/**
 * Convenience wrapper — PUTs JSON via authFetch.
 *
 * The incident PUT treats an absent field as "not supplied", so callers send only the
 * fields they mean to change rather than a whole stale entity.
 */
export async function apiPut<T>(url: string, body: unknown): Promise<T> {
  const res = await authFetch(url, { method: 'PUT', body: JSON.stringify(body) });
  if (!res.ok) throw new Error(await extractApiError(res));
  return res.json() as Promise<T>;
}
