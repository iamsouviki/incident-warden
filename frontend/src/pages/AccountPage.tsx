import React, { useState, useEffect } from 'react';
import { User, Mail, Shield, Building, Key, Clock, LogOut } from 'lucide-react';
import { getStoredUser, getTokenExpiry, clearAuth, authFetch } from '../services/api';

interface Props {
  onLogout: () => void;
}

const AccountPage: React.FC<Props> = ({ onLogout }) => {
  const user = getStoredUser();
  const expiry = getTokenExpiry();
  const [profile, setProfile] = useState<{email?: string} | null>(null);

  useEffect(() => {
    authFetch('/api/auth/me').then(r => r.ok ? r.json() : null).then(d => {
      if (d) setProfile(d);
    }).catch(() => {});
  }, []);

  const expiryLabel = expiry
    ? new Date(expiry).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' })
    : 'Unknown';

  const refreshWindowLabel = user?.refreshExpiresIn ? `${Math.round(user.refreshExpiresIn / (24 * 60 * 60 * 1000)) || 1} day${Math.round(user.refreshExpiresIn / (24 * 60 * 60 * 1000)) === 1 ? '' : 's'}` : 'Available while session is valid';

  const getColorForUser = (u: string) => {
    const colors = ['#c8102e', '#2563eb', '#10b981', '#8b5cf6', '#f59e0b'];
    let h = 0;
    for (let i = 0; i < u.length; i++) h = u.charCodeAt(i) + ((h << 5) - h);
    return colors[Math.abs(h) % colors.length];
  };

  const avatarColor = user ? getColorForUser(user.username) : '#c8102e';
  const initials = user ? user.username.substring(0, 2).toUpperCase() : 'US';

  const roleColors: Record<string, string> = {
    ADMIN: '#c8102e', ANALYST: '#2563eb', VIEWER: '#10b981'
  };
  const roleColor = roleColors[user?.role || ''] || '#64748b';

  return (
    <div style={{ maxWidth: '640px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '20px' }}>

      {/* Profile card */}
      <div className="card" style={{ overflow: 'hidden' }}>
        {/* Banner */}
        <div style={{
          height: '100px',
          background: `linear-gradient(135deg, ${avatarColor}22, ${avatarColor}08, transparent)`,
          borderBottom: `3px solid ${avatarColor}`,
          position: 'relative',
        }}>
          <div style={{ position: 'absolute', right: 0, bottom: 0, opacity: 0.05, lineHeight: 1 }}>
            <User size={140} />
          </div>
        </div>

        <div style={{ padding: '0 28px 28px' }}>
          {/* Avatar */}
          <div style={{
            width: '72px', height: '72px', borderRadius: '50%',
            background: avatarColor, color: 'white',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: '24px', fontWeight: 900, letterSpacing: '-1px',
            marginTop: '-36px', marginBottom: '16px',
            border: '4px solid var(--surface)',
            boxShadow: `0 0 0 2px ${avatarColor}40`,
          }}>
            {initials}
          </div>

          <h2 style={{ fontSize: '22px', fontWeight: 800, color: 'var(--text)', margin: '0 0 4px', letterSpacing: '-0.3px' }}>
            {user?.username}
          </h2>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
            <span style={{
              fontSize: '11px', fontWeight: 700, padding: '3px 10px', borderRadius: '20px',
              background: `${roleColor}15`, color: roleColor, border: `1px solid ${roleColor}30`,
              textTransform: 'uppercase', letterSpacing: '0.5px',
            }}>
              {user?.role}
            </span>
            <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>
              {user?.tenantName || user?.tenantId}
            </span>
          </div>
        </div>
      </div>

      {/* Info grid */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
        {[
          { icon: <User size={16}/>,     label: 'Username',  value: user?.username || '—' },
          { icon: <Mail size={16}/>,     label: 'Email',     value: profile?.email || '—' },
          { icon: <Shield size={16}/>,   label: 'Role',      value: user?.role || '—' },
          { icon: <Building size={16}/>, label: 'Workspace', value: user?.tenantName || user?.tenantId || '—' },
          { icon: <Key size={16}/>,      label: 'Tenant ID', value: user?.tenantId || '—' },
          {
            icon: <Clock size={16}/>,
            label: 'Access token expires',
            value: expiryLabel,
            extra: <span style={{ fontSize: '10px', color: '#10b981', fontWeight: 700, background: 'rgba(16,185,129,0.1)', padding: '1px 6px', borderRadius: '8px' }}>AUTO REFRESH</span>,
          },
          { icon: <Key size={16}/>, label: 'Refresh window', value: refreshWindowLabel },
        ].map((item, i) => (
          <div key={i} className="card" style={{ padding: '18px 20px', display: 'flex', gap: '12px', alignItems: 'flex-start' }}>
            <div style={{ padding: '8px', borderRadius: '8px', background: 'var(--surface2)', color: 'var(--text-muted)', flexShrink: 0 }}>
              {item.icon}
            </div>
            <div style={{ minWidth: 0 }}>
              <div style={{ fontSize: '10px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.6px', marginBottom: '4px' }}>
                {item.label}
              </div>
              <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text)', wordBreak: 'break-all' }}>
                {item.value}
              </div>
              {(item as any).extra && <div style={{ marginTop: '4px' }}>{(item as any).extra}</div>}
            </div>
          </div>
        ))}
      </div>

      {/* SSO notice */}
      <div className="card" style={{ padding: '20px 24px', background: 'var(--accent-dim)', border: '1px solid rgba(37,99,235,0.15)' }}>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--accent)', marginBottom: '6px' }}>
          SSO Integration Ready
        </div>
        <div style={{ fontSize: '12px', color: 'var(--text-dim)', lineHeight: '1.6' }}>
          Your account supports Single Sign-On. Contact your administrator to link an SSO provider (Okta, Azure AD, etc.).
          Once linked, you can sign in without a password.
        </div>
      </div>

      {/* Danger zone */}
      <div className="card" style={{ padding: '20px 24px', border: '1px solid rgba(239,68,68,0.15)' }}>
        <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text)', marginBottom: '12px' }}>Sign out</div>
        <button
          id="account-logout-btn"
          onClick={() => { clearAuth(); onLogout(); }}
          style={{
            display: 'flex', alignItems: 'center', gap: '8px',
            background: 'transparent', border: '1px solid rgba(239,68,68,0.3)',
            color: 'var(--red)', padding: '8px 16px', borderRadius: '8px',
            cursor: 'pointer', fontSize: '13px', fontWeight: 600, transition: 'all 0.2s',
            fontFamily: 'var(--sans)',
          }}
          onMouseEnter={e => { (e.currentTarget).style.background = 'var(--red-dim)'; (e.currentTarget).style.borderColor = 'var(--red)'; }}
          onMouseLeave={e => { (e.currentTarget).style.background = 'transparent'; (e.currentTarget).style.borderColor = 'rgba(239,68,68,0.3)'; }}
        >
          <LogOut size={14} /> Sign out of this session
        </button>
      </div>
    </div>
  );
};

export default AccountPage;
