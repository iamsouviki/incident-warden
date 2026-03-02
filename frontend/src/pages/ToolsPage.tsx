import React, { useState, useEffect } from 'react';
import { authFetch } from '../services/api';

interface Tool {
  name: string;
  description: string;
  category: string;
  requiredParams: string[];
  dangerous: boolean;
  enabled: boolean;
  custom: boolean;
}

interface CustomToolDetail {
  id: string;
  name: string;
  description: string;
  category: string;
  requiredParams: string[];
  dangerous: boolean;
  enabled: boolean;
  createdBy?: string;
  createdAt?: string;
}

interface AddToolForm {
  name: string;
  category: string;
  description: string;
  requiredParams: string;
  dangerous: boolean;
}

const CATEGORY_COLORS: Record<string, string> = {
  DATABASE:       'rgba(79,142,247,0.15)',
  NETWORK:        'rgba(48,217,156,0.1)',
  SECURITY:       'rgba(255,85,85,0.1)',
  INFRASTRUCTURE: 'rgba(181,123,255,0.1)',
  DEPLOYMENT:     'rgba(245,166,35,0.1)',
  MEMORY:         'rgba(245,166,35,0.1)',
  CACHE:          'rgba(181,123,255,0.1)',
  KUBERNETES:     'rgba(79,142,247,0.1)',
  MONITORING:     'rgba(48,217,156,0.1)',
  CUSTOM:         'rgba(136,136,170,0.12)',
};

const CATEGORY_TEXT: Record<string, string> = {
  DATABASE:       'var(--blue)',
  NETWORK:        'var(--green)',
  SECURITY:       'var(--red)',
  INFRASTRUCTURE: 'var(--purple)',
  DEPLOYMENT:     'var(--amber)',
  MEMORY:         'var(--amber)',
  CACHE:          'var(--purple)',
  KUBERNETES:     'var(--blue)',
  MONITORING:     'var(--green)',
  CUSTOM:         'var(--text-dim)',
};

const TOOL_CATEGORIES = [
  'DATABASE','NETWORK','SECURITY','INFRASTRUCTURE','DEPLOYMENT',
  'MEMORY','CACHE','KUBERNETES','MONITORING','CUSTOM','OTHER'
];

const emptyForm: AddToolForm = {
  name: '', category: 'INFRASTRUCTURE', description: '',
  requiredParams: '', dangerous: false,
};

const ToolsPage: React.FC = () => {
  const [tools, setTools]           = useState<Tool[]>([]);
  const [customTools, setCustomTools] = useState<CustomToolDetail[]>([]);
  const [categories, setCategories] = useState<string[]>([]);
  const [loading, setLoading]       = useState(true);
  const [error, setError]           = useState<string | null>(null);
  const [filter, setFilter]         = useState('ALL');

  const [showModal, setShowModal]   = useState(false);
  const [editTool, setEditTool]     = useState<CustomToolDetail | null>(null);
  const [isOverride, setIsOverride] = useState(false);
  const [form, setForm]             = useState<AddToolForm>(emptyForm);
  const [saving, setSaving]         = useState(false);
  const [saveError, setSaveError]   = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const fetchAll = async () => {
    setLoading(true);
    try {
      const [tr, cr, catR] = await Promise.all([
        authFetch('/api/v1/tools'),
        authFetch('/api/v1/tools/custom'),
        authFetch('/api/v1/tools/categories'),
      ]);
      if (tr.ok) setTools(await tr.json());
      if (cr.ok) setCustomTools(await cr.json());
      if (catR.ok) setCategories(await catR.json());
      setError(null);
    } catch {
      setError('Cannot connect to backend');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const allCategories = ['ALL', ...Array.from(new Set(['DATABASE','NETWORK','SECURITY','INFRASTRUCTURE','DEPLOYMENT','MONITORING','CACHE','KUBERNETES','CUSTOM', ...categories]))];
  const filtered = filter === 'ALL' ? tools : tools.filter(t => (t.category || '').toUpperCase() === filter);

  /* group by category */
  const grouped = filtered.reduce<Record<string, Tool[]>>((acc, t) => {
    const cat = (t.category || 'UNKNOWN').toUpperCase();
    if (!acc[cat]) acc[cat] = [];
    acc[cat].push(t);
    return acc;
  }, {});

  /* ── modal helpers ── */
  const openAdd = () => {
    setEditTool(null);
    setIsOverride(false);
    setForm(emptyForm);
    setSaveError(null);
    setShowModal(true);
  };

  const openEdit = (d: CustomToolDetail) => {
    setEditTool(d);
    setIsOverride(false);
    setForm({
      name: d.name,
      category: d.category,
      description: d.description,
      requiredParams: (d.requiredParams || []).join(', '),
      dangerous: d.dangerous,
    });
    setSaveError(null);
    setShowModal(true);
  };

  const closeModal = () => { setShowModal(false); setEditTool(null); setIsOverride(false); setSaveError(null); };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    if (type === 'checkbox') {
      setForm(f => ({ ...f, [name]: (e.target as HTMLInputElement).checked }));
    } else {
      setForm(f => ({ ...f, [name]: value }));
    }
  };

  const handleSave = async () => {
    if (!form.name.trim()) { setSaveError('Tool name is required.'); return; }
    setSaving(true); setSaveError(null);
    const payload = {
      name:           form.name.trim(),
      category:       form.category,
      description:    form.description.trim(),
      requiredParams: form.requiredParams.split(',').map(s => s.trim()).filter(Boolean),
      dangerous:      form.dangerous,
    };
    try {
      const r = editTool
        ? await authFetch(`/api/v1/tools/${editTool.id}`, { method: 'PUT', body: JSON.stringify(payload) })
        : await authFetch('/api/v1/tools', { method: 'POST', body: JSON.stringify(payload) });
      if (!r.ok) {
        const j = await r.json().catch(() => ({}));
        setSaveError(j.error || `Save failed (${r.status})`);
      } else {
        closeModal();
        fetchAll();
      }
    } catch { setSaveError('Network error'); }
    finally { setSaving(false); }
  };

  const handleDelete = async (id: string) => {
    try {
      await authFetch(`/api/v1/tools/${id}`, { method: 'DELETE' });
      setDeleteConfirm(null);
      fetchAll();
    } catch { /* ignore */ }
  };

  /* find custom detail for a tool */
  const customDetail = (name: string) => customTools.find(c => c.name === name) || null;

  /* open edit for ANY tool (built-in or custom) */
  const openEditAny = (tool: Tool) => {
    const detail = customDetail(tool.name);
    if (detail) {
      openEdit(detail);
    } else {
      // Pre-fill with built-in data; saving will POST a custom override
      setEditTool(null);
      setIsOverride(true);
      setForm({
        name: tool.name,
        category: tool.category || 'INFRASTRUCTURE',
        description: tool.description || '',
        requiredParams: (tool.requiredParams || []).join(', '),
        dangerous: tool.dangerous,
      });
      setSaveError(null);
      setShowModal(true);
    }
  };

  return (
    <div className="content">
      {/* Header */}
      <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between', marginBottom:24 }}>
        <div>
          <div style={{ fontFamily:'var(--mono)', fontSize:11, color:'var(--text-muted)', letterSpacing:2, textTransform:'uppercase', marginBottom:4 }}>
            TOOL REGISTRY
          </div>
          <div style={{ fontFamily:'var(--mono)', fontSize:20, fontWeight:700, color:'var(--text)' }}>
            {tools.length} Registered Tools
          </div>
        </div>
        <div style={{ display:'flex', gap:10 }}>
          <button className="btn btn-modify" onClick={fetchAll} style={{ fontSize:11 }}>⟳ REFRESH</button>
          <button
            onClick={openAdd}
            style={{
              padding:'7px 16px', borderRadius:6, fontSize:11, fontFamily:'var(--mono)', fontWeight:700,
              cursor:'pointer', border:'1px solid rgba(79,142,247,0.35)',
              background:'var(--blue-dim)', color:'var(--blue)', letterSpacing:0.5
            }}>
            + ADD TOOL
          </button>
        </div>
      </div>

      {/* Category filter */}
      <div className="tabs" style={{ marginBottom:24, flexWrap:'wrap' }}>
        {allCategories.map(cat => (
          <div key={cat} className={`tab ${filter === cat ? 'active' : ''}`} onClick={() => setFilter(cat)}>
            {cat}
          </div>
        ))}
      </div>

      {error && <div className="error-banner" style={{ marginBottom:16 }}>⚠ {error}</div>}
      {loading && <div className="loading-state" style={{ padding:60 }}>Loading tools…</div>}

      {/* Tool groups */}
      {!loading && Object.keys(grouped).sort().map(cat => (
        <div key={cat} style={{ marginBottom:32 }}>
          <div style={{
            fontFamily:'var(--mono)', fontSize:10, letterSpacing:3, color: CATEGORY_TEXT[cat] || 'var(--text-dim)',
            textTransform:'uppercase', marginBottom:12,
            borderLeft:`3px solid ${CATEGORY_TEXT[cat] || 'var(--border-bright)'}`, paddingLeft:10
          }}>
            {cat} — {grouped[cat].length} tools
          </div>
          <div style={{ display:'grid', gridTemplateColumns:'repeat(auto-fill,minmax(300px,1fr))', gap:12 }}>
            {grouped[cat].map(tool => {
              const detail = tool.custom ? customDetail(tool.name) : null;
              return (
                <div key={tool.name} style={{
                  background:'var(--surface)',
                  border:`1px solid ${tool.dangerous ? 'rgba(255,85,85,0.25)' : 'var(--border)'}`,
                  borderRadius:8, padding:'14px 16px',
                  display:'flex', flexDirection:'column', gap:8,
                  position:'relative',
                  transition:'border-color 0.15s'
                }}>
                  {/* Top row */}
                  <div style={{ display:'flex', alignItems:'flex-start', justifyContent:'space-between', gap:8 }}>
                    <div style={{ fontFamily:'var(--mono)', fontSize:12, fontWeight:700, color:'var(--text)', wordBreak:'break-all' }}>
                      {tool.name}
                    </div>
                    <div style={{ display:'flex', gap:5, flexShrink:0 }}>
                      {tool.dangerous && (
                        <span style={{ background:'rgba(255,85,85,0.12)', color:'var(--red)', fontFamily:'var(--mono)', fontSize:9, padding:'2px 6px', borderRadius:4, fontWeight:700 }}>
                          ⚠ DANGEROUS
                        </span>
                      )}
                      <span style={{
                        background: CATEGORY_COLORS[cat] || 'var(--surface2)',
                        color: CATEGORY_TEXT[cat] || 'var(--text-dim)',
                        fontFamily:'var(--mono)', fontSize:9, padding:'2px 7px', borderRadius:4, fontWeight:700
                      }}>
                        {tool.custom ? 'CUSTOM' : 'BUILT-IN'}
                      </span>
                    </div>
                  </div>

                  {/* Description */}
                  <div style={{ fontSize:12, color:'var(--text-dim)', lineHeight:1.5 }}>
                    {tool.description || <span style={{ color:'var(--text-muted)', fontStyle:'italic' }}>No description</span>}
                  </div>

                  {/* Required params */}
                  {tool.requiredParams && tool.requiredParams.length > 0 && (
                    <div style={{ display:'flex', flexWrap:'wrap', gap:4 }}>
                      {tool.requiredParams.map((p: string) => (
                        <span key={p} style={{
                          background:'var(--surface3)', color:'var(--text-muted)',
                          fontFamily:'var(--mono)', fontSize:9, padding:'2px 6px', borderRadius:4
                        }}>{p}</span>
                      ))}
                    </div>
                  )}

                  {/* Actions — edit available for ALL tools, delete only for custom */}
                  <div style={{ display:'flex', gap:8, marginTop:4, paddingTop:10, borderTop:'1px solid var(--border)' }}>
                    <button
                      onClick={() => openEditAny(tool)}
                      style={{
                        flex:1, padding:'5px 0', fontSize:11, fontFamily:'var(--mono)',
                        cursor:'pointer', border:'1px solid var(--border-bright)',
                        background:'var(--surface2)', color:'var(--text-dim)', borderRadius:4
                      }}>
                      ✎ EDIT
                    </button>
                    {tool.custom && customDetail(tool.name) && (
                      <button
                        onClick={() => setDeleteConfirm(customDetail(tool.name)!.id)}
                        style={{
                          flex:1, padding:'5px 0', fontSize:11, fontFamily:'var(--mono)',
                          cursor:'pointer', border:'1px solid rgba(255,85,85,0.25)',
                          background:'rgba(255,85,85,0.06)', color:'var(--red)', borderRadius:4
                        }}>
                        ✕ DELETE
                      </button>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      ))}

      {!loading && tools.length === 0 && (
        <div className="empty-state-msg">No tools registered.</div>
      )}

      {/* ── Delete confirm dialog ── */}
      {deleteConfirm && (
        <div style={{
          position:'fixed', inset:0, background:'rgba(15,23,42,0.55)', display:'flex',
          alignItems:'center', justifyContent:'center', zIndex:1000
        }}>
          <div style={{
            background:'var(--surface)', border:'1px solid var(--border-bright)',
            borderRadius:10, padding:28, width:380, boxShadow:'0 20px 60px rgba(0,0,0,0.18)'
          }}>
            <div style={{ fontFamily:'var(--mono)', fontSize:14, fontWeight:700, color:'var(--red)', marginBottom:12 }}>
              ⚠ DELETE TOOL
            </div>
            <div style={{ fontSize:13, color:'var(--text-dim)', marginBottom:24, lineHeight:1.6 }}>
              This will disable the tool and remove it from the registry. This action cannot be undone.
            </div>
            <div style={{ display:'flex', gap:10 }}>
              <button
                onClick={() => setDeleteConfirm(null)}
                style={{ flex:1, padding:'8px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)', cursor:'pointer', border:'1px solid var(--border-bright)', background:'var(--surface2)', color:'var(--text-dim)' }}>
                CANCEL
              </button>
              <button
                onClick={() => handleDelete(deleteConfirm)}
                style={{ flex:1, padding:'8px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)', fontWeight:700, cursor:'pointer', border:'1px solid rgba(255,85,85,0.4)', background:'rgba(255,85,85,0.12)', color:'var(--red)' }}>
                DELETE
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ── Add / Edit Tool Modal ── */}
      {showModal && (
        <div style={{
          position:'fixed', inset:0, background:'rgba(15,23,42,0.55)', display:'flex',
          alignItems:'center', justifyContent:'center', zIndex:1000
        }}>
          <div style={{
            background:'var(--surface)', border:'1px solid var(--border-bright)',
            borderRadius:10, padding:28, width:520, maxHeight:'90vh', overflowY:'auto',
            boxShadow:'0 20px 60px rgba(0,0,0,0.18)'
          }}>
            <div style={{ fontFamily:'var(--mono)', fontSize:13, fontWeight:700, color:'var(--blue)', marginBottom:20, letterSpacing:1 }}>
              {editTool ? '✎ EDIT TOOL' : isOverride ? '✎ OVERRIDE BUILT-IN TOOL' : '+ ADD NEW TOOL'}
            </div>

            {saveError && (
              <div style={{ background:'rgba(255,85,85,0.1)', border:'1px solid rgba(255,85,85,0.3)', color:'var(--red)', padding:'8px 12px', borderRadius:6, fontSize:12, marginBottom:16 }}>
                {saveError}
              </div>
            )}

            {/* Name */}
            <label style={labelStyle}>Tool Name *</label>
            <input
              name="name" value={form.name} onChange={handleChange}
              placeholder="e.g. restart_nginx_service"
              disabled={!!editTool}
              style={{ ...inputStyle, ...(editTool ? { opacity:0.5 } : {}) }}
            />

            {/* Category */}
            <label style={labelStyle}>Category</label>
            <select name="category" value={form.category} onChange={handleChange} style={inputStyle}>
              {TOOL_CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>

            {/* Description */}
            <label style={labelStyle}>Description</label>
            <textarea
              name="description" value={form.description} onChange={handleChange}
              placeholder="What does this tool do?"
              rows={3}
              style={{ ...inputStyle, resize:'vertical', minHeight:72 }}
            />

            {/* Required params */}
            <label style={labelStyle}>Required Parameters <span style={{ color:'var(--text-muted)', fontSize:10 }}>(comma-separated)</span></label>
            <input
              name="requiredParams" value={form.requiredParams} onChange={handleChange}
              placeholder="e.g. service_name, host, port"
              style={inputStyle}
            />

            {/* Dangerous toggle */}
            <label style={{ display:'flex', alignItems:'center', gap:10, marginTop:14, cursor:'pointer' }}>
              <input
                type="checkbox" name="dangerous" checked={form.dangerous}
                onChange={handleChange}
                style={{ width:16, height:16, cursor:'pointer', accentColor:'var(--red)' }}
              />
              <span style={{ fontSize:13, color: form.dangerous ? 'var(--red)' : 'var(--text-dim)' }}>
                ⚠ Mark as dangerous (requires HITL approval)
              </span>
            </label>

            {/* Buttons */}
            <div style={{ display:'flex', gap:10, marginTop:24 }}>
              <button
                onClick={closeModal}
                style={{ flex:1, padding:'9px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)', cursor:'pointer', border:'1px solid var(--border-bright)', background:'var(--surface2)', color:'var(--text-dim)' }}>
                CANCEL
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                style={{
                  flex:2, padding:'9px 0', borderRadius:6, fontSize:12, fontFamily:'var(--mono)', fontWeight:700,
                  cursor: saving ? 'not-allowed' : 'pointer',
                  border:'1px solid rgba(79,142,247,0.4)',
                  background:'var(--blue-dim)', color:'var(--blue)',
                  opacity: saving ? 0.6 : 1
                }}>
                {saving ? '⏳ SAVING…' : editTool ? '✓ UPDATE TOOL' : isOverride ? '✓ SAVE OVERRIDE' : '✓ CREATE TOOL'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const labelStyle: React.CSSProperties = {
  display:'block', fontFamily:'var(--mono)', fontSize:10, letterSpacing:1,
  color:'var(--text-muted)', textTransform:'uppercase', marginTop:14, marginBottom:5
};

const inputStyle: React.CSSProperties = {
  width:'100%', padding:'8px 10px', borderRadius:6, fontSize:13,
  border:'1px solid var(--border-bright)', background:'var(--surface2)',
  color:'var(--text)', outline:'none', fontFamily:'var(--sans)'
};

export default ToolsPage;
