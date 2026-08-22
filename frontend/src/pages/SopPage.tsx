import React, { useState, useEffect, useRef } from 'react';
import { Search, Trash2, Plus, Upload, Save, FileText, Edit2, X } from 'lucide-react';
import { authFetch } from '../services/api';

interface SopItem {
  id: string;
  content: string;
  metadata: string; // JSON string
}

/** An approved procedure: the record that authorises a script, not the prose that describes it. */
interface Procedure {
  id: string;
  sopId: string;
  stepNumber: number;
  title: string;
  description?: string;
  matchKeywords?: string;
  actionKey: string;
  approvalStatus: string;
  requiresApproval: boolean;
  executionOrder: number;
  reliability: number;
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
  const [procedures, setProcedures] = useState<Procedure[]>([]);
  const [searchQuery, setSearchQuery] = useState('');
  const [loadingList, setLoadingList] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Editor State (Document vs Procedure Authoring)
  const [activeTab, setActiveTab] = useState<'sops' | 'procedures'>('sops');
  const [selectedSop, setSelectedSop] = useState<SopItem | null>(null);
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<File | null>(null);
  
  // Procedure Authoring Modal/Form State
  const [editingProcedure, setEditingProcedure] = useState<Procedure | null>(null);
  const [procSopId, setProcSopId] = useState('SOP-001');
  const [procStepNumber, setProcStepNumber] = useState(1);
  const [procTitle, setProcTitle] = useState('');
  const [procDescription, setProcDescription] = useState('');
  const [procKeywords, setProcKeywords] = useState('');
  const [procActionKey, setProcActionKey] = useState('');
  const [procStatus, setProcStatus] = useState('APPROVED');
  const [procRequiresApproval, setProcRequiresApproval] = useState(true);
  const [procExecutionOrder, setProcExecutionOrder] = useState(10);
  const [showProcModal, setShowProcModal] = useState(false);
  const [procSaving, setProcSaving] = useState(false);
  const [procError, setProcError] = useState<string | null>(null);

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

  const fetchProcedures = async () => {
    try {
      const res = await authFetch('/api/v1/rag/procedures');
      if (res.ok) {
        const data = await res.json();
        setProcedures(Array.isArray(data) ? data : []);
      }
    } catch (e) {
      setProcedures([]);
    }
  };

  useEffect(() => {
    fetchSops();
    fetchProcedures();
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
        if (file) {
          await authFetch(`/api/v1/rag/sops/${selectedSop.id}`, { method: 'DELETE' });
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

  // Procedure Authoring Actions
  const openNewProcedureModal = () => {
    setEditingProcedure(null);
    setProcSopId('SOP-001');
    setProcStepNumber(procedures.length + 1);
    setProcTitle('');
    setProcDescription('');
    setProcKeywords('');
    setProcActionKey('RESTART_SERVICE:tomcat:linux');
    setProcStatus('APPROVED');
    setProcRequiresApproval(true);
    setProcExecutionOrder(10);
    setProcError(null);
    setShowProcModal(true);
  };

  const openEditProcedureModal = (proc: Procedure) => {
    setEditingProcedure(proc);
    setProcSopId(proc.sopId);
    setProcStepNumber(proc.stepNumber);
    setProcTitle(proc.title);
    setProcDescription(proc.description || '');
    setProcKeywords(proc.matchKeywords || '');
    setProcActionKey(proc.actionKey);
    setProcStatus(proc.approvalStatus);
    setProcRequiresApproval(proc.requiresApproval);
    setProcExecutionOrder(proc.executionOrder);
    setProcError(null);
    setShowProcModal(true);
  };

  const handleSaveProcedure = async () => {
    if (!procSopId.trim() || !procTitle.trim() || !procActionKey.trim()) {
      setProcError('SOP ID, Title, and Action Key are required.');
      return;
    }
    setProcSaving(true);
    setProcError(null);
    try {
      const payload = {
        sopId: procSopId.trim(),
        stepNumber: Number(procStepNumber) || 1,
        title: procTitle.trim(),
        description: procDescription.trim(),
        matchKeywords: procKeywords.trim(),
        actionKey: procActionKey.trim(),
        approvalStatus: procStatus,
        requiresApproval: procRequiresApproval,
        executionOrder: Number(procExecutionOrder) || 10
      };

      const url = editingProcedure ? `/api/v1/rag/procedures/${editingProcedure.id}` : '/api/v1/rag/procedures';
      const method = editingProcedure ? 'PUT' : 'POST';

      const res = await authFetch(url, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      if (!res.ok) {
        const data = await res.json().catch(() => ({}));
        throw new Error(data.error || `Save failed (${res.status})`);
      }

      setShowProcModal(false);
      await fetchProcedures();
    } catch (e: any) {
      setProcError(e.message || 'Failed to save procedure');
    } finally {
      setProcSaving(false);
    }
  };

  const handleDeleteProcedure = async (id: string) => {
    if (!window.confirm('Delete this procedure from authority registry?')) return;
    try {
      const res = await authFetch(`/api/v1/rag/procedures/${id}`, { method: 'DELETE' });
      if (res.ok) {
        await fetchProcedures();
      }
    } catch (e) {
      console.error('Failed to delete procedure', e);
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

  const filteredProcedures = procedures.filter(p => {
    const q = searchQuery.toLowerCase();
    return (
      p.title.toLowerCase().includes(q) ||
      p.sopId.toLowerCase().includes(q) ||
      p.actionKey.toLowerCase().includes(q) ||
      (p.matchKeywords && p.matchKeywords.toLowerCase().includes(q))
    );
  });

  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr)) 1.2fr', gap: '20px', minHeight: 'calc(100vh - 160px)', width: '100%' }}>
      
      {/* ── LEFT PANEL: SOP Library list & search ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ display: 'flex', flexDirection: 'column', gap: '12px', borderBottom: '1px solid var(--border)', padding: '16px 20px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '10px' }}>
            <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              Knowledge & Authority
            </span>
            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={() => setActiveTab('sops')}
                style={{
                  padding: '5px 10px', borderRadius: '4px', border: '1px solid var(--border)',
                  background: activeTab === 'sops' ? 'var(--accent-dim)' : 'transparent',
                  color: activeTab === 'sops' ? 'var(--accent)' : 'var(--text-2)',
                  fontSize: '11px', fontWeight: 700, cursor: 'pointer'
                }}
              >
                Docs ({sops.length})
              </button>
              <button
                onClick={() => setActiveTab('procedures')}
                style={{
                  padding: '5px 10px', borderRadius: '4px', border: '1px solid var(--border)',
                  background: activeTab === 'procedures' ? 'var(--accent-dim)' : 'transparent',
                  color: activeTab === 'procedures' ? 'var(--accent)' : 'var(--text-2)',
                  fontSize: '11px', fontWeight: 700, cursor: 'pointer'
                }}
              >
                Procedures ({procedures.length})
              </button>
              {activeTab === 'sops' ? (
                <button
                  onClick={handleNewSop}
                  className="btn-primary"
                  style={{ display: 'flex', alignItems: 'center', gap: '4px', padding: '5px 10px', fontSize: '11px', textTransform: 'uppercase' }}
                >
                  <Plus size={12} /> New Doc
                </button>
              ) : (
                <button
                  onClick={openNewProcedureModal}
                  className="btn-primary"
                  style={{ display: 'flex', alignItems: 'center', gap: '4px', padding: '5px 10px', fontSize: '11px', textTransform: 'uppercase' }}
                >
                  <Plus size={12} /> Author Proc
                </button>
              )}
            </div>
          </div>
          
          <div style={{ position: 'relative', width: '100%' }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-3)' }} />
            <input
              type="text"
              placeholder={activeTab === 'sops' ? "Search SOP title or content..." : "Search procedures, action keys, keywords..."}
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              style={{ paddingLeft: '32px', width: '100%', fontSize: '13px', height: '36px' }}
            />
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
          {activeTab === 'sops' ? (
            loadingList ? (
              <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '20px' }}>Loading SOPs...</div>
            ) : error ? (
              <div style={{ color: 'var(--crit)', fontSize: '13px', textAlign: 'center', padding: '20px' }}>{error}</div>
            ) : filteredSops.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '20px' }}>No SOPs found.</div>
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
                        background: selectedSop?.id === sop.id ? 'var(--surface-2)' : 'var(--surface-1)',
                        transition: 'all 0.2s', borderLeft: selectedSop?.id === sop.id ? '4px solid var(--accent)' : '1px solid var(--border)'
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                        <span style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text-1)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '240px' }} title={parsed.title}>
                          {parsed.title}
                        </span>
                        <button
                          onClick={(e) => handleDeleteSop(sop.id, e)}
                          style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--crit)', padding: '2px' }}
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                      {parsed.filename && (
                        <div style={{ fontSize: '10px', color: 'var(--accent)', background: 'var(--accent-dim)', display: 'inline-block', padding: '2px 6px', borderRadius: '4px', marginTop: '4px' }}>
                          {parsed.filename}
                        </div>
                      )}
                      <p style={{ fontSize: '11.5px', color: 'var(--text-3)', marginTop: '6px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                        {parsed.description}
                      </p>
                    </div>
                  );
                })}
              </div>
            )
          ) : (
            filteredProcedures.length === 0 ? (
              <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '20px' }}>
                No procedures found. Click "Author Proc" to define authority actions.
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                {filteredProcedures.map(p => (
                  <div key={p.id} style={{ padding: '12px', background: 'var(--surface-2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                      <span style={{ fontSize: '11px', fontWeight: 800, color: 'var(--accent)', letterSpacing: '0.5px' }}>
                        {p.sopId} · STEP {p.stepNumber}
                      </span>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <span style={{ fontSize: '10px', fontWeight: 700, padding: '2px 6px', borderRadius: '4px', background: p.approvalStatus === 'APPROVED' ? 'var(--ok-dim)' : 'var(--warn-dim)', color: p.approvalStatus === 'APPROVED' ? 'var(--ok)' : 'var(--warn)' }}>
                          {p.approvalStatus}
                        </span>
                        <button onClick={() => openEditProcedureModal(p)} style={{ background: 'transparent', border: 'none', color: 'var(--text-2)', cursor: 'pointer', padding: '2px' }}>
                          <Edit2 size={13} />
                        </button>
                        <button onClick={() => handleDeleteProcedure(p.id)} style={{ background: 'transparent', border: 'none', color: 'var(--crit)', cursor: 'pointer', padding: '2px' }}>
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                    <div style={{ fontSize: '13px', fontWeight: 700, color: 'var(--text-1)', marginBottom: '4px' }}>{p.title}</div>
                    {p.matchKeywords && (
                      <div style={{ fontSize: '11px', color: 'var(--text-3)', marginBottom: '6px' }}>
                        Keywords: <span style={{ color: 'var(--text-2)' }}>{p.matchKeywords}</span>
                      </div>
                    )}
                    <code style={{ fontSize: '11px', color: '#93c5fd', background: 'var(--surface-3)', padding: '3px 6px', borderRadius: '4px', display: 'inline-block', wordBreak: 'break-all' }}>
                      {p.actionKey}
                    </code>
                  </div>
                ))}
              </div>
            )
          )}
        </div>
      </div>

      {/* ── RIGHT PANEL: Ingestion & Details Editor ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ borderBottom: '1px solid var(--border)', padding: '16px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '10px' }}>
          <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-1)' }}>
            {selectedSop ? `Editing SOP Details` : 'Ingest Standard Operating Procedure'}
          </span>
          <button
            onClick={handleSaveOrIngest}
            disabled={loadingAction}
            className="btn-primary"
            style={{ padding: '8px 16px', fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '6px' }}
          >
            <Save size={13} /> {loadingAction ? 'Processing...' : selectedSop ? 'Save & Re-embed' : 'Ingest SOP'}
          </button>
        </div>

        <div style={{ padding: '20px', flex: 1, display: 'flex', flexDirection: 'column', gap: '18px', overflowY: 'auto' }}>
          {message && (
            <div style={{
              padding: '12px 14px', borderRadius: '8px',
              background: message.type === 'success' ? 'var(--ok-dim)' : 'var(--crit-dim)',
              color: message.type === 'success' ? 'var(--ok)' : 'var(--crit)',
              border: `1px solid ${message.type === 'success' ? 'rgba(16,185,129,0.3)' : 'rgba(239,68,68,0.3)'}`,
              fontSize: '13px', fontWeight: 600
            }}>
              {message.text}
            </div>
          )}

          {/* SOP Title */}
          <div>
            <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '6px' }}>
              SOP Document Title
            </label>
            <input
              type="text"
              placeholder="e.g. Database Maintenance Procedure"
              value={title}
              onChange={e => setTitle(e.target.value)}
              style={{ width: '100%', padding: '10px 12px', fontSize: '13.5px' }}
            />
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(280px, 1fr))', gap: '16px', flex: 1, minHeight: '300px' }}>
            {/* File Upload segment */}
            <div style={{ padding: '20px', border: '1px dashed var(--border)', borderRadius: '8px', background: 'var(--surface-2)', display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', textAlign: 'center' }}>
              <div style={{ padding: '16px', borderRadius: '50%', background: 'var(--surface-3)', color: 'var(--accent)', marginBottom: '12px' }}>
                <Upload size={32} />
              </div>
              <h3 style={{ fontSize: '14px', fontWeight: 800, color: 'var(--text-1)', marginBottom: '4px' }}>
                {selectedSop ? 'Replace SOP Document' : 'Upload Document'}
              </h3>
              <p style={{ fontSize: '11.5px', color: 'var(--text-3)', marginBottom: '16px', maxWidth: '260px', lineHeight: '1.4' }}>
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
                className="btn-secondary"
                onClick={() => fileInputRef.current?.click()}
                style={{ padding: '8px 16px', fontSize: '12px', fontWeight: 700 }}
              >
                Choose File
              </button>
              
              {file && (
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px', marginTop: '14px', background: 'var(--accent-dim)', color: 'var(--accent)', padding: '6px 12px', borderRadius: '6px', fontSize: '12px', fontWeight: 600 }}>
                  <FileText size={14} /> Selected: {file.name}
                </div>
              )}
            </div>

            {/* Manual text editing area */}
            <div style={{ display: 'flex', flexDirection: 'column', opacity: file ? 0.4 : 1, pointerEvents: file ? 'none' : 'auto', transition: 'opacity 0.2s' }}>
              <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '6px' }}>
                Manual SOP Prose
              </label>
              <textarea
                value={description}
                onChange={e => setDescription(e.target.value)}
                placeholder="Pasting text description is disabled if a document file is selected for upload on the left."
                style={{
                  flex: 1, width: '100%', padding: '12px', borderRadius: '8px', border: '1px solid var(--border)',
                  background: 'var(--surface-2)', color: 'var(--text-1)', fontFamily: 'var(--font-mono)', fontSize: '12.5px',
                  resize: 'none', lineHeight: '1.5'
                }}
              />
            </div>
          </div>
        </div>
      </div>

      {/* ── PROCEDURE AUTHORING MODAL ── */}
      {showProcModal && (
        <div className="modal-backdrop" onClick={() => setShowProcModal(false)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '560px' }}>
            <div className="modal-header">
              <h2 style={{ fontSize: '15px', fontWeight: 800, color: 'var(--text-1)' }}>
                {editingProcedure ? 'Edit Approved Procedure' : 'Author Approved Procedure'}
              </h2>
              <button className="close-btn" onClick={() => setShowProcModal(false)}><X size={18} /></button>
            </div>
            <div className="modal-form">
              <div className="form-row">
                <div className="form-field">
                  <label>SOP Identifier (e.g. SOP-001)</label>
                  <input type="text" value={procSopId} onChange={e => setProcSopId(e.target.value)} required />
                </div>
                <div className="form-field">
                  <label>Step Number</label>
                  <input type="number" value={procStepNumber} onChange={e => setProcStepNumber(Number(e.target.value))} required />
                </div>
              </div>
              <div className="form-field">
                <label>Procedure Title</label>
                <input type="text" placeholder="e.g. Restart Production Tomcat Service" value={procTitle} onChange={e => setProcTitle(e.target.value)} required />
              </div>
              <div className="form-field">
                <label>Match Vocabulary Keywords (Comma separated)</label>
                <input type="text" placeholder="e.g. tomcat, web service, 502, unresponsive" value={procKeywords} onChange={e => setProcKeywords(e.target.value)} />
              </div>
              <div className="form-field">
                <label>Action Key (Execution Tool Protocol)</label>
                <input type="text" placeholder="e.g. RESTART_SERVICE:tomcat:linux" value={procActionKey} onChange={e => setProcActionKey(e.target.value)} required />
              </div>
              <div className="form-row">
                <div className="form-field">
                  <label>Approval Status</label>
                  <select value={procStatus} onChange={e => setProcStatus(e.target.value)}>
                    <option value="APPROVED">APPROVED</option>
                    <option value="DRAFT">DRAFT</option>
                    <option value="RETIRED">RETIRED</option>
                  </select>
                </div>
                <div className="form-field">
                  <label>Execution Order</label>
                  <input type="number" value={procExecutionOrder} onChange={e => setProcExecutionOrder(Number(e.target.value))} />
                </div>
              </div>
              <div className="form-field">
                <label>Description & Notes</label>
                <textarea rows={3} value={procDescription} onChange={e => setProcDescription(e.target.value)} placeholder="Operational details for reviewers..." />
              </div>
              {procError && <div className="error-alert">{procError}</div>}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', marginTop: '10px' }}>
                <button className="btn-secondary" onClick={() => setShowProcModal(false)} style={{ padding: '8px 16px' }}>Cancel</button>
                <button className="btn-primary" onClick={handleSaveProcedure} disabled={procSaving} style={{ padding: '8px 18px' }}>
                  {procSaving ? 'Saving...' : 'Save Procedure'}
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
