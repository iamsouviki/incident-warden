import React, { useState, useEffect } from 'react';
import { ShieldAlert, Cpu, Share2, ToggleLeft, ToggleRight } from 'lucide-react';

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
  const [apiKey, setApiKey] = useState('');
  const [chatModel, setChatModel] = useState('');
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [ollamaModels, setOllamaModels] = useState<string[]>([]);
  
  // Rules and Thresholds Settings
  const [autoResolveThreshold, setAutoResolveThreshold] = useState('1.00');
  const [hitlThreshold, setHitlThreshold] = useState('0.80');
  const [blastRadiusThreshold, setBlastRadiusThreshold] = useState('0.40');
  
  // ITSM Toggles
  const [servicenowEnabled, setServicenowEnabled] = useState('false');
  const [freshserviceEnabled, setFreshserviceEnabled] = useState('false');

  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

  useEffect(() => {
    fetch('/api/v1/ai/config')
      .then(res => res.json())
      .then(data => {
        if (data.provider) setProvider(data.provider);
        if (data.baseUrl) setBaseUrl(data.baseUrl);
        if (data.apiKey) setApiKey(data.apiKey);
        if (data.chatModel) setChatModel(data.chatModel);
        if (data.embeddingModel) setEmbeddingModel(data.embeddingModel);
        if (data.autoResolveThreshold) setAutoResolveThreshold(data.autoResolveThreshold);
        if (data.hitlThreshold) setHitlThreshold(data.hitlThreshold);
        if (data.blastRadiusThreshold) setBlastRadiusThreshold(data.blastRadiusThreshold);
        if (data.servicenowEnabled) setServicenowEnabled(data.servicenowEnabled);
        if (data.freshserviceEnabled) setFreshserviceEnabled(data.freshserviceEnabled);
      })
      .catch(console.error);
  }, []);

  useEffect(() => {
    if (provider === 'ollama' && baseUrl) {
      fetch(`/api/v1/ai/config/ollama-models?url=${encodeURIComponent(baseUrl)}`)
        .then(res => res.json())
        .then(data => {
          if (Array.isArray(data)) {
            setOllamaModels(data);
          }
        })
        .catch(console.error);
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
      const res = await fetch('/api/v1/ai/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ 
          provider, 
          baseUrl, 
          apiKey, 
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

          {/* API Key */}
          {provider !== 'ollama' && (
            <div style={{ marginBottom: '20px' }}>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                API Key / Token
              </label>
              <input
                type="password"
                placeholder="Enter API Key"
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
              />
            </div>
          )}

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
