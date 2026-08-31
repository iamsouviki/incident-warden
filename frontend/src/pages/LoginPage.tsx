import React, { useState } from 'react';
import { ArrowLeft, ArrowRight, CheckCircle2, KeyRound, LockKeyhole, ShieldCheck } from 'lucide-react';
import { login, AuthUser } from '../services/api';
import './LoginPage.css';

interface Props {
  onLogin: (user: AuthUser) => void;
  /** Back to the assistant without an account. Absent when there is nowhere to go back to. */
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
    setError('');
    setLoading(true);
    try {
      onLogin(await login(username.trim(), password, rememberMe));
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
          <div className="login-brand"><span className="login-brand-mark">I</span><span>incident<span> warden</span></span></div>
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
          <p className="login-card-copy">Enter your verified operator account credentials to sign in.</p>
          {error && <div className="login-error" role="alert">{error}</div>}
          <form onSubmit={handleSubmit} className="login-form" noValidate>
            <label>Username<input id="login-username" type="text" autoComplete="username" placeholder="admin" value={username} onChange={event => setUsername(event.target.value)} required autoFocus /></label>
            <label>Password<input id="login-password" type="password" autoComplete="current-password" placeholder="Enter password" value={password} onChange={event => setPassword(event.target.value)} required /></label>
            <label className="login-remember"><input type="checkbox" checked={rememberMe} onChange={event => setRememberMe(event.target.checked)} /><span>Keep me signed in for 7 days</span></label>
            <button className="login-submit" type="submit" disabled={loading}>{loading ? <><span className="login-spinner" /> Signing in…</> : <>Open workspace <ArrowRight size={15} /></>}</button>
          </form>
          <div className="login-token-note"><LockKeyhole size={13} /><span>Session uses a short-lived access token with verified signature.</span></div>
          {onSkip && (
            <button type="button" className="login-skip" onClick={onSkip}>
              <ArrowLeft size={13} /> Continue without signing in
            </button>
          )}
        </div>
      </section>
      <footer className="login-footer">Incident Warden · Operations control plane · Enterprise environment</footer>
    </main>
  );
}
