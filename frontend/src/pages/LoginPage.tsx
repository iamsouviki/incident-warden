import React, { useState } from 'react';
import { ArrowRight, CheckCircle2, KeyRound, LockKeyhole, ShieldCheck } from 'lucide-react';
import { login, AuthUser } from '../services/api';
import './LoginPage.css';

interface Props { onLogin: (user: AuthUser) => void; }

export default function LoginPage({ onLogin }: Props) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'VIEWER' | 'ANALYST' | 'ADMIN'>('ADMIN');
  const [rememberMe, setRememberMe] = useState(false);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(event: React.FormEvent) {
    event.preventDefault();
    setError('');
    setLoading(true);
    try {
      onLogin(await login(username.trim(), password, rememberMe, role));
    } catch (err: any) {
      setError(err.message || 'Login failed');
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="login-page">
      <div className="login-grid" />
      <section className="login-shell">
        <div className="login-intro">
          <div className="login-brand"><span className="login-brand-mark">I</span><span>incident<span>.ai</span></span></div>
          <div className="login-eyebrow">Enterprise incident operations</div>
          <h1>Move from signal to safe action.</h1>
          <p>Monitor store systems, coordinate agents, and keep a human decision-maker in control of every high-impact remediation.</p>
          <div className="login-capabilities">
            <div><CheckCircle2 size={15} /><span>Universal incident intake</span></div>
            <div><ShieldCheck size={15} /><span>Configurable risk governance</span></div>
            <div><LockKeyhole size={15} /><span>Audited operator decisions</span></div>
          </div>
        </div>

        <div className="login-card">
          <div className="login-card-head"><div className="login-card-icon"><KeyRound size={17} /></div><div><div className="login-card-kicker">Secure workspace access</div><h2>Sign in</h2></div></div>
          <p className="login-card-copy">Use your demo operator account to open the incident command center.</p>
          {error && <div className="login-error" role="alert">{error}</div>}
          <form onSubmit={handleSubmit} className="login-form" noValidate>
            <label>Username<input id="login-username" type="text" autoComplete="username" placeholder="admin" value={username} onChange={event => setUsername(event.target.value)} required autoFocus /></label>
            <label>Password<input id="login-password" type="password" autoComplete="current-password" placeholder="Enter password" value={password} onChange={event => setPassword(event.target.value)} required /></label>
            <label>POC role<select id="login-role" value={role} onChange={event => setRole(event.target.value as 'VIEWER' | 'ANALYST' | 'ADMIN')}><option value="VIEWER">Viewer — read-only</option><option value="ANALYST">Analyst — propose plans</option><option value="ADMIN">Admin — approve and simulate</option></select></label>
            <label className="login-remember"><input type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} /><span>Keep me signed in for 7 days</span></label>
            <button className="login-submit" type="submit" disabled={loading}>{loading ? <><span className="login-spinner" /> Signing in…</> : <>Open workspace <ArrowRight size={15} /></>}</button>
          </form>
          <div className="login-demo-note"><span>Demo credentials</span><code>admin / admin123</code></div>
          <div className="login-token-note"><ShieldCheck size={13} /><span>POC role selection is enabled only in the local demo profile.</span></div>
          <div className="login-token-note"><LockKeyhole size={13} /><span>Session uses a short-lived access token with silent refresh.</span></div>
        </div>
      </section>
      <footer className="login-footer">incident.ai · Operations control plane · Demo environment</footer>
    </main>
  );
}
