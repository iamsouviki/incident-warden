import React, { useState, useEffect } from 'react';
import { Users, Mail, Search, AlertCircle, Shield, UserCheck, UserPlus, UserMinus, RefreshCw, ChevronRight, Hash, Check, Edit2, X, Briefcase, Building } from 'lucide-react';
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

  useEffect(() => { fetchTeams(); }, []);

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
    setEditFullName(emp.fullName || emp.username);
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

  const totalMembers = teams.reduce((a, t) => a + (t.employees?.length || 0), 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', minHeight: 'calc(100vh - 150px)' }}>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '14px', flexShrink: 0 }}>
        {[
          { icon: <Shield size={20}/>, color: 'var(--accent)', label: 'Active Teams', value: teams.length, bg: 'var(--accent-dim)' },
          { icon: <Users size={20}/>, color: 'var(--purple)', label: 'Total Members', value: totalMembers, bg: 'var(--purple-dim)' },
          { icon: <UserCheck size={20}/>, color: 'var(--ok)', label: 'Roster Availability', value: '100% READY', bg: 'var(--ok-dim)' },
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

      {/* Responsive Split Workspace */}
      <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: '16px', flex: 1, minHeight: 0 }}>

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
              <div style={{ padding: '24px', textAlign: 'center', color: 'var(--text-3)', fontSize: '13px' }}>No teams found</div>
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
                      <div style={{ fontSize: '11px', color: 'var(--text-3)', marginTop: '1px' }}>
                        {team.employees?.length || 0} members
                      </div>
                    </div>
                    {isSelected && <ChevronRight size={14} style={{ color: tc.bg, flexShrink: 0 }} />}
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* RIGHT: Team detail */}
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
                    background: `linear-gradient(180deg, ${tc.glow}, transparent)`,
                    borderTop: `3px solid ${tc.bg}`,
                    flexShrink: 0,
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '14px', flexWrap: 'wrap' }}>
                      <div style={{
                        width: '48px', height: '48px', borderRadius: '12px',
                        background: tc.bg, color: 'white',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: '16px', fontWeight: 800,
                        boxShadow: `0 4px 16px ${tc.glow}`,
                      }}>
                        {selectedTeam.name.substring(0, 2).toUpperCase()}
                      </div>
                      <div style={{ flex: 1, minWidth: '180px' }}>
                        <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 800, color: 'var(--text-1)' }}>
                          {selectedTeam.name}
                        </h2>
                        <p style={{ margin: '3px 0 0', fontSize: '12.5px', color: 'var(--text-2)' }}>
                          {selectedTeam.description || 'No description provided.'}
                        </p>
                      </div>
                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '26px', fontWeight: 800, color: tc.bg, lineHeight: 1 }}>
                          {selectedTeam.employees?.length || 0}
                        </div>
                        <div style={{ fontSize: '10px', color: 'var(--text-3)', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '2px' }}>
                          Members
                        </div>
                      </div>
                    </div>
                    {/* Team meta */}
                    <div style={{ display: 'flex', gap: '16px', marginTop: '14px', flexWrap: 'wrap' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-3)' }}>
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
                      <UserPlus size={14} style={{ color: 'var(--accent)' }} /> Add or Provision Member
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '10px' }}>
                      <input
                        type="text"
                        placeholder="Username *"
                        value={newUsername}
                        onChange={e => { setNewUsername(e.target.value); setMemberError(null); }}
                        style={{ height: '36px', fontSize: '12.5px' }}
                      />
                      <input
                        type="text"
                        placeholder="Full real name (e.g. Jane Doe)"
                        value={newFullName}
                        onChange={e => setNewFullName(e.target.value)}
                        style={{ height: '36px', fontSize: '12.5px' }}
                      />
                      <input
                        type="email"
                        placeholder="Email address"
                        value={newEmail}
                        onChange={e => setNewEmail(e.target.value)}
                        style={{ height: '36px', fontSize: '12.5px' }}
                      />
                      <input
                        type="text"
                        placeholder="Role (e.g. Lead SRE)"
                        value={newRole}
                        onChange={e => setNewRole(e.target.value)}
                        style={{ height: '36px', fontSize: '12.5px' }}
                      />
                      <input
                        type="text"
                        placeholder="Department"
                        value={newDepartment}
                        onChange={e => setNewDepartment(e.target.value)}
                        style={{ height: '36px', fontSize: '12.5px' }}
                      />
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: '12px', flexWrap: 'wrap', gap: '10px' }}>
                      <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--text-3)' }}>
                        Admins can customize username, full real name, email, role, and department.
                      </p>
                      <button
                        onClick={addMember}
                        disabled={!newUsername.trim() || memberBusy === 'add'}
                        className="btn-primary"
                        style={{
                          height: '36px', padding: '0 18px', fontSize: '12.5px', fontWeight: 700,
                          display: 'flex', alignItems: 'center', gap: '6px',
                          cursor: !newUsername.trim() || memberBusy === 'add' ? 'not-allowed' : 'pointer',
                          opacity: !newUsername.trim() || memberBusy === 'add' ? 0.5 : 1
                        }}
                      >
                        <UserPlus size={13} /> {memberBusy === 'add' ? 'Adding…' : 'Add to team'}
                      </button>
                    </div>
                    {memberError && <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--crit)' }}>{memberError}</p>}
                    {memberNotice && (
                      <p style={{ margin: '8px 0 0', fontSize: '12px', color: 'var(--ok)', display: 'flex', alignItems: 'center', gap: '5px' }}>
                        <Check size={13} /> {memberNotice}
                      </p>
                    )}
                  </div>
                )}

                {/* Member Roster Cards */}
                {(!selectedTeam.employees || selectedTeam.employees.length === 0) ? (
                  <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-3)', fontSize: '13px' }}>
                    <Users size={32} style={{ opacity: 0.3, marginBottom: '10px' }} />
                    <p>No members assigned to this team</p>
                  </div>
                ) : (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(290px, 1fr))', gap: '12px' }}>
                    {selectedTeam.employees.map((emp) => {
                      const displayName = emp.fullName?.trim() || emp.username;
                      const ac = getAvatarColor(displayName);
                      return (
                        <div
                          key={emp.id}
                          style={{
                            display: 'flex', alignItems: 'center', gap: '12px',
                            padding: '14px 16px',
                            background: 'var(--surface-2)',
                            border: '1px solid var(--border)',
                            borderRadius: '10px',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          <div style={{
                            width: '42px', height: '42px', borderRadius: '50%',
                            background: ac.bg, color: 'white',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontSize: '13px', fontWeight: 800, flexShrink: 0,
                            boxShadow: `0 2px 10px ${ac.glow}`,
                          }}>
                            {displayName.substring(0, 2).toUpperCase()}
                          </div>
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text-1)', display: 'flex', alignItems: 'center', gap: '6px' }}>
                              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{displayName}</span>
                            </div>
                            <div style={{ fontSize: '11px', color: 'var(--text-3)', marginTop: '2px', fontFamily: 'var(--font-mono)' }}>
                              @{emp.username}
                            </div>
                            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '4px', flexWrap: 'wrap' }}>
                              <span style={{ fontSize: '10.5px', color: ac.bg, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.3px', background: ac.glow, padding: '1px 6px', borderRadius: '4px' }}>
                                {emp.role || 'Engineer'}
                              </span>
                              {emp.department && (
                                <span style={{ fontSize: '10px', color: 'var(--text-3)' }}>
                                  · {emp.department}
                                </span>
                              )}
                            </div>
                          </div>
                          <div style={{ display: 'flex', gap: '4px', flexShrink: 0 }}>
                            {isAdmin && (
                              <button
                                onClick={() => openEditModal(emp)}
                                title={`Edit ${displayName}`}
                                style={{
                                  width: '32px', height: '32px', borderRadius: '6px',
                                  border: '1px solid var(--border)',
                                  background: 'var(--surface-1)',
                                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                                  color: 'var(--text-2)', cursor: 'pointer',
                                }}
                              >
                                <Edit2 size={13} />
                              </button>
                            )}
                            {emp.email && (
                              <a
                                href={`mailto:${emp.email}`}
                                title={`Email ${displayName}`}
                                style={{
                                  width: '32px', height: '32px', borderRadius: '6px',
                                  border: '1px solid var(--border)',
                                  background: 'var(--surface-1)',
                                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                                  color: 'var(--text-2)', textDecoration: 'none',
                                }}
                              >
                                <Mail size={13} />
                              </a>
                            )}
                            {isAdmin && (
                              <button
                                onClick={() => removeMember(emp.username)}
                                disabled={memberBusy === emp.username}
                                title={`Remove ${displayName} from ${selectedTeam.name}`}
                                style={{
                                  width: '32px', height: '32px', borderRadius: '6px',
                                  border: '1px solid var(--border)',
                                  background: 'var(--surface-1)',
                                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                                  color: 'var(--crit)', cursor: 'pointer',
                                }}
                              >
                                <UserMinus size={13} />
                              </button>
                            )}
                          </div>
                        </div>
                      );
                    })}
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
