import React, { useState, useEffect, useRef } from 'react';
import { Search, Trash2, Plus, Upload, Save, FileText, X, Loader2 } from 'lucide-react';
import { authFetch } from '../services/api';

interface SopItem {
  id: string;
  content: string;
  metadata: string; // JSON string
}

function parseSopContent(content: string, metadataStr: string) {
  let title = '';
  let description = content;
  let filename = '';

  try {
    const meta = JSON.parse(metadataStr || '{}');
    if (meta.sop_title) title = meta.sop_title;
    if (meta.file_name) filename = meta.file_name;
  } catch (e) {}

  const match = content.match(/^SOP:\s*(.*?)\nDescription:\s*([\s\S]*)$/i);
  if (match) {
    if (!title) title = match[1];
    description = match[2];
  } else if (!title) {
    title = filename ? `File: ${filename}` : 'Untitled SOP';
  }

  return { title, description, filename };
}

const SopPage: React.FC = () => {
  const [sops, setSops] = useState<SopItem[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loadingList, setLoadingList] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Ingestion / Edit Modal State
  const [showIngestModal, setShowIngestModal] = useState(false);
  const [selectedSop, setSelectedSop] = useState<SopItem | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<File | null>(null);

  // Loading & Messages
  const [loadingAction, setLoadingAction] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error', text: string } | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchSops = async () => {
    setLoadingList(true);
    setError(null);
    try {
      const res = await authFetch('/api/v1/rag/sops');
      if (!res.ok) throw new Error('Failed to load SOPs');
      const data = await res.json();
      if (Array.isArray(data)) setSops(data);
    } catch (e: any) {
      setError(e.message || 'Error loading SOP list');
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    fetchSops();
  }, []);

  const openNewSopModal = () => {
    setSelectedSop(null);
    setTitle('');
    setDescription('');
    setFile(null);
    setMessage(null);
    setShowIngestModal(true);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const openEditSopModal = (sop: SopItem) => {
    const parsed = parseSopContent(sop.content, sop.metadata);
    setSelectedSop(sop);
    setTitle(parsed.title);
    setDescription(parsed.description);
    setFile(null);
    setMessage(null);
    setShowIngestModal(true);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDeleteSop = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('Delete this SOP permanently from pgvector?')) return;
    try {
      const res = await authFetch(`/api/v1/rag/sops/${id}`, { method: 'DELETE' });
      if (res.ok) {
        setSops(prev => prev.filter(sop => sop.id !== id));
        setMessage({ type: 'success', text: 'SOP deleted successfully' });
      } else {
        const data = await res.json().catch(() => ({}));
        setMessage({ type: 'error', text: data.error || 'Failed to delete SOP' });
      }
    } catch (e) {
      setMessage({ type: 'error', text: 'Network error deleting SOP' });
    }
  };

  const [modalError, setModalError] = useState<string | null>(null);

  const handleSaveOrIngest = async () => {
    setLoadingAction(true);
    setModalError(null);
    setMessage(null);

    // Brief delay to trigger the round loading animation on the button
    await new Promise(resolve => setTimeout(resolve, 300));

    if (!file && (!title.trim() || (!selectedSop && !description.trim()))) {
      setModalError('Please select a document file OR provide a Title + SOP Text.');
      setLoadingAction(false);
      return;
    }

    try {
      if (selectedSop) {
        if (file) {
          await authFetch(`/api/v1/rag/sops/${selectedSop.id}`, { method: 'DELETE' });
          const formData = new FormData();
          formData.append('file', file);
          formData.append('title', title.trim() || file.name);

          const response = await authFetch('/api/v1/rag/upload', {
            method: 'POST',
            body: formData
          });

          const data = await response.json();
          if (response.ok) {
            setMessage({ type: 'success', text: 'SOP document updated and re-embedded successfully' });
            setShowIngestModal(false);
            fetchSops();
          } else {
            setModalError(data.error || 'Failed to update SOP document');
          }
        } else {
          const res = await authFetch(`/api/v1/rag/sops/${selectedSop.id}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, description })
          });
          const data = await res.json();
          if (res.ok) {
            setMessage({ type: 'success', text: data.message || 'SOP updated and re-embedded successfully' });
            setShowIngestModal(false);
            fetchSops();
          } else {
            setModalError(data.error || 'Failed to update SOP');
          }
        }
      } else {
        let response;
        if (file) {
          const formData = new FormData();
          formData.append('file', file);
          if (title.trim()) formData.append('title', title.trim());

          response = await authFetch('/api/v1/rag/upload', {
            method: 'POST',
            body: formData
          });
        } else {
          response = await authFetch('/api/v1/rag/ingest', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, description })
          });
        }

        const data = await response.json();
        if (response.ok) {
          setMessage({ type: 'success', text: data.message || 'SOP ingested and embedded successfully' });
          setShowIngestModal(false);
          fetchSops();
        } else {
          setModalError(data.error || 'Failed to ingest SOP');
        }
      }
    } catch (e) {
      setModalError('Network error occurred. Check inputs.');
    } finally {
      setLoadingAction(false);
    }
  };

  const filteredSops = sops.filter(sop => {
    const parsed = parseSopContent(sop.content, sop.metadata);
    const query = searchQuery.toLowerCase();
    return (
      parsed.title.toLowerCase().includes(query) ||
      parsed.description.toLowerCase().includes(query) ||
      (parsed.filename && parsed.filename.toLowerCase().includes(query))
    );
  });

  return (
    <div style={{ maxWidth: '1000px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '20px', width: '100%' }}>
      
      {/* Top Header Card */}
      <div className="card" style={{ padding: '20px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 800, color: 'var(--text-1)', margin: '0 0 4px' }}>
            Standard Operating Procedures (SOP Library)
          </h2>
          <p style={{ fontSize: '12.5px', color: 'var(--text-3)', margin: 0 }}>
            Uploaded SOP documents are embedded into the RAG vector store for grounded operational assistance.
          </p>
        </div>
        <button
          onClick={openNewSopModal}
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '9px 18px', fontSize: '13px', fontWeight: 700 }}
        >
          <Plus size={15} /> New SOP
        </button>
      </div>

      {message && (
        <div style={{
          padding: '12px 16px', borderRadius: '8px',
          background: message.type === 'success' ? 'var(--ok-dim)' : 'var(--crit-dim)',
          color: message.type === 'success' ? 'var(--ok)' : 'var(--crit)',
          border: `1px solid ${message.type === 'success' ? 'rgba(16,185,129,0.3)' : 'rgba(239,68,68,0.3)'}`,
          fontSize: '13px', fontWeight: 600
        }}>
          {message.text}
        </div>
      )}

      {/* Main SOP List Card */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', minHeight: '400px' }}>
        <div className="card-header" style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
          <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
            Document Library ({filteredSops.length})
          </span>
          <div style={{ position: 'relative', width: '280px' }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
            <input
              type="text"
              placeholder="Search SOP title or content..."
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              style={{ paddingLeft: '32px', width: '100%', fontSize: '13px', height: '34px' }}
            />
          </div>
        </div>

        <div style={{ padding: '20px', flex: 1 }}>
          {loadingList ? (
            <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '40px' }}>Loading SOPs...</div>
          ) : error ? (
            <div style={{ color: 'var(--crit)', fontSize: '13px', textAlign: 'center', padding: '40px' }}>{error}</div>
          ) : filteredSops.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '40px' }}>
              No SOP documents found. Click <strong>"New SOP"</strong> above to ingest a document or add standard operating procedures.
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '16px' }}>
              {filteredSops.map(sop => {
                const parsed = parseSopContent(sop.content, sop.metadata);
                return (
                  <div
                    key={sop.id}
                    onClick={() => openEditSopModal(sop)}
                    style={{
                      padding: '16px', border: '1px solid var(--border)', borderRadius: '8px', cursor: 'pointer',
                      background: 'var(--surface-1)', transition: 'all 0.2s', display: 'flex', flexDirection: 'column', gap: '8px'
                    }}
                    onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
                    onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--border)')}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <span style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text-1)', wordBreak: 'break-word' }}>
                        {parsed.title}
                      </span>
                      <button
                        onClick={(e) => handleDeleteSop(sop.id, e)}
                        style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--crit)', padding: '2px' }}
                        title="Delete SOP"
                      >
                        <Trash2 size={14} />
                      </button>
                    </div>
                    {parsed.filename && (
                      <div style={{ fontSize: '11px', color: 'var(--accent)', background: 'var(--accent-dim)', padding: '2px 8px', borderRadius: '4px', alignSelf: 'flex-start' }}>
                        <FileText size={11} style={{ display: 'inline', marginRight: '4px' }} />
                        {parsed.filename}
                      </div>
                    )}
                    <p style={{ fontSize: '12px', color: 'var(--text-3)', margin: 0, display: '-webkit-box', WebkitLineClamp: 3, WebkitBoxOrient: 'vertical', overflow: 'hidden', lineHeight: '1.5' }}>
                      {parsed.description}
                    </p>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* ── INGEST / EDIT SOP MODAL ── */}
      {showIngestModal && (
        <div className="modal-backdrop" onClick={() => setShowIngestModal(false)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '640px', maxWidth: '90vw' }}>
            <div className="modal-header" style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <h2 style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-1)', margin: 0 }}>
                {selectedSop ? 'Edit SOP Document' : 'Ingest Standard Operating Procedure'}
              </h2>
              <button className="close-btn" onClick={() => setShowIngestModal(false)} style={{ background: 'transparent', border: 'none', color: 'var(--text-3)', cursor: 'pointer' }}>
                <X size={18} />
              </button>
            </div>

            <div style={{ padding: '20px', display: 'flex', flexDirection: 'column', gap: '16px' }}>
              {modalError && (
                <div style={{
                  padding: '10px 14px', borderRadius: '6px',
                  background: 'var(--crit-dim)', color: 'var(--crit)',
                  border: '1px solid rgba(239,68,68,0.3)', fontSize: '12.5px', fontWeight: 600
                }}>
                  ⚠️ {modalError}
                </div>
              )}

              {/* Title input */}
              <div>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '6px' }}>
                  SOP Document Title <span style={{ color: 'var(--crit)' }}>*</span>
                </label>
                <input
                  type="text"
                  placeholder="e.g. POS Terminal Troubleshooting & Maintenance"
                  value={title}
                  onChange={e => setTitle(e.target.value)}
                  style={{ width: '100%', padding: '10px 12px', fontSize: '13.5px' }}
                />
              </div>

              {/* Upload or Prose */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                <div style={{ padding: '18px', border: '1px dashed var(--border)', borderRadius: '8px', background: 'var(--surface-2)', textAlign: 'center' }}>
                  <Upload size={28} style={{ color: 'var(--accent)', marginBottom: '8px' }} />
                  <h4 style={{ fontSize: '13.5px', fontWeight: 700, color: 'var(--text-1)', margin: '0 0 4px' }}>
                    {selectedSop ? 'Replace Document File' : 'Upload Document File'}
                  </h4>
                  <p style={{ fontSize: '11.5px', color: 'var(--text-3)', margin: '0 0 12px' }}>
                    Supported formats: .pdf, .docx, .txt, .xlsx, .csv
                  </p>
                  <input
                    type="file"
                    ref={fileInputRef}
                    accept=".pdf,.docx,.txt,.xlsx,.csv"
                    onChange={e => setFile(e.target.files?.[0] || null)}
                    style={{ display: 'none' }}
                  />
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => fileInputRef.current?.click()}
                    style={{ padding: '7px 14px', fontSize: '12px', fontWeight: 700 }}
                  >
                    Choose File
                  </button>
                  {file && (
                    <div style={{ marginTop: '10px', fontSize: '12px', fontWeight: 600, color: 'var(--accent)' }}>
                      Selected: {file.name}
                    </div>
                  )}
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', opacity: file ? 0.4 : 1, pointerEvents: file ? 'none' : 'auto' }}>
                  <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '6px' }}>
                    Or Manual SOP Text Content
                  </label>
                  <textarea
                    rows={6}
                    value={description}
                    onChange={e => setDescription(e.target.value)}
                    placeholder="Enter standard operating procedure text here..."
                    style={{
                      width: '100%', padding: '10px 12px', borderRadius: '6px', border: '1px solid var(--border)',
                      background: 'var(--surface-2)', color: 'var(--text-1)', fontSize: '12.5px', lineHeight: '1.5'
                    }}
                  />
                </div>
              </div>

              {/* Action buttons */}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' }}>
                <button
                  className="btn-secondary"
                  onClick={() => setShowIngestModal(false)}
                  style={{ padding: '8px 16px', fontSize: '12.5px' }}
                >
                  Cancel
                </button>
                <button
                  onClick={handleSaveOrIngest}
                  disabled={loadingAction}
                  className="btn-primary"
                  style={{ padding: '8px 18px', fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '6px' }}
                >
                  {loadingAction ? <Loader2 size={14} style={{ animation: 'spin 1s linear infinite' }} /> : <Save size={14} />}
                  {loadingAction ? 'Processing...' : selectedSop ? 'Save & Re-embed' : 'Ingest SOP'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

    </div>
  );
};

export default SopPage;
