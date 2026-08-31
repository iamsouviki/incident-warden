import React, { useState, useEffect } from 'react';
import { Users, Mail, Search, AlertCircle, Shield, UserCheck, UserPlus, UserMinus, RefreshCw, ChevronRight, Hash, Check, Edit2, X, Plus } from 'lucide-react';
import { authFetch, getStoredUser } from '../services/api';

interface EmployeeItem {
  id: string;
  username: string;
  fullName?: string;
  email: string;
  role?: string;
  department?: string;
}

interface TeamItem {
  id: string;
  name: string;
  description: string;
  email: string | null;
  employees: EmployeeItem[];
}

interface UserAccount {
  id: string;
  username: string;
  fullName: string;
  email: string;
  role: string;
  department: string;
  enabled: boolean;
}

const TEAM_COLORS = [
  { bg: '#3b82f6', glow: 'rgba(59,130,246,0.15)' },
  { bg: '#10b981', glow: 'rgba(16,185,129,0.15)' },
  { bg: '#8b5cf6', glow: 'rgba(139,92,246,0.15)' },
  { bg: '#f59e0b', glow: 'rgba(245,158,11,0.15)' },
  { bg: '#06b6d4', glow: 'rgba(6,182,212,0.15)' },
  { bg: '#ec4899', glow: 'rgba(236,72,153,0.15)' },
];

const getAvatarColor = (str: string) => {
  let h = 0;
  for (let i = 0; i < (str || '').length; i++) h = str.charCodeAt(i) + ((h << 5) - h);
  return TEAM_COLORS[Math.abs(h) % TEAM_COLORS.length];
};

const TeamsPage: React.FC = () => {
  const [teams, setTeams] = useState<TeamItem[]>([]);
  const [usersList, setUsersList] = useState<UserAccount[]>([]);
  const [activeTab, setActiveTab] = useState<'teams' | 'users'>('teams');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTeam, setSelectedTeam] = useState<TeamItem | null>(null);

  const isAdmin = getStoredUser()?.role === 'ADMIN';
  const [teamEmail, setTeamEmail] = useState('');
  const [savingEmail, setSavingEmail] = useState(false);
  const [emailError, setEmailError] = useState<string | null>(null);
  const [emailSaved, setEmailSaved] = useState(false);

  // Add Member State
  const [newUsername, setNewUsername] = useState('');
  const [newFullName, setNewFullName] = useState('');
  const [newEmail, setNewEmail] = useState('');
  const [newRole, setNewRole] = useState('');
  const [newDepartment, setNewDepartment] = useState('');
  const [memberBusy, setMemberBusy] = useState<string | null>(null);
  const [memberError, setMemberError] = useState<string | null>(null);
  const [memberNotice, setMemberNotice] = useState<string | null>(null);

  // Create Team Modal State
  const [showTeamModal, setShowTeamModal] = useState(false);
  const [teamName, setTeamName] = useState('');
  const [teamDesc, setTeamDesc] = useState('');
  const [teamDistEmail, setTeamDistEmail] = useState('');
  const [creatingTeam, setCreatingTeam] = useState(false);
  const [teamModalError, setTeamModalError] = useState<string | null>(null);

  // Create User Account Modal State
  const [showUserModal, setShowUserModal] = useState(false);
  const [userAccUsername, setUserAccUsername] = useState('');
  const [userAccFullName, setUserAccFullName] = useState('');
  const [userAccEmail, setUserAccEmail] = useState('');
  const [userAccRole, setUserAccRole] = useState<'VIEWER' | 'ANALYST' | 'ADMIN'>('ANALYST');
  const [userAccDepartment, setUserAccDepartment] = useState('');
  const [creatingUser, setCreatingUser] = useState(false);
  // Empty until the server answers. It used to open on a hard-coded literal, which was a
  // guess at a credential this page does not own: the backend derives it from
  // MCP_DEFAULT_PASSWORD or generates one per boot, so any literal here is either stale or
  // — worse — correct and printed on a screen before anyone has created a user.
  const [defaultPassword, setDefaultPassword] = useState('');
  const [userModalError, setUserModalError] = useState<string | null>(null);
  const [userModalSuccess, setUserModalSuccess] = useState<string | null>(null);

  // Edit Member Modal State
  const [editingMember, setEditingMember] = useState<EmployeeItem | null>(null);
  const [editFullName, setEditFullName] = useState('');
  const [editEmail, setEditEmail] = useState('');
  const [editRole, setEditRole] = useState('');
  const [editDepartment, setEditDepartment] = useState('');
  const [savingEdit, setSavingEdit] = useState(false);
  const [editError, setEditError] = useState<string | null>(null);

  useEffect(() => {
    setTeamEmail(selectedTeam?.email || '');
    setEmailError(null);
    setEmailSaved(false);
    setNewUsername(''); 
    setNewFullName('');
    setNewEmail('');
    setNewRole('');
    setNewDepartment('');
    setMemberError(null); 
    setMemberNotice(null);
  }, [selectedTeam?.id]);

  const fetchUsers = async () => {
    try {
      const res = await authFetch('/api/auth/users');
      if (res.ok) {
        const data = await res.json();
        if (Array.isArray(data)) setUsersList(data);
      }
    } catch (e) {
      console.error('Failed to load workspace users', e);
    }
  };

  const fetchTeams = async () => {
    setLoading(true); setError(null);
    try {
      const res = await authFetch('/api/v1/teams');
      if (!res.ok) throw new Error('Failed to load teams');
      const data = await res.json();
      if (Array.isArray(data)) {
        setTeams(data);
        setSelectedTeam(current =>
          (current && data.find((t: TeamItem) => t.id === current.id)) || data[0] || null);
      }
    } catch (e: any) {
      setError(e.message || 'Error loading teams');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTeams();
    if (isAdmin) fetchUsers();
  }, [isAdmin]);

  const handleCreateTeam = async () => {
    if (!teamName.trim()) {
      setTeamModalError('Team name is required.');
      return;
    }
    setCreatingTeam(true);
    setTeamModalError(null);
    try {
      const res = await authFetch('/api/v1/teams', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: teamName.trim(),
          description: teamDesc.trim(),
          email: teamDistEmail.trim()
        })
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || `Create failed (${res.status})`);
      }
      setTeamName('');
      setTeamDesc('');
      setTeamDistEmail('');
      setShowTeamModal(false);
      await fetchTeams();
    } catch (e: any) {
      setTeamModalError(e.message || 'Failed to create team');
    } finally {
      setCreatingTeam(false);
    }
  };

  const handleCreateUser = async () => {
    if (!userAccUsername.trim()) {
      setUserModalError('Username is required.');
      return;
    }
    if (!userAccEmail.trim()) {
      setUserModalError('An email address is required, or this user can never be notified about an incident.');
      return;
    }
    setCreatingUser(true);
    setUserModalError(null);
    setUserModalSuccess(null);
    try {
      // No password sent: the server owns the default, and reports it back. The UI used to
      // hardcode its own copy, which is how it came to advertise a password the seeded
      // admin account did not have.
      const res = await authFetch('/api/auth/users', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username: userAccUsername.trim(),
          fullName: userAccFullName.trim(),
          email: userAccEmail.trim(),
          role: userAccRole,
          department: userAccDepartment.trim()
        })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || `Failed to create user (${res.status})`);
      }
      const issued: string = data.defaultPassword || '';
      setDefaultPassword(issued);
      setUserAccUsername('');
      setUserAccFullName('');
      setUserAccEmail('');
      setUserAccDepartment('');
      setUserModalSuccess(issued
        ? `User created. Their password is ${issued} — they should change it on first sign-in.`
        : 'User created. The server did not return a password, so reset it from the API before handing the account over.');
      await fetchUsers();
      // Left open deliberately. This banner is the only place the issued password is ever
      // shown; it used to auto-close after 1.5s, which is not long enough to read a
      // credential, let alone pass it on. The admin closes it when they have it, and the
      // cleared fields above mean the next account can be created without reopening.
    } catch (e: any) {
      setUserModalError(e.message || 'Failed to create user');
    } finally {
      setCreatingUser(false);
    }
  };

  const saveTeamEmail = async () => {
    if (!selectedTeam) return;
    setSavingEmail(true); setEmailError(null); setEmailSaved(false);
    try {
      const res = await authFetch(`/api/v1/teams/${selectedTeam.id}/email`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email: teamEmail.trim() })
      });
      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || `Save failed (${res.status})`);
      }
      const saved = teamEmail.trim() || null;
      setSelectedTeam({ ...selectedTeam, email: saved });
      setTeams(list => list.map(t => (t.id === selectedTeam.id ? { ...t, email: saved } : t)));
      setEmailSaved(true);
    } catch (e: any) {
      setEmailError(e.message || 'Could not save the team mail id');
    } finally {
      setSavingEmail(false);
    }
  };

  const addMember = async () => {
    const username = newUsername.trim();
    if (!selectedTeam || !username) return;
    setMemberBusy('add'); setMemberError(null); setMemberNotice(null);
    try {
      const res = await authFetch(`/api/v1/teams/${selectedTeam.id}/members`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username,
          fullName: newFullName.trim(),
          email: newEmail.trim(),
          role: newRole.trim(),
          department: newDepartment.trim()
        })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Could not add the member (${res.status})`);
      setNewUsername(''); 
      setNewFullName('');
      setNewEmail('');
      setNewRole('');
      setNewDepartment('');
      setMemberNotice(data.movedFrom ? `Moved ${data.fullName || data.username} here from ${data.movedFrom}.`
                                     : `Added ${data.fullName || data.username} (${data.email}).`);
      await fetchTeams();
    } catch (e: any) {
      setMemberError(e.message || 'Could not add the member');
    } finally {
      setMemberBusy(null);
    }
  };

  const openEditModal = (emp: EmployeeItem) => {
    setEditingMember(emp);
    setEditFullName(emp.fullName || '');
    setEditEmail(emp.email || '');
    setEditRole(emp.role || '');
    setEditDepartment(emp.department || '');
    setEditError(null);
  };

  const handleUpdateMember = async () => {
    if (!selectedTeam || !editingMember) return;
    setSavingEdit(true);
    setEditError(null);
    try {
      const res = await authFetch(`/api/v1/teams/${selectedTeam.id}/members/${encodeURIComponent(editingMember.username)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          fullName: editFullName.trim(),
          email: editEmail.trim(),
          role: editRole.trim(),
          department: editDepartment.trim()
        })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Update failed (${res.status})`);
      setEditingMember(null);
      await fetchTeams();
    } catch (e: any) {
      setEditError(e.message || 'Could not update member');
    } finally {
      setSavingEdit(false);
    }
  };

  const removeMember = async (username: string) => {
    if (!selectedTeam) return;
    if (!window.confirm(`Remove ${username} from ${selectedTeam.name}?`)) return;
    setMemberBusy(username); setMemberError(null); setMemberNotice(null);
    try {
      const res = await authFetch(`/api/v1/teams/${selectedTeam.id}/members/${encodeURIComponent(username)}`, { method: 'DELETE' });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || `Could not remove the member (${res.status})`);
      setMemberNotice(`Removed ${username}.`);
      await fetchTeams();
    } catch (e: any) {
      setMemberError(e.message || 'Could not remove the member');
    } finally {
      setMemberBusy(null);
    }
  };

  const filteredTeams = searchQuery
    ? teams.filter(t =>
        t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        t.description?.toLowerCase().includes(searchQuery.toLowerCase()) ||
        t.employees?.some(e =>
          e.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
          e.fullName?.toLowerCase().includes(searchQuery.toLowerCase()) ||
          e.email?.toLowerCase().includes(searchQuery.toLowerCase())
        )
      )
    : teams;

  const filteredUsers = searchQuery
    ? usersList.filter(u =>
        u.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.fullName.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.email.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.role.toLowerCase().includes(searchQuery.toLowerCase()) ||
        u.department.toLowerCase().includes(searchQuery.toLowerCase())
      )
    : usersList;

  const totalMembers = teams.reduce((a, t) => a + (t.employees?.length || 0), 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', minHeight: 'calc(100vh - 150px)' }}>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '14px', flexShrink: 0 }}>
        {[
          { icon: <Shield size={20}/>, color: 'var(--accent)', label: 'Active Teams', value: teams.length, bg: 'var(--accent-dim)' },
          { icon: <Users size={20}/>, color: 'var(--purple)', label: 'Total Team Members', value: totalMembers, bg: 'var(--purple-dim)' },
          { icon: <UserCheck size={20}/>, color: 'var(--ok)', label: 'Workspace Users', value: usersList.length > 0 ? usersList.length : '1 Admin', bg: 'var(--ok-dim)' },
        ].map((kpi, i) => (
          <div key={i} className="card" style={{ padding: '18px 20px', display: 'flex', alignItems: 'center', gap: '14px', position: 'relative', overflow: 'hidden' }}>
            <div style={{ padding: '10px', borderRadius: '10px', background: kpi.bg, color: kpi.color, flexShrink: 0 }}>
              {kpi.icon}
            </div>
            <div>
              <div style={{ fontSize: '22px', fontWeight: 800, color: 'var(--text-1)', lineHeight: 1.1 }}>{kpi.value}</div>
              <div style={{ fontSize: '11px', color: 'var(--text-3)', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '3px' }}>{kpi.label}</div>
            </div>
          </div>
        ))}
      </div>

      {/* Action Header & Tabs */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button
            onClick={() => setActiveTab('teams')}
            style={{
              padding: '6px 14px', borderRadius: '6px', border: '1px solid var(--border)',
              background: activeTab === 'teams' ? 'var(--accent-dim)' : 'var(--surface-1)',
              color: activeTab === 'teams' ? 'var(--accent)' : 'var(--text-2)',
              fontSize: '12.5px', fontWeight: 700, cursor: 'pointer'
            }}
          >
            Teams ({teams.length})
          </button>
          {isAdmin && (
            <button
              onClick={() => setActiveTab('users')}
              style={{
                padding: '6px 14px', borderRadius: '6px', border: '1px solid var(--border)',
                background: activeTab === 'users' ? 'var(--accent-dim)' : 'var(--surface-1)',
                color: activeTab === 'users' ? 'var(--accent)' : 'var(--text-2)',
                fontSize: '12.5px', fontWeight: 700, cursor: 'pointer'
              }}
            >
              Workspace Users ({usersList.length})
            </button>
          )}
        </div>

        {isAdmin && (
          <div style={{ display: 'flex', gap: '8px' }}>
            <button
              className="btn-secondary"
              onClick={() => setShowTeamModal(true)}
              style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '6px 14px', fontSize: '12px', fontWeight: 700 }}
            >
              <Plus size={14} /> Add Team
            </button>
            <button
              className="btn-primary"
              onClick={() => setShowUserModal(true)}
              style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '6px 14px', fontSize: '12px', fontWeight: 700 }}
            >
              <UserPlus size={14} /> Create User Account
            </button>
          </div>
        )}
      </div>

      {activeTab === 'teams' ? (
        /* Responsive Split Workspace */
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr)) 2.2fr', gap: '16px', flex: 1, minHeight: 0 }}>

          {/* LEFT: Team list */}
          <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
            <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--border)', flexShrink: 0 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
                <h2 style={{ margin: 0, fontSize: '12px', fontWeight: 800, color: 'var(--text-1)', textTransform: 'uppercase', letterSpacing: '0.6px' }}>
                  Teams ({filteredTeams.length})
                </h2>
                <button
                  onClick={fetchTeams}
                  style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-3)', display: 'flex', padding: '4px' }}
                  title="Refresh"
                >
                  <RefreshCw size={13} className={loading ? 'spin' : ''} />
                </button>
              </div>
              <div style={{ position: 'relative' }}>
                <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
                <input
                  type="text"
                  placeholder="Search teams or people…"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  style={{ paddingLeft: '32px', height: '34px', fontSize: '12.5px', width: '100%' }}
                />
              </div>
            </div>

            <div style={{ flex: 1, overflowY: 'auto', padding: '8px' }}>
              {loading ? (
                <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-3)', fontSize: '13px' }}>
                  Loading teams…
                </div>
              ) : error ? (
                <div style={{ padding: '20px', textAlign: 'center' }}>
                  <AlertCircle size={24} style={{ color: 'var(--crit)', marginBottom: '8px' }} />
                  <p style={{ color: 'var(--crit)', fontSize: '13px' }}>{error}</p>
                </div>
              ) : filteredTeams.length === 0 ? (
                <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-3)', fontSize: '13px' }}>
                  No teams configured. Click "+ Add Team" to create one.
                </div>
              ) : (
                filteredTeams.map(team => {
                  const tc = getAvatarColor(team.name);
                  const isSelected = selectedTeam?.id === team.id;
                  return (
                    <div
                      key={team.id}
                      onClick={() => setSelectedTeam(team)}
                      style={{
                        padding: '12px',
                        borderRadius: '8px',
                        cursor: 'pointer',
                        marginBottom: '4px',
                        background: isSelected ? 'var(--surface-2)' : 'transparent',
                        border: `1px solid ${isSelected ? tc.bg + '60' : 'transparent'}`,
                        borderLeft: `3px solid ${isSelected ? tc.bg : 'transparent'}`,
                        transition: 'all 0.15s ease',
                        display: 'flex',
                        alignItems: 'center',
                        gap: '10px',
                      }}
                    >
                      <div style={{
                        width: '32px', height: '32px', borderRadius: '8px',
                        background: tc.bg, color: 'white', display: 'flex',
                        alignItems: 'center', justifyContent: 'center',
                        fontSize: '11px', fontWeight: 800, flexShrink: 0,
                      }}>
                        {team.name.substring(0, 2).toUpperCase()}
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: '13px', fontWeight: 700, color: isSelected ? 'var(--text-1)' : 'var(--text-2)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                          {team.name}
                        </div>
                        <div style={{ fontSize: '11px', color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: '4px', marginTop: '2px' }}>
                          <Users size={10} />
                          <span>{team.employees?.length || 0} members</span>
                          {team.email && <span style={{ color: 'var(--accent)' }}>· email</span>}
                        </div>
                      </div>
                      <ChevronRight size={13} style={{ color: isSelected ? tc.bg : 'var(--text-3)', flexShrink: 0 }} />
                    </div>
                  );
                })
              )}
            </div>
          </div>

          {/* RIGHT: Team Detail */}
          <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
            {selectedTeam ? (
              <>
                {/* Team header banner */}
                {(() => {
                  const tc = getAvatarColor(selectedTeam.name);
                  return (
                    <div style={{
                      padding: '20px 24px',
                      borderBottom: '1px solid var(--border)',
                      background: `linear-gradient(135deg, ${tc.glow}, transparent)`,
                      display: 'flex',
                      alignItems: 'flex-start',
                      justifyContent: 'space-between',
                      flexWrap: 'wrap',
                      gap: '12px',
                    }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
                        <div style={{
                          width: '44px', height: '44px', borderRadius: '12px',
                          background: tc.bg, color: 'white', display: 'flex',
                          alignItems: 'center', justifyContent: 'center',
                          fontSize: '16px', fontWeight: 800, boxShadow: `0 4px 12px ${tc.glow}`,
                        }}>
                          {selectedTeam.name.substring(0, 2).toUpperCase()}
                        </div>
                        <div>
                          <h1 style={{ margin: 0, fontSize: '18px', fontWeight: 800, color: 'var(--text-1)' }}>
                            {selectedTeam.name}
                          </h1>
                          <p style={{ margin: '3px 0 0', fontSize: '12.5px', color: 'var(--text-2)' }}>
                            {selectedTeam.description || 'No description provided.'}
                          </p>
                        </div>
                      </div>
                      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '4px' }}>
                        <div style={{ fontSize: '11px', color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: '4px' }}>
                          <Hash size={12} />
                          <span style={{ fontFamily: 'var(--font-mono)' }}>{selectedTeam.id.substring(0, 8)}</span>
                        </div>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px' }}>
                          <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: 'var(--ok)', boxShadow: '0 0 6px var(--ok)', display: 'inline-block' }} />
                          <span style={{ color: 'var(--ok)', fontWeight: 600 }}>Active Escalation Target</span>
                        </div>
                      </div>
                    </div>
                  );
                })()}

                {/* Members workspace */}
                <div style={{ flex: 1, overflowY: 'auto', padding: '20px 24px' }}>

                  {/* Group distribution email */}
                  <div style={{ marginBottom: '24px', padding: '16px', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '10px' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                      <Mail size={14} style={{ color: 'var(--accent)' }} />
                      <span style={{ fontSize: '11px', fontWeight: 800, color: 'var(--text-1)', textTransform: 'uppercase', letterSpacing: '0.6px' }}>
                        Team Distribution Address
                      </span>
                    </div>
                    <p style={{ margin: '0 0 12px', fontSize: '12px', color: 'var(--text-2)' }}>
                      Copied on automated action reports and notifications for incidents assigned to this team.
                    </p>
                    {isAdmin ? (
                      <div style={{ display: 'flex', gap: '10px', alignItems: 'center', flexWrap: 'wrap' }}>
                        <input
                          type="email"
                          placeholder="e.g. it-ops@company.com"
                          value={teamEmail}
                          onChange={e => { setTeamEmail(e.target.value); setEmailSaved(false); }}
                          style={{ flex: 1, minWidth: '200px', height: '36px', fontSize: '13px' }}
                        />
                        <button
                          onClick={saveTeamEmail}
                          disabled={savingEmail || teamEmail.trim() === (selectedTeam.email || '')}
                          className="btn-primary"
                          style={{
                            height: '36px', padding: '0 18px', fontSize: '12.5px', fontWeight: 700,
                            cursor: savingEmail || teamEmail.trim() === (selectedTeam.email || '') ? 'not-allowed' : 'pointer',
                            opacity: savingEmail || teamEmail.trim() === (selectedTeam.email || '') ? 0.5 : 1
                          }}
                        >
                          {savingEmail ? 'Saving…' : 'Save address'}
                        </button>
                      </div>
                    ) : (
                      <div style={{ fontSize: '13px', color: selectedTeam.email ? 'var(--text-1)' : 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>
                        {selectedTeam.email || 'Not set — an administrator can configure one.'}
                      </div>
                    )}
                    {emailError && <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--crit)' }}>{emailError}</p>}
                    {emailSaved && (
                      <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--ok)', display: 'flex', alignItems: 'center', gap: '5px' }}>
                        <Check size={13} /> Distribution address saved
                      </p>
                    )}
                  </div>

                  {/* Add member section */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
                    <div style={{ fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.8px' }}>
                      Team Members & Roles
                    </div>
                  </div>

                  {isAdmin && (
                    <div style={{ marginBottom: '20px', padding: '16px', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '10px' }}>
                      <div style={{ fontSize: '12.5px', fontWeight: 700, color: 'var(--text-1)', marginBottom: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <UserPlus size={14} style={{ color: 'var(--accent)' }} /> Add Member to {selectedTeam.name}
                      </div>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: '10px', marginBottom: '10px' }}>
                        <div>
                          <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                            Username *
                          </label>
                          <input
                            type="text"
                            placeholder="e.g. jdoe"
                            value={newUsername}
                            onChange={e => setNewUsername(e.target.value)}
                            style={{ height: '34px', fontSize: '12.5px', width: '100%' }}
                          />
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                            Real Name
                          </label>
                          <input
                            type="text"
                            placeholder="e.g. Jane Doe"
                            value={newFullName}
                            onChange={e => setNewFullName(e.target.value)}
                            style={{ height: '34px', fontSize: '12.5px', width: '100%' }}
                          />
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                            Email Address
                          </label>
                          <input
                            type="email"
                            placeholder="name@company.com"
                            value={newEmail}
                            onChange={e => setNewEmail(e.target.value)}
                            style={{ height: '34px', fontSize: '12.5px', width: '100%' }}
                          />
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                            Role
                          </label>
                          <input
                            type="text"
                            placeholder="e.g. Lead SRE"
                            value={newRole}
                            onChange={e => setNewRole(e.target.value)}
                            style={{ height: '34px', fontSize: '12.5px', width: '100%' }}
                          />
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                            Department
                          </label>
                          <input
                            type="text"
                            placeholder="e.g. Operations"
                            value={newDepartment}
                            onChange={e => setNewDepartment(e.target.value)}
                            style={{ height: '34px', fontSize: '12.5px', width: '100%' }}
                          />
                        </div>
                      </div>
                      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '10px' }}>
                        <button
                          onClick={addMember}
                          disabled={memberBusy === 'add' || !newUsername.trim()}
                          className="btn-primary"
                          style={{
                            height: '34px', padding: '0 16px', fontSize: '12.5px', fontWeight: 700,
                            display: 'flex', alignItems: 'center', gap: '6px',
                            cursor: memberBusy === 'add' || !newUsername.trim() ? 'not-allowed' : 'pointer',
                            opacity: memberBusy === 'add' || !newUsername.trim() ? 0.5 : 1
                          }}
                        >
                          <Plus size={13} /> {memberBusy === 'add' ? 'Adding…' : 'Add to Team'}
                        </button>
                      </div>
                      {memberError && <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--crit)' }}>{memberError}</p>}
                      {memberNotice && <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--ok)' }}>{memberNotice}</p>}
                    </div>
                  )}

                  {/* Members list */}
                  {selectedTeam.employees?.length === 0 ? (
                    <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-3)', background: 'var(--surface-2)', borderRadius: '8px', border: '1px dashed var(--border)' }}>
                      No members assigned to this team yet.
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
                      {selectedTeam.employees?.map(emp => (
                        <div
                          key={emp.id || emp.username}
                          style={{
                            padding: '12px 16px',
                            background: 'var(--surface-2)',
                            border: '1px solid var(--border)',
                            borderRadius: '8px',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'space-between',
                            gap: '12px',
                          }}
                        >
                          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', minWidth: 0 }}>
                            <div style={{
                              width: '36px', height: '36px', borderRadius: '50%',
                              background: 'var(--surface-3)', color: 'var(--accent)',
                              display: 'flex', alignItems: 'center', justifyContent: 'center',
                              fontSize: '12px', fontWeight: 800, flexShrink: 0,
                            }}>
                              {(emp.fullName || emp.username).substring(0, 2).toUpperCase()}
                            </div>
                            <div style={{ minWidth: 0 }}>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <span style={{ fontSize: '13.5px', fontWeight: 700, color: 'var(--text-1)' }}>
                                  {emp.fullName || emp.username}
                                </span>
                                <span style={{ fontSize: '11px', color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>
                                  @{emp.username}
                                </span>
                              </div>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', fontSize: '11.5px', color: 'var(--text-2)', marginTop: '2px' }}>
                                <span>{emp.role || 'Team Member'}</span>
                                {emp.department && <span>· {emp.department}</span>}
                                <span>· {emp.email}</span>
                              </div>
                            </div>
                          </div>

                          {isAdmin && (
                            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                              <button
                                onClick={() => openEditModal(emp)}
                                className="btn-secondary"
                                style={{ padding: '6px 10px', fontSize: '11.5px', display: 'flex', alignItems: 'center', gap: '4px' }}
                              >
                                <Edit2 size={12} /> Edit
                              </button>
                              <button
                                onClick={() => removeMember(emp.username)}
                                disabled={memberBusy === emp.username}
                                style={{
                                  padding: '6px 10px', fontSize: '11.5px', background: 'transparent',
                                  border: '1px solid rgba(239,68,68,0.3)', color: 'var(--crit)', borderRadius: '6px',
                                  cursor: 'pointer', display: 'flex', alignItems: 'center', gap: '4px'
                                }}
                              >
                                <UserMinus size={12} /> Remove
                              </button>
                            </div>
                          )}
                        </div>
                      ))}
                    </div>
                  )}

                </div>
              </>
            ) : (
              <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-3)', flexDirection: 'column', gap: '12px' }}>
                <Users size={48} style={{ opacity: 0.2 }} />
                <p style={{ fontSize: '14px' }}>Select a team to view its members</p>
              </div>
            )}
          </div>
        </div>
      ) : (
        /* Workspace Users Directory Table */
        <div className="card" style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '10px' }}>
            <div>
              <h2 style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-1)', margin: '0 0 4px' }}>
                Workspace User Accounts & Roles
              </h2>
              <p style={{ fontSize: '12px', color: 'var(--text-3)', margin: 0 }}>
                Manage login accounts, security roles, and profile assignments for this instance.
              </p>
            </div>
            <div style={{ position: 'relative', minWidth: '240px' }}>
              <Search size={13} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
              <input
                type="text"
                placeholder="Search accounts…"
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                style={{ paddingLeft: '32px', height: '34px', fontSize: '12.5px', width: '100%' }}
              />
            </div>
          </div>

          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', textAlign: 'left', minWidth: '600px' }}>
              <thead>
                <tr style={{ borderBottom: '1px solid var(--border)', color: 'var(--text-3)', fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.6px' }}>
                  <th style={{ padding: '10px 14px' }}>User</th>
                  <th style={{ padding: '10px 14px' }}>Email</th>
                  <th style={{ padding: '10px 14px' }}>Role</th>
                  <th style={{ padding: '10px 14px' }}>Department</th>
                  <th style={{ padding: '10px 14px' }}>Status</th>
                </tr>
              </thead>
              <tbody>
                {filteredUsers.map(u => (
                  <tr key={u.id || u.username} style={{ borderBottom: '1px solid var(--border)', fontSize: '13px' }}>
                    <td style={{ padding: '12px 14px' }}>
                      <div style={{ fontWeight: 700, color: 'var(--text-1)' }}>{u.fullName || u.username}</div>
                      <div style={{ fontSize: '11px', color: 'var(--text-3)', fontFamily: 'var(--font-mono)' }}>@{u.username}</div>
                    </td>
                    <td style={{ padding: '12px 14px', color: 'var(--text-2)' }}>{u.email || '—'}</td>
                    <td style={{ padding: '12px 14px' }}>
                      <span style={{
                        fontSize: '10.5px', fontWeight: 800, padding: '3px 8px', borderRadius: '4px',
                        background: u.role === 'ADMIN' ? 'var(--accent-dim)' : u.role === 'ANALYST' ? 'var(--purple-dim)' : 'var(--ok-dim)',
                        color: u.role === 'ADMIN' ? 'var(--accent)' : u.role === 'ANALYST' ? 'var(--purple)' : 'var(--ok)',
                      }}>
                        {u.role}
                      </span>
                    </td>
                    <td style={{ padding: '12px 14px', color: 'var(--text-2)' }}>{u.department || 'Operations'}</td>
                    <td style={{ padding: '12px 14px' }}>
                      <span style={{ fontSize: '11px', fontWeight: 600, color: u.enabled ? 'var(--ok)' : 'var(--crit)' }}>
                        {u.enabled ? 'Active' : 'Disabled'}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* ── CREATE TEAM MODAL ── */}
      {showTeamModal && (
        <div className="modal-backdrop" onClick={() => setShowTeamModal(false)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '480px' }}>
            <div className="modal-header">
              <h2 style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-1)' }}>Create New Team</h2>
              <button className="close-btn" onClick={() => setShowTeamModal(false)}><X size={18} /></button>
            </div>
            <div className="modal-form">
              <div className="form-field">
                <label>Team Name *</label>
                <input type="text" placeholder="e.g. Database Engineering" value={teamName} onChange={e => setTeamName(e.target.value)} required />
              </div>
              <div className="form-field">
                <label>Description</label>
                <input type="text" placeholder="e.g. Primary DB SRE & escalation coverage" value={teamDesc} onChange={e => setTeamDesc(e.target.value)} />
              </div>
              <div className="form-field">
                <label>Distribution Email</label>
                <input type="email" placeholder="e.g. db-sre@company.com" value={teamDistEmail} onChange={e => setTeamDistEmail(e.target.value)} />
              </div>
              {teamModalError && <div className="error-alert">{teamModalError}</div>}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button className="btn-secondary" onClick={() => setShowTeamModal(false)} style={{ padding: '8px 16px' }}>Cancel</button>
                <button className="btn-primary" onClick={handleCreateTeam} disabled={creatingTeam} style={{ padding: '8px 18px' }}>
                  {creatingTeam ? 'Creating…' : 'Create Team'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ── CREATE USER ACCOUNT MODAL ── */}
      {showUserModal && (
        <div className="modal-backdrop" onClick={() => setShowUserModal(false)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '480px' }}>
            <div className="modal-header">
              <h2 style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-1)' }}>Create New User Account</h2>
              <button className="close-btn" onClick={() => setShowUserModal(false)}><X size={18} /></button>
            </div>
            <div className="modal-form">
              <div className="form-field">
                <label>Username *</label>
                <input type="text" placeholder="e.g. sarah_connor" value={userAccUsername} onChange={e => setUserAccUsername(e.target.value)} required />
              </div>
              <div className="form-field">
                <label>Full Real Name</label>
                <input type="text" placeholder="e.g. Sarah Connor" value={userAccFullName} onChange={e => setUserAccFullName(e.target.value)} />
              </div>
              <div className="form-field">
                <label>Email Address *</label>
                <input type="email" placeholder="e.g. sconnor@company.com" value={userAccEmail} onChange={e => setUserAccEmail(e.target.value)} required />
                <span style={{ fontSize: '11px', color: 'var(--text-3)' }}>
                  Required: this is where incident and auto-remediation notices reach them.
                </span>
              </div>
              <div className="form-row">
                <div className="form-field">
                  <label>Assign Role *</label>
                  <select value={userAccRole} onChange={e => setUserAccRole(e.target.value as any)}>
                    <option value="VIEWER">Viewer (Read Only)</option>
                    <option value="ANALYST">Analyst (Propose & Review Plans)</option>
                    <option value="ADMIN">Admin (Full Administrative Access)</option>
                  </select>
                </div>
                <div className="form-field">
                  <label>Department</label>
                  <input type="text" placeholder="e.g. Security Operations" value={userAccDepartment} onChange={e => setUserAccDepartment(e.target.value)} />
                </div>
              </div>
              <div style={{ padding: '10px 12px', background: 'var(--surface-2)', border: '1px solid var(--border)', borderRadius: '6px', fontSize: '11.5px', color: 'var(--text-2)' }}>
                {defaultPassword
                  ? <><span>Initial default password will be: </span><code style={{ color: 'var(--accent)', fontWeight: 800 }}>{defaultPassword}</code></>
                  : <span>The server issues the initial password when the account is created, and it is shown here once. Copy it then — this is the only time it is displayed.</span>}
              </div>
              {userModalError && <div className="error-alert">{userModalError}</div>}
              {userModalSuccess && <div style={{ padding: '10px 14px', background: 'var(--ok-dim)', color: 'var(--ok)', border: '1px solid rgba(16,185,129,0.3)', borderRadius: '6px', fontSize: '12.5px', fontWeight: 600 }}>{userModalSuccess}</div>}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button className="btn-secondary" onClick={() => setShowUserModal(false)} style={{ padding: '8px 16px' }}>Cancel</button>
                <button className="btn-primary" onClick={handleCreateUser} disabled={creatingUser} style={{ padding: '8px 18px' }}>
                  {creatingUser ? 'Creating…' : 'Create User Account'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* Edit Member Modal */}
      {editingMember && (
        <div className="modal-backdrop" onClick={() => setEditingMember(null)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '480px' }}>
            <div className="modal-header">
              <h2 style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-1)' }}>Edit Team Member Details</h2>
              <button className="close-btn" onClick={() => setEditingMember(null)}><X size={18} /></button>
            </div>
            <div className="modal-form">
              <div className="form-field">
                <label>Username</label>
                <input type="text" value={editingMember.username} disabled style={{ opacity: 0.6 }} />
              </div>
              <div className="form-field">
                <label>Full Real Name</label>
                <input
                  type="text"
                  placeholder="e.g. Jane Doe"
                  value={editFullName}
                  onChange={e => setEditFullName(e.target.value)}
                />
              </div>
              <div className="form-field">
                <label>Email Address</label>
                <input
                  type="email"
                  placeholder="name@company.com"
                  value={editEmail}
                  onChange={e => setEditEmail(e.target.value)}
                />
              </div>
              <div className="form-row">
                <div className="form-field">
                  <label>Role</label>
                  <input
                    type="text"
                    placeholder="e.g. Senior SRE"
                    value={editRole}
                    onChange={e => setEditRole(e.target.value)}
                  />
                </div>
                <div className="form-field">
                  <label>Department</label>
                  <input
                    type="text"
                    placeholder="e.g. Cloud Ops"
                    value={editDepartment}
                    onChange={e => setEditDepartment(e.target.value)}
                  />
                </div>
              </div>
              {editError && <div className="error-alert">{editError}</div>}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '12px' }}>
                <button className="btn-secondary" onClick={() => setEditingMember(null)} style={{ padding: '8px 16px' }}>Cancel</button>
                <button className="btn-primary" onClick={handleUpdateMember} disabled={savingEdit} style={{ padding: '8px 18px' }}>
                  {savingEdit ? 'Saving…' : 'Save Changes'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

    </div>
  );
};

export default TeamsPage;
