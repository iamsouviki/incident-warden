import React, { useEffect, useState } from 'react';
import { authFetch } from '../services/api';
import { Layers, RefreshCw, CheckCircle, AlertCircle, Clock, Mail } from 'lucide-react';

/**
 * Whether a credential is set — never the credential.
 *
 * These used to be editable password inputs, which meant every save posted the secret through
 * the browser and the server ignored it anyway (credentials come from MCP_* environment
 * variables). An input that cannot change anything but can leak the value is the worst of both,
 * so the panel now reports state and names the variable to set.
 */
function SecretStatus({ label, envVar, set }: { label: string; envVar: string; set?: boolean }) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>{label}</label>
      <div style={{
        display: 'flex', alignItems: 'center', gap: '7px', height: '34px', padding: '0 9px',
        borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface2)',
        fontSize: '12px', color: set ? 'var(--ok, #16a34a)' : 'var(--warn, #b45309)',
      }}>
        {set ? <CheckCircle size={13} /> : <AlertCircle size={13} />}
        <span>{set ? 'Set from environment' : 'Not set'}</span>
      </div>
      <code style={{ display: 'block', marginTop: '4px', fontSize: '10.5px', color: 'var(--text-muted)' }}>{envVar}</code>
    </div>
  );
}

/** Shared field shell, so the relay form matches the integration forms without repeating styles. */
function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) {
  return (
    <div>
      <label htmlFor={id} style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>{label}</label>
      {children}
    </div>
  );
}

/**
 * The SMTP relay behind escalation email.
 *
 * These three endpoints existed with nothing calling them, which meant the only way to point the
 * platform at a mail relay was a SQL statement against config.system_config. Deleting them would
 * have removed a working feature; this is the missing half.
 */
function NotificationRelay() {
  const [relay, setRelay] = useState<{ enabled: boolean; host: string; port: number; from: string }>(
    { enabled: false, host: '', port: 25, from: '' });
  const [testTo, setTestTo] = useState('');
  const [busy, setBusy] = useState<'save' | 'test' | null>(null);
  const [note, setNote] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    authFetch('/api/v1/ai/config/notifications')
      .then(res => (res.ok ? res.json() : null))
      .then(data => data && setRelay({
        enabled: !!data.enabled, host: data.host || '', port: Number(data.port) || 25, from: data.from || '' }))
      .catch(() => setNote({ type: 'error', text: 'Could not load notification settings.' }));
  }, []);

  const post = async (path: string, body: unknown, kind: 'save' | 'test') => {
    setBusy(kind);
    setNote(null);
    try {
      const res = await authFetch(path, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
      const data = await res.json().catch(() => ({}));
      // The server validates host/from/port too; this surfaces its message rather than guessing.
      setNote({ type: res.ok ? 'success' : 'error', text: data.message || data.error || (res.ok ? 'Saved.' : 'Request failed.') });
    } catch {
      setNote({ type: 'error', text: 'Network error. Nothing was changed.' });
    } finally {
      setBusy(null);
    }
  };

  const invalid = relay.enabled && (!relay.host.trim() || !relay.from.trim());

  return (
    <div className="card" style={{ padding: '24px', marginTop: '24px', borderRadius: '12px' }}>
      <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '8px' }}>
        <Mail size={18} className="text-accent" />
        Escalation Email Relay
      </h3>
      <p style={{ margin: '4px 0 16px', fontSize: '13px', color: 'var(--text-muted)' }}>
        Where the platform sends notice when a plan needs review or an execution fails. The relay is
        used unauthenticated, so it must be one that accepts mail from this host.
      </p>

      {note && (
        <div role="status" style={{
          padding: '10px 14px', borderRadius: '8px', marginBottom: '16px', fontSize: '13px',
          display: 'flex', alignItems: 'center', gap: '8px',
          background: note.type === 'success' ? 'var(--green-dim, rgba(34,197,94,0.15))' : 'var(--red-dim, rgba(239,68,68,0.15))',
          color: note.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)',
          border: `1px solid ${note.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)'}`,
        }}>
          {note.type === 'success' ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
          {note.text}
        </div>
      )}

      <form onSubmit={e => { e.preventDefault(); post('/api/v1/ai/config/notifications', relay, 'save'); }}>
        <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12.5px', cursor: 'pointer', marginBottom: '12px' }}>
          <input type="checkbox" checked={relay.enabled} onChange={e => setRelay({ ...relay, enabled: e.target.checked })} />
          Send escalation email
        </label>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px' }}>
          <Field id="relay-host" label="SMTP host">
            <input id="relay-host" type="text" placeholder="smtp.company.internal" value={relay.host}
              onChange={e => setRelay({ ...relay, host: e.target.value })}
              style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }} />
          </Field>
          <Field id="relay-port" label="Port">
            <input id="relay-port" type="number" min={1} max={65535} value={relay.port}
              onChange={e => setRelay({ ...relay, port: Number(e.target.value) })}
              style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }} />
          </Field>
          <Field id="relay-from" label="From address">
            <input id="relay-from" type="email" placeholder="incident-warden@company.com" value={relay.from}
              onChange={e => setRelay({ ...relay, from: e.target.value })}
              style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }} />
          </Field>
        </div>

        {invalid && (
          <p style={{ margin: '10px 0 0', fontSize: '12px', color: 'var(--red, #ef4444)' }}>
            A host and a from address are required before email can be switched on.
          </p>
        )}

        <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-end', justifyContent: 'flex-end', marginTop: '16px', flexWrap: 'wrap' }}>
          <Field id="relay-test-to" label="Send a test message to">
            <input id="relay-test-to" type="email" placeholder="you@company.com" value={testTo}
              onChange={e => setTestTo(e.target.value)}
              style={{ width: '220px', height: '34px', padding: '0 8px', fontSize: '12.5px' }} />
          </Field>
          <button type="button" className="btn-secondary" disabled={busy !== null || !testTo.trim()}
            onClick={() => post('/api/v1/ai/config/notifications/test', { to: testTo.trim() }, 'test')}
            style={{ height: '34px', padding: '0 14px', fontSize: '12.5px' }}>
            {busy === 'test' ? 'Sending…' : 'Send test'}
          </button>
          <button type="submit" className="btn-primary" disabled={busy !== null || invalid}
            style={{ height: '38px', padding: '0 20px', fontSize: '13px' }}>
            {busy === 'save' ? 'Saving…' : 'Save Relay Settings'}
          </button>
        </div>
      </form>
    </div>
  );
}

export default function IntegrationAdminPanel() {
  const [settings, setSettings] = useState<{
    serviceNowEnabled?: boolean;
    serviceNowUrl?: string;
    serviceNowUsername?: string;
    serviceNowSecretSet?: boolean;
    freshserviceEnabled?: boolean;
    freshserviceUrl?: string;
    freshserviceSecretSet?: boolean;
    jiraEnabled?: boolean;
    jiraUrl?: string;
    jiraEmail?: string;
    jiraSecretSet?: boolean;
    jiraJql?: string;
    syncIntervalHours?: number;
    lastSyncTime?: string;
    lastSyncStatus?: string;
  }>({});

  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [testingService, setTestingService] = useState<string | null>(null);
  const [statusMessage, setStatusMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    loadSettings();
  }, []);

  const loadSettings = async () => {
    try {
      const res = await authFetch('/api/v1/integrations/settings');
      if (res.ok) {
        const data = await res.json();
        setSettings(data);
      }
    } catch (e) {
      console.error('Failed to load integration settings:', e);
    }
  };

  const handleSave = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setStatusMessage(null);
    try {
      const res = await authFetch('/api/v1/integrations/settings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settings)
      });
      if (res.ok) {
        setStatusMessage({ type: 'success', text: 'Enterprise integration settings saved successfully.' });
      } else {
        setStatusMessage({ type: 'error', text: 'Failed to save integration settings.' });
      }
    } catch (e) {
      setStatusMessage({ type: 'error', text: 'Network error while saving integration settings.' });
    } finally {
      setLoading(false);
    }
  };

  const handleTest = async (service: string) => {
    setTestingService(service);
    setStatusMessage(null);
    try {
      const res = await authFetch('/api/v1/integrations/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ service })
      });
      const data = await res.json();
      if (data.connected) {
        setStatusMessage({ type: 'success', text: `${service}: ${data.status}` });
      } else {
        setStatusMessage({ type: 'error', text: `${service}: ${data.status}` });
      }
    } catch (e) {
      setStatusMessage({ type: 'error', text: `Failed to test connection to ${service}.` });
    } finally {
      setTestingService(null);
    }
  };

  const handleManualSync = async () => {
    setSyncing(true);
    setStatusMessage(null);
    try {
      const res = await authFetch('/api/v1/integrations/sync', { method: 'POST' });
      const data = await res.json();
      if (data.status === 'SUCCESS') {
        setStatusMessage({ type: 'success', text: `Sync complete: ${data.totalSynced ?? 0} open incidents retrieved across active sources.` });
        loadSettings();
      } else {
        setStatusMessage({ type: 'error', text: data.error || 'Sync encountered errors.' });
      }
    } catch (e) {
      setStatusMessage({ type: 'error', text: 'Failed to trigger sync.' });
    } finally {
      setSyncing(false);
    }
  };

  return (
    <>
    <div className="card" style={{ padding: '24px', marginTop: '24px', borderRadius: '12px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Layers size={18} className="text-accent" />
            External ITSM & Bug Tracker Integrations
          </h3>
          <p style={{ margin: '4px 0 0', fontSize: '13px', color: 'var(--text-muted)' }}>
            Configure automatic synchronization and bidirectional work note / status updates for ServiceNow, Freshservice, and Jira.
          </p>
        </div>
        <button
          type="button"
          className="btn-sync"
          onClick={handleManualSync}
          disabled={syncing}
          style={{ display: 'flex', alignItems: 'center', gap: '6px', height: '36px', padding: '0 14px', fontSize: '13px' }}
        >
          <RefreshCw size={14} className={syncing ? 'spin' : ''} />
          {syncing ? 'Syncing…' : 'Sync All Active Now'}
        </button>
      </div>

      {statusMessage && (
        <div style={{
          padding: '10px 14px',
          borderRadius: '8px',
          marginBottom: '16px',
          fontSize: '13px',
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          background: statusMessage.type === 'success' ? 'var(--green-dim, rgba(34,197,94,0.15))' : 'var(--red-dim, rgba(239,68,68,0.15))',
          color: statusMessage.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)',
          border: `1px solid ${statusMessage.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)'}`
        }}>
          {statusMessage.type === 'success' ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
          {statusMessage.text}
        </div>
      )}

      <form onSubmit={handleSave}>
        {/* SYNC DURATION / INTERVAL ROW */}
        <div style={{ padding: '14px', borderRadius: '8px', background: 'var(--surface2, #1e293b)', border: '1px solid var(--border)', marginBottom: '20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: '12px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Clock size={18} style={{ color: 'var(--accent)' }} />
            <div>
              <strong style={{ fontSize: '13px', display: 'block' }}>Automatic Fetch Frequency</strong>
              <span style={{ fontSize: '12px', color: 'var(--text-muted)' }}>Background job polling interval for open incident intake.</span>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <select
              value={settings.syncIntervalHours ?? 1}
              onChange={e => setSettings({ ...settings, syncIntervalHours: Number(e.target.value) })}
              style={{ height: '34px', padding: '0 10px', borderRadius: '6px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontSize: '13px' }}
            >
              <option value={1}>Every 1 Hour</option>
              <option value={2}>Every 2 Hours</option>
              <option value={4}>Every 4 Hours</option>
              <option value={12}>Every 12 Hours</option>
              <option value={24}>Every 24 Hours</option>
            </select>
          </div>
        </div>

        {/* 1. SERVICENOW */}
        <div style={{ padding: '16px', borderRadius: '10px', border: '1px solid var(--border)', marginBottom: '16px', background: 'var(--surface)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span style={{ fontWeight: 700, fontSize: '14px' }}>ServiceNow</span>
              <span style={{ fontSize: '11px', padding: '2px 6px', borderRadius: '4px', background: 'var(--surface2)', color: 'var(--text-muted)' }}>Table API</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12.5px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.serviceNowEnabled !== false}
                  onChange={e => setSettings({ ...settings, serviceNowEnabled: e.target.checked })}
                />
                Enabled
              </label>
              <button
                type="button"
                className="btn-secondary"
                style={{ padding: '3px 10px', fontSize: '11.5px', height: '28px' }}
                onClick={() => handleTest('ServiceNow')}
                disabled={testingService === 'ServiceNow'}
              >
                {testingService === 'ServiceNow' ? 'Testing…' : 'Test Connection'}
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Instance URL</label>
              <input
                type="text"
                placeholder="https://instance.service-now.com"
                value={settings.serviceNowUrl || ''}
                onChange={e => setSettings({ ...settings, serviceNowUrl: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>API Username</label>
              <input
                type="text"
                placeholder="admin"
                value={settings.serviceNowUsername || ''}
                onChange={e => setSettings({ ...settings, serviceNowUsername: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
            <SecretStatus label="Password / API Key" envVar="MCP_SERVICENOW_PASSWORD" set={settings.serviceNowSecretSet} />
          </div>
        </div>

        {/* 2. FRESHSERVICE */}
        <div style={{ padding: '16px', borderRadius: '10px', border: '1px solid var(--border)', marginBottom: '16px', background: 'var(--surface)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span style={{ fontWeight: 700, fontSize: '14px' }}>Freshservice</span>
              <span style={{ fontSize: '11px', padding: '2px 6px', borderRadius: '4px', background: 'var(--surface2)', color: 'var(--text-muted)' }}>v2 REST API</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12.5px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.freshserviceEnabled !== false}
                  onChange={e => setSettings({ ...settings, freshserviceEnabled: e.target.checked })}
                />
                Enabled
              </label>
              <button
                type="button"
                className="btn-secondary"
                style={{ padding: '3px 10px', fontSize: '11.5px', height: '28px' }}
                onClick={() => handleTest('Freshservice')}
                disabled={testingService === 'Freshservice'}
              >
                {testingService === 'Freshservice' ? 'Testing…' : 'Test Connection'}
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: '12px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Domain URL</label>
              <input
                type="text"
                placeholder="https://company.freshservice.com"
                value={settings.freshserviceUrl || ''}
                onChange={e => setSettings({ ...settings, freshserviceUrl: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
            <SecretStatus label="Freshservice API Key" envVar="MCP_FRESHSERVICE_API_KEY" set={settings.freshserviceSecretSet} />
          </div>
        </div>

        {/* 3. JIRA */}
        <div style={{ padding: '16px', borderRadius: '10px', border: '1px solid var(--border)', marginBottom: '16px', background: 'var(--surface)' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <span style={{ fontWeight: 700, fontSize: '14px' }}>Jira Software / Service Management</span>
              <span style={{ fontSize: '11px', padding: '2px 6px', borderRadius: '4px', background: 'var(--surface2)', color: 'var(--text-muted)' }}>Atlassian REST v3</span>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: '6px', fontSize: '12.5px', cursor: 'pointer' }}>
                <input
                  type="checkbox"
                  checked={settings.jiraEnabled !== false}
                  onChange={e => setSettings({ ...settings, jiraEnabled: e.target.checked })}
                />
                Enabled
              </label>
              <button
                type="button"
                className="btn-secondary"
                style={{ padding: '3px 10px', fontSize: '11.5px', height: '28px' }}
                onClick={() => handleTest('Jira')}
                disabled={testingService === 'Jira'}
              >
                {testingService === 'Jira' ? 'Testing…' : 'Test Connection'}
              </button>
            </div>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px' }}>
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Jira Site URL</label>
              <input
                type="text"
                placeholder="https://company.atlassian.net"
                value={settings.jiraUrl || ''}
                onChange={e => setSettings({ ...settings, jiraUrl: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Atlassian Account Email</label>
              <input
                type="email"
                placeholder="ops-lead@company.com"
                value={settings.jiraEmail || ''}
                onChange={e => setSettings({ ...settings, jiraEmail: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
            <SecretStatus label="API Token" envVar="MCP_JIRA_API_TOKEN" set={settings.jiraSecretSet} />
            <div style={{ gridColumn: '1 / -1' }}>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>JQL Filter Query</label>
              <input
                type="text"
                placeholder="statusCategory != Done ORDER BY created DESC"
                value={settings.jiraJql || ''}
                onChange={e => setSettings({ ...settings, jiraJql: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: '16px' }}>
          <button type="submit" className="btn-primary" disabled={loading} style={{ height: '38px', padding: '0 20px', fontSize: '13px' }}>
            {loading ? 'Saving Settings…' : 'Save Integration Settings'}
          </button>
        </div>
      </form>
    </div>
    <NotificationRelay />
    </>
  );
}
