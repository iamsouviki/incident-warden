import React, { useState, useEffect } from 'react';
import { Users, Mail, Search, AlertCircle, Shield, UserCheck, RefreshCw, ChevronRight, Hash } from 'lucide-react';
import { authFetch } from '../services/api';

interface EmployeeItem {
  id: string;
  username: string;
  email: string;
}

interface TeamItem {
  id: string;
  name: string;
  description: string;
  employees: EmployeeItem[];
}

const TEAM_COLORS = [
  { bg: '#c8102e', glow: 'rgba(200,16,46,0.15)' },
  { bg: '#2563eb', glow: 'rgba(37,99,235,0.15)' },
  { bg: '#10b981', glow: 'rgba(16,185,129,0.15)' },
  { bg: '#8b5cf6', glow: 'rgba(139,92,246,0.15)' },
  { bg: '#f59e0b', glow: 'rgba(245,158,11,0.15)' },
  { bg: '#06b6d4', glow: 'rgba(6,182,212,0.15)' },
];

const getAvatarColor = (str: string) => {
  let h = 0;
  for (let i = 0; i < str.length; i++) h = str.charCodeAt(i) + ((h << 5) - h);
  return TEAM_COLORS[Math.abs(h) % TEAM_COLORS.length];
};

const TeamsPage: React.FC = () => {
  const [teams,       setTeams]       = useState<TeamItem[]>([]);
  const [loading,     setLoading]     = useState(true);
  const [error,       setError]       = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedTeam, setSelectedTeam] = useState<TeamItem | null>(null);

  const fetchTeams = async () => {
    setLoading(true); setError(null);
    try {
      const res = await authFetch('/api/v1/teams');
      if (!res.ok) throw new Error('Failed to load teams');
      const data = await res.json();
      if (Array.isArray(data)) {
        setTeams(data);
        if (!selectedTeam && data.length > 0) setSelectedTeam(data[0]);
      }
    } catch (e: any) {
      setError(e.message || 'Error loading teams');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchTeams(); }, []);

  const filteredTeams = searchQuery
    ? teams.filter(t =>
        t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
        t.description?.toLowerCase().includes(searchQuery.toLowerCase()) ||
        t.employees?.some(e =>
          e.username.toLowerCase().includes(searchQuery.toLowerCase()) ||
          e.email?.toLowerCase().includes(searchQuery.toLowerCase())
        )
      )
    : teams;

  const totalMembers = teams.reduce((a, t) => a + (t.employees?.length || 0), 0);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px', height: 'calc(100vh - 140px)' }}>

      {/* KPI row */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '14px', flexShrink: 0 }}>
        {[
          { icon: <Shield size={20}/>, color: '#c8102e', label: 'Active Teams',         value: teams.length,   bg: 'rgba(200,16,46,0.08)' },
          { icon: <Users  size={20}/>, color: '#2563eb', label: 'Total Members',        value: totalMembers,    bg: 'rgba(37,99,235,0.08)' },
          { icon: <UserCheck size={20}/>, color: '#10b981', label: 'All Teams On-Call', value: 'AVAILABLE',     bg: 'rgba(16,185,129,0.08)' },
        ].map((kpi, i) => (
          <div key={i} className="card" style={{ padding: '18px 20px', display: 'flex', alignItems: 'center', gap: '14px', position: 'relative', overflow: 'hidden' }}>
            <div style={{ padding: '10px', borderRadius: '10px', background: kpi.bg, color: kpi.color, flexShrink: 0 }}>
              {kpi.icon}
            </div>
            <div>
              <div style={{ fontSize: '22px', fontWeight: 900, color: 'var(--text)', lineHeight: 1.1 }}>{kpi.value}</div>
              <div style={{ fontSize: '11px', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '2px' }}>{kpi.label}</div>
            </div>
            <div style={{ position: 'absolute', right: '-10px', bottom: '-10px', color: kpi.color, opacity: 0.04, lineHeight: 1 }}>
              {React.cloneElement(kpi.icon, { size: 90 } as any)}
            </div>
          </div>
        ))}
      </div>

      {/* Split panel */}
      <div style={{ display: 'grid', gridTemplateColumns: '280px 1fr', gap: '16px', flex: 1, minHeight: 0 }}>

        {/* LEFT: Team list */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
          <div style={{ padding: '14px 16px', borderBottom: '1px solid var(--border)', flexShrink: 0 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
              <h2 style={{ margin: 0, fontSize: '13px', fontWeight: 800, color: 'var(--text)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
                Teams ({filteredTeams.length})
              </h2>
              <button
                onClick={fetchTeams}
                style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--text-muted)', display: 'flex', padding: '4px' }}
                title="Refresh"
              >
                <RefreshCw size={13} className={loading ? 'spin' : ''} />
              </button>
            </div>
            <div style={{ position: 'relative' }}>
              <Search size={12} style={{ position: 'absolute', left: '9px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
              <input
                type="text"
                placeholder="Search teams..."
                value={searchQuery}
                onChange={e => setSearchQuery(e.target.value)}
                style={{ paddingLeft: '28px', height: '32px', fontSize: '12px' }}
              />
            </div>
          </div>

          <div style={{ flex: 1, overflowY: 'auto', padding: '8px' }}>
            {loading ? (
              <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
                Loading teams...
              </div>
            ) : error ? (
              <div style={{ padding: '20px', textAlign: 'center' }}>
                <AlertCircle size={24} style={{ color: 'var(--red)', marginBottom: '8px' }} />
                <p style={{ color: 'var(--red)', fontSize: '13px' }}>{error}</p>
              </div>
            ) : filteredTeams.length === 0 ? (
              <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>No teams found</div>
            ) : (
              filteredTeams.map(team => {
                const tc = getAvatarColor(team.name);
                const isSelected = selectedTeam?.id === team.id;
                return (
                  <div
                    key={team.id}
                    onClick={() => setSelectedTeam(team)}
                    style={{
                      padding: '12px 12px',
                      borderRadius: '8px',
                      cursor: 'pointer',
                      marginBottom: '4px',
                      background: isSelected ? tc.glow : 'transparent',
                      border: `1px solid ${isSelected ? tc.bg + '40' : 'transparent'}`,
                      borderLeft: `3px solid ${isSelected ? tc.bg : 'transparent'}`,
                      transition: 'all 0.2s',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '10px',
                    }}
                    onMouseEnter={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'var(--surface2)'; }}
                    onMouseLeave={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'transparent'; }}
                  >
                    <div style={{
                      width: '32px', height: '32px', borderRadius: '8px',
                      background: tc.bg, color: 'white', display: 'flex',
                      alignItems: 'center', justifyContent: 'center',
                      fontSize: '11px', fontWeight: 800, flexShrink: 0,
                      letterSpacing: '-0.5px',
                    }}>
                      {team.name.substring(0, 2).toUpperCase()}
                    </div>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div style={{ fontSize: '13px', fontWeight: 700, color: isSelected ? tc.bg : 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {team.name}
                      </div>
                      <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '1px' }}>
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
                    background: tc.glow,
                    borderTop: `3px solid ${tc.bg}`,
                    flexShrink: 0,
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
                      <div style={{
                        width: '48px', height: '48px', borderRadius: '12px',
                        background: tc.bg, color: 'white',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                        fontSize: '16px', fontWeight: 900,
                        boxShadow: `0 4px 16px ${tc.glow}`,
                      }}>
                        {selectedTeam.name.substring(0, 2).toUpperCase()}
                      </div>
                      <div style={{ flex: 1 }}>
                        <h2 style={{ margin: 0, fontSize: '18px', fontWeight: 800, color: 'var(--text)' }}>
                          {selectedTeam.name}
                        </h2>
                        <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-dim)' }}>
                          {selectedTeam.description || 'No description provided.'}
                        </p>
                      </div>
                      <div style={{ textAlign: 'right' }}>
                        <div style={{ fontSize: '28px', fontWeight: 900, color: tc.bg, lineHeight: 1 }}>
                          {selectedTeam.employees?.length || 0}
                        </div>
                        <div style={{ fontSize: '10px', color: 'var(--text-muted)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '2px' }}>
                          Members
                        </div>
                      </div>
                    </div>
                    {/* Team meta */}
                    <div style={{ display: 'flex', gap: '16px', marginTop: '16px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px', color: 'var(--text-muted)' }}>
                        <Hash size={11} />
                        <span style={{ fontFamily: 'var(--mono)' }}>{selectedTeam.id.substring(0, 8)}</span>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '11px' }}>
                        <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: '#10b981', boxShadow: '0 0 6px #10b981', display: 'inline-block' }} />
                        <span style={{ color: '#10b981', fontWeight: 600 }}>On-call available</span>
                      </div>
                    </div>
                  </div>
                );
              })()}

              {/* Members list */}
              <div style={{ flex: 1, overflowY: 'auto', padding: '16px 24px' }}>
                <div style={{ fontSize: '11px', fontWeight: 800, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.8px', marginBottom: '12px' }}>
                  Team Members
                </div>

                {(!selectedTeam.employees || selectedTeam.employees.length === 0) ? (
                  <div style={{ padding: '40px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
                    <Users size={32} style={{ opacity: 0.3, marginBottom: '10px' }} />
                    <p>No members assigned to this team</p>
                  </div>
                ) : (
                  <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '10px' }}>
                    {selectedTeam.employees.map((emp, idx) => {
                      const ac = getAvatarColor(emp.username);
                      return (
                        <div
                          key={emp.id}
                          style={{
                            display: 'flex', alignItems: 'center', gap: '12px',
                            padding: '14px 16px',
                            background: 'var(--surface2)',
                            border: '1px solid var(--border)',
                            borderRadius: '10px',
                            transition: 'all 0.2s',
                          }}
                          onMouseEnter={e => { (e.currentTarget).style.borderColor = ac.bg + '60'; (e.currentTarget).style.background = ac.glow; }}
                          onMouseLeave={e => { (e.currentTarget).style.borderColor = 'var(--border)'; (e.currentTarget).style.background = 'var(--surface2)'; }}
                        >
                          <div style={{
                            width: '40px', height: '40px', borderRadius: '50%',
                            background: ac.bg, color: 'white',
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                            fontSize: '13px', fontWeight: 700, flexShrink: 0,
                            boxShadow: `0 2px 8px ${ac.glow}`,
                          }}>
                            {emp.username.substring(0, 2).toUpperCase()}
                          </div>
                          <div style={{ flex: 1, minWidth: 0 }}>
                            <div style={{ fontSize: '14px', fontWeight: 700, color: 'var(--text)' }}>
                              {emp.username}
                            </div>
                            {emp.email && (
                              <div style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '2px', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                                {emp.email}
                              </div>
                            )}
                            <div style={{ fontSize: '10px', color: ac.bg, fontWeight: 600, marginTop: '3px', textTransform: 'uppercase', letterSpacing: '0.4px' }}>
                              {idx === 0 ? 'Team Lead' : 'Engineer'}
                            </div>
                          </div>
                          {emp.email && (
                            <a
                              href={`mailto:${emp.email}`}
                              title={`Email ${emp.username}`}
                              style={{
                                width: '32px', height: '32px', borderRadius: '8px',
                                border: '1px solid var(--border)',
                                background: 'var(--surface)',
                                display: 'flex', alignItems: 'center', justifyContent: 'center',
                                color: 'var(--text-muted)', flexShrink: 0,
                                transition: 'all 0.2s',
                              }}
                              onMouseEnter={e => { (e.currentTarget).style.borderColor = ac.bg; (e.currentTarget).style.color = ac.bg; }}
                              onMouseLeave={e => { (e.currentTarget).style.borderColor = 'var(--border)'; (e.currentTarget).style.color = 'var(--text-muted)'; }}
                            >
                              <Mail size={13} />
                            </a>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </>
          ) : (
            <div style={{ flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-muted)', flexDirection: 'column', gap: '12px' }}>
              <Users size={48} style={{ opacity: 0.2 }} />
              <p style={{ fontSize: '14px' }}>Select a team to view its members</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
};

export default TeamsPage;
