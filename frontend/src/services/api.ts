/**
 * api.ts — centralised HTTP + JWT helpers
 *
 * Token lifecycle:
 *   setAuth()   → called after successful login
 *   clearAuth() → called on logout / 401
 *   getToken()  → read token for Authorization header
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
  const token = getToken();
  const headers = new Headers(init.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (!headers.has('Content-Type') && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json');
  }

  const res = await fetch(input, { ...init, headers });

  if (res.status === 401) {
    clearAuth();
    window.location.reload();
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
