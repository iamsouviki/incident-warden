import React, { useState, useEffect } from 'react';

const CHAT_MODELS = [
  'qwen2.5-coder:latest',
  'qwen2.5-coder:14b',
  'qwen2.5-coder:3b',
  'qwen3.5:9b',
  'gemma4:latest'
];

const EMBEDDING_MODELS = [
  'nomic-embed-text',
  'nomic-embed-text:latest',
  'mxbai-embed-large',
  'all-minilm'
];

const AiConfigPage: React.FC = () => {
  const [chatModel, setChatModel] = useState('');
  const [embeddingModel, setEmbeddingModel] = useState('');
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

  useEffect(() => {
    fetch('/api/v1/ai/config')
      .then(res => res.json())
      .then(data => {
        if (data.chatModel) setChatModel(data.chatModel);
        if (data.embeddingModel) setEmbeddingModel(data.embeddingModel);
      })
      .catch(console.error);
  }, []);

  const handleSave = async () => {
    setLoading(true);
    setMessage(null);
    try {
      const res = await fetch('/api/v1/ai/config', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chatModel, embeddingModel })
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

          <div style={{ marginBottom: '24px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              Generative Chat Model
            </label>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' }}>
              Select the local Ollama model to use for answering questions and generating responses.
            </p>
            <select
              value={chatModel}
              onChange={e => setChatModel(e.target.value)}
              style={{
                width: '100%', padding: '12px', borderRadius: '6px',
                border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                outline: 'none', appearance: 'auto'
              }}
            >
              <option value="" disabled>Select a chat model...</option>
              {CHAT_MODELS.map(m => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </div>

          <div style={{ marginBottom: '24px' }}>
            <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>
              Embedding Model
            </label>
            <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '12px' }}>
              Select the local Ollama embedding model. Note: Changing this requires re-ingesting your SOPs to match the vector database dimensions.
            </p>
            <select
              value={embeddingModel}
              onChange={e => setEmbeddingModel(e.target.value)}
              style={{
                width: '100%', padding: '12px', borderRadius: '6px',
                border: '1px solid var(--border)', background: 'var(--surface2)', color: 'var(--text)',
                outline: 'none', appearance: 'auto'
              }}
            >
              <option value="" disabled>Select an embedding model...</option>
              {EMBEDDING_MODELS.map(m => (
                <option key={m} value={m}>{m}</option>
              ))}
            </select>
          </div>

          <button
            onClick={handleSave}
            disabled={loading || !chatModel || !embeddingModel}
            style={{ padding: '12px 24px', background: 'var(--blue)', color: 'white', border: 'none', borderRadius: '6px', cursor: (loading || !chatModel || !embeddingModel) ? 'not-allowed' : 'pointer', fontWeight: 'bold', width: '100%' }}
          >
            {loading ? 'Saving...' : 'Save Configuration'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default AiConfigPage;
