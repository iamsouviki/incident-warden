import React, { useState, useEffect } from 'react';
import './App.css';
import OverviewPage from './pages/OverviewPage';
import HitlPage from './pages/HitlPage';
import SopPage from './pages/SopPage';
import AnalyticsPage from './pages/AnalyticsPage';
import AuditLogPage from './pages/AuditLogPage';
import LoginPage from './pages/LoginPage';
import { AuthUser, clearAuth, getStoredUser, authFetch } from './services/api';

const TENANT_ID = '00000000-0000-0000-0000-000000000001';

type Page = 'overview' | 'hitl' | 'sop' | 'analytics' | 'audit' | 'health';

const PAGE_TITLES: Record<Page, string> = {
  overview:  'OPERATIONS DASHBOARD',
  hitl:      'HITL APPROVAL QUEUE',
  sop:       'SOP LIBRARY',
  analytics: 'ANALYTICS',
  audit:     'AUDIT LOG',
  health:    'MCP TOOL HEALTH',
};

const App: React.FC = () => {
  const [user, setUser]     = useState<AuthUser | null>(() => getStoredUser());
  const [page, setPage]     = useState<Page>('overview');
  const [now, setNow]       = useState(new Date());
  const [hitlCount, setHitlCount] = useState(0);
  const [creating, setCreating]   = useState(false);

  // ── clock ──
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  // ── HITL badge poll ──
  useEffect(() => {
    if (!user) return;
    const poll = async () => {
      try {
        const r = await authFetch(`/api/v1/hitl/pending?tenantId=${TENANT_ID}`);
        if (r.ok) { const d = await r.json(); setHitlCount(d.count ?? 0); }
      } catch {}
    };
    poll();
    const t = setInterval(poll, 15000);
    return () => clearInterval(t);
  }, [user]);

  // ── Show login when no user ──
  if (!user) {
    return <LoginPage onLogin={(u) => setUser(u)} />;
  }

  const handleLogout = () => {
    clearAuth();
    setUser(null);
  };

  const handleCreateIncident = async () => {
    setCreating(true);
    try {
      const ticket = `TEST-${Date.now()}`;
      const severities = ['P1', 'P2', 'P2', 'P3'];
      const titles = [
        'Database connection pool exhausted on order-svc',
        'Memory leak detected on api-gateway — heap at 94%',
        'Redis OOM — eviction policy misconfiguration',
        'Disk usage > 80% on log-server prod-01',
        'K8s pod CrashLoopBackOff — auth-service',
      ];
      const sev   = severities[Math.floor(Math.random() * severities.length)];
      const title = titles[Math.floor(Math.random() * titles.length)];
      const r = await authFetch('/api/v1/incidents', {
        method: 'POST',
        body: JSON.stringify({
          title, severity: sev, sourceSystem: 'dashboard',
          tenantId: TENANT_ID, sourceTicketId: ticket, status: 'PENDING',
          description: `Automated test incident: ${title}`
        })
      });
      if (!r.ok) { const e = await r.json(); alert('Error: ' + (e.error || JSON.stringify(e))); }
    } catch {
      alert('Create failed');
    } finally {
      setCreating(false);
    }
  };

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
               onClick={() => setPage('overview')}>
            <span className="nav-icon">⬡</span> Dashboard
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Incidents</div>
          <div className={`nav-item ${page === 'overview' ? 'active' : ''}`}
               onClick={() => setPage('overview')}>
            <span className="nav-icon">◉</span> Live Incidents
          </div>
          <div className={`nav-item ${page === 'analytics' ? 'active' : ''}`}
               onClick={() => setPage('analytics')}>
            <span className="nav-icon">⤢</span> Analytics
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Approvals</div>
          <div className={`nav-item ${page === 'hitl' ? 'active' : ''}`}
               onClick={() => setPage('hitl')}>
            <span className="nav-icon">✋</span> Pending HITL
            {hitlCount > 0 && <span className="nav-badge amber">{hitlCount}</span>}
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">Knowledge</div>
          <div className={`nav-item ${page === 'sop' ? 'active' : ''}`}
               onClick={() => setPage('sop')}>
            <span className="nav-icon">⊞</span> SOP Library
          </div>
        </div>

        <div className="nav-section">
          <div className="nav-label">System</div>
          <div className={`nav-item ${page === 'health' ? 'active' : ''}`}
               onClick={() => setPage('health')}>
            <span className="nav-icon">⬡</span> MCP Tools
          </div>
          <div className={`nav-item ${page === 'audit' ? 'active' : ''}`}
               onClick={() => setPage('audit')}>
            <span className="nav-icon">⊟</span> Audit Log
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="tenant-chip">
            <div className="label">TENANT</div>
            <div className="name">Acme Corp</div>
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
            <button className="btn-create" onClick={handleCreateIncident} disabled={creating}>
              {creating ? '⏳' : '+ NEW INCIDENT'}
            </button>
            <button className="btn-logout" onClick={handleLogout} title="Sign out">
              ⎋ Logout
            </button>
          </div>
        </div>

        {/* Page content */}
        {page === 'overview'  && <OverviewPage   onNavigate={setPage as any} tenantId={TENANT_ID} />}
        {page === 'hitl'      && <HitlPage       tenantId={TENANT_ID} />}
        {page === 'sop'       && <SopPage        tenantId={TENANT_ID} />}
        {page === 'analytics' && <AnalyticsPage  />}
        {page === 'audit'     && <AuditLogPage   />}
        {page === 'health'    && <HealthPage />}
      </div>
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
