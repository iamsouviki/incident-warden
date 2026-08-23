import React, { useEffect, useMemo, useState } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import {
  Activity,
  Bell,
  BookOpen,
  CheckCircle2,
  ChevronDown,
  Command,
  FileCode2,
  LogOut,
  Moon,
  Plus,
  Search,
  Settings,
  ShieldAlert,
  SlidersHorizontal,
  Sun,
  User,
  Users,
  X,
} from 'lucide-react';
import './AppShell.css';
import './EnterprisePages.css';
import LoginPage from './pages/LoginPage';
import SopPage from './pages/SopPage';
import TeamsPage from './pages/TeamsPage';
import AiConfigPage from './pages/AiConfigPage';
import IncidentManagementPage from './pages/IncidentManagementPage';
import ToolsPage from './pages/ToolsPage';
import HitlPage from './pages/HitlPage';
import AccountPage from './pages/AccountPage';
import AutonomyPage from './pages/AutonomyPage';
import ChatbotWidget from './components/ChatbotWidget';
import { AuthUser, clearAuth, getStoredUser } from './services/api';

const DEFAULT_TENANT_ID = 'tenant-1';

type NavItem = { path: string; label: string; icon: React.ReactNode; group: 'Operate' | 'Manage' };

const NAV_ITEMS: NavItem[] = [
  { path: '/autonomy', label: 'Autonomous ops', icon: <Activity size={16} />, group: 'Operate' },
  { path: '/incidents', label: 'Incidents', icon: <ShieldAlert size={16} />, group: 'Operate' },
  { path: '/hitl', label: 'HITL queue', icon: <CheckCircle2 size={16} />, group: 'Operate' },
  { path: '/tools', label: 'Tools & scripts', icon: <FileCode2 size={16} />, group: 'Operate' },
  { path: '/sops', label: 'SOP library', icon: <BookOpen size={16} />, group: 'Manage' },
  { path: '/teams', label: 'Teams', icon: <Users size={16} />, group: 'Manage' },
  { path: '/settings/ai', label: 'AI configuration', icon: <SlidersHorizontal size={16} />, group: 'Manage' },
];

const PAGE_META: Record<string, { title: string; subtitle: string }> = {
  '/autonomy': { title: 'Autonomous operations', subtitle: 'Observe the agent loop, policy gates, remediation, and verification in one place.' },
  '/incidents': { title: 'Incidents', subtitle: 'Monitor, triage, and resolve issues from every connected source.' },
  '/hitl': { title: 'HITL approval queue', subtitle: 'Review proposed actions before they affect production systems.' },
  '/tools': { title: 'Tools & scripts', subtitle: 'Manage safe remediation actions and execution history.' },
  '/sops': { title: 'SOP library', subtitle: 'Maintain the operational knowledge agents use for recommendations.' },
  '/teams': { title: 'Teams', subtitle: 'Manage ownership, escalation paths, and support coverage.' },
  '/settings/ai': { title: 'AI configuration', subtitle: 'Tune providers, confidence thresholds, and integration sync.' },
  '/account': { title: 'My account', subtitle: 'Manage your profile and session.' },
};

function WorkflowRail({ active }: { active: number }) {
  const steps = ['Intake', 'Understand', 'Decide', 'Act', 'Verify'];
  return <div className="workflow-rail" aria-label="Incident operations workflow">{steps.map((step, index) => <React.Fragment key={step}><div className={`workflow-step ${index === active ? 'active' : ''} ${index < active ? 'complete' : ''}`}><span>{index + 1}</span>{step}</div>{index < steps.length - 1 && <div className={`workflow-connector ${index < active ? 'complete' : ''}`} />}</React.Fragment>)}</div>;
}

function AppContent({ user, onLogout }: { user: AuthUser; onLogout: () => void }) {
  const location = useLocation();
  const navigate = useNavigate();
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [commandQuery, setCommandQuery] = useState('');
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  const [theme, setTheme] = useState<'dark' | 'light'>(() => (localStorage.getItem('mcp_theme') as 'dark' | 'light') || 'dark');

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('mcp_theme', theme);
  }, [theme]);

  const toggleTheme = () => {
    setTheme(prev => (prev === 'dark' ? 'light' : 'dark'));
  };

  const activePath = location.pathname.startsWith('/incidents/') ? '/incidents' : location.pathname;
  const meta = PAGE_META[activePath] || PAGE_META['/incidents'];
  const tenantId = user.tenantId || DEFAULT_TENANT_ID;
  const workspace = user.tenantName?.trim() || 'Primary workspace';
  const displayName = user.fullName?.trim() || user.username;
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
  };

  return (
    <div className="shell">
      <aside className="app-sidebar" aria-label="Primary navigation">
        <div className="brand-block">
          <div className="brand-mark">I</div>
          <div className="brand-name">incident<span>.ai</span></div>
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
            <div className="workspace-card-name" title={tenantId}>{workspace}</div>
          </div>
        </div>
      </aside>

      <div className="app-main">
        <header className="app-topbar">
          <button className="topbar-search" onClick={() => setCommandOpen(true)} aria-label="Open command palette">
            <Search size={14} /><span>Search or jump to…</span><kbd>⌘K</kbd>
          </button>
          <div className="topbar-spacer" />
          
          <button className="topbar-action" onClick={toggleTheme} title={`Switch to ${theme === 'dark' ? 'Light' : 'Dark'} mode`} aria-label="Toggle theme">
            {theme === 'dark' ? <Sun size={14} /> : <Moon size={14} />}
          </button>

          <div className="live-indicator">LIVE</div>
          <button className="topbar-action" onClick={() => go('/hitl')} aria-label="Open notifications">
            <Bell size={14} /><span>Alerts</span>
          </button>
          <button className="topbar-action primary" onClick={() => { go('/incidents'); setShowCreateModal(true); }}>
            <Plus size={14} /><span>New incident</span>
          </button>
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
                <button className="user-menu-item" onClick={() => go('/settings/ai')}><Settings size={14} /> Settings</button>
                <button className="user-menu-item danger" onClick={onLogout}><LogOut size={14} /> Sign out</button>
              </div>
            )}
          </div>
        </header>

        <main className="page-area">
          <div className="page-header">
            <div><h1>{meta.title}</h1><p>{meta.subtitle}</p></div>
          </div>
          {(activePath === '/incidents' || activePath === '/hitl' || activePath === '/tools') && <WorkflowRail active={activePath === '/incidents' ? 0 : activePath === '/hitl' ? 2 : 3} />}
          <div className="page-content">
            <Routes>
              <Route path="/autonomy" element={<AutonomyPage />} />
              <Route path="/incidents" element={<IncidentManagementPage showCreateModal={showCreateModal} setShowCreateModal={setShowCreateModal} />} />
              <Route path="/incidents/:id" element={<IncidentManagementPage showCreateModal={showCreateModal} setShowCreateModal={setShowCreateModal} />} />
              <Route path="/hitl" element={<HitlPage />} />
              <Route path="/tools" element={<ToolsPage />} />
              <Route path="/sops" element={<SopPage />} />
              <Route path="/teams" element={<TeamsPage />} />
              <Route path="/settings/ai" element={<AiConfigPage />} />
              <Route path="/account" element={<AccountPage onLogout={onLogout} />} />
              <Route path="*" element={<Navigate to="/incidents" replace />} />
            </Routes>
          </div>
        </main>
      </div>

      <ChatbotWidget tenantId={tenantId} />

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
  const handleLogout = () => { clearAuth(); setUser(null); };

  useEffect(() => {
    const handleAuthExpired = () => setUser(null);
    window.addEventListener('mcp:auth-expired', handleAuthExpired);
    return () => window.removeEventListener('mcp:auth-expired', handleAuthExpired);
  }, []);

  if (!user) return <LoginPage onLogin={setUser} />;
  return <BrowserRouter><AppContent user={user} onLogout={handleLogout} /></BrowserRouter>;
};

export default App;
