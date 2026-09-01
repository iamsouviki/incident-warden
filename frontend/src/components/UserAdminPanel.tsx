import React, { useEffect, useState } from 'react';
import { AlertTriangle, KeyRound, Plus, Save, UserCog, X } from 'lucide-react';
import { authFetch, getStoredUser } from '../services/api';

/**
 * Who has an account, and what they are allowed to do with it.
 *
 * This is what replaced the threshold sliders and integration toggles that used to fill this
 * page: separation of duties is on and not switchable, so a workspace needs a second account
 * before anything can be approved and run. Creating that account is the control that matters,
 * and it is the only one here.
 *
 * Passwords are handed over, never chosen for someone. A created or reset account starts on the
 * starter password the server returns and cannot do anything until the person replaces it at
 * first sign-in — the server sets must_change_password and the sign-in flow blocks on it.
 */

interface Row {
  id: string;
  username: string;
  fullName: string;
  email: string;
  role: string;
  department: string;
  enabled: boolean;
  mustChangePassword: boolean;
}

/** ADMIN and ANALYST only. VIEWER exists in the backend but is not something to hand out here. */
const ROLES: Array<{ id: string; title: string; what: string }> = [
  { id: 'ADMIN', title: 'Admin', what: 'Everything: runs approved plans, edits SOPs, tools and skills, manages these accounts.' },
  { id: 'ANALYST', title: 'Analyst', what: 'Chat, raise a plan, approve someone else’s, dry-run it. Cannot execute or change settings.' },
];

const blank = { username: '', fullName: '', email: '', role: 'ANALYST', department: '' };

const UserAdminPanel: React.FC = () => {
  const [rows, setRows] = useState<Row[]>([]);
  const [draft, setDraft] = useState<typeof blank | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  // The password the server just handed back. Shown until dismissed, because it is the one
  // moment it exists in readable form — nothing stores it and no page can show it again.
  const [handover, setHandover] = useState<{ username: string; password: string; note: string } | null>(null);
  // Your own password is changed, never reset: a reset hands a published starter to somebody
  // else, and doing that to the account you are signed in with would lock you out of the page
  // you did it from. So the own row gets this form instead of the reset button.
  const [pwd, setPwd] = useState<{ current: string; next: string; confirm: string } | null>(null);
  const [notice, setNotice] = useState('');
  const currentUser = getStoredUser();
  const me = currentUser?.username;
  const isOwner = currentUser?.role === 'OWNER';

  const load = async () => {
    setError('');
    try {
      const res = await authFetch('/api/auth/users');
      if (!res.ok) throw new Error(`Could not load accounts (${res.status})`);
      // Sorted here because the API returns rows in whatever order the table hands back, so the
      // list reshuffled after every write. Your own account first, then alphabetical.
      const list: Row[] = await res.json();
      setRows(list.sort((a, b) => (a.username === me ? -1 : b.username === me ? 1 : a.username.localeCompare(b.username))));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load accounts.');
    }
  };

  useEffect(() => { void load(); }, []);

  /** One caller for every write on this panel; the server's own message is what gets shown. */
  const send = async (path: string, init: RequestInit, onOk: (body: any) => void) => {
    setBusy(true);
    setError('');
    try {
      const res = await authFetch(path, init);
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error(body?.error || `Request failed (${res.status})`);
      onOk(body);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Request failed.');
    } finally {
      setBusy(false);
    }
  };

  const create = () => draft && send('/api/auth/users',
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(draft) },
    body => {
      setHandover({ username: body.username, password: body.defaultPassword, note: body.message });
      setDraft(null);
    });

  const update = (row: Row, patch: Partial<Pick<Row, 'role' | 'enabled'>>) =>
    send(`/api/auth/users/${encodeURIComponent(row.username)}`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(patch) },
      () => setHandover(null));

  const reset = (row: Row) => {
    if (!window.confirm(`Reset ${row.username}'s password? They will be asked to set a new one at their next sign-in.`)) return;
    void send(`/api/auth/users/${encodeURIComponent(row.username)}/reset-password`, { method: 'POST' },
      body => setHandover({ username: row.username, password: body.defaultPassword, note: body.message }));
  };

  const changeOwn = () => {
    if (!pwd) return;
    if (pwd.next.length < 8) { setError('Password must be at least 8 characters long.'); return; }
    if (pwd.next !== pwd.confirm) { setError('The two new passwords do not match.'); return; }
    void send('/api/auth/password',
      { method: 'POST', body: JSON.stringify({ currentPassword: pwd.current, newPassword: pwd.next }) },
      body => { setPwd(null); setNotice(body?.message || 'Password updated.'); });
  };

  const label: React.CSSProperties = {
    display: 'block', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase',
    letterSpacing: '0.4px', color: 'var(--text-muted)', marginBottom: '5px',
  };
  const input: React.CSSProperties = {
    width: '100%', padding: '9px 10px', minHeight: '38px', fontSize: '12.5px',
    background: 'var(--surface2)', color: 'var(--text)',
    border: '1px solid var(--border)', borderRadius: '5px',
  };
  const smallBtn: React.CSSProperties = {
    display: 'flex', alignItems: 'center', gap: '5px', minHeight: '34px', padding: '0 12px',
    background: 'transparent', color: 'var(--text)', border: '1px solid var(--border)',
    borderRadius: '5px', fontSize: '11.5px', fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap',
  };

  return (
    <div className="card">
      <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
        <UserCog size={18} style={{ color: 'var(--accent)' }} />
        <div className="card-title">Accounts &amp; Access</div>
      </div>

      <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
        <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
          Whoever raises a plan cannot approve it, so this workspace needs at least two accounts
          before anything can be run. Accounts are switched off rather than deleted: an incident,
          a plan and an approval all name the person who raised them.
        </p>

        {error && (
          <div style={{ display: 'flex', gap: '8px', alignItems: 'flex-start', padding: '12px 14px', borderRadius: '6px', background: 'rgba(220,38,38,0.08)', border: '1px solid rgba(220,38,38,0.3)', color: 'var(--red)', fontSize: '12.5px' }}>
            <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: '1px' }} /> <span>{error}</span>
          </div>
        )}

        {handover && (
          <div style={{ padding: '14px 16px', borderRadius: '6px', background: 'var(--surface2)', border: '1px solid var(--accent)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', alignItems: 'flex-start' }}>
              <strong style={{ fontSize: '13px' }}>Password for {handover.username}</strong>
              <button onClick={() => setHandover(null)} aria-label="Dismiss password"
                      style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
                <X size={16} />
              </button>
            </div>
            <code style={{ display: 'inline-block', margin: '8px 0', padding: '8px 12px', fontSize: '15px', fontWeight: 700, letterSpacing: '0.5px', background: 'var(--surface3)', borderRadius: '5px', overflowWrap: 'anywhere' }}>
              {handover.password}
            </code>
            <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>{handover.note}</p>
          </div>
        )}

        {notice && (
          <div style={{ display: 'flex', justifyContent: 'space-between', gap: '12px', padding: '12px 14px', borderRadius: '6px', background: 'var(--surface2)', border: '1px solid var(--green)', color: 'var(--text)', fontSize: '12.5px' }}>
            <span>{notice}</span>
            <button onClick={() => setNotice('')} aria-label="Dismiss message"
                    style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
              <X size={15} />
            </button>
          </div>
        )}

        {pwd && (
          <div style={{ padding: '16px 18px', background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: '8px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
              <strong style={{ fontSize: '13px' }}>Change your password</strong>
              <button onClick={() => setPwd(null)} aria-label="Close password form"
                      style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
                <X size={16} />
              </button>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '14px' }}>
              <div>
                <label style={label} htmlFor="cp-current">Current password</label>
                <input id="cp-current" style={input} type="password" autoComplete="current-password"
                       value={pwd.current} onChange={e => setPwd({ ...pwd, current: e.target.value })} />
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <label style={label} htmlFor="cp-next">New password</label>
                  <span style={{ fontSize: '11px', color: pwd.next.length >= 8 ? 'var(--green, #22c55e)' : (pwd.next.length > 0 ? '#f59e0b' : 'var(--text-muted)') }}>
                    {pwd.next.length}/8 chars {pwd.next.length >= 8 ? '✓' : ''}
                  </span>
                </div>
                <input id="cp-next" style={input} type="password" autoComplete="new-password"
                       value={pwd.next} onChange={e => setPwd({ ...pwd, next: e.target.value })} />
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <label style={label} htmlFor="cp-confirm">New password again</label>
                  {pwd.confirm.length > 0 && (
                    <span style={{ fontSize: '11px', color: pwd.next === pwd.confirm ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)' }}>
                      {pwd.next === pwd.confirm ? 'Match ✓' : 'Mismatch ✗'}
                    </span>
                  )}
                </div>
                <input id="cp-confirm" style={input} type="password" autoComplete="new-password"
                       value={pwd.confirm} onChange={e => setPwd({ ...pwd, confirm: e.target.value })} />
              </div>
            </div>
            <button onClick={changeOwn} disabled={busy || !pwd.current || pwd.next.length < 8 || pwd.next !== pwd.confirm}
                    className="btn-primary"
                    style={{ display: 'flex', alignItems: 'center', gap: '6px', minHeight: '40px', padding: '0 16px', marginTop: '16px', border: 'none', fontSize: '13px', cursor: (busy || !pwd.current || pwd.next.length < 8 || pwd.next !== pwd.confirm) ? 'not-allowed' : 'pointer' }}>
              <Save size={14} /> {busy ? 'Saving…' : 'Save password'}
            </button>
          </div>
        )}

        {isOwner ? (
          draft ? (
            <div style={{ padding: '16px 18px', background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: '8px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
                <strong style={{ fontSize: '13px' }}>New account</strong>
                <button onClick={() => setDraft(null)} aria-label="Close form"
                        style={{ background: 'none', border: 'none', color: 'var(--text-muted)', cursor: 'pointer' }}>
                  <X size={16} />
                </button>
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '14px' }}>
                <div>
                  <label style={label} htmlFor="nu-username">Username</label>
                  <input id="nu-username" style={input} value={draft.username} placeholder="p.mehta"
                         onChange={e => setDraft({ ...draft, username: e.target.value })} />
                </div>
                <div>
                  <label style={label} htmlFor="nu-fullname">Full name</label>
                  <input id="nu-fullname" style={input} value={draft.fullName} placeholder="Priya Mehta"
                         onChange={e => setDraft({ ...draft, fullName: e.target.value })} />
                </div>
                <div>
                  <label style={label} htmlFor="nu-email">Email</label>
                  <input id="nu-email" style={input} type="email" value={draft.email} placeholder="priya.mehta@company.com"
                         onChange={e => setDraft({ ...draft, email: e.target.value })} />
                </div>
                <div>
                  <label style={label} htmlFor="nu-role">Role</label>
                  <select id="nu-role" style={{ ...input, appearance: 'auto' }} value={draft.role}
                          onChange={e => setDraft({ ...draft, role: e.target.value })}>
                    {ROLES.map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
                  </select>
                </div>
                <div>
                  <label style={label} htmlFor="nu-dept">Department</label>
                  <input id="nu-dept" style={input} value={draft.department} placeholder="Store Systems"
                         onChange={e => setDraft({ ...draft, department: e.target.value })} />
                </div>
              </div>

              <p style={{ margin: '12px 0 0', fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                {ROLES.find(r => r.id === draft.role)?.what} An email address is required, or this
                person can never be told about an incident assigned to them.
              </p>

              <button onClick={() => void create()} disabled={busy || !draft.username.trim() || !draft.email.trim()}
                      className="btn-primary"
                      style={{ display: 'flex', alignItems: 'center', gap: '6px', minHeight: '40px', padding: '0 16px', marginTop: '16px', border: 'none', fontSize: '13px', cursor: busy ? 'not-allowed' : 'pointer' }}>
                <Save size={14} /> {busy ? 'Creating…' : 'Create account'}
              </button>
            </div>
          ) : (
            <button onClick={() => setDraft({ ...blank })} style={{ ...smallBtn, minHeight: '38px', alignSelf: 'flex-start', background: 'var(--surface2)' }}>
              <Plus size={13} /> Add an account
            </button>
          )
        ) : (
          <div style={{ padding: '14px 16px', borderRadius: '8px', background: 'var(--surface2)', border: '1px solid var(--border)', color: 'var(--text-muted)', fontSize: '12.5px', lineHeight: 1.6 }}>
            User account creation is restricted to Workspace Owners. Contact your workspace owner to invite new operators.
          </div>
        )}

        <div style={{ display: 'grid', gap: '8px' }}>
          {rows.length === 0 && (
            <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)' }}>No accounts loaded yet.</p>
          )}
          {rows.map(row => (
            <div key={row.id || row.username} style={{
              display: 'flex', gap: '12px', alignItems: 'center', flexWrap: 'wrap',
              padding: '12px', background: 'var(--surface2)', border: '1px solid var(--border)',
              borderRadius: '6px', opacity: row.enabled ? 1 : 0.55,
            }}>
              <div style={{ flex: '1 1 240px', minWidth: 0 }}>
                <strong style={{ fontSize: '13px' }}>{row.fullName || row.username}</strong>
                {row.username === me && (
                  <span style={{ marginLeft: '8px', fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--accent)' }}>you</span>
                )}
                {!row.enabled && (
                  <span style={{ marginLeft: '8px', fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)' }}>off</span>
                )}
                <div style={{ fontSize: '11.5px', color: 'var(--text-muted)', marginTop: '3px', overflowWrap: 'anywhere' }}>
                  {row.username}{row.email ? ` · ${row.email}` : ''}
                </div>
                {row.mustChangePassword && (
                  <div style={{ fontSize: '11.5px', color: 'var(--amber, #b45309)', marginTop: '3px' }}>
                    Has not set their own password yet.
                  </div>
                )}
              </div>

              {/* Your own row is read-only on purpose. You cannot demote yourself (the server
                  refuses it), you cannot switch yourself off, and resetting your own password
                  would hand your account the published starter — so the only self-service
                  action here is changing the password to something you chose. */}
              {row.username === me ? (
                <>
                  <select value="OWNER" disabled aria-label={`Role for ${row.username}`}
                          title="This is the account you are signed in with — its role cannot be changed from here."
                          style={{ ...input, width: 'auto', minWidth: '128px', appearance: 'auto', opacity: 0.7, cursor: 'not-allowed' }}>
                    <option value="OWNER">Owner</option>
                  </select>
                  <button onClick={() => { setNotice(''); setPwd({ current: '', next: '', confirm: '' }); }}
                          disabled={busy} style={smallBtn} title="Change your own password">
                    <KeyRound size={13} /> Change password
                  </button>
                </>
              ) : (
                <>
                  <select
                    value={row.role}
                    aria-label={`Role for ${row.username}`}
                    disabled={busy}
                    onChange={e => void update(row, { role: e.target.value })}
                    style={{ ...input, width: 'auto', minWidth: '128px', appearance: 'auto' }}
                  >
                    {/* A VIEWER created before this panel existed keeps showing its real role. */}
                    {(ROLES.some(r => r.id === row.role) ? ROLES : [...ROLES, { id: row.role, title: row.role, what: '' }])
                      .map(r => <option key={r.id} value={r.id}>{r.title}</option>)}
                  </select>

                  <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
                    <button onClick={() => reset(row)} disabled={busy} style={smallBtn} title="Reset password">
                      <KeyRound size={13} /> Reset password
                    </button>
                    <button onClick={() => void update(row, { enabled: !row.enabled })} disabled={busy}
                            style={{ ...smallBtn, color: row.enabled ? 'var(--red)' : 'var(--green)' }}>
                      {row.enabled ? 'Switch off' : 'Switch on'}
                    </button>
                  </div>
                </>
              )}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
};

export default UserAdminPanel;
