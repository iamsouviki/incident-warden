import React, { useState, useEffect } from 'react';

const PROVIDERS = [
  { id: 'ollama', name: 'Ollama (Local)', defaultUrl: 'http://localhost:11434' },
  { id: 'openai', name: 'OpenAI', defaultUrl: 'https://api.openai.com/v1' },
  { id: 'groq', name: 'Groq', defaultUrl: 'https://api.groq.com/openai/v1' },
  { id: 'custom', name: 'Custom OpenAI-Compatible', defaultUrl: '' }
];

const AiConfigPage: React.FC = () => {
  const [provider, setProvider] = useState('ollama');
  const [baseUrl, setBaseUrl] = useState('http://localhost:11434');
  const [apiKey, setApiKey] = useState('');
  const [chatModel, setChatModel] = useState('');
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [ollamaModels, setOllamaModels] = useState<string[]>([]);
  
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
        body: JSON.stringify({ provider, baseUrl, apiKey, chatModel, embeddingModel })
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
    <div className="content" style={{ maxWidth: 800, margin: '40px auto' }}>
      <div className="card">
        <div className="card-header">
          <div className="card-title">AI Configuration</div>
        </div>
        <div style={{ padding: '24px' }}>
          {message && (
            <div style={{
              padding: '12px', marginBottom: '16px', borderRadius: '6px',
              background: message.type === 'success' ? 'rgba(48,217,156,0.1)' : 'rgba(220,38,38,0.1)',
              color: message.type === 'success' ? 'var(--green)' : 'var(--red)',
              border: `1px solid ${message.type === 'success' ? 'var(--green-dim)' : 'rgba(220,38,38,0.3)'}`
            }}>
              {message.text}
            </div>
          )}

          {/* Provider Select */}
          <div style={{ marginBottom: '20px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              AI Provider
            </label>
            <select
              value={provider}
              onChange={e => handleProviderChange(e.target.value)}
              style={{
                width: '100%', padding: '12px', borderRadius: '6px',
                border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                outline: 'none', appearance: 'auto'
              }}
            >
              {PROVIDERS.map(p => (
                <option key={p.id} value={p.id}>{p.name}</option>
              ))}
            </select>
          </div>

          {/* Base URL */}
          <div style={{ marginBottom: '20px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              Base API URL
            </label>
            <input
              type="text"
              placeholder="e.g. http://localhost:11434 or https://api.openai.com/v1"
              value={baseUrl}
              onChange={e => setBaseUrl(e.target.value)}
              style={{
                width: '100%', padding: '12px', borderRadius: '6px',
                border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                outline: 'none', boxSizing: 'border-box'
              }}
            />
          </div>

          {/* API Key (Show for OpenAI, Groq, Custom) */}
          {provider !== 'ollama' && (
            <div style={{ marginBottom: '20px' }}>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
                API Key / Token
              </label>
              <input
                type="password"
                placeholder="Enter API Key / Token"
                value={apiKey}
                onChange={e => setApiKey(e.target.value)}
                style={{
                  width: '100%', padding: '12px', borderRadius: '6px',
                  border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                  outline: 'none', boxSizing: 'border-box'
                }}
              />
            </div>
          )}

          {/* Chat Model Name */}
          <div style={{ marginBottom: '20px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              Chat / Generation Model
            </label>
            {provider === 'ollama' ? (
              <select
                value={chatModel}
                onChange={e => setChatModel(e.target.value)}
                style={{
                  width: '100%', padding: '12px', borderRadius: '6px',
                  border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                  outline: 'none', appearance: 'auto'
                }}
              >
                <option value="" disabled>Select a local model...</option>
                {ollamaModels.map(m => (
                  <option key={m} value={m}>{m}</option>
                ))}
              </select>
            ) : (
              <input
                type="text"
                placeholder="e.g. gpt-4o, llama3-70b-8192"
                value={chatModel}
                onChange={e => setChatModel(e.target.value)}
                style={{
                  width: '100%', padding: '12px', borderRadius: '6px',
                  border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                  outline: 'none', boxSizing: 'border-box'
                }}
              />
            )}
          </div>

          {/* Embedding Model Name */}
          <div style={{ marginBottom: '24px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              Embedding Model
            </label>
            {provider === 'ollama' ? (
              <select
                value={embeddingModel}
                onChange={e => setEmbeddingModel(e.target.value)}
                style={{
                  width: '100%', padding: '12px', borderRadius: '6px',
                  border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                  outline: 'none', appearance: 'auto'
                }}
              >
                <option value="" disabled>Select a local embedding model...</option>
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
                style={{
                  width: '100%', padding: '12px', borderRadius: '6px',
                  border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                  outline: 'none', boxSizing: 'border-box'
                }}
              />
            )}
            <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '6px', lineHeight: '1.4' }}>
              ⚠️ Changing embedding model requires re-ingesting your SOPs to match the vector database dimensions.
            </p>
          </div>

          <button
            onClick={handleSave}
            disabled={loading || !chatModel || !embeddingModel || !baseUrl}
            style={{
              padding: '12px 24px', background: 'var(--blue)', color: 'white', border: 'none',
              borderRadius: '6px', cursor: (loading || !chatModel || !embeddingModel || !baseUrl) ? 'not-allowed' : 'pointer',
              fontWeight: 'bold', width: '100%'
            }}
          >
            {loading ? 'Saving...' : 'Save Configuration'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default AiConfigPage;
