import React, { useState, useEffect } from 'react';
import './App.css';
import OverviewPage from './pages/OverviewPage';
import HitlPage from './pages/HitlPage';
import SopPage from './pages/SopPage';
import ToolsPage from './pages/ToolsPage';
import AnalyticsPage from './pages/AnalyticsPage';
import AuditLogPage from './pages/AuditLogPage';
import LoginPage from './pages/LoginPage';
import KnowledgeBasePage from './pages/KnowledgeBasePage';
import ChatbotWidget from './components/ChatbotWidget';
import SettingsPage from './pages/SettingsPage';
import { AuthUser, clearAuth, getStoredUser, getTokenExpiry, authFetch, refreshToken, isTokenExpiringSoon } from './services/api';

const DEFAULT_TENANT_ID = '00000000-0000-0000-0000-000000000001';

type Page = 'overview' | 'hitl' | 'sop' | 'kb' | 'analytics' | 'audit' | 'health' | 'tools' | 'settings';

const PAGE_TITLES: Record<Page, string> = {
  overview:  'OPERATIONS DASHBOARD',
  hitl:      'HITL APPROVAL QUEUE',
  sop:       'SOP LIBRARY',
  kb:        'RESOLVED INCIDENTS',
  analytics: 'ANALYTICS',
  audit:     'AUDIT LOG',
  health:    'MCP TOOL HEALTH',
  tools:     'MCP TOOLS',
  settings:  'SETTINGS',
};

const App: React.FC = () => {
  const [user, setUser]     = useState<AuthUser | null>(() => {
    const stored = getStoredUser();
    if (!stored) return null;
    // If the stored token is already expired, discard it immediately so the app
    // never enters the authenticated view with a dead JWT (prevents a burst of
    // 401 errors on every useEffect that fires before the mcp:auth-expired
    // listener can attach).
    const exp = getTokenExpiry();
    if (exp !== null && exp < Date.now()) {
      clearAuth();
      return null;
    }
    return stored;
  });
  const [page, setPage]     = useState<Page>(() => {
    const path = window.location.pathname.replace(/^\//, '') as Page;
    return path in PAGE_TITLES ? path : 'overview';
  });
  const [now, setNow]       = useState(new Date());
  const [hitlCount, setHitlCount] = useState(0);

  /** Navigate to a page and push a clean URL path (no hash) for bookmarkable links. */
  const navigate = (p: Page) => {
    setPage(p);
    window.history.pushState(null, '', '/' + p);
  };

  // ── clock ──
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  // ── Sync page state with browser back / forward buttons ──
  useEffect(() => {
    const handler = () => {
      const path = window.location.pathname.replace(/^\//, '') as Page;
      if (path in PAGE_TITLES) setPage(path);
    };
    window.addEventListener('popstate', handler);
    return () => window.removeEventListener('popstate', handler);
  }, []);

  // ── Handle expired/rejected token without a hard page reload ──
  // authFetch dispatches this event instead of calling window.location.reload(),
  // which prevents the React component tree from being torn down mid-render
  // (the main cause of the white-page flash on ToolsPage / HITL polling).
  useEffect(() => {
    const handler = () => { clearAuth(); setUser(null); };
    window.addEventListener('mcp:auth-expired', handler);
    return () => window.removeEventListener('mcp:auth-expired', handler);
  }, []);

  // ── HITL badge poll ──
  useEffect(() => {
    if (!user) return;
    const poll = async () => {
      try {
        const r = await authFetch(`/api/v1/hitl/pending?tenantId=${user.tenantId || DEFAULT_TENANT_ID}`);
        if (r.ok) { const d = await r.json(); setHitlCount(d.count ?? 0); }
      } catch {}
    };
    poll();
    const t = setInterval(poll, 15000);
    return () => clearInterval(t);
  }, [user]);

  // ── Activity-based token auto-refresh ──────────────────────────────────────
  // Tracks the last user interaction. Every 4 minutes, if the user was active
  // in the past 10 minutes and the token expires within 30 minutes, silently
  // exchange it for a fresh one — so active sessions never expire mid-use.
  useEffect(() => {
    if (!user) return;
    let lastActivity = Date.now();

    const onActivity = () => { lastActivity = Date.now(); };
    const events = ['click', 'keydown', 'scroll', 'mousemove', 'touchstart'] as const;
    events.forEach(e => window.addEventListener(e, onActivity, { passive: true }));

    const interval = setInterval(async () => {
      const recentlyActive = Date.now() - lastActivity < 10 * 60 * 1000; // active in last 10 min
      if (recentlyActive && isTokenExpiringSoon(30 * 60 * 1000)) {        // expires within 30 min
        const ok = await refreshToken();
        if (!ok) {
          // Token already expired and could not be refreshed — force re-login
          clearAuth();
          setUser(null);
        }
      }
    }, 4 * 60 * 1000); // check every 4 minutes

    return () => {
      events.forEach(e => window.removeEventListener(e, onActivity));
      clearInterval(interval);
    };
  }, [user]);

  // ── Show login when no user ──
  if (!user) {
    return <LoginPage onLogin={(u) => setUser(u)} />;
  }

  const handleLogout = () => {
    clearAuth();
    setUser(null);
  };

  const activeTenantId = user.tenantId || DEFAULT_TENANT_ID;
  const activeWorkspaceName = user.tenantName?.trim()
    || (activeTenantId === DEFAULT_TENANT_ID ? 'Primary Workspace' : 'Workspace');

  return (
    <div className="shell">
      {/* ── Sidebar ───────────────────────────────────────────────── */}
      <nav className="sidebar">
        <div className="logo">
          <div className="logo-tag">MCP Automation</div>
          <div className="logo-name">INCIDENT.AI</div>
        </div>

        <div className="nav-section">
          <div className={`nav-item ${page === 'overview' ? 'active' : ''}`}
               onClick={() => navigate('overview')}>
            <span className="nav-icon">⬡</span> Dashboard
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Incidents</div>
          <div className={`nav-item ${page === 'overview' ? 'active' : ''}`}
               onClick={() => navigate('overview')}>
            <span className="nav-icon">◉</span> Live Incidents
          </div>
          <div className={`nav-item ${page === 'analytics' ? 'active' : ''}`}
               onClick={() => navigate('analytics')}>
            <span className="nav-icon">⤢</span> Analytics
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Approvals</div>
          <div className={`nav-item ${page === 'hitl' ? 'active' : ''}`}
               onClick={() => navigate('hitl')}>
            <span className="nav-icon">✋</span> Pending HITL
            {hitlCount > 0 && <span className="nav-badge amber">{hitlCount}</span>}
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Knowledge</div>
          <div className={`nav-item ${page === 'sop' ? 'active' : ''}`}
               onClick={() => navigate('sop')}>
            <span className="nav-icon">⊞</span> SOP Library
          </div>
          <div className={`nav-item ${page === 'kb' ? 'active' : ''}`}
               onClick={() => navigate('kb')}>
            <span className="nav-icon">⧗</span> Resolved Incidents
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">System</div>
          <div className={`nav-item ${page === 'tools' ? 'active' : ''}`}
               onClick={() => navigate('tools')}>
            <span className="nav-icon">⬡</span> MCP Tools
          </div>
          <div className={`nav-item ${page === 'audit' ? 'active' : ''}`}
               onClick={() => navigate('audit')}>
            <span className="nav-icon">⊟</span> Audit Log
          </div>
          <div className={`nav-item ${page === 'settings' ? 'active' : ''}`}
               onClick={() => navigate('settings')}>
            <span className="nav-icon">⚙</span> Settings
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="tenant-chip" title={activeTenantId}>
            <div className="label">WORKSPACE</div>
            <div className="name">{activeWorkspaceName}</div>
          </div>
          <div className="status-dot"></div>
        </div>
      </nav>

      {/* ── Main ──────────────────────────────────────────────────── */}
      <div className="main">
        {/* Topbar */}
        <div className="topbar">
          <div>
            <div className="page-title">{PAGE_TITLES[page] ?? 'DASHBOARD'}</div>
            <div className="page-subtitle">
              Last updated: {now.toUTCString().replace('GMT', 'UTC')}
            </div>
          </div>
          <div className="topbar-right">
            <div className="pill">
              <span className="live-dot"></span>LIVE
            </div>
            <div className="pill user-pill" title={`Role: ${user.role}`}>
              👤 {user.username}
            </div>
            <button className="btn-logout" onClick={handleLogout} title="Sign out">
              ⎋ Logout
            </button>
          </div>
        </div>

        {/* Page content */}
        {page === 'overview'  && <OverviewPage   onNavigate={navigate as any} tenantId={activeTenantId} />}
        {page === 'hitl'      && <HitlPage       tenantId={activeTenantId} />}
        {page === 'sop'       && <SopPage        tenantId={activeTenantId} />}
        {page === 'kb'        && <KnowledgeBasePage tenantId={activeTenantId} />}
        {page === 'analytics' && <AnalyticsPage  tenantId={activeTenantId} />}
        {page === 'audit'     && <AuditLogPage   tenantId={activeTenantId} />}
        {page === 'health'    && <HealthPage />}
        {page === 'tools'     && <ToolsPage tenantId={activeTenantId} />}
        {page === 'settings'  && <SettingsPage tenantId={activeTenantId} />}
      </div>
      {/* ── Floating Chatbot ── */}
      <ChatbotWidget tenantId={activeTenantId} />
    </div>
  );
};

const HealthPage: React.FC = () => (
  <div className="content">
    <div className="health-grid" style={{ marginTop: 24 }}>
      {[
        { name: 'restart_k8s_pods',        val: 'Error rate: 0.2% · Last: 2 min ago',  st: 'ok',   icon: '⬡' },
        { name: 'scale_db_pool',            val: 'Error rate: 0.0% · Last: 14 min ago', st: 'ok',   icon: '⬡' },
        { name: 'update_servicenow_inc',    val: 'Error rate: 48.0% · Last: 3 min ago', st: 'warn', icon: '⚠' },
        { name: 'get_prometheus_alerts',    val: 'Error rate: 0.5% · Last: 1 min ago',  st: 'ok',   icon: '⬡' },
        { name: 'flush_redis_cache',        val: 'Error rate: 0.0% · Last: 8 min ago',  st: 'ok',   icon: '⬡' },
        { name: 'rollback_helm_release',    val: 'Error rate: 0.0% · Last: 2 hrs ago',  st: 'ok',   icon: '⬡' },
        { name: 'trigger_pagerduty_alert',  val: 'Error rate: 0.0% · Last: 1 hr ago',   st: 'ok',   icon: '⬡' },
        { name: 'update_jira_ticket',       val: 'No response · Timed out',             st: 'err',  icon: '✗' },
        { name: 'send_slack_notification',  val: 'Error rate: 1.2% · Last: 5 min ago',  st: 'ok',   icon: '⬡' },
      ].map(h => (
        <div key={h.name} className="health-item">
          <div className={`health-icon ${h.st}`}>{h.icon}</div>
          <div>
            <div className="health-name">{h.name}</div>
            <div className="health-val">{h.val}</div>
          </div>
          <div className={`health-status-tag ${h.st}`}>
            {h.st === 'ok' ? 'CLOSED' : h.st === 'warn' ? 'HALF-OPEN' : 'OPEN'}
          </div>
        </div>
      ))}
    </div>
  </div>
);

export default App;
