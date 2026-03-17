import React, { useState, useEffect } from 'react';
import { authFetch, extractApiError, SIMPLE_ERROR_MESSAGE } from '../services/api';

interface Sop {
  id: string; title: string; scope: string;
  status: string; reliabilityScore: number; successCount: number;
  failureCount: number; actionPlanJson?: string; description?: string;
  ownerTeam?: string; createdAt: string; version?: string;
  linkedToolId?: string; linkedToolName?: string; linkedScriptName?: string;
}

interface ParsedSop {
  title: string; description: string;
  resolutionSteps: string; mcpToolScript?: string; sourceFileName?: string; warnings?: string[];
}

interface ManualForm {
  title: string; description: string;
  resolutionSteps: string; ownerTeam: string; tenantId: string;
}

const SopPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [sops, setSops]       = useState<Sop[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState<string | null>(null);
  const [selected, setSelected] = useState<Sop | null>(null);

  /* ── Upload / parse state ── */
  const [uploading, setUploading]       = useState(false);
  const [parsedSop, setParsedSop]       = useState<ParsedSop | null>(null);
  const [showValidate, setShowValidate] = useState(false);
  const [editParsed, setEditParsed]     = useState<ParsedSop | null>(null);
  const [approving, setApproving]       = useState(false);
  const [uploadError, setUploadError]   = useState<string | null>(null);
  const [showUploadPopup, setShowUploadPopup] = useState(false);
  const [uploadFile, setUploadFile]     = useState<File | null>(null);

  /* ── Manual entry state ── */
  const [showManual, setShowManual] = useState(false);
  const [manualForm, setManualForm] = useState<ManualForm>({
    title:'', description:'',
    resolutionSteps:'', ownerTeam:'', tenantId,
  });
  const [savingManual, setSavingManual] = useState(false);
  const [manualError, setManualError]   = useState<string | null>(null);

  /* ── Paste content state ── */
  const [showPaste, setShowPaste]         = useState(false);
  const [pasteContent, setPasteContent]   = useState('');
  const [pasteParsing, setPasteParsing]   = useState(false);
  const [pasteError, setPasteError]       = useState<string | null>(null);
  const [pasteSaving, setPasteSaving]     = useState(false);

  /* ── Inline SOP edit state ── */
  const [showEditSop, setShowEditSop] = useState(false);
  const [editSopForm, setEditSopForm] = useState<{
    title: string; description: string;
    resolutionSteps: string; ownerTeam: string;
  } | null>(null);
  const [savingEditSop, setSavingEditSop] = useState(false);
  const [editSopError, setEditSopError]   = useState<string | null>(null);
  const [deletingSop, setDeletingSop]     = useState(false);
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false);
  const [deleteSopError, setDeleteSopError] = useState<string | null>(null);

  const fetchSops = async () => {
    try {
      const r = await authFetch(`/api/v1/sops?tenantId=${tenantId}`);
      if (r.ok) { const d = await r.json(); setSops(Array.isArray(d) ? d : d.sops || []); setError(null); }
      else setError(await extractApiError(r));
    } catch { setError(SIMPLE_ERROR_MESSAGE); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchSops(); }, [tenantId]);

  /* ── Generate MCP tool bash script from resolution steps ── */
  const generateMcpBashScript = (title: string, steps: string): string => {
    const safeName = title.toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_|_$/g, '');
    const stepLines = steps.split('\n').filter(l => l.trim()).map(l => `# ${l.trim()}`).join('\n');
    return `#!/bin/bash\n# MCP Tool: ${title}\n# Auto-generated remediation script\n# Tool ID: ${safeName}\n\nset -euo pipefail\n\necho "[MCP] Starting: ${title}"\n\n${stepLines}\n\necho "[MCP] Completed: ${title}"\n`;
  };

  /* ── Upload & Review ── */
  const handleUploadAndReview = async () => {
    if (!uploadFile) return;
    setUploading(true); setUploadError(null);
    const fd = new FormData();
    fd.append('file', uploadFile);
    try {
      const r = await authFetch('/api/v1/sops/parse', { method: 'POST', body: fd });
      if (!r.ok) { setUploadError(await extractApiError(r)); }
      else {
        const parsed: ParsedSop = await r.json();
        const mcpScript = parsed.mcpToolScript || generateMcpBashScript(parsed.title, parsed.resolutionSteps);
        setParsedSop({ ...parsed, mcpToolScript: mcpScript });
        setEditParsed({ ...parsed, mcpToolScript: mcpScript });
        setShowUploadPopup(false);
        setUploadFile(null);
        setShowValidate(true);
      }
    } catch { setUploadError(SIMPLE_ERROR_MESSAGE); }
    finally { setUploading(false); }
  };

  const handleApproveUpload = async () => {
    if (!editParsed) return;
    setApproving(true);
    try {
      const r = await authFetch('/api/v1/sops/manual', {
        method: 'POST',
        body: JSON.stringify({ ...editParsed, tenantId }),
      });
      if (!r.ok) { setUploadError(await extractApiError(r)); }
      else { setShowValidate(false); setParsedSop(null); setEditParsed(null); fetchSops(); }
    } catch { setUploadError(SIMPLE_ERROR_MESSAGE); }
    finally { setApproving(false); }
  };

  /* ── Manual save ── */
  const handleManualSave = async () => {
    if (!manualForm.title.trim()) { setManualError('Title is required.'); return; }
    setSavingManual(true); setManualError(null);
    try {
      const r = await authFetch('/api/v1/sops/manual', {
        method: 'POST',
        body: JSON.stringify(manualForm),
      });
      if (!r.ok) { setManualError(await extractApiError(r)); }
      else {
        setShowManual(false);
        setManualForm({ title:'', description:'', resolutionSteps:'', ownerTeam:'', tenantId });
        fetchSops();
      }
    } catch { setManualError(SIMPLE_ERROR_MESSAGE); }
    finally { setSavingManual(false); }
  };

  /* ── Paste content: parse text ── */
  const handlePasteParseOnly = async () => {
    if (!pasteContent.trim()) { setPasteError('Please paste some content first.'); return; }
    setPasteParsing(true); setPasteError(null);
    try {
      const r = await authFetch('/api/v1/sops/parse-text', {
        method: 'POST',
        body: JSON.stringify({ content: pasteContent, fileName: 'pasted-content.md' }),
      });
      if (!r.ok) { setPasteError(await extractApiError(r)); }
      else {
        const parsed: ParsedSop = await r.json();
        const mcpScript = parsed.mcpToolScript || generateMcpBashScript(parsed.title, parsed.resolutionSteps);
        setParsedSop({ ...parsed, mcpToolScript: mcpScript });
        setEditParsed({ ...parsed, mcpToolScript: mcpScript });
        setShowPaste(false);
        setPasteContent('');
        setShowValidate(true);
      }
    } catch { setPasteError(SIMPLE_ERROR_MESSAGE); }
    finally { setPasteParsing(false); }
  };

  /* ── Paste content: parse + save ── */
  const handlePasteAndSave = async () => {
    if (!pasteContent.trim()) { setPasteError('Please paste some content first.'); return; }
    setPasteSaving(true); setPasteError(null);
    try {
      const r = await authFetch('/api/v1/sops/parse-and-save', {
        method: 'POST',
        body: JSON.stringify({ content: pasteContent, fileName: 'pasted-content.md', tenantId }),
      });
      if (!r.ok) { setPasteError(await extractApiError(r)); }
      else { await r.json(); setShowPaste(false); setPasteContent(''); fetchSops(); }
    } catch { setPasteError(SIMPLE_ERROR_MESSAGE); }
    finally { setPasteSaving(false); }
  };

  /* ── Open edit for unapproved SOP ── */
  const openEditSop = (sop: Sop) => {
    setEditSopForm({
      title: sop.title,
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
          description: editSopForm.description,
          resolutionSteps: editSopForm.resolutionSteps,
          ownerTeam: editSopForm.ownerTeam,
          tenantId,
        }),
      });
      if (!r.ok) { setEditSopError(await extractApiError(r)); }
      else { setShowEditSop(false); setEditSopForm(null); setSelected(null); fetchSops(); }
    } catch { setEditSopError(SIMPLE_ERROR_MESSAGE); }
    finally { setSavingEditSop(false); }
  };

  const openDeleteSopConfirm = () => {
    if (!selected) return;
    setDeleteSopError(null);
    setShowDeleteConfirm(true);
  };

  const handleDeleteSop = async () => {
    if (!selected) return;

    setDeletingSop(true);
    setDeleteSopError(null);
    try {
      const r = await authFetch(`/api/v1/sops/${selected.id}`, { method: 'DELETE' });
      if (!r.ok) {
        const apiError = await extractApiError(r);
        setDeleteSopError(apiError);
        setError(apiError);
        return;
      }
      setShowDeleteConfirm(false);
      setSelected(null);
      setError(null);
      await fetchSops();
    } catch {
      setDeleteSopError(SIMPLE_ERROR_MESSAGE);
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setDeletingSop(false);
    }
  };

  const getRelClass = (r: number) => r >= 0.85 ? '' : r >= 0.65 ? 'amber' : 'red';
  const getSopStatusClass = (s: string) => s === 'ACTIVE' ? 'sop-active' : s === 'DRAFT' ? 'sop-draft' : 'sop-stale';
  const openLinkedTool = (toolId?: string) => {
    if (!toolId) return;
    const url = new URL(window.location.href);
    url.pathname = '/tools';
    url.searchParams.set('tool', toolId);
    window.history.pushState(null, '', url.pathname + url.search);
    window.dispatchEvent(new PopStateEvent('popstate'));
  };

  if (loading) return <div className="loading-state" style={{padding:80}}>Loading SOPs…</div>;

  return (
    <div className="content">
      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:24, flexWrap:'wrap', gap:10}}>
        <div>
          <div style={{fontFamily:'var(--mono)',fontSize:11,color:'var(--text-muted)',letterSpacing:2,textTransform:'uppercase',marginBottom:4}}>KNOWLEDGE BASE</div>
          <div style={{fontFamily:'var(--mono)',fontSize:20,fontWeight:700,color:'var(--text)'}}>{sops.length} Standard Operating Procedures</div>
        </div>
        <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
          <button className="btn btn-modify" onClick={fetchSops} style={{fontSize:11}}>⟳ REFRESH</button>
          <button
            onClick={() => { setUploadFile(null); setUploadError(null); setShowUploadPopup(true); }}
            style={actnBtnStyle('var(--green-dim)','rgba(48,217,156,0.35)','var(--green)')}>
            ⬆ UPLOAD & REVIEW
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

      {error && <div className="error-banner">⚠ {error}</div>}

      {/* SOP list + Detail split */}
      <div className="sop-layout">
        <div>
          {sops.length === 0 ? (
            <div className="empty-state-msg">No SOPs found. Upload or add one above.</div>
          ) : sops.map(sop => (
            <div
              key={sop.id}
              className={`sop-list-item ${selected?.id === sop.id ? 'selected' : ''}`}
              onClick={() => setSelected(sop)}
            >
              <div className="sop-list-title">{sop.title}</div>
              <div className="sop-list-meta">
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
                  <span className={`sop-status ${getSopStatusClass(selected.status)}`}>{selected.status}</span>
                  {selected.version && <span style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>v{selected.version}</span>}
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
                  <button
                    onClick={openDeleteSopConfirm}
                    disabled={deletingSop}
                    style={{
                      padding:'5px 12px', borderRadius:6, fontSize:11,
                      fontFamily:'var(--mono)', fontWeight:700,
                      cursor: deletingSop ? 'not-allowed' : 'pointer',
                      border:'1px solid rgba(220,38,38,0.35)',
                      background:'rgba(220,38,38,0.08)', color:'var(--red)', letterSpacing:0.5,
                      opacity: deletingSop ? 0.6 : 1
                    }}>
                    {deletingSop ? '… DELETING' : '✕ DELETE SOP'}
                  </button>
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
                {selected.linkedToolName && (
                  <div className="detail-row">
                    <span className="detail-label">MCP Tool:</span>
                    <button
                      type="button"
                      onClick={() => openLinkedTool(selected.linkedToolId)}
                      style={{
                        background: 'transparent',
                        border: 'none',
                        color: 'var(--blue)',
                        cursor: selected.linkedToolId ? 'pointer' : 'default',
                        padding: 0,
                        fontSize: 13,
                        textDecoration: 'underline',
                        fontFamily: 'inherit'
                      }}>
                      {selected.linkedToolName}
                    </button>
                  </div>
                )}
                {selected.linkedScriptName && <div className="detail-row"><span className="detail-label">Linked Script:</span><span className="detail-val">{selected.linkedScriptName}</span></div>}
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
              <th>Title</th><th>Reliability</th>
              <th>Success</th><th>Failed</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {sops.map(sop => (
              <tr key={sop.id} onClick={() => setSelected(sop)} style={{cursor:'pointer'}}>
                <td style={{color:'var(--text)',fontWeight:500}}>{sop.title}</td>
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

      {/* ── Upload popup ── */}
      {showUploadPopup && (
        <div style={overlayStyle}>
          <div style={modalStyle(480)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--green)', marginBottom:4, letterSpacing:1 }}>
              ⬆ UPLOAD & REVIEW
            </div>
            <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:18 }}>
              Choose a document to parse. The system will use AI to extract Title, Description, Resolution Steps and generate an MCP Tool script.
            </div>
            {uploadError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                ⚠ {uploadError}
              </div>
            )}
            <label style={lblStyle}>Choose File</label>
            <input type="file" accept=".pdf,.docx,.xlsx,.xls,.txt,.md"
              onChange={e => setUploadFile(e.target.files?.[0] || null)}
              style={{ ...inpStyle, padding:'10px', cursor:'pointer' }} />
            {uploadFile && (
              <div style={{ fontSize:11, color:'var(--green)', marginTop:8, marginBottom:4, fontFamily:'var(--mono)' }}>
                ✓ {uploadFile.name} ({(uploadFile.size / 1024).toFixed(1)} KB)
              </div>
            )}
            <div style={{ display:'flex', gap:10, marginTop:22 }}>
              <button onClick={() => { setShowUploadPopup(false); setUploadFile(null); setUploadError(null); }} style={cancelBtnStyle}>CANCEL</button>
              <button onClick={handleUploadAndReview} disabled={uploading || !uploadFile}
                style={{ ...saveBtnStyle(uploading || !uploadFile), border:'1px solid rgba(48,217,156,0.4)', background: uploading ? 'rgba(48,217,156,0.15)' : 'rgba(48,217,156,0.08)', color:'var(--green)' }}>
                {uploading ? '⏳ PARSING WITH AI…' : '🔍 SAVE & REVIEW'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Validation popup (post-parse) ── */}
      {showValidate && editParsed && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle(720), maxHeight:'95vh' }}>
            <div style={{ fontFamily:'var(--mono)', fontSize:14, fontWeight:700, color:'var(--green)', marginBottom:6, letterSpacing:1 }}>
              ✓ AI EXTRACTION COMPLETE
            </div>
            {parsedSop?.sourceFileName && (
              <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:8 }}>📄 {parsedSop.sourceFileName}</div>
            )}
            {parsedSop?.warnings && parsedSop.warnings.length > 0 && (
              <div style={{ background:'rgba(217,119,6,0.08)', border:'1px solid rgba(217,119,6,0.25)', color:'var(--amber)', padding:'10px 14px', borderRadius:6, fontSize:12, marginBottom:14, lineHeight:1.5 }}>
                {parsedSop.warnings.map((w, i) => <div key={i}>⚠ {w}</div>)}
              </div>
            )}
            <div style={{ fontSize:12, color:'var(--text-muted)', marginBottom:14 }}>Review and edit the extracted fields below. All fields are editable before saving.</div>

            <label style={lblStyle}>Title *</label>
            <input value={editParsed.title} onChange={e => setEditParsed(p => p ? {...p, title:e.target.value} : p)} style={{ ...inpStyle, fontSize:14, fontWeight:600, padding:'10px 12px' }} />

            <label style={lblStyle}>Description</label>
            <textarea value={editParsed.description} onChange={e => setEditParsed(p => p ? {...p, description:e.target.value} : p)} rows={5} style={{ ...inpStyle, resize:'vertical', minHeight:90, lineHeight:'1.6' }} />

            <label style={lblStyle}>Resolution Steps</label>
            <textarea value={editParsed.resolutionSteps} onChange={e => setEditParsed(p => p ? {...p, resolutionSteps:e.target.value} : p)} rows={10} style={{ ...inpStyle, resize:'vertical', minHeight:180, fontFamily:'var(--mono)', fontSize:11, lineHeight:'1.6', whiteSpace:'pre-wrap' }} />

            <label style={lblStyle}>MCP Tool Script (Auto)</label>
            <textarea value={editParsed.mcpToolScript || ''} onChange={e => setEditParsed(p => p ? {...p, mcpToolScript:e.target.value} : p)} rows={14} style={{ ...inpStyle, resize:'vertical', minHeight:240, fontFamily:'var(--mono)', fontSize:11, background:'rgba(15,23,42,0.8)', color:'var(--green)', lineHeight:'1.6', whiteSpace:'pre', tabSize:4, padding:'14px 12px', borderRadius:8 }} />

            {uploadError && <div style={{ color:'var(--red)', fontSize:12, marginTop:8 }}>⚠ {uploadError}</div>}

            <div style={{ display:'flex', gap:10, marginTop:22 }}>
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

      {/* ── Edit SOP modal ── */}
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
            <input value={editSopForm.title} onChange={e => setEditSopForm(f => f ? {...f, title:e.target.value} : f)} style={inpStyle} />

            <label style={lblStyle}>Description</label>
            <textarea value={editSopForm.description} onChange={e => setEditSopForm(f => f ? {...f, description:e.target.value} : f)} rows={3} style={{ ...inpStyle, resize:'vertical', minHeight:60 }} />

            <label style={lblStyle}>Resolution Steps</label>
            <textarea value={editSopForm.resolutionSteps} onChange={e => setEditSopForm(f => f ? {...f, resolutionSteps:e.target.value} : f)} rows={7}
              placeholder={"1. Check logs\n2. Restart service\n3. Verify recovery"} style={{ ...inpStyle, resize:'vertical', minHeight:120, fontFamily:'var(--mono)', fontSize:11 }} />

            <label style={lblStyle}>Owner Team <span style={{ color:'var(--text-muted)', fontSize:10, textTransform:'none' }}>(optional)</span></label>
            <input value={editSopForm.ownerTeam} onChange={e => setEditSopForm(f => f ? {...f, ownerTeam:e.target.value} : f)} placeholder="e.g. Platform Engineering" style={inpStyle} />

            <div style={{ display:'flex', gap:10, marginTop:20 }}>
              <button onClick={() => { setShowEditSop(false); setEditSopForm(null); setEditSopError(null); }} style={cancelBtnStyle}>CANCEL</button>
              <button onClick={handleEditSopSave} disabled={savingEditSop} style={saveBtnStyle(savingEditSop)}>
                {savingEditSop ? '⏳ SAVING…' : '✓ UPDATE SOP'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Delete SOP confirm modal ── */}
      {showDeleteConfirm && selected && (
        <div style={overlayStyle}>
          <div style={modalStyle(420)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--red)', marginBottom:6, letterSpacing:1 }}>
              ✕ DELETE SOP
            </div>
            <div style={{ fontSize:12, color:'var(--text-dim)', marginBottom:18, lineHeight:1.6 }}>
              Delete <strong>{selected.title}</strong> and its linked MCP tool and script. This action cannot be undone.
            </div>
            {deleteSopError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                ⚠ {deleteSopError}
              </div>
            )}
            <div style={{ display:'flex', gap:10, marginTop:20 }}>
              <button
                onClick={() => { if (!deletingSop) { setShowDeleteConfirm(false); setDeleteSopError(null); } }}
                disabled={deletingSop}
                style={{ ...cancelBtnStyle, opacity: deletingSop ? 0.6 : 1 }}>
                CANCEL
              </button>
              <button
                onClick={handleDeleteSop}
                disabled={deletingSop}
                style={{
                  ...saveBtnStyle(deletingSop),
                  flex:1,
                  border:'1px solid rgba(220,38,38,0.4)',
                  background:'rgba(220,38,38,0.08)',
                  color:'var(--red)'
                }}>
                {deletingSop ? '… DELETING' : 'DELETE'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Paste Content modal ── */}
      {showPaste && (
        <div style={overlayStyle}>
          <div style={modalStyle(620)}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'#b57bff', marginBottom:4, letterSpacing:1 }}>
              📋 PASTE SOP CONTENT
            </div>
            <div style={{ fontSize:11, color:'var(--text-muted)', marginBottom:14 }}>
              Paste your SOP text, Markdown, or document content below. The system will extract Title, Description, and Resolution Steps automatically — no file upload needed.
            </div>
            {pasteError && (
              <div style={{ background:'rgba(220,38,38,0.08)', border:'1px solid rgba(220,38,38,0.25)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:14 }}>
                ⚠ {pasteError}
              </div>
            )}
            <textarea value={pasteContent} onChange={e => setPasteContent(e.target.value)} rows={18}
              placeholder={`Paste your SOP content here...\n\nExample:\n# SOP: Tomcat API URL Not Accessible\n\n## Description\nAPI endpoint hosted on Tomcat returns 502/503...\n\n## Resolution Steps\n1. Check Tomcat status: sudo systemctl status tomcat\n2. Restart service: sudo systemctl restart tomcat\n3. Verify: curl http://localhost:8080/health`}
              style={{ ...inpStyle, resize:'vertical', minHeight:320, fontFamily:'var(--mono)', fontSize:11, lineHeight:'1.6', whiteSpace:'pre', tabSize:4 }}
              spellCheck={false} />
            <div style={{ fontSize:11, color:'var(--text-muted)', marginTop:6, marginBottom:10 }}>
              {pasteContent.length > 0 ? `${pasteContent.length} characters · ${pasteContent.split('\n').length} lines` : 'Supports: Markdown, plain text, SOP templates'}
            </div>
            <div style={{ display:'flex', gap:10, marginTop:10 }}>
              <button onClick={() => { setShowPaste(false); setPasteContent(''); setPasteError(null); }} style={cancelBtnStyle}>CANCEL</button>
              <button onClick={handlePasteParseOnly} disabled={pasteParsing || !pasteContent.trim()}
                style={{ ...saveBtnStyle(pasteParsing || !pasteContent.trim()), flex:2, border:'1px solid rgba(181,123,255,0.4)', background:'rgba(181,123,255,0.08)', color:'#b57bff' }}>
                {pasteParsing ? '⏳ PARSING…' : '🔍 PARSE & REVIEW'}
              </button>
              <button onClick={handlePasteAndSave} disabled={pasteSaving || !pasteContent.trim()}
                style={{ ...saveBtnStyle(pasteSaving || !pasteContent.trim()), flex:2 }}>
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
