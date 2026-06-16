import React, { useState, useEffect } from 'react';
import './App.css';
import LoginPage from './pages/LoginPage';
import SopPage from './pages/SopPage';
import TeamsPage from './pages/TeamsPage';
import AiConfigPage from './pages/AiConfigPage';
import IncidentManagementPage from './pages/IncidentManagementPage';
import ToolsPage from './pages/ToolsPage';
import ChatbotWidget from './components/ChatbotWidget';
import { Database, Settings, LogOut, User, ShieldAlert, Plus, Terminal, Wrench, BookOpen, Clock, Users } from 'lucide-react';
import { AuthUser, getStoredUser, clearAuth, refreshToken, isTokenExpiringSoon } from './services/api';

const DEFAULT_TENANT_ID = 'tenant-1';

const PAGE_TITLES: Record<string, string> = {
  incidents: 'INCIDENT DIRECTORY',
  sop: 'STANDARD OPERATING PROCEDURES (SOP)',
  tools: 'REMEDIATION TOOLS & SCRIPTS',
  teams: 'SUPPORT TEAMS & MEMBERS',
  ai_config: 'AI CONFIGURATION'
};

const App: React.FC = () => {
  const [user, setUser] = useState<AuthUser | null>(getStoredUser());
  const [page, setPage] = useState<string>('incidents');
  const [now, setNow]   = useState(new Date());
  const [showCreateModal, setShowCreateModal] = useState(false);

  // Parse query parameters on load
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const pageParam = params.get('page');
    if (pageParam && PAGE_TITLES[pageParam]) {
      setPage(pageParam);
    }
  }, []);

  // Keep clock updated
  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  // ── Auth Expiry Event Listener ──
  useEffect(() => {
    const handleAuthExpired = () => {
      console.warn("Auth expired event received. Logging out.");
      setUser(null);
    };
    window.addEventListener('mcp:auth-expired', handleAuthExpired);
    return () => window.removeEventListener('mcp:auth-expired', handleAuthExpired);
  }, []);

  // ── Token Refresh Loop ──
  useEffect(() => {
    if (!user) return;
    let lastActivity = Date.now();

    const onActivity = () => { lastActivity = Date.now(); };
    const events = ['click', 'keydown', 'scroll', 'mousemove', 'touchstart'] as const;
    events.forEach(e => window.addEventListener(e, onActivity, { passive: true }));

    const interval = setInterval(async () => {
      const recentlyActive = Date.now() - lastActivity < 10 * 60 * 1000;
      if (recentlyActive && isTokenExpiringSoon(30 * 60 * 1000)) {
        const ok = await refreshToken();
        if (!ok) {
          clearAuth();
          setUser(null);
        }
      }
    }, 4 * 60 * 1000);

    return () => {
      events.forEach(e => window.removeEventListener(e, onActivity));
      clearInterval(interval);
    };
  }, [user]);

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
    <div className="shell top-nav-layout">
      {/* ── ServiceNow & Michaels-themed Top Banner Header ────────────────── */}
      <header className="topbar-unified">
        <div className="topbar-left-brand">
          <div className="logo-text">
            <span className="brand-primary">INCIDENT</span>
            <span className="brand-secondary">.AI</span>
          </div>
          <span className="tenant-badge" title={`Workspace ID: ${activeTenantId}`}>{activeWorkspaceName}</span>
        </div>

        {/* Top Navigation Tabs */}
        <nav className="topbar-nav">
          <button 
            className={`topbar-nav-btn ${page === 'incidents' ? 'active' : ''}`}
            onClick={() => setPage('incidents')}
          >
            <ShieldAlert size={16} /> Incident
          </button>
          <button 
            className={`topbar-nav-btn ${page === 'sop' ? 'active' : ''}`}
            onClick={() => setPage('sop')}
          >
            <BookOpen size={16} /> SOPs
          </button>
          <button 
            className={`topbar-nav-btn ${page === 'tools' ? 'active' : ''}`}
            onClick={() => setPage('tools')}
          >
            <Wrench size={16} /> Tools
          </button>
          <button 
            className={`topbar-nav-btn ${page === 'teams' ? 'active' : ''}`}
            onClick={() => setPage('teams')}
          >
            <Users size={16} /> Teams
          </button>
          <button 
            className={`topbar-nav-btn ${page === 'ai_config' ? 'active' : ''}`}
            onClick={() => setPage('ai_config')}
          >
            <Settings size={16} /> Config
          </button>
        </nav>

        {/* Topbar Actions & Profile */}
        <div className="topbar-right-actions">
          <button 
            className="btn-create-incident-top"
            onClick={() => {
              setPage('incidents');
              setShowCreateModal(true);
            }}
          >
            <Plus size={16} /> New Incident
          </button>

          <div className="live-status-pill">
            <span className="live-status-dot"></span>LIVE
          </div>

          <div className="user-profile-pill" title={`Role: ${user.role}`}>
            <User size={14} style={{ marginRight: '6px' }} />
            {user.username}
          </div>

          <button className="btn-logout-unified" onClick={handleLogout} title="Log Out">
            <LogOut size={14} />
          </button>
        </div>
      </header>

      {/* ── Main Content Container ────────────────────────────────── */}
      <main className="main-content-layout">
        {/* Page title header */}
        <div className="content-page-header">
          <div>
            <h1 className="content-title">{PAGE_TITLES[page]}</h1>
            <p className="content-subtitle">System Time: {now.toUTCString().replace('GMT', 'UTC')}</p>
          </div>
        </div>

        {/* Page switcher */}
        <div className="content-view-wrap">
          {page === 'sop' && <SopPage />}
          {page === 'teams' && <TeamsPage />}
          {page === 'ai_config' && <AiConfigPage />}
          {page === 'incidents' && (
            <IncidentManagementPage
              showCreateModal={showCreateModal}
              setShowCreateModal={setShowCreateModal}
            />
          )}
          {page === 'tools' && <ToolsPage />}
        </div>
      </main>

      {/* ── Floating Chatbot (Only one floating widget now) ── */}
      <ChatbotWidget tenantId={activeTenantId} />
    </div>
  );
};

export default App;

