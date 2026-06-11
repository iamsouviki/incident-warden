import React, { useState } from 'react';
import { login, AuthUser } from '../services/api';
import { Zap } from 'lucide-react';
import './LoginPage.css';

interface Props {
  onLogin: (user: AuthUser) => void;
}

export default function LoginPage({ onLogin }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError]       = useState('');
  const [loading, setLoading]   = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const user = await login(username.trim(), password);
      onLogin(user);
    } catch (err: any) {
      setError(err.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-root">
      <div className="login-card">
        {/* Logo / brand */}
        <div className="login-logo">
          <span className="login-logo-icon"><Zap size={28} /></span>
          <div>
            <div className="login-logo-title">MCP Incident&nbsp;Automation</div>
            <div className="login-logo-sub">AI-Powered Operations Platform</div>
          </div>
        </div>

        <form className="login-form" onSubmit={handleSubmit} noValidate>
          <h2 className="login-heading">Sign&nbsp;in</h2>

          {error && <div className="login-error">{error}</div>}

          <div className="login-field">
            <label htmlFor="username">Username</label>
            <input
              id="username"
              type="text"
              autoComplete="username"
              placeholder="admin · analyst · viewer"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              autoFocus
            />
          </div>

          <div className="login-field">
            <label htmlFor="password">Password</label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              placeholder="••••••••"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
            />
          </div>

          <button className="login-btn" type="submit" disabled={loading}>
            {loading ? <span className="login-spinner" /> : 'Sign in'}
          </button>
        </form>

        {/* Local hint */}
        <p className="login-hint">
          Local credentials:&nbsp;
          <code>admin / admin123</code>
        </p>
      </div>
    </div>
  );
}
