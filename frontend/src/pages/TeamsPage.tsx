import React, { useState, useEffect } from 'react';
import { Users, Mail, Search, AlertCircle, Shield, Award, UserCheck, CheckCircle } from 'lucide-react';
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

const TeamsPage: React.FC = () => {
  const [teams, setTeams] = useState<TeamItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');

  const fetchTeams = async () => {
    setLoading(true);
    setError(null);
    try {
      const res = await authFetch('/api/v1/teams');
      if (!res.ok) {
        throw new Error('Failed to load teams');
      }
      const data = await res.json();
      if (Array.isArray(data)) {
        setTeams(data);
      }
    } catch (e: any) {
      setError(e.message || 'Error loading teams directory');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchTeams();
  }, []);

  // Filter teams and members
  const filteredTeams = teams.filter(team => {
    const query = searchQuery.toLowerCase();
    const teamMatch = 
      team.name.toLowerCase().includes(query) ||
      team.description.toLowerCase().includes(query);
      
    const memberMatch = team.employees?.some(emp => 
      emp.username.toLowerCase().includes(query) ||
      (emp.email && emp.email.toLowerCase().includes(query))
    );

    return teamMatch || memberMatch;
  });

  const totalMembers = teams.reduce((acc, t) => acc + (t.employees?.length || 0), 0);

  // Helper for profile bubble colors
  const getColorForUser = (username: string) => {
    const colors = [
      '#3b82f6', '#10b981', '#8b5cf6', '#ec4899', '#f59e0b', '#06b6d4'
    ];
    let hash = 0;
    for (let i = 0; i < username.length; i++) {
      hash = username.charCodeAt(i) + ((hash << 5) - hash);
    }
    return colors[Math.abs(hash) % colors.length];
  };

  return (
    <div className="content" style={{ maxWidth: '100%', padding: '0', display: 'flex', flexDirection: 'column', gap: '20px' }}>
      
      {/* ── Visual Metrics Row ── */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '16px' }}>
        
        {/* Metric 1 */}
        <div className="card" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '20px', background: 'var(--surface)', position: 'relative', overflow: 'hidden' }}>
          <div style={{ padding: '14px', borderRadius: '12px', background: 'rgba(200, 16, 46, 0.08)', color: 'var(--michaels-red)' }}>
            <Shield size={24} />
          </div>
          <div>
            <div style={{ fontSize: '28px', fontWeight: 900, color: 'var(--text)', lineHeight: '1.2' }}>{teams.length}</div>
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: '600', textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '4px' }}>
              Active Resolution Teams
            </div>
          </div>
          <div style={{ position: 'absolute', right: '-15px', bottom: '-15px', opacity: 0.04, pointerEvents: 'none' }}>
            <Shield size={120} />
          </div>
        </div>

        {/* Metric 2 */}
        <div className="card" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '20px', background: 'var(--surface)', position: 'relative', overflow: 'hidden' }}>
          <div style={{ padding: '14px', borderRadius: '12px', background: 'rgba(37, 99, 235, 0.08)', color: 'var(--accent)' }}>
            <Users size={24} />
          </div>
          <div>
            <div style={{ fontSize: '28px', fontWeight: 900, color: 'var(--text)', lineHeight: '1.2' }}>{totalMembers}</div>
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: '600', textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '4px' }}>
              Support Engineers Enrolled
            </div>
          </div>
          <div style={{ position: 'absolute', right: '-15px', bottom: '-15px', opacity: 0.04, pointerEvents: 'none' }}>
            <Users size={120} />
          </div>
        </div>

        {/* Metric 3 */}
        <div className="card" style={{ padding: '24px', display: 'flex', alignItems: 'center', gap: '20px', background: 'var(--surface)', position: 'relative', overflow: 'hidden' }}>
          <div style={{ padding: '14px', borderRadius: '12px', background: 'rgba(16, 185, 129, 0.08)', color: 'var(--green)' }}>
            <UserCheck size={24} />
          </div>
          <div>
            <div style={{ fontSize: '28px', fontWeight: 900, color: 'var(--text)', lineHeight: '1.2' }}>Available</div>
            <div style={{ fontSize: '12px', color: 'var(--text-muted)', fontWeight: '600', textTransform: 'uppercase', letterSpacing: '0.5px', marginTop: '4px' }}>
              On-Call Support Status
            </div>
          </div>
          <div style={{ position: 'absolute', right: '-10px', top: '-10px', color: 'var(--green)', opacity: 0.1 }}>
            <CheckCircle size={80} />
          </div>
        </div>

      </div>

      {/* ── Search Bar Card ── */}
      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: '16px', padding: '16px 24px', background: 'var(--surface)' }}>
        <div style={{ position: 'relative', flex: 1 }}>
          <Search size={18} style={{ position: 'absolute', left: '12px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
          <input
            type="text"
            placeholder="Search teams by name, focus area, or support engineer username..."
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            style={{ paddingLeft: '40px', width: '100%', height: '42px', fontSize: '14px' }}
          />
        </div>
        <button className="btn-primary" onClick={fetchTeams} style={{ padding: '12px 24px', fontSize: '13px' }}>
          Refresh Directory
        </button>
      </div>

      {/* ── Main Layout View ── */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '60px', color: 'var(--text-muted)', fontSize: '14px' }}>Loading teams and members directory...</div>
      ) : error ? (
        <div className="card" style={{ padding: '30px', textAlign: 'center', border: '1px solid rgba(220,38,38,0.2)' }}>
          <AlertCircle size={32} style={{ color: 'var(--red)', marginBottom: '12px' }} />
          <p style={{ color: 'var(--red)', fontWeight: 'bold' }}>{error}</p>
          <button className="btn-primary" onClick={fetchTeams} style={{ marginTop: '12px', padding: '8px 16px' }}>Retry</button>
        </div>
      ) : filteredTeams.length === 0 ? (
        <div className="card" style={{ padding: '60px', textAlign: 'center', color: 'var(--text-muted)' }}>
          <Users size={48} style={{ strokeWidth: 1, marginBottom: '16px', opacity: 0.5 }} />
          <p style={{ fontSize: '15px', fontWeight: 600 }}>No support teams matched your search criteria.</p>
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))', gap: '24px' }}>
          {filteredTeams.map(team => (
            <div
              className="card"
              key={team.id}
              style={{
                display: 'flex', flexDirection: 'column', height: '100%',
                borderTop: '4px solid var(--michaels-red)', transition: 'all 0.3s ease'
              }}
            >
              <div style={{ padding: '24px 24px 16px', borderBottom: '1px solid var(--border)' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '8px' }}>
                  <h3 style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text)', margin: 0 }}>
                    {team.name}
                  </h3>
                  <span style={{ fontSize: '10px', color: 'var(--text-muted)', fontFamily: 'monospace' }}>
                    #{team.id.substring(0, 8)}
                  </span>
                </div>
                <p style={{ fontSize: '13px', color: 'var(--text-dim)', lineHeight: '1.5', margin: 0 }}>
                  {team.description || 'No focus description details provided.'}
                </p>
              </div>

              <div style={{ padding: '20px 24px 24px', flex: 1, display: 'flex', flexDirection: 'column', gap: '16px' }}>
                <div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                    <h4 style={{ fontSize: '11px', fontWeight: 800, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.8px' }}>
                      Group Engineers
                    </h4>
                    <span style={{ fontSize: '11px', color: 'var(--accent)', fontWeight: 'bold', background: 'var(--accent-dim)', padding: '2px 8px', borderRadius: '12px' }}>
                      {team.employees?.length || 0} active
                    </span>
                  </div>

                  {(!team.employees || team.employees.length === 0) ? (
                    <div style={{ padding: '16px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)', textAlign: 'center', fontSize: '12px', color: 'var(--text-muted)', fontStyle: 'italic' }}>
                      No members assigned to this queue.
                    </div>
                  ) : (
                    <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                      {team.employees.map(emp => {
                        const avatarBg = getColorForUser(emp.username);
                        return (
                          <div
                            key={emp.id}
                            style={{
                              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                              padding: '10px 14px', background: 'var(--surface2)', borderRadius: '8px',
                              border: '1px solid var(--border)', transition: 'all 0.2s'
                            }}
                          >
                            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                              <div style={{
                                width: '28px', height: '28px', borderRadius: '50%',
                                background: avatarBg, color: 'white', display: 'flex',
                                alignItems: 'center', justifyContent: 'center', fontSize: '11px', fontWeight: 'bold'
                              }}>
                                {emp.username.substring(0, 2).toUpperCase()}
                              </div>
                              <div>
                                <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text)' }}>
                                  {emp.username}
                                </div>
                                <div style={{ fontSize: '10.5px', color: 'var(--text-muted)' }}>
                                  Tier 2 Engineer
                                </div>
                              </div>
                            </div>
                            
                            {emp.email && (
                              <a
                                href={`mailto:${emp.email}`}
                                style={{
                                  display: 'flex', alignItems: 'center', justifyItems: 'center', padding: '6px',
                                  borderRadius: '50%', border: '1px solid var(--border-bright)',
                                  background: 'var(--surface)', color: 'var(--text-muted)', transition: 'all 0.2s'
                                }}
                                title={emp.email}
                              >
                                <Mail size={12} />
                              </a>
                            )}
                          </div>
                        );
                      })}
                    </div>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default TeamsPage;
