import React, { useState, useEffect } from 'react';
import { Cpu } from 'lucide-react';
import { authFetch } from '../services/api';
import UserAdminPanel from '../components/UserAdminPanel';
import IntegrationAdminPanel from '../components/IntegrationAdminPanel';

const PROVIDERS = [
  { id: 'ollama', name: 'Ollama (Local)', defaultUrl: 'http://localhost:11434' },
  { id: 'openai', name: 'OpenAI', defaultUrl: 'https://api.openai.com/v1' },
  { id: 'gemini', name: 'Google Gemini', defaultUrl: 'https://generativelanguage.googleapis.com/v1beta/openai' },
  { id: 'groq', name: 'Groq', defaultUrl: 'https://api.groq.com/openai/v1' },
  { id: 'custom', name: 'Custom OpenAI-Compatible', defaultUrl: '' }
];

/**
 * Two things an admin configures: which model answers, and who has an account.
 *
 * This page used to carry threshold sliders, ITSM sync toggles, an SMTP form and two access
 * switches. They are gone. The sliders tuned numbers that decide whether a plan is offered —
 * but nothing runs without a person reading the script and approving it, so a percentage was
 * never the control anyone actually used. The ITSM toggles wrote config rows no code read. And
 * the access switches let a single operator turn off the requirement for a second reviewer,
 * which is the one guarantee this platform makes; the answer to a one-person workspace is a
 * second account, which is what the panel below is for.
 */
const AiConfigPage: React.FC = () => {
  const [provider, setProvider] = useState('ollama');
  const [baseUrl, setBaseUrl] = useState('http://localhost:11434');
  // The provider key is not editable here. It is read from the MCP_LLM_API_KEY
  // environment variable on the server; this page only learns whether one is set.
  const [apiKeyPresent, setApiKeyPresent] = useState(false);
  const [chatModel, setChatModel] = useState('');
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [ollamaModels, setOllamaModels] = useState<string[]>([]);
  const [modelLoadError, setModelLoadError] = useState('');

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
      })
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
        body: JSON.stringify({ provider, baseUrl, chatModel, embeddingModel })
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

      {/* AI CORE ENGINE CONFIGURATION */}
      <div className="card">
        <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <Cpu size={18} style={{ color: 'var(--accent)' }} />
          <div className="card-title">AI Core Engine Settings</div>
        </div>
        <div style={{ padding: '24px' }}>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '20px', marginBottom: '20px' }}>
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
                  : 'No key configured. Set LLM_API_KEY in the server environment and restart — this provider will fail model calls until you do.'}
              </p>
            </div>
          )}

          {modelLoadError && <p style={{ margin: '0 0 12px', color: 'var(--red)', fontSize: '12px' }}>{modelLoadError}</p>}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '20px', marginBottom: '20px' }}>
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

          <button
            onClick={handleSave}
            disabled={loading || !chatModel || !embeddingModel || !baseUrl}
            className="btn-primary"
            style={{
              padding: '14px 24px', border: 'none', fontSize: '14px',
              cursor: (loading || !chatModel || !embeddingModel || !baseUrl) ? 'not-allowed' : 'pointer',
              width: '100%', textTransform: 'uppercase', letterSpacing: '0.5px'
            }}
          >
            {loading ? 'Persisting Configuration...' : 'Save AI Settings'}
          </button>
        </div>
      </div>

      {/* ACCOUNTS & ACCESS */}
      <UserAdminPanel />

      {/* EXTERNAL ITSM & BUG TRACKER INTEGRATIONS */}
      <IntegrationAdminPanel />

    </div>
  );
};

export default AiConfigPage;
