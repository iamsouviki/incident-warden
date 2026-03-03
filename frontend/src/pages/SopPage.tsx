import React, { useState, useEffect, useRef } from 'react';
import { authFetch } from '../services/api';

interface Sop {
  id: string; title: string; category: string; scope: string;
  status: string; reliabilityScore: number; successCount: number;
  failureCount: number; actionPlanJson?: string; description?: string;
  ownerTeam?: string; createdAt: string; version?: string;
}

interface ParsedSop {
  title: string; category: string; description: string;
  resolutionSteps: string; sourceFileName?: string; warnings?: string[];
}

interface ManualForm {
  title: string; category: string; description: string;
  resolutionSteps: string; ownerTeam: string; tenantId: string;
}

const SOP_CATEGORIES = [
  'DATABASE','NETWORK','SECURITY','INFRASTRUCTURE','DEPLOYMENT',
  'MEMORY','STORAGE','CACHE','KUBERNETES','MONITORING','OTHER'
];

const CATEGORY_COLORS: Record<string, string> = {
  DATABASE: 'rgba(79,142,247,0.15)', NETWORK: 'rgba(48,217,156,0.1)',
  SECURITY: 'rgba(255,85,85,0.1)', INFRASTRUCTURE: 'rgba(181,123,255,0.1)',
  DEPLOYMENT: 'rgba(245,166,35,0.1)', MEMORY: 'rgba(245,166,35,0.1)',
  STORAGE: 'rgba(136,136,170,0.1)', CACHE: 'rgba(181,123,255,0.1)',
};

const CATEGORY_TEXT: Record<string, string> = {
  DATABASE: 'var(--blue)', NETWORK: 'var(--green)', SECURITY: 'var(--red)',
  INFRASTRUCTURE: 'var(--purple)', DEPLOYMENT: 'var(--amber)',
  MEMORY: 'var(--amber)', STORAGE: 'var(--text-dim)', CACHE: 'var(--purple)',
};

const SopPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [sops, setSops]       = useState<Sop[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [selected, setSelected] = useState<Sop | null>(null);
  const [filter, setFilter]   = useState('ALL');

  /* ── Upload / parse state ── */
  const fileInputRef             = useRef<HTMLInputElement>(null);
  const fileDirectSaveRef        = useRef<HTMLInputElement>(null);
  const [uploading, setUploading]       = useState(false);
  const [parsedSop, setParsedSop]       = useState<ParsedSop | null>(null);
  const [showValidate, setShowValidate] = useState(false);
  const [editParsed, setEditParsed]     = useState<ParsedSop | null>(null);
  const [approving, setApproving]       = useState(false);
  const [uploadError, setUploadError]   = useState<string | null>(null);
  const [directSaving, setDirectSaving] = useState(false);

  /* ── Manual entry state ── */
  const [showManual, setShowManual] = useState(false);
  const [manualForm, setManualForm] = useState<ManualForm>({
    title:'', category:'INFRASTRUCTURE', description:'',
    resolutionSteps:'', ownerTeam:'', tenantId,
  });
  const [savingManual, setSavingManual] = useState(false);
  const [manualError, setManualError]   = useState<string | null>(null);

  /* ── Paste content state (parse text directly — no file needed) ── */
  const [showPaste, setShowPaste]         = useState(false);
  const [pasteContent, setPasteContent]   = useState('');
  const [pasteParsing, setPasteParsing]   = useState(false);
  const [pasteError, setPasteError]       = useState<string | null>(null);
  const [pasteSaving, setPasteSaving]     = useState(false);

  /* ── Inline SOP edit state ── */
  const [showEditSop, setShowEditSop] = useState(false);
  const [editSopForm, setEditSopForm] = useState<{
    title: string; category: string; description: string;
    resolutionSteps: string; ownerTeam: string;
  } | null>(null);
  const [savingEditSop, setSavingEditSop] = useState(false);
  const [editSopError, setEditSopError]   = useState<string | null>(null);

  const fetchSops = async () => {
    try {
      const r = await authFetch(`/api/v1/sops?tenantId=${tenantId}`);
      if (r.ok) { const d = await r.json(); setSops(Array.isArray(d) ? d : d.sops || []); setError(null); }
      else setError('Failed to load SOPs');
    } catch { setError('Cannot connect to backend'); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchSops(); }, [tenantId]);

  /* ── Upload handler ── */
  const handleFileChange = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true); setUploadError(null);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await authFetch('/api/v1/sops/parse', { method: 'POST', body: fd });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setUploadError(j.error || `Parse failed (${r.status})`); }
      else {
        const parsed: ParsedSop = await r.json();
        setParsedSop(parsed);
        setEditParsed({ ...parsed });
        setShowValidate(true);
      }
    } catch { setUploadError('Cannot connect to backend'); }
    finally { setUploading(false); if (fileInputRef.current) fileInputRef.current.value = ''; }
  };

  const handleApproveUpload = async () => {
    if (!editParsed) return;
    setApproving(true);
    try {
      const r = await authFetch('/api/v1/sops/manual', {
        method: 'POST',
        body: JSON.stringify({ ...editParsed, tenantId }),
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setUploadError(j.error || 'Save failed'); }
      else { setShowValidate(false); setParsedSop(null); setEditParsed(null); fetchSops(); }
    } catch { setUploadError('Network error'); }
    finally { setApproving(false); }
  };

  /* ── Upload & save directly to DB (no review step) ── */
  const handleDirectFileSave = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setDirectSaving(true); setUploadError(null);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await authFetch(`/api/v1/sops/upload-and-save?tenantId=${tenantId}`, { method: 'POST', body: fd });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setUploadError(j.error || `Save failed (${r.status})`); }
      else { fetchSops(); }
    } catch { setUploadError('Cannot connect to backend'); }
    finally { setDirectSaving(false); if (fileDirectSaveRef.current) fileDirectSaveRef.current.value = ''; }
  };

  /* ── Manual save handler ── */
  const handleManualSave = async () => {
    if (!manualForm.title.trim()) { setManualError('Title is required.'); return; }
    setSavingManual(true); setManualError(null);
    try {
      const r = await authFetch('/api/v1/sops/manual', {
        method: 'POST',
        body: JSON.stringify(manualForm),
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setManualError(j.error || 'Save failed'); }
      else {
        setShowManual(false);
        setManualForm({ title:'', category:'INFRASTRUCTURE', description:'', resolutionSteps:'', ownerTeam:'', tenantId });
        fetchSops();
      }
    } catch { setManualError('Network error'); }
    finally { setSavingManual(false); }
  };

  /* ── Paste content: parse text, show in validation modal ── */
  const handlePasteParseOnly = async () => {
    if (!pasteContent.trim()) { setPasteError('Please paste some content first.'); return; }
    setPasteParsing(true); setPasteError(null);
    try {
      const r = await authFetch('/api/v1/sops/parse-text', {
        method: 'POST',
        body: JSON.stringify({ content: pasteContent, fileName: 'pasted-content.md' }),
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setPasteError(j.error || `Parse failed (${r.status})`); }
      else {
        const parsed: ParsedSop = await r.json();
        // Switch to the validation modal so user can review & edit
        setParsedSop(parsed);
        setEditParsed({ ...parsed });
        setShowPaste(false);
        setPasteContent('');
        setShowValidate(true);
      }
    } catch { setPasteError('Cannot connect to backend'); }
    finally { setPasteParsing(false); }
  };

  /* ── Paste content: parse + save directly in one call ── */
  const handlePasteAndSave = async () => {
    if (!pasteContent.trim()) { setPasteError('Please paste some content first.'); return; }
    setPasteSaving(true); setPasteError(null);
    try {
      const r = await authFetch('/api/v1/sops/parse-and-save', {
        method: 'POST',
        body: JSON.stringify({ content: pasteContent, fileName: 'pasted-content.md', tenantId }),
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setPasteError(j.error || `Save failed (${r.status})`); }
      else {
        await r.json();
        setShowPaste(false);
        setPasteContent('');
        fetchSops();
      }
    } catch { setPasteError('Cannot connect to backend'); }
    finally { setPasteSaving(false); }
  };

  /* ── Open edit for unapproved SOP ── */
  const openEditSop = (sop: Sop) => {
    setEditSopForm({
      title: sop.title,
      category: sop.category,
      description: sop.description || '',
      resolutionSteps: (() => { try { const obj = JSON.parse(sop.actionPlanJson || ''); return typeof obj === 'string' ? obj : JSON.stringify(obj, null, 2); } catch { return sop.actionPlanJson || ''; } })(),
      ownerTeam: sop.ownerTeam || '',
    });
    setEditSopError(null);
    setShowEditSop(true);
  };

  /* ── Save edited SOP ── */
  const handleEditSopSave = async () => {
    if (!editSopForm || !selected) return;
    if (!editSopForm.title.trim()) { setEditSopError('Title is required.'); return; }
    setSavingEditSop(true); setEditSopError(null);
    try {
      const r = await authFetch(`/api/v1/sops/${selected.id}`, {
        method: 'PUT',
        body: JSON.stringify({
          title: editSopForm.title,
          category: editSopForm.category,
          description: editSopForm.description,
          resolutionSteps: editSopForm.resolutionSteps,
          ownerTeam: editSopForm.ownerTeam,
          tenantId,
        }),
      });
      if (!r.ok) { const j = await r.json().catch(() => ({})); setEditSopError(j.error || `Save failed (${r.status})`); }
      else {
        setShowEditSop(false);
        setEditSopForm(null);
        setSelected(null);
        fetchSops();
      }
    } catch { setEditSopError('Network error'); }
    finally { setSavingEditSop(false); }
  };

  const categories = ['ALL', ...Array.from(new Set(sops.map(s => s.category)))];
  const filtered = filter === 'ALL' ? sops : sops.filter(s => s.category === filter);

  const getRelClass = (r: number) => r >= 0.85 ? '' : r >= 0.65 ? 'amber' : 'red';
  const getSopStatusClass = (s: string) => s === 'ACTIVE' ? 'sop-active' : s === 'DRAFT' ? 'sop-draft' : 'sop-stale';

  if (loading) return <div className="loading-state" style={{padding:80}}>Loading SOPs…</div>;

  return (
    <div className="content">
      {/* hidden file inputs */}
      <input
        ref={fileInputRef} type="file" accept=".pdf,.docx,.xlsx,.xls,.txt,.md"
        style={{ display:'none' }} onChange={handleFileChange}
      />
      <input
        ref={fileDirectSaveRef} type="file" accept=".pdf,.docx,.xlsx,.xls,.txt,.md"
        style={{ display:'none' }} onChange={handleDirectFileSave}
      />

      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:24, flexWrap:'wrap', gap:10}}>
        <div>
          <div style={{fontFamily:'var(--mono)',fontSize:11,color:'var(--text-muted)',letterSpacing:2,textTransform:'uppercase',marginBottom:4}}>KNOWLEDGE BASE</div>
          <div style={{fontFamily:'var(--mono)',fontSize:20,fontWeight:700,color:'var(--text)'}}>{sops.length} Standard Operating Procedures</div>
        </div>
        <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
          <button className="btn btn-modify" onClick={fetchSops} style={{fontSize:11}}>⟳ REFRESH</button>
          <button
            onClick={() => fileInputRef.current?.click()}
            disabled={uploading}
            style={actnBtnStyle('var(--green-dim)','rgba(48,217,156,0.35)','var(--green)')}>
            {uploading ? '⏳ PARSING…' : '⬆ UPLOAD & REVIEW'}
          </button>
          <button
            onClick={() => fileDirectSaveRef.current?.click()}
            disabled={directSaving}
            style={actnBtnStyle('rgba(48,217,156,0.2)','rgba(48,217,156,0.45)','var(--green)')}>
            {directSaving ? '⏳ SAVING…' : '⚡ UPLOAD & SAVE'}
          </button>
          <button
            onClick={() => { setPasteError(null); setPasteContent(''); setShowPaste(true); }}
            style={actnBtnStyle('rgba(181,123,255,0.1)','rgba(181,123,255,0.35)','#b57bff')}>
            📋 PASTE CONTENT
          </button>
          <button
            onClick={() => { setManualError(null); setShowManual(true); }}
            style={actnBtnStyle('var(--blue-dim)','rgba(79,142,247,0.35)','var(--blue)')}>
            + ADD MANUAL
          </button>
        </div>
      </div>
      {uploadError && <div className="error-banner" style={{marginBottom:12}}>⚠ {uploadError}</div>}

      {/* Category filter tabs */}
      <div className="tabs" style={{marginBottom:20}}>
        {categories.map(cat => (
          <div key={cat} className={`tab ${filter === cat ? 'active' : ''}`} onClick={() => setFilter(cat)}>{cat}</div>
        ))}
      </div>

      {error && <div className="error-banner">⚠ {error}</div>}

      {/* Table view + Detail split */}
      <div className="sop-layout">
        <div>
          {filtered.length === 0 ? (
            <div className="empty-state-msg">No SOPs for this category.</div>
          ) : filtered.map(sop => (
            <div
              key={sop.id}
              className={`sop-list-item ${selected?.id === sop.id ? 'selected' : ''}`}
              onClick={() => setSelected(sop)}
            >
              <div className="sop-list-title">{sop.title}</div>
              <div className="sop-list-meta">
                <span
                  className="sop-category-tag"
                  style={{background: CATEGORY_COLORS[sop.category] || 'var(--surface3)', color: CATEGORY_TEXT[sop.category] || 'var(--text-dim)'}}
                >{sop.category}</span>
                <span className={`sop-status ${getSopStatusClass(sop.status)}`}>{sop.status}</span>
                <span style={{fontFamily:'var(--mono)',fontSize:10,color:(sop.reliabilityScore||0) >= 0.85 ? 'var(--green)' : (sop.reliabilityScore||0) >= 0.65 ? 'var(--amber)' : 'var(--red)'}}>
                  ★ {((sop.reliabilityScore||0)*100).toFixed(0)}%
                </span>
              </div>
            </div>
          ))}
        </div>

        <div className="sop-detail-panel">
          {!selected ? (
            <div className="sop-detail-empty">← Select a SOP to view details</div>
          ) : (
            <>
              <div className="sop-detail-header">
                <div className="sop-detail-title">{selected.title}</div>
                <div style={{display:'flex',gap:8,flexWrap:'wrap',alignItems:'center'}}>
                  <span className="sop-category-tag" style={{background: CATEGORY_COLORS[selected.category] || 'var(--surface3)', color: CATEGORY_TEXT[selected.category] || 'var(--text-dim)', fontSize:10, padding:'3px 8px', borderRadius:4}}>{selected.category}</span>
                  <span className={`sop-status ${getSopStatusClass(selected.status)}`}>{selected.status}</span>
                  {selected.version && <span style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>v{selected.version}</span>}
                  {/* Edit button for unapproved (DRAFT) SOPs */}
                  {(selected.status === 'DRAFT' || selected.status === 'STALE') && (
                    <button
                      onClick={() => openEditSop(selected)}
                      style={{
                        marginLeft:'auto', padding:'5px 12px', borderRadius:6, fontSize:11,
                        fontFamily:'var(--mono)', fontWeight:700, cursor:'pointer',
                        border:'1px solid rgba(37,99,235,0.35)',
                        background:'rgba(37,99,235,0.08)', color:'var(--blue)', letterSpacing:0.5
                      }}>
                      ✎ EDIT SOP
                    </button>
                  )}
                </div>
              </div>
              <div className="sop-detail-body">
                {/* Reliability bar */}
                <div style={{background:'var(--surface2)',borderRadius:8,padding:'16px 20px',marginBottom:16,border:'1px solid var(--border)'}}>
                  <div style={{display:'flex',justifyContent:'space-between',marginBottom:8}}>
                    <span style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>Reliability Score</span>
                    <span style={{fontFamily:'var(--mono)',fontSize:16,fontWeight:700,color:(selected.reliabilityScore||0) >= 0.85 ? 'var(--green)' : (selected.reliabilityScore||0) >= 0.65 ? 'var(--amber)' : 'var(--red)'}}>
                      {((selected.reliabilityScore||0)*100).toFixed(0)}%
                    </span>
                  </div>
                  <div style={{background:'var(--surface3)',borderRadius:4,height:8,overflow:'hidden'}}>
                    <div style={{height:'100%',borderRadius:4,width:`${(selected.reliabilityScore||0)*100}%`,background:(selected.reliabilityScore||0)>=0.85?'var(--green)':(selected.reliabilityScore||0)>=0.65?'var(--amber)':'var(--red)',transition:'width 0.8s ease'}}></div>
                  </div>
                  <div style={{display:'flex',justifyContent:'space-between',marginTop:8}}>
                    <span style={{fontSize:12,color:'var(--green)'}}>✓ {selected.successCount} Succeeded</span>
                    <span style={{fontSize:12,color:'var(--red)'}}>✗ {selected.failureCount} Failed</span>
                  </div>
                </div>

                <div className="detail-row"><span className="detail-label">Scope:</span><span className="detail-val">{selected.scope || '—'}</span></div>
                {selected.ownerTeam && <div className="detail-row"><span className="detail-label">Owner Team:</span><span className="detail-val">{selected.ownerTeam}</span></div>}
                <div className="detail-row"><span className="detail-label">Created:</span><span className="detail-val">{new Date(selected.createdAt).toLocaleDateString()}</span></div>
                {selected.description && (
                  <div style={{background:'var(--surface2)',borderRadius:6,padding:'12px 14px',marginTop:12,marginBottom:12,fontSize:13,color:'var(--text-dim)',lineHeight:1.6}}>
                    {selected.description}
                  </div>
                )}

                {selected.actionPlanJson && (
                  <div className="sop-action-plan">
                    <h4>Action Plan</h4>
                    <pre>{(() => { try { return JSON.stringify(JSON.parse(selected.actionPlanJson), null, 2); } catch { return selected.actionPlanJson; } })()}</pre>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Summary table */}
      <div className="card" style={{marginTop:24,marginBottom:40}}>
        <div className="card-header">
          <div className="card-title">SOP Library — All Procedures</div>
          <div style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>{sops.length} TOTAL</div>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Title</th><th>Category</th><th>Reliability</th>
              <th>Success</th><th>Failed</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {sops.map(sop => (
              <tr key={sop.id} onClick={() => setSelected(sop)} style={{cursor:'pointer'}}>
                <td style={{color:'var(--text)',fontWeight:500}}>{sop.title}</td>
                <td>
                  <span style={{fontFamily:'var(--mono)',fontSize:9,color: CATEGORY_TEXT[sop.category] || 'var(--text-dim)'}}>
                    {sop.category}
                  </span>
                </td>
                <td>
                  <div className="reliability">
                    <div className="rel-bar"><div className={`rel-fill ${getRelClass(sop.reliabilityScore||0)}`} style={{width:`${(sop.reliabilityScore||0)*100}%`}}></div></div>
                    <span style={{fontFamily:'var(--mono)',fontSize:11,color:(sop.reliabilityScore||0)>=0.85?'var(--green)':(sop.reliabilityScore||0)>=0.65?'var(--amber)':'var(--red)'}}>
                      {((sop.reliabilityScore||0)*100).toFixed(0)}%
                    </span>
                  </div>
                </td>
                <td style={{fontFamily:'var(--mono)',color:'var(--green)'}}>{sop.successCount}</td>
                <td style={{fontFamily:'var(--mono)',color:'var(--red)'}}>{sop.failureCount}</td>
                <td><span className={`sop-status ${getSopStatusClass(sop.status)}`}>{sop.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ── Validation popup (post-parse) ── */}
      {showValidate && editParsed && (
        <div style={overlayStyle}>
          <div style={modalStyle(560)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--green)', marginBottom:4, letterSpacing:1 }}>
              ✓ DOCUMENT PARSED
            </div>
            {parsedSop?.sourceFileName && (
              <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:16 }}>{parsedSop.sourceFileName}</div>
            )}
            {parsedSop?.warnings && parsedSop.warnings.length > 0 && (
              <div style={            { background:'rgba(217,119,6,0.08)', border:'1px solid rgba(217,119,6,0.25)', color:'var(--amber)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                ⚠ {parsedSop.warnings.join(' · ')}
              </div>
            )}
            <div style={{ fontSize:12, color:'var(--text-muted)', marginBottom:14 }}>Review and edit the extracted fields before saving:</div>

            <label style={lblStyle}>Title *</label>
            <input value={editParsed.title} onChange={e => setEditParsed(p => p ? {...p, title:e.target.value} : p)} style={inpStyle} />

            <label style={lblStyle}>Category</label>
            <select value={editParsed.category} onChange={e => setEditParsed(p => p ? {...p, category:e.target.value} : p)} style={inpStyle}>
              {SOP_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>

            <label style={lblStyle}>Description</label>
            <textarea value={editParsed.description} onChange={e => setEditParsed(p => p ? {...p, description:e.target.value} : p)} rows={3} style={{ ...inpStyle, resize:'vertical', minHeight:60 }} />

            <label style={lblStyle}>Resolution Steps</label>
            <textarea value={editParsed.resolutionSteps} onChange={e => setEditParsed(p => p ? {...p, resolutionSteps:e.target.value} : p)} rows={5} style={{ ...inpStyle, resize:'vertical', minHeight:100, fontFamily:'var(--mono)', fontSize:11 }} />

            {uploadError && <div style={{ color:'var(--red)', fontSize:12, marginTop:8 }}>⚠ {uploadError}</div>}

            <div style={{ display:'flex', gap:10, marginTop:20 }}>
              <button onClick={() => { setShowValidate(false); setParsedSop(null); setEditParsed(null); setUploadError(null); }} style={cancelBtnStyle}>DISCARD</button>
              <button onClick={handleApproveUpload} disabled={approving} style={saveBtnStyle(approving)}>
                {approving ? '⏳ SAVING…' : '✓ APPROVE & SAVE'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Manual SOP entry modal ── */}
      {showManual && (
        <div style={overlayStyle}>
          <div style={modalStyle(520)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--blue)', marginBottom:20, letterSpacing:1 }}>
              + ADD SOP MANUALLY
            </div>
            {manualError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                {manualError}
              </div>
            )}
            <label style={lblStyle}>Title *</label>
            <input value={manualForm.title} onChange={e => setManualForm(f => ({...f, title:e.target.value}))} placeholder="Incident title…" style={inpStyle} />

            <label style={lblStyle}>Category</label>
            <select value={manualForm.category} onChange={e => setManualForm(f => ({...f, category:e.target.value}))} style={inpStyle}>
              {SOP_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>

            <label style={lblStyle}>Description</label>
            <textarea value={manualForm.description} onChange={e => setManualForm(f => ({...f, description:e.target.value}))} rows={3} placeholder="Short description of the problem and context…" style={{ ...inpStyle, resize:'vertical', minHeight:60 }} />

            <label style={lblStyle}>Resolution Steps</label>
            <textarea value={manualForm.resolutionSteps} onChange={e => setManualForm(f => ({...f, resolutionSteps:e.target.value}))} rows={6} placeholder={"1. Check logs\n2. Restart service\n3. Verify recovery"} style={{ ...inpStyle, resize:'vertical', minHeight:110, fontFamily:'var(--mono)', fontSize:11 }} />

            <label style={lblStyle}>Owner Team <span style={{ color:'var(--text-muted)', fontSize:10, textTransform:'none' }}>(optional)</span></label>
            <input value={manualForm.ownerTeam} onChange={e => setManualForm(f => ({...f, ownerTeam:e.target.value}))} placeholder="e.g. Platform Engineering" style={inpStyle} />

            <div style={{ display:'flex', gap:10, marginTop:20 }}>
              <button onClick={() => { setShowManual(false); setManualError(null); }} style={cancelBtnStyle}>CANCEL</button>
              <button onClick={handleManualSave} disabled={savingManual} style={saveBtnStyle(savingManual)}>
                {savingManual ? '⏳ SAVING…' : '✓ SAVE AS DRAFT'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Edit SOP modal (for DRAFT/STALE SOPs) ── */}
      {showEditSop && editSopForm && (
        <div style={overlayStyle}>
          <div style={modalStyle(540)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--blue)', marginBottom:4, letterSpacing:1 }}>
              ✎ EDIT SOP
            </div>
            <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:18 }}>
              Editing is available for DRAFT and STALE SOPs only.
            </div>
            {editSopError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                {editSopError}
              </div>
            )}

            <label style={lblStyle}>Title *</label>
            <input
              value={editSopForm.title}
              onChange={e => setEditSopForm(f => f ? {...f, title:e.target.value} : f)}
              style={inpStyle}
            />

            <label style={lblStyle}>Category</label>
            <select
              value={editSopForm.category}
              onChange={e => setEditSopForm(f => f ? {...f, category:e.target.value} : f)}
              style={inpStyle}>
              {SOP_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>

            <label style={lblStyle}>Description</label>
            <textarea
              value={editSopForm.description}
              onChange={e => setEditSopForm(f => f ? {...f, description:e.target.value} : f)}
              rows={3}
              style={{ ...inpStyle, resize:'vertical', minHeight:60 }}
            />

            <label style={lblStyle}>Resolution Steps</label>
            <textarea
              value={editSopForm.resolutionSteps}
              onChange={e => setEditSopForm(f => f ? {...f, resolutionSteps:e.target.value} : f)}
              rows={7}
              placeholder={"1. Check logs\n2. Restart service\n3. Verify recovery"}
              style={{ ...inpStyle, resize:'vertical', minHeight:120, fontFamily:'var(--mono)', fontSize:11 }}
            />

            <label style={lblStyle}>Owner Team <span style={{ color:'var(--text-muted)', fontSize:10, textTransform:'none' }}>(optional)</span></label>
            <input
              value={editSopForm.ownerTeam}
              onChange={e => setEditSopForm(f => f ? {...f, ownerTeam:e.target.value} : f)}
              placeholder="e.g. Platform Engineering"
              style={inpStyle}
            />

            <div style={{ display:'flex', gap:10, marginTop:20 }}>
              <button
                onClick={() => { setShowEditSop(false); setEditSopForm(null); setEditSopError(null); }}
                style={cancelBtnStyle}>
                CANCEL
              </button>
              <button onClick={handleEditSopSave} disabled={savingEditSop} style={saveBtnStyle(savingEditSop)}>
                {savingEditSop ? '⏳ SAVING…' : '✓ UPDATE SOP'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Paste Content modal — parse raw text directly into DB ── */}
      {showPaste && (
        <div style={overlayStyle}>
          <div style={modalStyle(620)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'#b57bff', marginBottom:4, letterSpacing:1 }}>
              📋 PASTE SOP CONTENT
            </div>
            <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:14 }}>
              Paste your SOP text, Markdown, or document content below. The system will extract Title, Category, Description, and Resolution Steps automatically — no file upload needed.
            </div>

            {pasteError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                ⚠ {pasteError}
              </div>
            )}

            <textarea
              value={pasteContent}
              onChange={e => setPasteContent(e.target.value)}
              rows={18}
              placeholder={`Paste your SOP content here...\n\nExample:\n# SOP: Tomcat API URL Not Accessible\n\n**Category:** APPLICATION\n**Severity:** SEV-2\n\n## Description\nAPI endpoint hosted on Tomcat returns 502/503...\n\n## Resolution Steps\n1. Check Tomcat status: sudo systemctl status tomcat\n2. Restart service: sudo systemctl restart tomcat\n3. Verify: curl http://localhost:8080/health`}
              style={{
                ...inpStyle,
                resize:'vertical',
                minHeight:320,
                fontFamily:'var(--mono)',
                fontSize:11,
                lineHeight:'1.6',
                whiteSpace:'pre',
                tabSize:4,
              }}
              spellCheck={false}
            />

            <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:6, marginBottom:10 }}>
              {pasteContent.length > 0
                ? `${pasteContent.length} characters · ${pasteContent.split('\n').length} lines`
                : 'Supports: Markdown, plain text, SOP templates'}
            </div>

            <div style={{ display:'flex', gap:10, marginTop:10 }}>
              <button
                onClick={() => { setShowPaste(false); setPasteContent(''); setPasteError(null); }}
                style={cancelBtnStyle}>
                CANCEL
              </button>
              <button
                onClick={handlePasteParseOnly}
                disabled={pasteParsing || !pasteContent.trim()}
                style={{
                  ...saveBtnStyle(pasteParsing || !pasteContent.trim()),
                  flex:2,
                  border:'1px solid rgba(181,123,255,0.4)',
                  background:'rgba(181,123,255,0.08)',
                  color:'#b57bff',
                }}>
                {pasteParsing ? '⏳ PARSING…' : '🔍 PARSE & REVIEW'}
              </button>
              <button
                onClick={handlePasteAndSave}
                disabled={pasteSaving || !pasteContent.trim()}
                style={{
                  ...saveBtnStyle(pasteSaving || !pasteContent.trim()),
                  flex:2,
                }}>
                {pasteSaving ? '⏳ SAVING…' : '⚡ PARSE & SAVE'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const actnBtnStyle = (bg: string, border: string, color: string): React.CSSProperties => ({
  padding:'7px 14px', borderRadius:6, fontSize:11, fontFamily:'var(--mono)', fontWeight:700,
  cursor:'pointer', border:`1px solid ${border}`, background:bg, color, letterSpacing:0.5
});

const overlayStyle: React.CSSProperties = {
  position:'fixed', inset:0, background:'rgba(15,23,42,0.6)',
  display:'flex', alignItems:'center', justifyContent:'center', zIndex:1000
};
const modalStyle = (w: number): React.CSSProperties => ({
  background:'var(--surface)', border:'1px solid var(--border-bright)', borderRadius:10,
  padding:28, width:w, maxHeight:'92vh', overflowY:'auto',
  boxShadow:'0 24px 72px rgba(0,0,0,0.18)'
});
const lblStyle: React.CSSProperties = {
  display:'block', fontFamily:'var(--mono)', fontSize:10, letterSpacing:1,
  color:'var(--text-muted)', textTransform:'uppercase', marginTop:14, marginBottom:5
};
const inpStyle: React.CSSProperties = {
  width:'100%', padding:'8px 10px', borderRadius:6, fontSize:13,
  border:'1px solid var(--border-bright)', background:'var(--surface2)',
  color:'var(--text)', outline:'none', fontFamily:'var(--sans)'
};
const cancelBtnStyle: React.CSSProperties = {
  flex:1, padding:'9px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)',
  cursor:'pointer', border:'1px solid var(--border-bright)', background:'var(--surface2)', color:'var(--text-dim)'
};
const saveBtnStyle = (disabled: boolean): React.CSSProperties => ({
  flex:2, padding:'9px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)', fontWeight:700,
  cursor: disabled ? 'not-allowed' : 'pointer',
  border:'1px solid rgba(37,99,235,0.4)',
  background:'var(--blue-dim)', color:'var(--blue)',
  opacity: disabled ? 0.6 : 1
});

export default SopPage;
