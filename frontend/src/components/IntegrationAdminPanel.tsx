import React, { useEffect, useState } from 'react';
import { authFetch } from '../services/api';
import { Layers, RefreshCw, CheckCircle, AlertCircle, Clock } from 'lucide-react';

export default function IntegrationAdminPanel() {
  const [settings, setSettings] = useState<{
    serviceNowEnabled?: boolean;
    serviceNowUrl?: string;
    serviceNowUsername?: string;
    serviceNowPassword?: string;
    freshserviceEnabled?: boolean;
    freshserviceUrl?: string;
    freshserviceApiKey?: string;
    jiraEnabled?: boolean;
    jiraUrl?: string;
    jiraEmail?: string;
    jiraApiToken?: string;
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
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Password / API Key</label>
              <input
                type="password"
                placeholder="••••••••••••"
                value={settings.serviceNowPassword || ''}
                onChange={e => setSettings({ ...settings, serviceNowPassword: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
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
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Freshservice API Key</label>
              <input
                type="password"
                placeholder="••••••••••••"
                value={settings.freshserviceApiKey || ''}
                onChange={e => setSettings({ ...settings, freshserviceApiKey: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
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
            <div>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>API Token</label>
              <input
                type="password"
                placeholder="••••••••••••"
                value={settings.jiraApiToken || ''}
                onChange={e => setSettings({ ...settings, jiraApiToken: e.target.value })}
                style={{ width: '100%', height: '34px', padding: '0 8px', fontSize: '12.5px' }}
              />
            </div>
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
  );
}
