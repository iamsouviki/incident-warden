import React, { useState } from 'react';
import { ArrowRight, Lock, User, AlertCircle } from 'lucide-react';
import { login, AuthUser } from '../services/api';
import './LoginPage.css';

interface Props {
  onLogin: (user: AuthUser) => void;
  onSkip?: () => void;
}

export default function LoginPage({ onLogin, onSkip }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    if (!username.trim() || !password) {
      setError('Please enter both username and password.');
      return;
    }
    setError('');
    setLoading(true);
    try {
      const user = await login(username.trim(), password, rememberMe);
      onLogin(user);
    } catch (err: any) {
      setError(err.message || 'Invalid username or password.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-root">
      <div className="login-card">
        <div className="login-header">
          <div className="login-logo-badge">I</div>
          <h1 className="login-title">Sign in to Incident Warden</h1>
          <p className="login-subtitle">Enter your operator credentials to access tools & remediation.</p>
        </div>

        {error && (
          <div className="login-error-alert" role="alert">
            <AlertCircle size={16} />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="login-form-body" noValidate>
          <div className="login-input-group">
            <label htmlFor="login-username">Username</label>
            <div className="login-input-wrap">
              <User size={16} className="login-input-icon" />
              <input
                id="login-username"
                type="text"
                autoComplete="username"
                placeholder="Enter username (e.g. admin)"
                value={username}
                onChange={e => setUsername(e.target.value)}
                required
                autoFocus
              />
            </div>
          </div>

          <div className="login-input-group">
            <label htmlFor="login-password">Password</label>
            <div className="login-input-wrap">
              <Lock size={16} className="login-input-icon" />
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                placeholder="Enter password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                required
              />
            </div>
          </div>



          <button className="login-submit-btn" type="submit" disabled={loading}>
            {loading ? (
              <>
                <span className="login-submit-spinner" />
                <span>Signing in…</span>
              </>
            ) : (
              <>
                <span>Sign in</span>
                <ArrowRight size={16} />
              </>
            )}
          </button>
        </form>

        {onSkip && (
          <div className="login-skip-wrap">
            <button type="button" className="login-skip-link" onClick={onSkip}>
              ← Continue as Guest (Public Mode)
            </button>
          </div>
        )}
      </div>
    </main>
  );
}
