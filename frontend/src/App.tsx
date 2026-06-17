import React, { useState, useEffect } from 'react';
import './App.css';
import LoginPage from './pages/LoginPage';
import SopPage from './pages/SopPage';
import TeamsPage from './pages/TeamsPage';
import AiConfigPage from './pages/AiConfigPage';
import IncidentManagementPage from './pages/IncidentManagementPage';
import ToolsPage from './pages/ToolsPage';
import AccountPage from './pages/AccountPage';
import ChatbotWidget from './components/ChatbotWidget';
import { Settings, LogOut, User, ShieldAlert, Plus, Wrench, BookOpen, Users, ChevronDown } from 'lucide-react';
import { AuthUser, getStoredUser, clearAuth, refreshToken, isTokenExpiringSoon } from './services/api';

const DEFAULT_TENANT_ID = 'tenant-1';

const PAGE_TITLES: Record<string, string> = {
  incidents: 'INCIDENT DIRECTORY',
  sop:       'STANDARD OPERATING PROCEDURES',
  tools:     'REMEDIATION TOOLS & SCRIPTS',
  teams:     'SUPPORT TEAMS & MEMBERS',
  ai_config: 'AI CONFIGURATION',
  account:   'MY ACCOUNT',
};

const App: React.FC = () => {
  const [user, setUser]                   = useState<AuthUser | null>(getStoredUser());
  const [page, setPage]                   = useState<string>('incidents');
  const [now,  setNow]                    = useState(new Date());
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [userMenuOpen, setUserMenuOpen]   = useState(false);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const p = params.get('page');
    if (p && PAGE_TITLES[p]) setPage(p);
  }, []);

  useEffect(() => {
    const t = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(t);
  }, []);

  useEffect(() => {
    const handleAuthExpired = () => { console.warn('Auth expired'); setUser(null); };
    window.addEventListener('mcp:auth-expired', handleAuthExpired);
    return () => window.removeEventListener('mcp:auth-expired', handleAuthExpired);
  }, []);

  // Close user menu on outside click
  useEffect(() => {
    if (!userMenuOpen) return;
    const handler = (e: MouseEvent) => {
      const menu = document.getElementById('user-menu-root');
      if (menu && !menu.contains(e.target as Node)) setUserMenuOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [userMenuOpen]);

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
        if (!ok) { clearAuth(); setUser(null); }
      }
    }, 4 * 60 * 1000);

    return () => {
      events.forEach(e => window.removeEventListener(e, onActivity));
      clearInterval(interval);
    };
  }, [user]);

  if (!user) return <LoginPage onLogin={(u) => setUser(u)} />;

  const handleLogout = () => { clearAuth(); setUser(null); };

  const activeTenantId   = user.tenantId || DEFAULT_TENANT_ID;
  const activeWorkspace  = user.tenantName?.trim() || 'Primary Workspace';

  return (
    <div className="shell top-nav-layout">
      <header className="topbar-unified">
        <div className="topbar-left-brand">
          <div className="logo-text">
            <span className="brand-primary">INCIDENT</span>
            <span className="brand-secondary">.AI</span>
          </div>
          <span className="tenant-badge" title={`Workspace: ${activeTenantId}`}>{activeWorkspace}</span>
        </div>

        <nav className="topbar-nav">
          {[
            { key: 'incidents', icon: <ShieldAlert size={16}/>, label: 'Incidents' },
            { key: 'sop',       icon: <BookOpen  size={16}/>, label: 'SOPs' },
            { key: 'tools',     icon: <Wrench    size={16}/>, label: 'Tools' },
            { key: 'teams',     icon: <Users     size={16}/>, label: 'Teams' },
            { key: 'ai_config', icon: <Settings  size={16}/>, label: 'Config' },
          ].map(({ key, icon, label }) => (
            <button
              key={key}
              className={`topbar-nav-btn ${page === key ? 'active' : ''}`}
              onClick={() => setPage(key)}
            >
              {icon} {label}
            </button>
          ))}
        </nav>

        <div className="topbar-right-actions">
          <button
            className="btn-create-incident-top"
            onClick={() => { setPage('incidents'); setShowCreateModal(true); }}
          >
            <Plus size={14}/> New Incident
          </button>

          <div className="live-status-pill">
            <span className="live-status-dot" />LIVE
          </div>

          {/* User menu */}
          <div id="user-menu-root" style={{ position: 'relative' }}>
            <button
              id="user-profile-btn"
              onClick={() => setUserMenuOpen(o => !o)}
              style={{
                display: 'flex', alignItems: 'center', gap: '6px',
                background: userMenuOpen ? 'rgba(255,255,255,0.1)' : 'rgba(255,255,255,0.05)',
                border: '1px solid rgba(255,255,255,0.12)',
                color: '#cbd5e1', fontSize: '12px', fontWeight: 600,
                padding: '6px 12px', borderRadius: '6px',
                cursor: 'pointer', transition: 'all 0.2s',
                fontFamily: 'var(--sans)',
              }}
            >
              <User size={14}/> {user.username}
              <ChevronDown size={12} style={{ transition: 'transform 0.2s', transform: userMenuOpen ? 'rotate(180deg)' : 'none' }}/>
            </button>

            {userMenuOpen && (
              <div style={{
                position: 'absolute', top: 'calc(100% + 8px)', right: 0,
                background: '#1a1a1a', border: '1px solid rgba(255,255,255,0.1)',
                borderRadius: '10px', minWidth: '200px', overflow: 'hidden',
                boxShadow: '0 16px 40px rgba(0,0,0,0.5)',
                animation: 'ssSlideIn 0.15s ease',
                zIndex: 9999,
              }}>
                {/* Header */}
                <div style={{ padding: '14px 16px', borderBottom: '1px solid rgba(255,255,255,0.08)' }}>
                  <div style={{ fontSize: '13px', fontWeight: 700, color: '#f1f5f9' }}>{user.username}</div>
                  <div style={{ fontSize: '11px', color: '#64748b', marginTop: '2px' }}>{user.role} · {activeWorkspace}</div>
                </div>
                {/* Menu items */}
                {[
                  { label: 'My Account', icon: <User size={13}/>, action: () => { setPage('account'); setUserMenuOpen(false); } },
                  { label: 'Sign out',   icon: <LogOut size={13}/>, action: handleLogout, danger: true },
                ].map(item => (
                  <button
                    key={item.label}
                    onClick={item.action}
                    style={{
                      width: '100%', display: 'flex', alignItems: 'center', gap: '10px',
                      padding: '11px 16px', background: 'transparent', border: 'none',
                      color: (item as any).danger ? '#f87171' : '#94a3b8',
                      fontSize: '13px', fontWeight: 500, cursor: 'pointer',
                      transition: 'all 0.15s', fontFamily: 'var(--sans)', textAlign: 'left',
                    }}
                    onMouseEnter={e => { e.currentTarget.style.background = (item as any).danger ? 'rgba(239,68,68,0.08)' : 'rgba(255,255,255,0.05)'; e.currentTarget.style.color = (item as any).danger ? '#ef4444' : '#e2e8f0'; }}
                    onMouseLeave={e => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = (item as any).danger ? '#f87171' : '#94a3b8'; }}
                  >
                    {item.icon} {item.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </header>

      <main className="main-content-layout">
        <div className="content-page-header">
          <div>
            <h1 className="content-title">{PAGE_TITLES[page]}</h1>
            <p className="content-subtitle">System Time: {now.toUTCString().replace('GMT', 'UTC')}</p>
          </div>
        </div>

        <div className="content-view-wrap">
          {page === 'sop'      && <SopPage />}
          {page === 'teams'    && <TeamsPage />}
          {page === 'ai_config'&& <AiConfigPage />}
          {page === 'account'  && <AccountPage onLogout={handleLogout} />}
          {page === 'incidents'&& (
            <IncidentManagementPage
              showCreateModal={showCreateModal}
              setShowCreateModal={setShowCreateModal}
            />
          )}
          {page === 'tools' && <ToolsPage />}
        </div>
      </main>

      <ChatbotWidget tenantId={activeTenantId} />
    </div>
  );
};

export default App;
