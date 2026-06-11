import React, { useState, useRef } from 'react';

const RagIngestPage: React.FC = () => {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [loading, setLoading] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleIngest = async () => {
    if (!file && (!title.trim() || !description.trim())) {
      setMessage({ type: 'error', text: 'Either select a file OR provide a Title + Description.' });
      return;
    }
    setLoading(true);
    setMessage(null);
    try {
      let response;
      if (file) {
        // File Upload Path
        const formData = new FormData();
        formData.append('file', file);
        if (title.trim()) formData.append('title', title.trim());

        response = await fetch('/api/v1/rag/upload', {
          method: 'POST',
          body: formData
        });
      } else {
        // Manual Text Path
        response = await fetch('/api/v1/rag/ingest', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ title, description })
        });
      }

      const data = await response.json();
      if (response.ok) {
        setMessage({ type: 'success', text: data.message || 'Successfully ingested!' });
        setTitle('');
        setDescription('');
        setFile(null);
        if (fileInputRef.current) fileInputRef.current.value = '';
      } else {
        setMessage({ type: 'error', text: data.error || 'Failed to ingest.' });
      }
    } catch (e) {
      setMessage({ type: 'error', text: 'Network error.' });
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="content" style={{ maxWidth: 800, margin: '40px auto' }}>
      <div className="card">
        <div className="card-header">
          <div className="card-title">Ingest SOP to RAG</div>
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

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
            
            {/* File Upload Section */}
            <div style={{ padding: '20px', border: '1px dashed var(--border)', borderRadius: '6px', background: 'var(--surface2)' }}>
              <h3 style={{ margin: '0 0 16px 0', fontSize: '14px', color: 'var(--text)' }}>Upload Document</h3>
              <p style={{ fontSize: '12px', color: 'var(--text-muted)', marginBottom: '16px' }}>
                Supports .pdf, .docx, .txt, .xlsx, .csv
              </p>
              <input
                type="file"
                ref={fileInputRef}
                accept=".pdf,.docx,.txt,.xlsx,.csv"
                onChange={e => setFile(e.target.files?.[0] || null)}
                style={{ width: '100%', color: 'var(--text-muted)' }}
              />
              {file && <p style={{ marginTop: '12px', fontSize: '12px', color: 'var(--blue)' }}>Selected: {file.name}</p>}
            </div>

            {/* Manual Entry Section */}
            <div style={{ padding: '20px', border: '1px solid var(--border)', borderRadius: '6px', background: 'var(--surface2)', opacity: file ? 0.5 : 1, pointerEvents: file ? 'none' : 'auto' }}>
              <h3 style={{ margin: '0 0 16px 0', fontSize: '14px', color: 'var(--text)' }}>Or Paste Manual Text</h3>
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>Title</label>
              <input
                type="text"
                value={title}
                onChange={e => setTitle(e.target.value)}
                style={{ width: '100%', padding: '10px', marginBottom: '16px', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)' }}
                placeholder="Optional Title (for file or text)"
              />
              
              <label style={{ display: 'block', marginBottom: '8px', color: 'var(--text-muted)', fontSize: '12px', fontWeight: 'bold' }}>Content</label>
              <textarea
                value={description}
                onChange={e => setDescription(e.target.value)}
                rows={5}
                style={{ width: '100%', padding: '10px', borderRadius: '4px', border: '1px solid var(--border)', background: 'var(--surface)', color: 'var(--text)', fontFamily: 'var(--mono)', resize: 'vertical' }}
                placeholder="Pasting text will be disabled if a file is selected."
              />
            </div>
          </div>

          <button
            onClick={handleIngest}
            disabled={loading}
            style={{ marginTop: '24px', padding: '14px 24px', background: 'var(--blue)', color: 'white', border: 'none', borderRadius: '6px', cursor: loading ? 'not-allowed' : 'pointer', fontWeight: 'bold', width: '100%' }}
          >
            {loading ? 'Processing & Embedding (May take a moment)...' : 'Ingest to Knowledge Base'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default RagIngestPage;
