import React, { useState, useEffect } from 'react';
import { ShieldAlert, Cpu, Share2, ToggleLeft, ToggleRight, Mail, Send, Zap } from 'lucide-react';
import { authFetch } from '../services/api';

const PROVIDERS = [
  { id: 'ollama', name: 'Ollama (Local)', defaultUrl: 'http://localhost:11434' },
  { id: 'openai', name: 'OpenAI', defaultUrl: 'https://api.openai.com/v1' },
  { id: 'gemini', name: 'Google Gemini', defaultUrl: 'https://generativelanguage.googleapis.com/v1beta/openai' },
  { id: 'groq', name: 'Groq', defaultUrl: 'https://api.groq.com/openai/v1' },
  { id: 'custom', name: 'Custom OpenAI-Compatible', defaultUrl: '' }
];

const AiConfigPage: React.FC = () => {
  // AI Settings
  const [provider, setProvider] = useState('ollama');
  const [baseUrl, setBaseUrl] = useState('http://localhost:11434');
  // The provider key is not editable here. It is read from the MCP_LLM_API_KEY
  // environment variable on the server; this page only learns whether one is set.
  const [apiKeyPresent, setApiKeyPresent] = useState(false);
  const [chatModel, setChatModel] = useState('');
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [ollamaModels, setOllamaModels] = useState<string[]>([]);
  const [modelLoadError, setModelLoadError] = useState('');
  
  // Rules and Thresholds Settings
  const [autoResolveThreshold, setAutoResolveThreshold] = useState('1.00');
  const [hitlThreshold, setHitlThreshold] = useState('0.80');
  const [blastRadiusThreshold, setBlastRadiusThreshold] = useState('0.40');
  
  // ITSM Toggles
  const [servicenowEnabled, setServicenowEnabled] = useState('false');
  const [freshserviceEnabled, setFreshserviceEnabled] = useState('false');

  // Notification transport. Saved separately from the AI settings below because it is a
  // different subsystem with its own validation — and because a bad SMTP host must not
  // block saving a model change.
  const [notifyEnabled, setNotifyEnabled] = useState(false);
  const [notifyHost, setNotifyHost] = useState('');
  const [notifyPort, setNotifyPort] = useState('25');
  const [notifyFrom, setNotifyFrom] = useState('');
  const [notifySaving, setNotifySaving] = useState(false);
  const [testTo, setTestTo] = useState('');

  // The unattended-remediation kill switch. Saved the moment it is flipped rather than on a
  // Save button: the reason anyone touches this control is to stop the platform acting.
  const [autoRun, setAutoRun] = useState(false);
  const [autoRunSaving, setAutoRunSaving] = useState(false);

  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

  useEffect(() => {
    authFetch('/api/v1/ai/config')
      .then(res => res.json())
      .then(data => {
        if (data.provider) setProvider(data.provider);
        if (data.baseUrl) setBaseUrl(data.baseUrl);
        setApiKeyPresent(Boolean(data.apiKeyPresent));
        if (data.chatModel) setChatModel(data.chatModel);
        if (data.embeddingModel) setEmbeddingModel(data.embeddingModel);
        if (data.autoResolveThreshold) setAutoResolveThreshold(data.autoResolveThreshold);
        if (data.hitlThreshold) setHitlThreshold(data.hitlThreshold);
        if (data.blastRadiusThreshold) setBlastRadiusThreshold(data.blastRadiusThreshold);
        if (data.servicenowEnabled) setServicenowEnabled(data.servicenowEnabled);
        if (data.freshserviceEnabled) setFreshserviceEnabled(data.freshserviceEnabled);
      })
      .catch(console.error);

    authFetch('/api/v1/ai/config/notifications')
      .then(res => res.json())
      .then(data => {
        setNotifyEnabled(Boolean(data.enabled));
        setNotifyHost(data.host || '');
        setNotifyPort(String(data.port ?? 25));
        setNotifyFrom(data.from || '');
      })
      .catch(console.error);

    authFetch('/api/v1/ai/config/autorun')
      .then(res => res.json())
      .then(data => setAutoRun(Boolean(data.enabled)))
      .catch(console.error);
  }, []);

  useEffect(() => {
    if (provider === 'ollama' && baseUrl) {
      setModelLoadError('');
      authFetch(`/api/v1/ai/config/ollama-models?url=${encodeURIComponent(baseUrl)}`)
        .then(res => {
          if (!res.ok) throw new Error(`Model discovery failed (${res.status})`);
          return res.json();
        })
        .then(data => {
          if (!Array.isArray(data)) throw new Error('Model discovery returned an invalid response');
          setOllamaModels(data);
        })
        .catch(error => {
          console.error(error);
          setOllamaModels([]);
          setModelLoadError('Could not load local Ollama models. Confirm the backend can reach the Base API URL.');
        });
    }
  }, [provider, baseUrl]);

  const handleProviderChange = (newProvider: string) => {
    setProvider(newProvider);
    const selected = PROVIDERS.find(p => p.id === newProvider);
    if (selected && selected.defaultUrl) {
      setBaseUrl(selected.defaultUrl);
    }
  };

  const handleSave = async () => {
    setLoading(true);
    setMessage(null);
    try {
      const res = await authFetch('/api/v1/ai/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          provider, 
          baseUrl, 
          chatModel, 
          embeddingModel,
          autoResolveThreshold,
          hitlThreshold,
          blastRadiusThreshold,
          servicenowEnabled,
          freshserviceEnabled
        })
      });
      const data = await res.json();
      if (res.ok) {
        setMessage({ type: 'success', text: data.message });
      } else {
        setMessage({ type: 'error', text: data.error || 'Failed to update config' });
      }
    } catch (e) {
      setMessage({ type: 'error', text: 'Network error' });
    } finally {
      setLoading(false);
    }
  };

  const saveNotifications = async () => {
    setNotifySaving(true);
    setMessage(null);
    try {
      const res = await authFetch('/api/v1/ai/config/notifications', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: String(notifyEnabled), host: notifyHost, port: notifyPort, from: notifyFrom })
      });
      const data = await res.json();
      setMessage(res.ok
        ? { type: 'success', text: data.message }
        : { type: 'error', text: data.error || 'Failed to save notification settings' });
    } catch {
      setMessage({ type: 'error', text: 'Network error' });
    } finally {
      setNotifySaving(false);
    }
  };

  const saveAutoRun = async () => {
    const next = !autoRun;
    setAutoRunSaving(true);
    setMessage(null);
    try {
      const res = await authFetch('/api/v1/ai/config/autorun', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ enabled: String(next) })
      });
      const data = await res.json();
      if (res.ok) {
        // Taken from the response, not assumed: the switch must show what the server stored.
        setAutoRun(Boolean(data.enabled));
        setMessage({ type: 'success', text: data.message });
      } else {
        setMessage({ type: 'error', text: data.error || 'Failed to change unattended remediation' });
      }
    } catch {
      setMessage({ type: 'error', text: 'Network error' });
    } finally {
      setAutoRunSaving(false);
    }
  };

  const sendTestMail = async () => {
    setMessage(null);
    try {
      const res = await authFetch('/api/v1/ai/config/notifications/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ to: testTo })
      });
      const data = await res.json();
      setMessage(res.ok
        ? { type: 'success', text: data.message }
        : { type: 'error', text: data.error || 'Test message failed' });
    } catch {
      setMessage({ type: 'error', text: 'Network error' });
    }
  };

  return (
    <div className="content" style={{ maxWidth: 900, margin: '20px auto', display: 'flex', flexDirection: 'column', gap: '24px' }}>
      
      {message && (
        <div style={{
          padding: '14px 20px', borderRadius: '8px',
          background: message.type === 'success' ? 'rgba(48,217,156,0.1)' : 'rgba(220,38,38,0.1)',
          color: message.type === 'success' ? 'var(--green)' : 'var(--red)',
          border: `1px solid ${message.type === 'success' ? 'var(--green-dim)' : 'rgba(220,38,38,0.3)'}`,
          fontWeight: 600, fontSize: '14px'
        }}>
          {message.text}
        </div>
      )}

      {/* SECTION 1: AI CORE ENGINE CONFIGURATION */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Cpu size={18} style={{ color: 'var(--accent)' }} />
          <div className="card-title">AI Core Engine Settings</div>
        </div>
        <div style={{ padding: '24px' }}>
          
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '20px' }}>
            {/* Provider Select */}
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                AI Provider
              </label>
              <select
                value={provider}
                onChange={e => handleProviderChange(e.target.value)}
                style={{ appearance: 'auto' }}
              >
                {PROVIDERS.map(p => (
                  <option key={p.id} value={p.id}>{p.name}</option>
                ))}
              </select>
            </div>

            {/* Base URL */}
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                Base API URL
              </label>
              <input
                type="text"
                placeholder="e.g. http://localhost:11434"
                value={baseUrl}
                onChange={e => setBaseUrl(e.target.value)}
              />
            </div>
          </div>

          {/* API key status. Read-only by design: a credential typed into a browser form
              gets stored somewhere, and the somewhere was a plaintext database column. */}
          {provider !== 'ollama' && (
            <div style={{ marginBottom: '20px' }}>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                API Key / Token
              </label>
              <p style={{ margin: 0, padding: '10px 12px', border: '1px solid var(--border)', borderRadius: '6px', fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                {apiKeyPresent
                  ? 'A key is configured in the server environment. It is never displayed or stored in the database.'
                  : 'No key configured. Set MCP_LLM_API_KEY in the server environment and restart — this provider will fail model calls until you do.'}
              </p>
            </div>
          )}

          {modelLoadError && <p style={{ margin: '0 0 12px', color: 'var(--red)', fontSize: '12px' }}>{modelLoadError}</p>}
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', marginBottom: '20px' }}>
            {/* Chat Model Name */}
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                Chat Model
              </label>
              {provider === 'ollama' ? (
                <select
                  value={chatModel}
                  onChange={e => setChatModel(e.target.value)}
                  style={{ appearance: 'auto' }}
                >
                  <option value="" disabled>Select model...</option>
                  {ollamaModels.map(m => (
                    <option key={m} value={m}>{m}</option>
                  ))}
                </select>
              ) : (
                <input
                  type="text"
                  placeholder="e.g. gpt-4o"
                  value={chatModel}
                  onChange={e => setChatModel(e.target.value)}
                />
              )}
            </div>

            {/* Embedding Model Name */}
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                Embedding Model
              </label>
              {provider === 'ollama' ? (
                <select
                  value={embeddingModel}
                  onChange={e => setEmbeddingModel(e.target.value)}
                  style={{ appearance: 'auto' }}
                >
                  <option value="" disabled>Select embedding model...</option>
                  {ollamaModels.map(m => (
                    <option key={m} value={m}>{m}</option>
                  ))}
                </select>
              ) : (
                <input
                  type="text"
                  placeholder="e.g. text-embedding-3-small"
                  value={embeddingModel}
                  onChange={e => setEmbeddingModel(e.target.value)}
                />
              )}
            </div>
          </div>
        </div>
      </div>

      {/* SECTION 2: AI CONFIDENCE THRESHOLDS & BOUNDS */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <ShieldAlert size={18} style={{ color: 'var(--michaels-red)' }} />
          <div className="card-title">AI Incident Actions & Confidence Thresholds</div>
        </div>
        <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '24px' }}>
          
          {/* Auto Resolve Slider */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
              <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text)' }}>
                Auto-Resolve Confidence Threshold
              </span>
              <span style={{ fontFamily: 'var(--mono)', fontSize: '13px', fontWeight: 'bold', color: 'var(--accent)' }}>
                {Math.round(parseFloat(autoResolveThreshold) * 100)}%
              </span>
            </div>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' }}>
              Minimum AI classification confidence required to automatically resolve a support ticket without technician approval.
            </p>
            <input
              type="range"
              min="0.0"
              max="1.0"
              step="0.05"
              value={autoResolveThreshold}
              onChange={e => setAutoResolveThreshold(e.target.value)}
              style={{ width: '100%', height: '6px', background: 'var(--surface3)', borderRadius: '3px', outline: 'none' }}
            />
          </div>

          {/* HITL Slider */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
              <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text)' }}>
                HITL (Human-in-the-Loop) Assistance Threshold
              </span>
              <span style={{ fontFamily: 'var(--mono)', fontSize: '13px', fontWeight: 'bold', color: 'var(--accent)' }}>
                {Math.round(parseFloat(hitlThreshold) * 100)}%
              </span>
            </div>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' }}>
              Confidence threshold below which the platform prompts support technicians with interactive step-by-step suggestions.
            </p>
            <input
              type="range"
              min="0.0"
              max="1.0"
              step="0.05"
              value={hitlThreshold}
              onChange={e => setHitlThreshold(e.target.value)}
              style={{ width: '100%', height: '6px', background: 'var(--surface3)', borderRadius: '3px', outline: 'none' }}
            />
          </div>

          {/* Blast Radius Slider */}
          <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '8px' }}>
              <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text)' }}>
                Maximum Allowed Action Blast Radius
              </span>
              <span style={{ fontFamily: 'var(--mono)', fontSize: '13px', fontWeight: 'bold', color: 'var(--accent)' }}>
                {Math.round(parseFloat(blastRadiusThreshold) * 100)}%
              </span>
            </div>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' }}>
              Upper boundary of estimated system risk. Remediation actions with risk assessment values higher than this require senior admin bypass.
            </p>
            <input
              type="range"
              min="0.0"
              max="1.0"
              step="0.05"
              value={blastRadiusThreshold}
              onChange={e => setBlastRadiusThreshold(e.target.value)}
              style={{ width: '100%', height: '6px', background: 'var(--surface3)', borderRadius: '3px', outline: 'none' }}
            />
          </div>
        </div>
      </div>

      {/* SECTION 3: ITSM INTEGRATIONS SYNC */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Share2 size={18} style={{ color: 'var(--purple)' }} />
          <div className="card-title">External ITSM Integration Sync</div>
        </div>
        <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '20px' }}>
          
          {/* ServiceNow Sync Toggle */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <div>
              <h4 style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)', marginBottom: '4px' }}>Sync ServiceNow Incidents</h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: 0 }}>
                Periodically fetch new tickets and push resolution statuses back to your ServiceNow instance.
              </p>
            </div>
            <button
              onClick={() => setServicenowEnabled(servicenowEnabled === 'true' ? 'false' : 'true')}
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', color: servicenowEnabled === 'true' ? 'var(--green)' : 'var(--text-muted)' }}
            >
              {servicenowEnabled === 'true' ? <ToggleRight size={40} /> : <ToggleLeft size={40} />}
            </button>
          </div>

          {/* Freshservice Sync Toggle */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <div>
              <h4 style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)', marginBottom: '4px' }}>Sync Freshservice Tickets</h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: 0 }}>
                Periodically synchronize active work logs, custom fields, and asset tracking with Freshservice.
              </p>
            </div>
            <button
              onClick={() => setFreshserviceEnabled(freshserviceEnabled === 'true' ? 'false' : 'true')}
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', color: freshserviceEnabled === 'true' ? 'var(--green)' : 'var(--text-muted)' }}
            >
              {freshserviceEnabled === 'true' ? <ToggleRight size={40} /> : <ToggleLeft size={40} />}
            </button>
          </div>

        </div>
      </div>

      {/* SECTION 4: NOTIFICATION DELIVERY */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Mail size={18} style={{ color: 'var(--green)' }} />
          <div className="card-title">Notification Delivery</div>
        </div>
        <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '20px' }}>

          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
            Every incident update, and every action the platform takes without waiting for approval,
            is emailed to the person who reported the incident, the assignee, and the assigned group's
            mail id (set on the <strong>Teams</strong> page). There is no recipient list to maintain —
            recipients come from the incident itself.
            <br />
            The relay is contacted unauthenticated on your internal network: no username or password is
            requested, stored, or sent.
          </p>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <div>
              <h4 style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)', marginBottom: '4px' }}>Send Notification Email</h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: 0 }}>
                Off by default. While off, nothing is sent and no relay is contacted.
              </p>
            </div>
            <button
              onClick={() => setNotifyEnabled(!notifyEnabled)}
              style={{ background: 'transparent', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', color: notifyEnabled ? 'var(--green)' : 'var(--text-muted)' }}
            >
              {notifyEnabled ? <ToggleRight size={40} /> : <ToggleLeft size={40} />}
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: '20px' }}>
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                SMTP Relay Host
              </label>
              <input type="text" placeholder="e.g. smtp.internal.company.com" value={notifyHost} onChange={e => setNotifyHost(e.target.value)} />
            </div>
            <div>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                Port
              </label>
              <input type="number" min={1} max={65535} placeholder="25" value={notifyPort} onChange={e => setNotifyPort(e.target.value)} />
            </div>
          </div>

          <div>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              From Address
            </label>
            <input type="email" placeholder="incident-automation@company.com" value={notifyFrom} onChange={e => setNotifyFrom(e.target.value)} />
          </div>

          <div style={{ display: 'flex', gap: '12px', alignItems: 'flex-end' }}>
            <div style={{ flex: 1 }}>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                Send a test message to
              </label>
              <input type="email" placeholder="you@company.com" value={testTo} onChange={e => setTestTo(e.target.value)} />
            </div>
            <button
              onClick={sendTestMail}
              disabled={!testTo}
              style={{
                display: 'flex', alignItems: 'center', gap: '8px', height: '38px', padding: '0 16px',
                background: 'var(--surface2)', color: 'var(--text)', border: '1px solid var(--border)',
                borderRadius: '6px', fontSize: '13px', fontWeight: 600,
                cursor: testTo ? 'pointer' : 'not-allowed', opacity: testTo ? 1 : 0.5
              }}
            >
              <Send size={14} /> Test
            </button>
          </div>

          <button
            onClick={saveNotifications}
            disabled={notifySaving}
            className="btn-primary"
            style={{ padding: '12px 20px', border: 'none', fontSize: '14px', cursor: notifySaving ? 'not-allowed' : 'pointer' }}
          >
            {notifySaving ? 'Saving...' : 'Save Notification Settings'}
          </button>
        </div>
      </div>

      {/* SECTION 5: UNATTENDED REMEDIATION */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Zap size={18} style={{ color: autoRun ? 'var(--amber, #f59e0b)' : 'var(--text-muted)' }} />
          <div className="card-title">Unattended Remediation</div>
        </div>
        <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '20px' }}>

          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
            When a new incident closely matches one this tenant already resolved, the platform can
            repeat that incident's saved tool immediately instead of asking for the same approval twice,
            then email everyone on the incident. It only does so when <em>all</em> of the following hold:
          </p>

          <ul style={{ margin: 0, paddingLeft: '18px', fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.9 }}>
            <li>a past incident here was fixed by a plan a <strong>human approved</strong>, and that run succeeded</li>
            <li>that plan cites an <strong>approved SOP</strong> and its script came from the SOP, never from the model's own knowledge</li>
            <li>at least 60% of the new incident's wording, and 3 or more distinct terms, are covered by the past one</li>
            <li>the saved tool only <strong>reads</strong> or <strong>restarts</strong> — cache flushes and job reruns always wait for a person</li>
            <li>the script passes a fresh guardrail scan with no findings, and the action passes the guardrail boundary again</li>
            <li>the incident is not P1, and has no plan already awaiting approval</li>
          </ul>

          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px', background: 'var(--surface2)', borderRadius: '8px', border: `1px solid ${autoRun ? 'var(--amber, #f59e0b)' : 'var(--border)'}` }}>
            <div>
              <h4 style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)', marginBottom: '4px' }}>
                Act without approval on proven precedent
              </h4>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)', margin: 0 }}>
                {autoRun
                  ? 'ON. Matching incidents are remediated at creation time and the result is emailed. Everything else still waits for approval.'
                  : 'OFF. Every action waits for a human approval in the review queue.'}
              </p>
            </div>
            <button
              onClick={saveAutoRun}
              disabled={autoRunSaving}
              style={{ background: 'transparent', border: 'none', cursor: autoRunSaving ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', color: autoRun ? 'var(--amber, #f59e0b)' : 'var(--text-muted)' }}
            >
              {autoRun ? <ToggleRight size={40} /> : <ToggleLeft size={40} />}
            </button>
          </div>

          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)' }}>
            This switch takes effect on the next incident logged — there is no cycle to wait for. Every
            unattended run is written to the audit trail with the past incident it inherited its
            approval from.
          </p>
        </div>
      </div>

      {/* SAVE BUTTON */}
      <button
        onClick={handleSave}
        disabled={loading || !chatModel || !embeddingModel || !baseUrl}
        className="btn-primary"
        style={{
          padding: '16px 24px', border: 'none', fontSize: '15px',
          cursor: (loading || !chatModel || !embeddingModel || !baseUrl) ? 'not-allowed' : 'pointer',
          width: '100%', textTransform: 'uppercase', letterSpacing: '0.5px'
        }}
      >
        {loading ? 'Persisting Configuration...' : 'Save Configuration & Thresholds'}
      </button>

    </div>
  );
};

export default AiConfigPage;
