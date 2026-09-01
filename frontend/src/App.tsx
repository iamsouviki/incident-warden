import React, { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  BookOpen,
  ChevronDown,
  Command,
  FileCode2,
  LogIn,
  LogOut,
  Menu,
  MessageSquare,
  Moon,
  Search,
  Settings,
  SlidersHorizontal,
  Sun,
  UploadCloud,
  User,
  X,
} from 'lucide-react';
import './AppShell.css';
import './EnterprisePages.css';
import ChatPage from './pages/ChatPage';
import LoginPage from './pages/LoginPage';
import SopPage from './pages/SopPage';
import TeamsPage from './pages/TeamsPage';
import AiConfigPage from './pages/AiConfigPage';
import IncidentManagementPage from './pages/IncidentManagementPage';
import ToolsPage from './pages/ToolsPage';
import HitlPage from './pages/HitlPage';
import AccountPage from './pages/AccountPage';
import { AuthUser, authFetch, clearAuth, getStoredUser, setAuth } from './services/api';

const DEFAULT_TENANT_ID = 'tenant-1';

type NavItem = { path: string; label: string; icon: React.ReactNode; group: 'Operate' | 'Manage' };

/**
 * Incident operations navigation items.
 */
const NAV_ITEMS: NavItem[] = [
  { path: '/', label: 'Assistant', icon: <MessageSquare size={16} />, group: 'Operate' },
  { path: '/incidents', label: 'Incident Dump', icon: <UploadCloud size={16} />, group: 'Operate' },
  { path: '/tools', label: 'Skills & Tools', icon: <FileCode2 size={16} />, group: 'Operate' },
  { path: '/sops', label: 'SOP library', icon: <BookOpen size={16} />, group: 'Manage' },
  { path: '/settings/ai', label: 'Settings', icon: <SlidersHorizontal size={16} />, group: 'Manage' },
];

const PAGE_META: Record<string, { title: string; subtitle: string }> = {
  '/incidents': { title: 'Incidents', subtitle: 'Monitor, triage, and resolve issues from every connected source.' },
  '/hitl': { title: 'HITL approval queue', subtitle: 'Review proposed actions before they affect production systems.' },
  '/tools': { title: 'Tools & scripts', subtitle: 'Manage safe remediation actions and execution history.' },
  '/sops': { title: 'SOP library', subtitle: 'Maintain the operational knowledge agents use for recommendations.' },
  '/teams': { title: 'Teams', subtitle: 'Manage ownership, escalation paths, and support coverage.' },
  '/settings/ai': { title: 'Settings', subtitle: 'Choose the model that answers, and who has an account here.' },
  '/account': { title: 'My account', subtitle: 'Manage your profile and session.' },
};

function WorkflowRail({ active }: { active: number }) {
  const steps = ['Intake', 'Understand', 'Decide', 'Act', 'Verify'];
  return <div className="workflow-rail" aria-label="Incident operations workflow">{steps.map((step, index) => <React.Fragment key={step}><div className={`workflow-step ${index === active ? 'active' : ''} ${index < active ? 'complete' : ''}`}><span>{index + 1}</span>{step}</div>{index < steps.length - 1 && <div className={`workflow-connector ${index < active ? 'complete' : ''}`} />}</React.Fragment>)}</div>;
}

/** Signs in, then lands on the assistant rather than leaving the URL on /login. */
function LoginRoute({ onLogin }: { onLogin: (user: AuthUser) => void }) {
  const navigate = useNavigate();
  return (
    <LoginPage
      onLogin={user => { onLogin(user); navigate('/', { replace: true }); }}
      onSkip={() => navigate('/', { replace: true })}
    />
  );
}

/**
 * Blocks the whole app while an account still carries a password somebody else chose for it.
 *
 * An admin creates an account, reads the starter password out over a desk, and the server marks
 * it must_change_password. Until this form succeeds there is nothing else on screen: a password
 * that has been spoken aloud, written in a ticket, or read out of this repository is not a
 * credential, and letting the session continue would leave it in place indefinitely. Signing out
 * is the only other way past it.
 */
function ForcePasswordReset({ user, onDone, onLogout }: { user: AuthUser; onDone: (user: AuthUser) => void; onLogout: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const isLongEnough = next.length >= 8;
  const isMatching = next.length > 0 && confirm.length > 0 && next === confirm;
  const canSubmit = !busy && current.trim().length > 0 && isLongEnough && isMatching;

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    if (!isLongEnough) {
      setError('Password must be at least 8 characters long.');
      return;
    }
    if (next !== confirm) {
      setError('The two new passwords do not match.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      const res = await authFetch('/api/auth/password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
      });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error(body?.error || 'That password could not be set.');
      const updated = { ...user, mustChangePassword: false };
      setAuth(updated);
      onDone(updated);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'That password could not be set.');
    } finally {
      setBusy(false);
    }
  };

  const field: React.CSSProperties = {
    width: '100%', minHeight: '44px', padding: '10px 12px', fontSize: '14px',
    background: 'var(--surface-2, #152033)', color: 'var(--text-1, #ffffff)',
    border: '1.5px solid var(--border, #334155)', borderRadius: '8px', boxSizing: 'border-box',
    outline: 'none',
  };
  const label: React.CSSProperties = {
    display: 'block', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase',
    letterSpacing: '0.4px', color: 'var(--text-2, #cbd5e1)', marginBottom: '5px',
  };

  return (
    <div role="dialog" aria-modal="true" aria-label="Set your password"
         style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '20px', background: 'var(--bg, #0f172a)', overflowY: 'auto' }}>
      <form onSubmit={submit} style={{ width: 'min(440px, 100%)', padding: '32px 28px', background: 'var(--surface-1, #1e293b)', border: '1px solid var(--border, #334155)', borderRadius: '16px', display: 'flex', flexDirection: 'column', gap: '16px', boxShadow: '0 20px 40px rgba(0,0,0,0.35)' }}>
        <div>
          <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 700, color: 'var(--text-1, #ffffff)' }}>Set your secure password</h2>
          <p style={{ margin: '6px 0 0', fontSize: '13px', color: 'var(--text-3, #94a3b8)', lineHeight: 1.6 }}>
            {user.fullName || user.username}, this account is on the starter password (<strong>admin</strong>).
            Please choose a secure password (at least 8 characters) to complete first sign-in.
          </p>
        </div>

        {error && (
          <div style={{ padding: '10px 12px', borderRadius: '8px', background: 'rgba(239,68,68,0.12)', border: '1px solid rgba(239,68,68,0.35)', color: '#ef4444', fontSize: '13px' }}>
            {error}
          </div>
        )}

        <div>
          <label style={label} htmlFor="fpr-current">Current password (admin)</label>
          <input id="fpr-current" style={field} type="password" autoComplete="current-password" autoFocus
                 value={current} onChange={e => setCurrent(e.target.value)} />
        </div>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label style={label} htmlFor="fpr-next">New password</label>
            <span style={{ fontSize: '11px', color: isLongEnough ? '#22c55e' : (next.length > 0 ? '#f59e0b' : 'var(--text-3, #94a3b8)'), fontWeight: 600 }}>
              {next.length}/8 chars {isLongEnough ? '✓' : ''}
            </span>
          </div>
          <input id="fpr-next" style={{ ...field, borderColor: next.length > 0 ? (isLongEnough ? '#22c55e' : '#f59e0b') : 'var(--border, #334155)' }}
                 type="password" autoComplete="new-password"
                 value={next} onChange={e => setNext(e.target.value)} />
        </div>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <label style={label} htmlFor="fpr-confirm">Confirm new password</label>
            {confirm.length > 0 && (
              <span style={{ fontSize: '11px', color: isMatching ? '#22c55e' : '#ef4444', fontWeight: 600 }}>
                {isMatching ? 'Passwords match ✓' : 'Passwords do not match ✗'}
              </span>
            )}
          </div>
          <input id="fpr-confirm" style={{ ...field, borderColor: confirm.length > 0 ? (isMatching ? '#22c55e' : '#ef4444') : 'var(--border, #334155)' }}
                 type="password" autoComplete="new-password"
                 value={confirm} onChange={e => setConfirm(e.target.value)} />
        </div>

        {/* Live requirement indicators */}
        <div style={{ padding: '10px 12px', background: 'var(--surface-2, #152033)', borderRadius: '8px', border: '1px solid var(--border, #334155)', display: 'flex', flexDirection: 'column', gap: '6px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: isLongEnough ? '#22c55e' : 'var(--text-3, #94a3b8)' }}>
            <span>{isLongEnough ? '✓' : '•'}</span>
            <span>At least 8 characters long ({next.length}/8)</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '12px', color: isMatching ? '#22c55e' : (confirm.length > 0 ? '#ef4444' : 'var(--text-3, #94a3b8)') }}>
            <span>{isMatching ? '✓' : '•'}</span>
            <span>Passwords match exactly</span>
          </div>
        </div>

        <button type="submit" disabled={!canSubmit}
                style={{ minHeight: '44px', border: 'none', borderRadius: '8px', background: canSubmit ? '#2563eb' : 'var(--surface-2, #334155)', color: canSubmit ? '#ffffff' : 'var(--text-3, #94a3b8)', fontWeight: 600, fontSize: '14px', cursor: canSubmit ? 'pointer' : 'not-allowed', transition: 'all 0.2s ease' }}>
          {busy ? 'Saving…' : 'Save and continue'}
        </button>
        <button type="button" onClick={onLogout}
                style={{ minHeight: '40px', background: 'transparent', color: 'var(--text-3, #94a3b8)', border: '1px solid var(--border, #334155)', borderRadius: '8px', fontSize: '13px', cursor: 'pointer' }}>
          Sign out instead
        </button>
      </form>
    </div>
  );
}

function AppContent({ user, onLogin, onLogout, theme, toggleTheme }: { user: AuthUser | null; onLogin: (user: AuthUser) => void; onLogout: () => void; theme: 'dark' | 'light'; toggleTheme: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [commandOpen, setCommandOpen] = useState(false);
  const [commandQuery, setCommandQuery] = useState('');
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);

  // The management surfaces are for admins and owners; everyone else gets the assistant.
  const isAdmin = user?.role === 'ADMIN' || user?.role === 'OWNER';
  const activePath = location.pathname.startsWith('/incidents/') ? '/incidents' : location.pathname;
  const isChat = activePath === '/';
  const meta = PAGE_META[activePath] || PAGE_META['/incidents'];
  const workspace = user?.tenantName?.trim() || 'Primary workspace';
  const displayName = user?.fullName?.trim() || user?.username || '';
  const avatarLetter = (displayName || 'U').slice(0, 1).toUpperCase();

  const filteredCommands = useMemo(() => {
    const q = commandQuery.trim().toLowerCase();
    return NAV_ITEMS.filter(item => !q || item.label.toLowerCase().includes(q));
  }, [commandQuery]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setCommandOpen(true);
      }
      if (event.key === 'Escape') {
        setCommandOpen(false);
        setUserMenuOpen(false);
        setDrawerOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  // No keep-alive timer on purpose. authFetch() refreshes the access token when a request
  // needs one, so the session follows the person: work and it renews, walk away and it
  // lapses at the refresh token's own expiry. A clock-driven renewal did the opposite —
  // it kept an unattended tab signed in for as long as the browser stayed open.

  const go = (path: string) => {
    navigate(path);
    setCommandOpen(false);
    setCommandQuery('');
    setUserMenuOpen(false);
    setDrawerOpen(false);
  };

  return (
    <div className="shell">
      {isAdmin && (
        <>
          <aside className={`app-sidebar ${drawerOpen ? 'open' : ''}`} aria-label="Primary navigation">
            <div className="brand-block">
              <div className="brand-mark">I</div>
              <div className="brand-name">incident<span> warden</span></div>
            </div>

            {(['Operate', 'Manage'] as const).map(group => (
              <div className="sidebar-section" key={group}>
                <div className="sidebar-label">{group}</div>
                <nav className="sidebar-nav">
                  {NAV_ITEMS.filter(item => item.group === group).map(item => (
                    <button
                      key={item.path}
                      className={`sidebar-link ${activePath === item.path ? 'active' : ''}`}
                      onClick={() => go(item.path)}
                      aria-current={activePath === item.path ? 'page' : undefined}
                    >
                      {item.icon}<span>{item.label}</span>
                    </button>
                  ))}
                </nav>
              </div>
            ))}

            <div className="sidebar-spacer" />
            <div className="sidebar-footer">
              <div className="workspace-card">
                <div className="workspace-card-label">Workspace</div>
                <div className="workspace-card-name" title={user?.tenantId || DEFAULT_TENANT_ID}>{workspace}</div>
              </div>
            </div>
          </aside>
          {drawerOpen && <div className="sidebar-backdrop" onClick={() => setDrawerOpen(false)} aria-hidden="true" />}
        </>
      )}

      <div className="app-main">
        <header className="app-topbar">
          {isAdmin && (
            <button className="topbar-drawer" onClick={() => setDrawerOpen(value => !value)} aria-label="Toggle navigation" aria-expanded={drawerOpen}>
              <Menu size={17} />
            </button>
          )}
          {isAdmin ? (
            <button className="topbar-search" onClick={() => setCommandOpen(true)} aria-label="Open command palette">
              <Search size={14} /><span>Search or jump to…</span><kbd>⌘K</kbd>
            </button>
          ) : (
            <button className="topbar-brand" onClick={() => go('/')} aria-label="Incident Warden home">
              <span className="brand-mark">I</span><span className="brand-name">incident<span> warden</span></span>
            </button>
          )}
          <div className="topbar-spacer" />

          <button className="topbar-action" onClick={toggleTheme} title={`Switch to ${theme === 'dark' ? 'Light' : 'Dark'} mode`} aria-label="Toggle theme">
            {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
          </button>

          {isAdmin && <div className="live-indicator">LIVE</div>}

          {user ? (
            <div style={{ position: 'relative' }}>
              <button className="user-button" onClick={() => setUserMenuOpen(value => !value)} aria-expanded={userMenuOpen} title={displayName}>
                <span className="user-avatar">{avatarLetter}</span><ChevronDown size={13} />
              </button>
              {userMenuOpen && (
                <div className="user-menu">
                  <div className="user-menu-head">
                    <div className="user-menu-name">{displayName}</div>
                    <div className="user-menu-meta">{user.role} · @{user.username}</div>
                  </div>
                  <button className="user-menu-item" onClick={() => go('/account')}><User size={14} /> My account</button>
                  {isAdmin && <button className="user-menu-item" onClick={() => go('/settings/ai')}><Settings size={14} /> Settings</button>}
                  <button className="user-menu-item danger" onClick={onLogout}><LogOut size={14} /> Sign out</button>
                </div>
              )}
            </div>
          ) : (
            <button className="topbar-signin" onClick={() => go('/login')}>
              <LogIn size={14} /><span>Sign in</span>
            </button>
          )}
        </header>

        <main className={`page-area ${isChat ? 'page-area-chat' : ''}`}>
          {!isChat && (
            <div className="page-header">
              <div><h1>{meta.title}</h1><p>{meta.subtitle}</p></div>
            </div>
          )}
          {(activePath === '/incidents' || activePath === '/hitl' || activePath === '/tools') && <WorkflowRail active={activePath === '/incidents' ? 0 : activePath === '/hitl' ? 2 : 3} />}
          <div className={`page-content ${isChat ? 'page-content-chat' : ''}`}>
            <Routes>
              <Route path="/" element={<ChatPage user={user} onLogin={onLogin} />} />
              {/* Admin-only, and a non-admin URL guess lands on the assistant rather than an
                  empty shell that looks broken. */}
              {isAdmin ? (
                <>
                  <Route path="/incidents" element={<IncidentManagementPage />} />
                  <Route path="/incidents/:id" element={<IncidentManagementPage />} />
                  <Route path="/hitl" element={<HitlPage />} />
                  <Route path="/tools" element={<ToolsPage />} />
                  <Route path="/sops" element={<SopPage />} />
                  <Route path="/teams" element={<TeamsPage />} />
                  <Route path="/settings/ai" element={<AiConfigPage />} />
                </>
              ) : null}
              {user && <Route path="/account" element={<AccountPage onLogout={onLogout} />} />}
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
          </div>
        </main>
      </div>

      {commandOpen && (
        <div className="command-backdrop" role="dialog" aria-modal="true" aria-label="Command palette" onMouseDown={event => { if (event.currentTarget === event.target) setCommandOpen(false); }}>
          <div className="command-dialog">
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '0 14px' }}><Command size={15} color="var(--text-3)" /><input autoFocus className="command-input" value={commandQuery} onChange={event => setCommandQuery(event.target.value)} placeholder="Jump to a workspace…" /><button className="user-menu-item" style={{ width: 'auto' }} onClick={() => setCommandOpen(false)} aria-label="Close command palette"><X size={15} /></button></div>
            <div className="command-list">
              {filteredCommands.map(item => <button key={item.path} className="command-item" onClick={() => go(item.path)}>{item.icon}<span>{item.label}</span></button>)}
              {!filteredCommands.length && <div className="command-empty">No matching destinations.</div>}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

const App: React.FC = () => {
  const [user, setUser] = useState<AuthUser | null>(getStoredUser());
  const [theme, setTheme] = useState<'dark' | 'light'>(() => (localStorage.getItem('mcp_theme') as 'dark' | 'light') || 'dark');
  const handleLogout = () => {
    try {
      window.dispatchEvent(new CustomEvent('mcp:logout'));
      sessionStorage.removeItem('iw_chat_history');
      sessionStorage.removeItem('iw_active_session_id');
    } catch {}
    logoutUser();
    setUser(null);
  };

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('mcp_theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setTheme(prev => (prev === 'dark' ? 'light' : 'dark'));
  };

  useEffect(() => {
    const handleAuthExpired = () => setUser(null);
    window.addEventListener('mcp:auth-expired', handleAuthExpired);
    return () => window.removeEventListener('mcp:auth-expired', handleAuthExpired);
  }, []);

  return (
    <BrowserRouter>
      {user?.mustChangePassword ? (
        <ForcePasswordReset user={user} onDone={setUser} onLogout={handleLogout} />
      ) : (
        <Routes>
          <Route path="/login" element={<LoginRoute onLogin={setUser} />} />
          <Route path="*" element={<AppContent user={user} onLogin={setUser} onLogout={handleLogout} theme={theme} toggleTheme={toggleTheme} />} />
        </Routes>
      )}
    </BrowserRouter>
  );
};

export default App;
