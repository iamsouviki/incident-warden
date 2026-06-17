import React, { useState } from 'react';
import { login, AuthUser } from '../services/api';

interface Props {
  onLogin: (user: AuthUser) => void;
}

export default function LoginPage({ onLogin }: Props) {
  const [username,   setUsername]   = useState('');
  const [password,   setPassword]   = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error,      setError]      = useState('');
  const [loading,    setLoading]    = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const user = await login(username.trim(), password, rememberMe);
      onLogin(user);
    } catch (err: any) {
      setError(err.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={{
      minHeight: '100vh',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      background: '#0a0a0a',
      backgroundImage: 'radial-gradient(ellipse at 60% 0%, rgba(200,16,46,0.12) 0%, transparent 60%), linear-gradient(rgba(255,255,255,0.015) 1px,transparent 1px), linear-gradient(90deg,rgba(255,255,255,0.015) 1px,transparent 1px)',
      backgroundSize: 'cover, 32px 32px, 32px 32px',
      fontFamily: 'var(--sans)',
      position: 'relative',
      overflow: 'hidden',
    }}>
      {/* Decorative glow */}
      <div style={{
        position: 'absolute', top: 0, left: '50%', transform: 'translateX(-50%)',
        width: '600px', height: '3px', background: 'var(--michaels-red)',
        boxShadow: '0 0 40px 4px rgba(200,16,46,0.5)'
      }} />

      <div style={{
        width: '100%', maxWidth: '420px', padding: '0 20px',
        animation: 'fadeUp 0.4s cubic-bezier(0.16,1,0.3,1)',
      }}>
        {/* Brand */}
        <div style={{ textAlign: 'center', marginBottom: '40px' }}>
          <div style={{ display: 'inline-flex', alignItems: 'center', gap: '10px', marginBottom: '12px' }}>
            <div style={{
              width: '40px', height: '40px', borderRadius: '10px',
              background: 'var(--michaels-red)',
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              boxShadow: '0 0 20px rgba(200,16,46,0.4)',
            }}>
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.5">
                <path d="m13 2-3 9h-7l5.6 4.1-2.1 6.9L12 18l5.5 4-2.1-6.9L21 11h-7Z"/>
              </svg>
            </div>
            <div style={{ textAlign: 'left' }}>
              <div style={{ fontSize: '18px', fontWeight: 900, letterSpacing: '-0.5px', lineHeight: 1.1 }}>
                <span style={{ color: '#ffffff' }}>INCIDENT</span>
                <span style={{ color: 'var(--michaels-red)' }}>.AI</span>
              </div>
              <div style={{ fontSize: '10px', color: '#64748b', fontWeight: 600, letterSpacing: '1.5px', textTransform: 'uppercase' }}>
                AI Operations Platform
              </div>
            </div>
          </div>
        </div>

        {/* Card */}
        <div style={{
          background: 'rgba(255,255,255,0.04)',
          border: '1px solid rgba(255,255,255,0.08)',
          borderRadius: '16px',
          padding: '36px 32px',
          backdropFilter: 'blur(20px)',
          boxShadow: '0 24px 60px rgba(0,0,0,0.4)',
        }}>
          <h1 style={{ fontSize: '22px', fontWeight: 800, color: '#f1f5f9', margin: '0 0 6px', letterSpacing: '-0.3px' }}>
            Welcome back
          </h1>
          <p style={{ fontSize: '13px', color: '#64748b', margin: '0 0 28px' }}>
            Sign in to your workspace
          </p>

          {error && (
            <div style={{
              background: 'rgba(239,68,68,0.1)', border: '1px solid rgba(239,68,68,0.3)',
              borderRadius: '8px', padding: '10px 14px', marginBottom: '20px',
              fontSize: '13px', color: '#f87171',
            }}>
              {error}
            </div>
          )}

          <form onSubmit={handleSubmit} noValidate style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.8px' }}>
                Username
              </label>
              <input
                id="login-username"
                type="text"
                autoComplete="username"
                placeholder="Enter your username"
                value={username}
                onChange={e => setUsername(e.target.value)}
                required
                autoFocus
                style={{
                  background: 'rgba(255,255,255,0.05)',
                  border: '1px solid rgba(255,255,255,0.1)',
                  borderRadius: '8px',
                  color: '#f1f5f9',
                  fontSize: '14px',
                  padding: '12px 14px',
                  width: '100%',
                  outline: 'none',
                  transition: 'border-color 0.2s, box-shadow 0.2s',
                  fontFamily: 'var(--sans)',
                }}
                onFocus={e => { e.target.style.borderColor = 'var(--michaels-red)'; e.target.style.boxShadow = '0 0 0 3px rgba(200,16,46,0.15)'; }}
                onBlur={e  => { e.target.style.borderColor = 'rgba(255,255,255,0.1)'; e.target.style.boxShadow = 'none'; }}
              />
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
              <label style={{ fontSize: '11px', fontWeight: 700, color: '#94a3b8', textTransform: 'uppercase', letterSpacing: '0.8px' }}>
                Password
              </label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                placeholder="••••••••"
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
                style={{
                  background: 'rgba(255,255,255,0.05)',
                  border: '1px solid rgba(255,255,255,0.1)',
                  borderRadius: '8px',
                  color: '#f1f5f9',
                  fontSize: '14px',
                  padding: '12px 14px',
                  width: '100%',
                  outline: 'none',
                  transition: 'border-color 0.2s, box-shadow 0.2s',
                  fontFamily: 'var(--sans)',
                }}
                onFocus={e => { e.target.style.borderColor = 'var(--michaels-red)'; e.target.style.boxShadow = '0 0 0 3px rgba(200,16,46,0.15)'; }}
                onBlur={e  => { e.target.style.borderColor = 'rgba(255,255,255,0.1)'; e.target.style.boxShadow = 'none'; }}
              />
            </div>

            {/* Remember me */}
            <label style={{ display: 'flex', alignItems: 'center', gap: '10px', cursor: 'pointer', userSelect: 'none' }}>
              <div
                onClick={() => setRememberMe(r => !r)}
                style={{
                  width: '18px', height: '18px', borderRadius: '4px', flexShrink: 0,
                  border: `2px solid ${rememberMe ? 'var(--michaels-red)' : 'rgba(255,255,255,0.2)'}`,
                  background: rememberMe ? 'var(--michaels-red)' : 'transparent',
                  display: 'flex', alignItems: 'center', justifyContent: 'center',
                  transition: 'all 0.2s',
                }}
              >
                {rememberMe && (
                  <svg width="10" height="10" viewBox="0 0 10 10" fill="none">
                    <path d="M1.5 5l2.5 2.5 4.5-4.5" stroke="white" strokeWidth="1.5" strokeLinecap="round"/>
                  </svg>
                )}
              </div>
              <span style={{ fontSize: '13px', color: '#94a3b8' }}>
                Remember me for 7 days
              </span>
            </label>

            <button
              id="login-submit"
              type="submit"
              disabled={loading}
              style={{
                marginTop: '4px',
                background: loading ? 'rgba(200,16,46,0.6)' : 'var(--michaels-red)',
                color: '#ffffff',
                border: 'none',
                borderRadius: '8px',
                fontSize: '14px',
                fontWeight: 700,
                padding: '13px',
                cursor: loading ? 'not-allowed' : 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '8px',
                transition: 'all 0.2s',
                boxShadow: '0 4px 16px rgba(200,16,46,0.35)',
                fontFamily: 'var(--sans)',
                letterSpacing: '0.2px',
              }}
              onMouseEnter={e => { if (!loading) (e.target as HTMLButtonElement).style.boxShadow = '0 6px 24px rgba(200,16,46,0.5)'; }}
              onMouseLeave={e => { (e.target as HTMLButtonElement).style.boxShadow = '0 4px 16px rgba(200,16,46,0.35)'; }}
            >
              {loading ? (
                <>
                  <span style={{ width: '16px', height: '16px', border: '2px solid rgba(255,255,255,0.3)', borderTopColor: '#fff', borderRadius: '50%', animation: 'spin 0.7s linear infinite', display: 'inline-block' }} />
                  Signing in...
                </>
              ) : 'Sign in'}
            </button>
          </form>

          <div style={{ marginTop: '24px', paddingTop: '20px', borderTop: '1px solid rgba(255,255,255,0.06)', textAlign: 'center' }}>
            <p style={{ fontSize: '12px', color: '#475569', margin: 0 }}>
              Default credentials: <code style={{ color: '#94a3b8', background: 'rgba(255,255,255,0.06)', padding: '2px 6px', borderRadius: '4px', fontSize: '11px' }}>admin / admin123</code>
            </p>
          </div>
        </div>

        {/* Footer */}
        <p style={{ textAlign: 'center', marginTop: '20px', fontSize: '11px', color: '#334155' }}>
          Enterprise Incident Management · SSO support coming soon
        </p>
      </div>
    </div>
  );
}
