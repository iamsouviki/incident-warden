import React, { useEffect, useMemo, useState } from 'react';
import { authFetch, extractApiError, SIMPLE_ERROR_MESSAGE } from '../services/api';

interface Tool {
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
  APPLICATION:    'rgba(79,142,247,0.12)',
  DATABASE:       'rgba(79,142,247,0.15)',
  DEPLOYMENT:     'rgba(245,166,35,0.1)',
  GENERAL:        'rgba(136,136,170,0.12)',
  INFRASTRUCTURE: 'rgba(181,123,255,0.1)',
  MONITORING:     'rgba(48,217,156,0.1)',
  NETWORK:        'rgba(48,217,156,0.1)',
  SECURITY:       'rgba(255,85,85,0.1)',
};

const CATEGORY_TEXT: Record<string, string> = {
  APPLICATION:    'var(--blue)',
  DATABASE:       'var(--blue)',
  DEPLOYMENT:     'var(--amber)',
  GENERAL:        'var(--text-dim)',
  INFRASTRUCTURE: 'var(--purple)',
  MONITORING:     'var(--green)',
  NETWORK:        'var(--green)',
  SECURITY:       'var(--red)',
};

const TOOL_CATEGORIES = [
  'APPLICATION', 'DATABASE', 'DEPLOYMENT', 'GENERAL',
  'INFRASTRUCTURE', 'MONITORING', 'NETWORK', 'SECURITY', 'OTHER'
];

const emptyForm: AddToolForm = {
  name: '',
  category: 'APPLICATION',
  description: '',
  requiredParams: '',
  dangerous: false,
};

const ToolsPage: React.FC = () => {
  const [tools, setTools] = useState<Tool[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('ALL');

  const [showModal, setShowModal] = useState(false);
  const [editTool, setEditTool] = useState<Tool | null>(null);
  const [form, setForm] = useState<AddToolForm>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const fetchAll = async () => {
    setLoading(true);
    try {
      const response = await authFetch('/api/v1/tools');
      if (!response.ok) {
        setError(await extractApiError(response));
        return;
      }
      const payload = await response.json();
      setTools(Array.isArray(payload) ? payload : payload.tools || []);
      setError(null);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, []);

  const toolsArr = Array.isArray(tools) ? tools : [];
  const categories = useMemo(
    () => Array.from(new Set(
      toolsArr
        .map(tool => (tool.category || '').trim().toUpperCase())
        .filter(Boolean)
    )).sort(),
    [toolsArr]
  );

  useEffect(() => {
    if (filter !== 'ALL' && !categories.includes(filter)) {
      setFilter('ALL');
    }
  }, [categories, filter]);

  const filtered = filter === 'ALL'
    ? toolsArr
    : toolsArr.filter(tool => (tool.category || '').toUpperCase() === filter);

  const grouped = filtered.reduce<Record<string, Tool[]>>((acc, tool) => {
    const category = (tool.category || 'UNKNOWN').toUpperCase();
    if (!acc[category]) acc[category] = [];
    acc[category].push(tool);
    return acc;
  }, {});

  const openAdd = () => {
    setEditTool(null);
    setForm(emptyForm);
    setSaveError(null);
    setShowModal(true);
  };

  const openEdit = (tool: Tool) => {
    setEditTool(tool);
    setForm({
      name: tool.name,
      category: tool.category || 'APPLICATION',
      description: tool.description || '',
      requiredParams: (tool.requiredParams || []).join(', '),
      dangerous: tool.dangerous,
    });
    setSaveError(null);
    setShowModal(true);
  };

  const closeModal = () => {
    setShowModal(false);
    setEditTool(null);
    setSaveError(null);
  };

  const handleChange = (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) => {
    const { name, value, type } = e.target;
    if (type === 'checkbox') {
      setForm(current => ({ ...current, [name]: (e.target as HTMLInputElement).checked }));
      return;
    }
    setForm(current => ({ ...current, [name]: value }));
  };

  const handleSave = async () => {
    if (!form.name.trim()) {
      setSaveError('Tool name is required.');
      return;
    }

    setSaving(true);
    setSaveError(null);

    const payload = {
      name: form.name.trim(),
      category: form.category.toUpperCase(),
      description: form.description.trim(),
      requiredParams: form.requiredParams.split(',').map(value => value.trim()).filter(Boolean),
      dangerous: form.dangerous,
    };

    try {
      const response = editTool
        ? await authFetch(`/api/v1/tools/${editTool.id}`, { method: 'PUT', body: JSON.stringify(payload) })
        : await authFetch('/api/v1/tools', { method: 'POST', body: JSON.stringify(payload) });

      if (!response.ok) {
        setSaveError(await extractApiError(response));
        return;
      }

      closeModal();
      fetchAll();
    } catch {
      setSaveError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (id: string) => {
    try {
      const response = await authFetch(`/api/v1/tools/${id}`, { method: 'DELETE' });
      if (!response.ok) {
        setError(await extractApiError(response));
        return;
      }
      setDeleteConfirm(null);
      fetchAll();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    }
  };

  return (
    <div className="content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-muted)', letterSpacing: 2, textTransform: 'uppercase', marginBottom: 4 }}>
            MCP TOOLS
          </div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 20, fontWeight: 700, color: 'var(--text)' }}>
            {toolsArr.length} Custom Tools
          </div>
        </div>
        <div style={{ display: 'flex', gap: 10 }}>
          <button className="btn btn-modify" onClick={fetchAll} style={{ fontSize: 11 }}>⟳ REFRESH</button>
          <button
            onClick={openAdd}
            style={{
              padding: '7px 16px',
              borderRadius: 6,
              fontSize: 11,
              fontFamily: 'var(--mono)',
              fontWeight: 700,
              cursor: 'pointer',
              border: '1px solid rgba(79,142,247,0.35)',
              background: 'var(--blue-dim)',
              color: 'var(--blue)',
              letterSpacing: 0.5
            }}>
            + ADD TOOL
          </button>
        </div>
      </div>

      <div className="tabs" style={{ marginBottom: 24, flexWrap: 'wrap' }}>
        {['ALL', ...categories].map(category => (
          <div
            key={category}
            className={`tab ${filter === category ? 'active' : ''}`}
            onClick={() => setFilter(category)}>
            {category}
          </div>
        ))}
      </div>

      {error && <div className="error-banner" style={{ marginBottom: 16 }}>⚠ {error}</div>}
      {loading && <div className="loading-state" style={{ padding: 60 }}>Loading tools…</div>}

      {!loading && Object.keys(grouped).sort().map(category => (
        <div key={category} style={{ marginBottom: 32 }}>
          <div style={{
            fontFamily: 'var(--mono)',
            fontSize: 10,
            letterSpacing: 3,
            color: CATEGORY_TEXT[category] || 'var(--text-dim)',
            textTransform: 'uppercase',
            marginBottom: 12,
            borderLeft: `3px solid ${CATEGORY_TEXT[category] || 'var(--border-bright)'}`,
            paddingLeft: 10
          }}>
            {category} — {grouped[category].length} tools
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill,minmax(300px,1fr))', gap: 12 }}>
            {grouped[category].map(tool => (
              <div
                key={tool.id}
                style={{
                  background: 'var(--surface)',
                  border: `1px solid ${tool.dangerous ? 'rgba(255,85,85,0.25)' : 'var(--border)'}`,
                  borderRadius: 8,
                  padding: '14px 16px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8
                }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
                  <div style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 700, color: 'var(--text)', wordBreak: 'break-all' }}>
                    {tool.name}
                  </div>
                  <div style={{ display: 'flex', gap: 5, flexShrink: 0 }}>
                    {tool.dangerous && (
                      <span style={{ background: 'rgba(255,85,85,0.12)', color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 9, padding: '2px 6px', borderRadius: 4, fontWeight: 700 }}>
                        ⚠ DANGEROUS
                      </span>
                    )}
                    <span style={{
                      background: CATEGORY_COLORS[category] || 'var(--surface2)',
                      color: CATEGORY_TEXT[category] || 'var(--text-dim)',
                      fontFamily: 'var(--mono)',
                      fontSize: 9,
                      padding: '2px 7px',
                      borderRadius: 4,
                      fontWeight: 700
                    }}>
                      {category}
                    </span>
                  </div>
                </div>

                <div style={{ fontSize: 12, color: 'var(--text-dim)', lineHeight: 1.5 }}>
                  {tool.description || <span style={{ color: 'var(--text-muted)', fontStyle: 'italic' }}>No description</span>}
                </div>

                {tool.requiredParams && tool.requiredParams.length > 0 && (
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                    {tool.requiredParams.map(param => (
                      <span
                        key={param}
                        style={{
                          background: 'var(--surface3)',
                          color: 'var(--text-muted)',
                          fontFamily: 'var(--mono)',
                          fontSize: 9,
                          padding: '2px 6px',
                          borderRadius: 4
                        }}>
                        {param}
                      </span>
                    ))}
                  </div>
                )}

                <div style={{ display: 'flex', gap: 8, marginTop: 4, paddingTop: 10, borderTop: '1px solid var(--border)' }}>
                  <button
                    onClick={() => openEdit(tool)}
                    style={{
                      flex: 1,
                      padding: '5px 0',
                      fontSize: 11,
                      fontFamily: 'var(--mono)',
                      cursor: 'pointer',
                      border: '1px solid var(--border-bright)',
                      background: 'var(--surface2)',
                      color: 'var(--text-dim)',
                      borderRadius: 4
                    }}>
                    ✎ EDIT
                  </button>
                  <button
                    onClick={() => setDeleteConfirm(tool.id)}
                    style={{
                      flex: 1,
                      padding: '5px 0',
                      fontSize: 11,
                      fontFamily: 'var(--mono)',
                      cursor: 'pointer',
                      border: '1px solid rgba(255,85,85,0.25)',
                      background: 'rgba(255,85,85,0.06)',
                      color: 'var(--red)',
                      borderRadius: 4
                    }}>
                    ✕ DELETE
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}

      {!loading && toolsArr.length === 0 && (
        <div className="empty-state-msg">No MCP tools created yet. Save an SOP with a linked script or add a tool manually.</div>
      )}

      {deleteConfirm && (
        <div style={{
          position: 'fixed',
          inset: 0,
          background: 'rgba(15,23,42,0.55)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 1000
        }}>
          <div style={{
            background: 'var(--surface)',
            border: '1px solid var(--border-bright)',
            borderRadius: 10,
            padding: 28,
            width: 380,
            boxShadow: '0 20px 60px rgba(0,0,0,0.18)'
          }}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 14, fontWeight: 700, color: 'var(--red)', marginBottom: 12 }}>
              ⚠ DELETE TOOL
            </div>
            <div style={{ fontSize: 13, color: 'var(--text-dim)', marginBottom: 24, lineHeight: 1.6 }}>
              This will permanently delete the tool and remove it from the registry. This action cannot be undone.
            </div>
            <div style={{ display: 'flex', gap: 10 }}>
              <button
                onClick={() => setDeleteConfirm(null)}
                style={{ flex: 1, padding: '8px 0', borderRadius: 6, fontSize: 12, fontFamily: 'var(--mono)', cursor: 'pointer', border: '1px solid var(--border-bright)', background: 'var(--surface2)', color: 'var(--text-dim)' }}>
                CANCEL
              </button>
              <button
                onClick={() => handleDelete(deleteConfirm)}
                style={{ flex: 1, padding: '8px 0', borderRadius: 6, fontSize: 12, fontFamily: 'var(--mono)', fontWeight: 700, cursor: 'pointer', border: '1px solid rgba(255,85,85,0.4)', background: 'rgba(255,85,85,0.12)', color: 'var(--red)' }}>
                DELETE
              </button>
            </div>
          </div>
        </div>
      )}

      {showModal && (
        <div style={{
          position: 'fixed',
          inset: 0,
          background: 'rgba(15,23,42,0.55)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 1000
        }}>
          <div style={{
            background: 'var(--surface)',
            border: '1px solid var(--border-bright)',
            borderRadius: 10,
            padding: 28,
            width: 520,
            maxHeight: '90vh',
            overflowY: 'auto',
            boxShadow: '0 20px 60px rgba(0,0,0,0.18)'
          }}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 13, fontWeight: 700, color: 'var(--blue)', marginBottom: 20, letterSpacing: 1 }}>
              {editTool ? '✎ EDIT TOOL' : '+ ADD NEW TOOL'}
            </div>

            {saveError && (
              <div style={{ background: 'rgba(255,85,85,0.1)', border: '1px solid rgba(255,85,85,0.3)', color: 'var(--red)', padding: '8px 12px', borderRadius: 6, fontSize: 12, marginBottom: 16 }}>
                {saveError}
              </div>
            )}

            <label style={labelStyle}>Tool Name *</label>
            <input
              name="name"
              value={form.name}
              onChange={handleChange}
              placeholder="e.g. restart_nginx_service"
              disabled={!!editTool}
              style={{ ...inputStyle, ...(editTool ? { opacity: 0.5 } : {}) }}
            />

            <label style={labelStyle}>Category</label>
            <select name="category" value={form.category} onChange={handleChange} style={inputStyle}>
              {TOOL_CATEGORIES.map(category => <option key={category} value={category}>{category}</option>)}
            </select>

            <label style={labelStyle}>Description</label>
            <textarea
              name="description"
              value={form.description}
              onChange={handleChange}
              placeholder="What does this tool do?"
              rows={3}
              style={{ ...inputStyle, resize: 'vertical', minHeight: 72 }}
            />

            <label style={labelStyle}>Required Parameters <span style={{ color: 'var(--text-muted)', fontSize: 10 }}>(comma-separated)</span></label>
            <input
              name="requiredParams"
              value={form.requiredParams}
              onChange={handleChange}
              placeholder="e.g. service_name, host, port"
              style={inputStyle}
            />

            <label style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 14, cursor: 'pointer' }}>
              <input
                type="checkbox"
                name="dangerous"
                checked={form.dangerous}
                onChange={handleChange}
                style={{ width: 16, height: 16, cursor: 'pointer', accentColor: 'var(--red)' }}
              />
              <span style={{ fontSize: 13, color: form.dangerous ? 'var(--red)' : 'var(--text-dim)' }}>
                ⚠ Mark as dangerous (requires HITL approval)
              </span>
            </label>

            <div style={{ display: 'flex', gap: 10, marginTop: 24 }}>
              <button
                onClick={closeModal}
                style={{ flex: 1, padding: '9px 0', borderRadius: 6, fontSize: 12, fontFamily: 'var(--mono)', cursor: 'pointer', border: '1px solid var(--border-bright)', background: 'var(--surface2)', color: 'var(--text-dim)' }}>
                CANCEL
              </button>
              <button
                onClick={handleSave}
                disabled={saving}
                style={{
                  flex: 2,
                  padding: '9px 0',
                  borderRadius: 6,
                  fontSize: 12,
                  fontFamily: 'var(--mono)',
                  fontWeight: 700,
                  cursor: saving ? 'not-allowed' : 'pointer',
                  border: '1px solid rgba(79,142,247,0.4)',
                  background: 'var(--blue-dim)',
                  color: 'var(--blue)',
                  opacity: saving ? 0.6 : 1
                }}>
                {saving ? '⏳ SAVING…' : editTool ? '✓ UPDATE TOOL' : '✓ CREATE TOOL'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontFamily: 'var(--mono)',
  fontSize: 10,
  letterSpacing: 1,
  color: 'var(--text-muted)',
  textTransform: 'uppercase',
  marginTop: 14,
  marginBottom: 5
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 10px',
  borderRadius: 6,
  fontSize: 13,
  border: '1px solid var(--border-bright)',
  background: 'var(--surface2)',
  color: 'var(--text)',
  outline: 'none',
  fontFamily: 'var(--sans)'
};

export default ToolsPage;
