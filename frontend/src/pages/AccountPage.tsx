import React, { useState, useEffect } from 'react';
import { User, Mail, Shield, Clock, LogOut, Briefcase } from 'lucide-react';
import { getStoredUser, getTokenExpiry, clearAuth, authFetch } from '../services/api';

interface Props {
  onLogout: () => void;
}

interface UserProfile {
  username: string;
  fullName?: string;
  email?: string;
  role: string;
  department?: string;
  ssoProvider?: string;
}

const AccountPage: React.FC<Props> = ({ onLogout }) => {
  const storedUser = getStoredUser();
  const expiry = getTokenExpiry();
  const [profile, setProfile] = useState<UserProfile | null>(null);

  useEffect(() => {
    authFetch('/api/auth/me').then(r => r.ok ? r.json() : null).then(d => {
      if (d) setProfile(d);
    }).catch(() => {});
  }, []);

  const expiryLabel = expiry
    ? new Date(expiry).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
    : 'Unknown';


  const realName = profile?.fullName || storedUser?.fullName || storedUser?.username || 'User';
  const username = profile?.username || storedUser?.username || 'user';
  const department = profile?.department || storedUser?.department || 'Operations';
  const email = profile?.email || '—';
  const role = profile?.role || storedUser?.role || 'VIEWER';

  const initials = realName.substring(0, 2).toUpperCase();

  const roleColors: Record<string, string> = {
    OWNER: '#f59e0b', ADMIN: 'var(--accent)', ANALYST: 'var(--purple)', VIEWER: 'var(--ok)'
  };
  const roleColor = roleColors[role] || 'var(--text-3)';

  return (
    <div style={{ maxWidth: '680px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '20px' }}>

      {/* Profile Banner Card */}
      <div className="card" style={{ overflow: 'hidden' }}>
        <div style={{
          height: '110px',
          background: 'linear-gradient(135deg, rgba(59,130,246,0.18), rgba(139,92,246,0.12), transparent)',
          borderBottom: '1px solid var(--border)',
          position: 'relative',
        }}>
          <div style={{ position: 'absolute', right: 10, bottom: 0, opacity: 0.06, lineHeight: 1 }}>
            <User size={140} />
          </div>
        </div>

        <div style={{ padding: '0 28px 28px' }}>
          {/* Avatar */}
          <div style={{
            width: '76px', height: '76px', borderRadius: '50%',
            background: 'linear-gradient(135deg, #3b82f6, #1d4ed8)', color: 'white',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '26px', fontWeight: 800, letterSpacing: '-1px',
            marginTop: '-38px', marginBottom: '16px',
            border: '4px solid var(--surface-1)',
            boxShadow: '0 4px 16px var(--accent-glow)',
          }}>
            {initials}
          </div>

          <h2 style={{ fontSize: '22px', fontWeight: 800, color: 'var(--text-1)', margin: '0 0 4px', letterSpacing: '-0.3px' }}>
            {realName}
          </h2>
          <div style={{ fontSize: '13px', color: 'var(--text-3)', fontFamily: 'var(--font-mono)', marginBottom: '12px' }}>
            @{username}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
            <span style={{
              fontSize: '11px', fontWeight: 700, padding: '3px 10px', borderRadius: '20px',
              background: 'var(--accent-dim)', color: roleColor, border: `1px solid ${roleColor}40`,
              textTransform: 'uppercase', letterSpacing: '0.5px',
            }}>
              {role}
            </span>
            <span style={{ fontSize: '12px', color: 'var(--text-2)' }}>
              {department}
            </span>
          </div>
        </div>
      </div>

      {/* Info grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '14px' }}>
        {[
          { icon: <User size={16}/>,     label: 'Full Name',        value: realName },
          { icon: <User size={16}/>,     label: 'Username',         value: `@${username}` },
          { icon: <Mail size={16}/>,     label: 'Email',            value: email },
          { icon: <Briefcase size={16}/>,label: 'Department',       value: department },
          { icon: <Shield size={16}/>,   label: 'Security Role',    value: role },
          {
            icon: <Clock size={16}/>,
            label: 'Access Token Expiry',
            value: expiryLabel,
            extra: <span style={{ fontSize: '10px', color: 'var(--ok)', fontWeight: 700, background: 'var(--ok-dim)', padding: '2px 8px', borderRadius: '8px' }}>AUTO REFRESH ACTIVE</span>,
          },
        ].map((item, i) => (
          <div key={i} className="card" style={{ padding: '16px 18px', display: 'flex', gap: '14px', alignItems: 'flex-start' }}>
            <div style={{ padding: '8px', borderRadius: '8px', background: 'var(--surface-2)', color: 'var(--accent)', flexShrink: 0 }}>
              {item.icon}
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.6px', marginBottom: '3px' }}>
                {item.label}
              </div>
              <div style={{ fontSize: '13.5px', fontWeight: 600, color: 'var(--text-1)', wordBreak: 'break-word' }}>
                {item.value}
              </div>
              {(item as any).extra && <div style={{ marginTop: '4px' }}>{(item as any).extra}</div>}
            </div>
          </div>
        ))}
      </div>

      {/* Enterprise SSO notice */}
      <div className="card" style={{ padding: '20px 24px', background: 'var(--accent-dim)', border: '1px solid rgba(59,130,246,0.2)' }}>
        <div style={{ fontSize: '13px', fontWeight: 800, color: 'var(--accent)', marginBottom: '6px' }}>
          Enterprise Single Sign-On (SSO) Active
        </div>
        <div style={{ fontSize: '12.5px', color: 'var(--text-2)', lineHeight: '1.6' }}>
          Your account is configured for federated identity. You can link identity providers (Okta, Azure AD, Google Workspace) via OIDC tokens.
        </div>
      </div>

      {/* Sign out */}
      <div className="card" style={{ padding: '20px 24px', border: '1px solid rgba(239,68,68,0.2)' }}>
        <div style={{ fontSize: '13.5px', fontWeight: 800, color: 'var(--text-1)', marginBottom: '12px' }}>Session Management</div>
        <button
          id="account-logout-btn"
          onClick={() => { clearAuth(); onLogout(); }}
          style={{
            display: 'flex', alignItems: 'center', gap: '8px',
            background: 'transparent', border: '1px solid rgba(239,68,68,0.4)',
            color: 'var(--crit)', padding: '9px 18px', borderRadius: '8px',
            cursor: 'pointer', fontSize: '13px', fontWeight: 700, transition: 'all var(--transition-fast)',
            fontFamily: 'var(--font-sans)',
          }}
          onMouseEnter={e => { (e.currentTarget).style.background = 'var(--crit-dim)'; (e.currentTarget).style.borderColor = 'var(--crit)'; }}
          onMouseLeave={e => { (e.currentTarget).style.background = 'transparent'; (e.currentTarget).style.borderColor = 'rgba(239,68,68,0.4)'; }}
        >
          <LogOut size={14} /> Sign out of current session
        </button>
      </div>
    </div>
  );
};

export default AccountPage;
