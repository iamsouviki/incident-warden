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
const USER_KEY  = 'mcp_user';

// ─── Types ───────────────────────────────────────────────────────────────────
export interface AuthUser {
  username: string;
  role: string;
  tenantId: string;
  token: string;
  expiresIn: number;
}

export interface LoginResponse {
  token: string;
  username: string;
  role: string;
  tenantId: string;
  expiresIn: number;
}

// ─── Token storage ───────────────────────────────────────────────────────────
export function setAuth(user: AuthUser): void {
  localStorage.setItem(TOKEN_KEY, user.token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
}

export function clearAuth(): void {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function getStoredUser(): AuthUser | null {
  const raw = localStorage.getItem(USER_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as AuthUser;
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
 * Silently exchange the current (still-valid) JWT for a fresh one.
 * Calls POST /api/auth/refresh — no password required.
 * Returns true on success, false if the token is already expired or the call fails.
 */
export async function refreshToken(): Promise<boolean> {
  if (_refreshPromise) return _refreshPromise;
  const token = getToken();
  if (!token) return false;

  _refreshPromise = (async (): Promise<boolean> => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ token }),
      });
      if (!res.ok) return false;
      const data = await res.json();
      if (data.token) {
        const stored = getStoredUser();
        if (stored) {
          const updated: AuthUser = { ...stored, token: data.token, expiresIn: data.expiresIn ?? stored.expiresIn };
          setAuth(updated);
        } else {
          localStorage.setItem(TOKEN_KEY, data.token);
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
export async function login(username: string, password: string): Promise<AuthUser> {
  const res = await fetch('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });

  if (res.status === 401) {
    const err = await res.json().catch(() => ({}));
    throw new Error((err as any).error || 'Invalid username or password');
  }
  if (!res.ok) {
    throw new Error(`Login failed (HTTP ${res.status})`);
  }

  const data = (await res.json()) as LoginResponse;
  const user: AuthUser = { ...data };
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
    // Only force logout for auth-related 403s (i.e. when there's no token at all
    // or the token is provably expired).  Preserve the current response so callers
    // that legitimately handle 403 (permission errors) can still inspect it.
    const token = getToken();
    if (!token) {
      clearAuth();
      window.location.reload();
      return res;
    }
    if (res.status === 401) {
      clearAuth();
      window.location.reload();
    }
  }

  return res;
}

/**
 * Convenience wrapper — GETs JSON via authFetch.
 */
export async function apiGet<T>(url: string): Promise<T> {
  const res = await authFetch(url);
  if (!res.ok) throw new Error(`GET ${url} → HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

/**
 * Convenience wrapper — POSTs JSON via authFetch.
 */
export async function apiPost<T>(url: string, body: unknown): Promise<T> {
  const res = await authFetch(url, { method: 'POST', body: JSON.stringify(body) });
  if (!res.ok) throw new Error(`POST ${url} → HTTP ${res.status}`);
  return res.json() as Promise<T>;
}

// ─── Knowledge Base API helpers ──────────────────────────────────────────────────

export interface KbStats {
  totalEntries: number;
  pendingEmbedding: number;
  byCategory: Record<string, number>;
  vectorStoreActive: boolean;
  fullRagAvailable: boolean;
}

export async function fetchKbStats(tenantId: string): Promise<KbStats> {
  return apiGet<KbStats>(`/api/v1/kb/stats?tenantId=${tenantId}`);
}

/**
 * Search the KB using combined text + semantic similarity.
 * Returns ranked resolved-incident entries and optional RAG hints.
 */
export async function searchKb(
  tenantId: string,
  query: string,
  topK = 10,
): Promise<{ results: object[]; ragHints: object[]; vectorStoreActive: boolean }> {
  return apiPost('/api/v1/kb/search', { tenantId, query, topK });
}

/**
 * Get an LLM resolution suggestion derived from both SOPs and resolved-KB entries.
 * Falls back gracefully when ChatClient / VectorStore is not configured.
 */
export async function getKbSuggestion(
  incidentDescription: string,
): Promise<{ suggestion: string; sources: object[]; fullRagAvailable: boolean; sourcesFound: number }> {
  return apiPost('/api/v1/kb/suggest', { incidentDescription });
}
