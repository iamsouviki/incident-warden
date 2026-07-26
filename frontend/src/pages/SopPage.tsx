import React, { useState, useEffect, useRef } from 'react';
import { Search, Edit2, Trash2, BookOpen, AlertCircle, Plus, Upload, Save, FileText, ArrowRight } from 'lucide-react';
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

  // Editor State
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
      if (!res.ok) {
        throw new Error('Failed to load SOPs');
      }
      const data = await res.json();
      if (Array.isArray(data)) {
        setSops(data);
      }
    } catch (e: any) {
      setError(e.message || 'Error loading SOP list');
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    fetchSops();
  }, []);

  const handleNewSop = () => {
    setSelectedSop(null);
    setTitle('');
    setDescription('');
    setFile(null);
    setMessage(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleSelectSop = (sop: SopItem) => {
    const parsed = parseSopContent(sop.content, sop.metadata);
    setSelectedSop(sop);
    setTitle(parsed.title);
    setDescription(parsed.description);
    setFile(null);
    setMessage(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleDeleteSop = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('Delete this SOP permanently from pgvector?')) return;
    try {
      const res = await authFetch(`/api/v1/rag/sops/${id}`, { method: 'DELETE' });
      if (res.ok) {
        if (selectedSop?.id === id) handleNewSop();
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

  const handleSaveOrIngest = async () => {
    if (!file && (!title.trim() || (!selectedSop && !description.trim()))) {
      setMessage({ type: 'error', text: 'Please select a file OR provide a Title + Description.' });
      return;
    }

    setLoadingAction(true);
    setMessage(null);

    try {
      if (selectedSop) {
        // ── EDITING SOP MODE ──
        if (file) {
          // Replace SOP via new document upload
          // 1. Delete the old vector
          await authFetch(`/api/v1/rag/sops/${selectedSop.id}`, { method: 'DELETE' });
          
          // 2. Upload new document with matching title
          const formData = new FormData();
          formData.append('file', file);
          formData.append('title', title.trim() || file.name);

          const response = await fetch('/api/v1/rag/upload', {
            method: 'POST',
            body: formData
          });

          const data = await response.json();
          if (response.ok) {
            setMessage({ type: 'success', text: 'SOP document updated and re-embedded successfully' });
            handleNewSop();
            fetchSops();
          } else {
            setMessage({ type: 'error', text: data.error || 'Failed to update SOP document' });
          }
        } else {
          // Standard text edit update
          const res = await authFetch(`/api/v1/rag/sops/${selectedSop.id}`, {
            method: 'PUT',
            body: JSON.stringify({ title, description })
          });
          const data = await res.json();
          if (res.ok) {
            setMessage({ type: 'success', text: data.message || 'SOP updated and re-embedded successfully' });
            fetchSops();
          } else {
            setMessage({ type: 'error', text: data.error || 'Failed to update SOP' });
          }
        }
      } else {
        // ── INGESTING NEW SOP MODE ──
        let response;
        if (file) {
          const formData = new FormData();
          formData.append('file', file);
          if (title.trim()) formData.append('title', title.trim());

          response = await fetch('/api/v1/rag/upload', {
            method: 'POST',
            body: formData
          });
        } else {
          response = await fetch('/api/v1/rag/ingest', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title, description })
          });
        }

        const data = await response.json();
        if (response.ok) {
          setMessage({ type: 'success', text: data.message || 'SOP ingested and embedded successfully' });
          handleNewSop();
          fetchSops();
        } else {
          setMessage({ type: 'error', text: data.error || 'Failed to ingest SOP' });
        }
      }
    } catch (e) {
      setMessage({ type: 'error', text: 'Network error occurred' });
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
    <div style={{ display: 'grid', gridTemplateColumns: '400px 1fr', gap: '20px', height: 'calc(100vh - 160px)', minHeight: '600px', width: '100%' }}>
      
      {/* ── LEFT PANEL: SOP Library list & search ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ display: 'flex', flexDirection: 'column', gap: '12px', borderBottom: '1px solid var(--border)', padding: '16px 20px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              SOP Library Directory
            </span>
            <button
              onClick={handleNewSop}
              style={{
                display: 'flex', alignItems: 'center', gap: '4px', padding: '6px 12px',
                background: 'var(--michaels-red)', color: 'white', border: 'none', borderRadius: '4px',
                fontSize: '11px', fontWeight: 'bold', cursor: 'pointer', textTransform: 'uppercase'
              }}
            >
              <Plus size={12} /> New SOP
            </button>
          </div>
          
          <div style={{ position: 'relative', width: '100%' }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input
              type="text"
              placeholder="Search SOP title or content..."
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              style={{ paddingLeft: '32px', width: '100%', fontSize: '13px', height: '36px' }}
            />
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
          {loadingList ? (
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', padding: '20px' }}>Loading SOPs...</div>
          ) : error ? (
            <div style={{ color: 'var(--red)', fontSize: '13px', textAlign: 'center', padding: '20px' }}>{error}</div>
          ) : filteredSops.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', padding: '20px' }}>No SOPs found.</div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {filteredSops.map(sop => {
                const parsed = parseSopContent(sop.content, sop.metadata);
                return (
                  <div
                    key={sop.id}
                    onClick={() => handleSelectSop(sop)}
                    style={{
                      padding: '12px', border: '1px solid var(--border)', borderRadius: '8px', cursor: 'pointer',
                      background: selectedSop?.id === sop.id ? 'var(--surface3)' : 'var(--surface)',
                      transition: 'all 0.2s', borderLeft: selectedSop?.id === sop.id ? '4px solid var(--michaels-red)' : '1px solid var(--border)'
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <span style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '240px' }} title={parsed.title}>
                        {parsed.title}
                      </span>
                      <button
                        onClick={(e) => handleDeleteSop(sop.id, e)}
                        style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--red)', padding: '2px' }}
                      >
                        <Trash2 size={13} />
                      </button>
                    </div>
                    {parsed.filename && (
                      <div style={{ fontSize: '9px', color: 'var(--accent)', background: 'var(--accent-dim)', display: 'inline-block', padding: '1px 4px', borderRadius: '3px', marginTop: '4px' }}>
                        {parsed.filename}
                      </div>
                    )}
                    <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '6px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {parsed.description}
                    </p>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* ── RIGHT PANEL: SOP Ingestion and Text/File Editor ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ borderBottom: '1px solid var(--border)', padding: '16px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)' }}>
            {selectedSop ? `Editing SOP Details` : 'Ingest New Standard Operating Procedure'}
          </span>
          <button
            onClick={handleSaveOrIngest}
            disabled={loadingAction}
            className="btn-primary"
            style={{ padding: '8px 16px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px' }}
          >
            <Save size={12} /> {loadingAction ? 'Processing...' : selectedSop ? 'Save & Re-embed' : 'Ingest SOP'}
          </button>
        </div>

        <div style={{ padding: '20px', flex: 1, display: 'flex', flexDirection: 'column', gap: '20px', overflowY: 'auto' }}>
          
          {message && (
            <div style={{
              padding: '12px', borderRadius: '6px',
              background: message.type === 'success' ? 'rgba(48,217,156,0.1)' : 'rgba(220,38,38,0.1)',
              color: message.type === 'success' ? 'var(--green)' : 'var(--red)',
              border: `1px solid ${message.type === 'success' ? 'var(--green-dim)' : 'rgba(220,38,38,0.3)'}`,
              fontSize: '13px', fontWeight: 600
            }}>
              {message.text}
            </div>
          )}

          {/* SOP Title */}
          <div>
            <label style={{ display: 'block', fontSize: '11px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px' }}>
              SOP Title
            </label>
            <input
              type="text"
              placeholder="e.g. Database Reboot Procedure"
              value={title}
              onChange={e => setTitle(e.target.value)}
              style={{ width: '100%', padding: '10px 12px', fontSize: '14px' }}
            />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px', flex: 1, minHeight: '300px' }}>
            
            {/* File Upload segment */}
            <div style={{ padding: '20px', border: '1px dashed var(--border)', borderRadius: '8px', background: 'var(--surface2)', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', textAlign: 'center' }}>
              <div style={{ padding: '16px', borderRadius: '50%', background: 'var(--surface3)', color: 'var(--text-dim)', marginBottom: '12px' }}>
                <Upload size={32} />
              </div>
              <h3 style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)', marginBottom: '4px' }}>
                {selectedSop ? 'Replace SOP Document' : 'Upload Document'}
              </h3>
              <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginBottom: '16px', maxWidth: '240px', lineHeight: '1.4' }}>
                Drop in a .pdf, .docx, .txt, .xlsx, or .csv document to automatically parse and re-embed.
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
                onClick={() => fileInputRef.current?.click()}
                style={{
                  padding: '8px 16px', background: 'var(--surface)', color: 'var(--text)',
                  border: '1px solid var(--border-bright)', borderRadius: '6px', fontSize: '12px',
                  fontWeight: 'bold', cursor: 'pointer'
                }}
              >
                Choose File
              </button>
              
              {file && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '16px', background: 'var(--accent-dim)', color: 'var(--accent)', padding: '6px 12px', borderRadius: '4px', fontSize: '12px', fontWeight: 600 }}>
                  <FileText size={14} /> Selected: {file.name}
                </div>
              )}
            </div>

            {/* Manual text editing area */}
            <div style={{ display: 'flex', flexDirection: 'column', opacity: file ? 0.4 : 1, pointerEvents: file ? 'none' : 'auto', transition: 'opacity 0.2s' }}>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px' }}>
                Manual SOP Content
              </label>
              <textarea
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="Pasting text description is disabled if a document file is selected for upload on the left."
                style={{
                  flex: 1, width: '100%', padding: '12px', borderRadius: '8px', border: '1px solid var(--border)',
                  background: 'var(--surface)', color: 'var(--text)', fontFamily: 'var(--mono)', fontSize: '13px',
                  resize: 'none', lineHeight: '1.5'
                }}
              />
            </div>

          </div>

        </div>
      </div>

    </div>
  );
};

export default SopPage;
