import React, { useState, useEffect } from 'react';
import './App.css';
import LoginPage from './pages/LoginPage';
import RagIngestPage from './pages/RagIngestPage';
import AiConfigPage from './pages/AiConfigPage';
import ChatbotWidget from './components/ChatbotWidget';
import { Database, Settings, LogOut, User } from 'lucide-react';
import { AuthUser, getToken, getStoredUser, clearAuth, refreshToken, isTokenExpiringSoon } from './services/api';

const DEFAULT_TENANT_ID = 'tenant-1';

const PAGE_TITLES: Record<string, string> = {
  sop: 'SOP INGEST & RAG KNOWLEDGE BASE',
  ai_config: 'AI CONFIGURATION'
};

const App: React.FC = () => {
  const [user, setUser] = useState<AuthUser | null>(getStoredUser());
  const [page, setPage] = useState<string>('sop');
  const [now, setNow]   = useState(new Date());

  // Keep topbar clock updated
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
          <div className="nav-label">Knowledge</div>
          <div className={`nav-item ${page === 'sop' ? 'active' : ''}`}
               onClick={() => setPage('sop')}>
            <span className="nav-icon"><Database size={18} /></span> SOP Ingest
          </div>
          
          <div className="nav-label" style={{ marginTop: '20px' }}>Settings</div>
          <div className={`nav-item ${page === 'ai_config' ? 'active' : ''}`}
               onClick={() => setPage('ai_config')}>
            <span className="nav-icon"><Settings size={18} /></span> AI Config
          </div>
        </div>

        <div className="sidebar-footer">
          <div className="nav-item text-muted" onClick={handleLogout}>
            <span className="nav-icon"><LogOut size={18} /></span> Log Out
          </div>
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
              <User size={14} style={{ marginRight: '6px' }} />
              {user.username}
            </div>
            <button className="btn-logout" onClick={handleLogout} title="Sign out">
              ⎋ Logout
            </button>
          </div>
        </div>

        {/* Page content */}
        {page === 'sop' && <RagIngestPage />}
        {page === 'ai_config' && <AiConfigPage />}
      </div>
      
      {/* ── Floating Chatbot ── */}
      <ChatbotWidget tenantId={activeTenantId} />
    </div>
  );
};

export default App;
