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
  scriptWorkspaceId?: string | null;
  sopId?: string | null;
}

interface SavedScript {
  id: string;
  name: string;
  description: string;
  scriptContent: string;
  language: string;
  category: string;
  targetHost: string;
  toolName?: string;
  sopId?: string;
}

interface AddToolForm {
  name: string;
  category: string;
  description: string;
  requiredParams: string;
  dangerous: boolean;
}

const CATEGORY_COLORS: Record<string, string> = {
  APPLICATION: 'rgba(79,142,247,0.12)',
  DATABASE: 'rgba(79,142,247,0.15)',
  DEPLOYMENT: 'rgba(245,166,35,0.1)',
  GENERAL: 'rgba(136,136,170,0.12)',
  INFRASTRUCTURE: 'rgba(181,123,255,0.1)',
  MONITORING: 'rgba(48,217,156,0.1)',
  NETWORK: 'rgba(48,217,156,0.1)',
  SECURITY: 'rgba(255,85,85,0.1)',
};

const CATEGORY_TEXT: Record<string, string> = {
  APPLICATION: 'var(--blue)',
  DATABASE: 'var(--blue)',
  DEPLOYMENT: 'var(--amber)',
  GENERAL: 'var(--text-dim)',
  INFRASTRUCTURE: 'var(--purple)',
  MONITORING: 'var(--green)',
  NETWORK: 'var(--green)',
  SECURITY: 'var(--red)',
};

const TOOL_CATEGORIES = [
  'APPLICATION', 'DATABASE', 'DEPLOYMENT', 'GENERAL',
  'INFRASTRUCTURE', 'MONITORING', 'NETWORK', 'SECURITY', 'OTHER'
];

const SCRIPT_CATEGORIES = [
  'APPLICATION', 'PERFORMANCE', 'INFRASTRUCTURE', 'DATABASE', 'DEPLOYMENT', 'NETWORK'
];

const emptyForm: AddToolForm = {
  name: '',
  category: 'APPLICATION',
  description: '',
  requiredParams: '',
  dangerous: false,
};

const ToolsPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [tools, setTools] = useState<Tool[]>([]);
  const [scripts, setScripts] = useState<SavedScript[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState('ALL');
  const [selectedToolId, setSelectedToolId] = useState<string | null>(null);

  const [showModal, setShowModal] = useState(false);
  const [editTool, setEditTool] = useState<Tool | null>(null);
  const [form, setForm] = useState<AddToolForm>(emptyForm);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [deleteConfirm, setDeleteConfirm] = useState<string | null>(null);

  const [scriptName, setScriptName] = useState('');
  const [scriptDescription, setScriptDescription] = useState('');
  const [scriptContent, setScriptContent] = useState('');
  const [scriptLanguage, setScriptLanguage] = useState<'bash' | 'powershell'>('bash');
  const [scriptCategory, setScriptCategory] = useState('APPLICATION');
  const [scriptTargetHost, setScriptTargetHost] = useState('localhost');
  const [scriptDirty, setScriptDirty] = useState(false);
  const [scriptSaving, setScriptSaving] = useState(false);
  const [scriptMessage, setScriptMessage] = useState<string | null>(null);
  const [scriptError, setScriptError] = useState<string | null>(null);

  const fetchAll = async () => {
    setLoading(true);
    try {
      const [toolsResponse, scriptsResponse] = await Promise.all([
        authFetch('/api/v1/tools'),
        authFetch(`/api/v1/scripts?tenantId=${tenantId}`),
      ]);

      if (!toolsResponse.ok) {
        setError(await extractApiError(toolsResponse));
        return;
      }
      if (!scriptsResponse.ok) {
        setError(await extractApiError(scriptsResponse));
        return;
      }

      const toolPayload = await toolsResponse.json();
      const scriptPayload = await scriptsResponse.json();
      const nextTools = Array.isArray(toolPayload) ? toolPayload : toolPayload.tools || [];
      const nextScripts = scriptPayload.scripts || [];
      setTools(nextTools);
      setScripts(nextScripts);
      setError(null);

      const requestedId = new URLSearchParams(window.location.search).get('tool');
      const preferredId = requestedId && nextTools.some((tool: Tool) => tool.id === requestedId)
        ? requestedId
        : selectedToolId && nextTools.some((tool: Tool) => tool.id === selectedToolId)
          ? selectedToolId
          : nextTools[0]?.id ?? null;
      setSelectedToolId(preferredId);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchAll(); }, [tenantId]);

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

  const selectedTool = toolsArr.find(tool => tool.id === selectedToolId) || null;
  const linkedScript = selectedTool?.scriptWorkspaceId
    ? scripts.find(script => script.id === selectedTool.scriptWorkspaceId) || null
    : scripts.find(script => script.toolName === selectedTool?.name) || null;

  useEffect(() => {
    if (!selectedTool) {
      setScriptName('');
      setScriptDescription('');
      setScriptContent('');
      setScriptLanguage('bash');
      setScriptCategory('APPLICATION');
      setScriptTargetHost('localhost');
      setScriptDirty(false);
      setScriptMessage(null);
      setScriptError(null);
      return;
    }

    setScriptName(linkedScript?.name || `${selectedTool.name} Script`);
    setScriptDescription(linkedScript?.description || selectedTool.description || '');
    setScriptContent(linkedScript?.scriptContent || '');
    setScriptLanguage((linkedScript?.language || 'bash') as 'bash' | 'powershell');
    setScriptCategory(linkedScript?.category || selectedTool.category || 'APPLICATION');
    setScriptTargetHost(linkedScript?.targetHost || 'localhost');
    setScriptDirty(false);
    setScriptMessage(null);
    setScriptError(null);
  }, [selectedTool, linkedScript]);

  const updateSelectedToolInUrl = (toolId: string) => {
    const url = new URL(window.location.href);
    url.pathname = '/tools';
    url.searchParams.set('tool', toolId);
    window.history.replaceState(null, '', url.pathname + url.search);
  };

  const selectTool = (toolId: string) => {
    setSelectedToolId(toolId);
    updateSelectedToolInUrl(toolId);
  };

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

      const body = await response.json().catch(() => ({}));
      closeModal();
      await fetchAll();
      if (body.id) selectTool(body.id);
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
      if (selectedToolId === id) {
        setSelectedToolId(null);
      }
      fetchAll();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    }
  };

  const handleSaveScript = async () => {
    if (!selectedTool) return;
    if (!scriptName.trim()) {
      setScriptError('Script name is required.');
      return;
    }
    if (!scriptContent.trim()) {
      setScriptError('Script content is required.');
      return;
    }

    setScriptSaving(true);
    setScriptError(null);
    setScriptMessage(null);

    const payload = {
      name: scriptName.trim(),
      description: scriptDescription.trim(),
      scriptContent,
      language: scriptLanguage,
      category: scriptCategory,
      targetHost: scriptTargetHost.trim() || 'localhost',
      tenantId,
      toolName: selectedTool.name,
      sopId: selectedTool.sopId || null,
    };

    try {
      let scriptId = selectedTool.scriptWorkspaceId || linkedScript?.id || null;
      const scriptResponse = scriptId
        ? await authFetch(`/api/v1/scripts/${scriptId}`, { method: 'PUT', body: JSON.stringify(payload) })
        : await authFetch('/api/v1/scripts', { method: 'POST', body: JSON.stringify(payload) });

      if (!scriptResponse.ok) {
        setScriptError(await extractApiError(scriptResponse));
        return;
      }

      const scriptBody = await scriptResponse.json().catch(() => ({}));
      scriptId = scriptId || scriptBody.id || null;

      if (scriptId && selectedTool.scriptWorkspaceId !== scriptId) {
        const toolResponse = await authFetch(`/api/v1/tools/${selectedTool.id}`, {
          method: 'PUT',
          body: JSON.stringify({
            category: selectedTool.category,
            description: selectedTool.description,
            requiredParams: selectedTool.requiredParams || [],
            dangerous: selectedTool.dangerous,
            scriptWorkspaceId: scriptId,
            sopId: selectedTool.sopId || null,
          }),
        });
        if (!toolResponse.ok) {
          setScriptError(await extractApiError(toolResponse));
          return;
        }
      }

      setScriptDirty(false);
      setScriptMessage('Script saved to this MCP tool.');
      await fetchAll();
      selectTool(selectedTool.id);
    } catch {
      setScriptError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setScriptSaving(false);
    }
  };

  const updateScript = (setter: () => void) => {
    setter();
    setScriptDirty(true);
    setScriptMessage(null);
    setScriptError(null);
  };

  return (
    <div className="content">
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-muted)', letterSpacing: 2, textTransform: 'uppercase', marginBottom: 4 }}>
            MCP TOOLS
          </div>
          <div style={{ fontFamily: 'var(--mono)', fontSize: 20, fontWeight: 700, color: 'var(--text)' }}>
            {toolsArr.length} MCP Tools
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

      {!loading && (
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(340px, 1fr) minmax(420px, 1.2fr)', gap: 20, alignItems: 'start' }}>
          <div>
            {Object.keys(grouped).sort().map(category => (
              <div key={category} style={{ marginBottom: 28 }}>
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
                  {category} - {grouped[category].length} tools
                </div>
                <div style={{ display: 'grid', gap: 12 }}>
                  {grouped[category].map(tool => {
                    const active = selectedToolId === tool.id;
                    return (
                      <div
                        key={tool.id}
                        onClick={() => selectTool(tool.id)}
                        style={{
                          background: active ? 'var(--surface2)' : 'var(--surface)',
                          border: `1px solid ${active ? 'rgba(79,142,247,0.45)' : tool.dangerous ? 'rgba(255,85,85,0.25)' : 'var(--border)'}`,
                          borderRadius: 8,
                          padding: '14px 16px',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: 8,
                          cursor: 'pointer'
                        }}>
                        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
                          <div style={{ fontFamily: 'var(--mono)', fontSize: 12, fontWeight: 700, color: 'var(--text)', wordBreak: 'break-all' }}>
                            {tool.name}
                          </div>
                          <div style={{ display: 'flex', gap: 5, flexShrink: 0 }}>
                            {tool.dangerous && (
                              <span style={{ background: 'rgba(255,85,85,0.12)', color: 'var(--red)', fontFamily: 'var(--mono)', fontSize: 9, padding: '2px 6px', borderRadius: 4, fontWeight: 700 }}>
                                DANGEROUS
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

                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                          <span style={pillStyle(tool.scriptWorkspaceId ? 'var(--green-dim)' : 'var(--surface3)', tool.scriptWorkspaceId ? 'var(--green)' : 'var(--text-muted)')}>
                            {tool.scriptWorkspaceId ? 'SCRIPT LINKED' : 'NO SCRIPT'}
                          </span>
                          {tool.requiredParams?.length > 0 && tool.requiredParams.map(param => (
                            <span key={param} style={pillStyle('var(--surface3)', 'var(--text-muted)')}>{param}</span>
                          ))}
                        </div>

                        <div style={{ display: 'flex', gap: 8, marginTop: 4, paddingTop: 10, borderTop: '1px solid var(--border)' }}>
                          <button
                            onClick={(e) => { e.stopPropagation(); openEdit(tool); }}
                            style={secondaryButtonStyle}>
                            EDIT TOOL
                          </button>
                          <button
                            onClick={(e) => { e.stopPropagation(); setDeleteConfirm(tool.id); }}
                            style={dangerButtonStyle}>
                            DELETE
                          </button>
                        </div>
                      </div>
                    );
                  })}
                </div>
              </div>
            ))}

            {!loading && toolsArr.length === 0 && (
              <div className="empty-state-msg">No MCP tools created yet. Save an SOP or add a tool manually.</div>
            )}
          </div>

          <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 10, minHeight: 680, padding: 18 }}>
            {!selectedTool ? (
              <div className="empty-state-msg" style={{ paddingTop: 140 }}>Select an MCP tool to edit its script.</div>
            ) : (
              <>
                <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'flex-start', marginBottom: 16 }}>
                  <div>
                    <div style={{ fontFamily: 'var(--mono)', fontSize: 11, letterSpacing: 2, color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: 6 }}>
                      Script Editor
                    </div>
                    <div style={{ fontFamily: 'var(--mono)', fontSize: 18, fontWeight: 700, color: 'var(--text)' }}>{selectedTool.name}</div>
                    <div style={{ color: 'var(--text-dim)', fontSize: 12, lineHeight: 1.5, marginTop: 6 }}>
                      {selectedTool.description || 'This MCP tool runs the script below.'}
                    </div>
                  </div>
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', justifyContent: 'flex-end' }}>
                    <span style={pillStyle(CATEGORY_COLORS[selectedTool.category] || 'var(--surface3)', CATEGORY_TEXT[selectedTool.category] || 'var(--text-dim)')}>
                      {selectedTool.category}
                    </span>
                    <span style={pillStyle(selectedTool.scriptWorkspaceId ? 'var(--green-dim)' : 'rgba(245,166,35,0.08)', selectedTool.scriptWorkspaceId ? 'var(--green)' : 'var(--amber)')}>
                      {selectedTool.scriptWorkspaceId ? 'LINKED' : 'NEW SCRIPT'}
                    </span>
                  </div>
                </div>

                {scriptError && <div className="error-banner" style={{ marginBottom: 12 }}>⚠ {scriptError}</div>}
                {scriptMessage && <div style={{ marginBottom: 12, padding: '10px 12px', borderRadius: 8, border: '1px solid rgba(48,217,156,0.28)', background: 'rgba(48,217,156,0.08)', color: 'var(--green)', fontSize: 12 }}>{scriptMessage}</div>}

                <label style={labelStyle}>Script Name</label>
                <input value={scriptName} onChange={e => updateScript(() => setScriptName(e.target.value))} style={inputStyle} />

                <label style={labelStyle}>Script Description</label>
                <textarea
                  value={scriptDescription}
                  onChange={e => updateScript(() => setScriptDescription(e.target.value))}
                  rows={3}
                  style={{ ...inputStyle, resize: 'vertical', minHeight: 80 }}
                />

                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, minmax(0, 1fr))', gap: 12, marginTop: 14 }}>
                  <div>
                    <label style={labelStyle}>Language</label>
                    <select value={scriptLanguage} onChange={e => updateScript(() => setScriptLanguage(e.target.value as 'bash' | 'powershell'))} style={inputStyle}>
                      <option value="bash">Bash</option>
                      <option value="powershell">PowerShell</option>
                    </select>
                  </div>
                  <div>
                    <label style={labelStyle}>Category</label>
                    <select value={scriptCategory} onChange={e => updateScript(() => setScriptCategory(e.target.value))} style={inputStyle}>
                      {SCRIPT_CATEGORIES.map(category => <option key={category} value={category}>{category}</option>)}
                    </select>
                  </div>
                  <div>
                    <label style={labelStyle}>Target Host</label>
                    <input value={scriptTargetHost} onChange={e => updateScript(() => setScriptTargetHost(e.target.value))} style={inputStyle} />
                  </div>
                </div>

                <label style={{ ...labelStyle, marginTop: 14 }}>Script Editor</label>
                <textarea
                  value={scriptContent}
                  onChange={e => updateScript(() => setScriptContent(e.target.value))}
                  rows={24}
                  spellCheck={false}
                  style={{
                    ...inputStyle,
                    minHeight: 420,
                    resize: 'vertical',
                    fontFamily: 'var(--mono)',
                    fontSize: 12,
                    lineHeight: 1.6,
                    background: 'rgba(15,23,42,0.9)',
                    color: 'var(--green)',
                    whiteSpace: 'pre',
                    tabSize: 4
                  }}
                  placeholder="# This script will run as this MCP tool"
                />

                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, marginTop: 14 }}>
                  <div style={{ color: 'var(--text-muted)', fontSize: 11 }}>
                    {scriptDirty ? 'Unsaved script changes' : selectedTool.scriptWorkspaceId ? 'Linked script is up to date' : 'Save to link this tool with a script'}
                  </div>
                  <button
                    onClick={handleSaveScript}
                    disabled={scriptSaving}
                    style={{
                      padding: '9px 18px',
                      borderRadius: 6,
                      fontSize: 12,
                      fontFamily: 'var(--mono)',
                      fontWeight: 700,
                      cursor: scriptSaving ? 'not-allowed' : 'pointer',
                      border: '1px solid rgba(79,142,247,0.4)',
                      background: 'var(--blue-dim)',
                      color: 'var(--blue)',
                      opacity: scriptSaving ? 0.6 : 1
                    }}>
                    {scriptSaving ? 'SAVING...' : 'SAVE SCRIPT'}
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}

      {deleteConfirm && (
        <div style={overlayStyle}>
          <div style={modalStyle}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 14, fontWeight: 700, color: 'var(--red)', marginBottom: 12 }}>
              DELETE TOOL
            </div>
            <div style={{ fontSize: 13, color: 'var(--text-dim)', marginBottom: 24, lineHeight: 1.6 }}>
              This will permanently delete the tool and its linked script.
            </div>
            <div style={{ display: 'flex', gap: 10 }}>
              <button onClick={() => setDeleteConfirm(null)} style={{ ...secondaryButtonStyle, flex: 1 }}>CANCEL</button>
              <button onClick={() => handleDelete(deleteConfirm)} style={{ ...dangerButtonStyle, flex: 1 }}>DELETE</button>
            </div>
          </div>
        </div>
      )}

      {showModal && (
        <div style={overlayStyle}>
          <div style={{ ...modalStyle, width: 520, maxHeight: '90vh', overflowY: 'auto' }}>
            <div style={{ fontFamily: 'var(--mono)', fontSize: 13, fontWeight: 700, color: 'var(--blue)', marginBottom: 20, letterSpacing: 1 }}>
              {editTool ? 'EDIT TOOL' : 'ADD NEW TOOL'}
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

            <label style={labelStyle}>Required Parameters</label>
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
                Mark as dangerous (requires HITL approval)
              </span>
            </label>

            <div style={{ display: 'flex', gap: 10, marginTop: 24 }}>
              <button onClick={closeModal} style={{ ...secondaryButtonStyle, flex: 1 }}>CANCEL</button>
              <button onClick={handleSave} disabled={saving} style={{ ...primaryButtonStyle, flex: 2, opacity: saving ? 0.6 : 1 }}>
                {saving ? 'SAVING...' : editTool ? 'UPDATE TOOL' : 'CREATE TOOL'}
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
  marginTop: 12,
  marginBottom: 6,
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--surface2)',
  border: '1px solid var(--border)',
  borderRadius: 6,
  color: 'var(--text)',
  padding: '10px 12px',
  fontSize: 12,
  outline: 'none',
  boxSizing: 'border-box',
};

const pillStyle = (background: string, color: string): React.CSSProperties => ({
  background,
  color,
  fontFamily: 'var(--mono)',
  fontSize: 9,
  padding: '2px 7px',
  borderRadius: 4,
  fontWeight: 700,
});

const primaryButtonStyle: React.CSSProperties = {
  padding: '9px 0',
  borderRadius: 6,
  fontSize: 12,
  fontFamily: 'var(--mono)',
  fontWeight: 700,
  cursor: 'pointer',
  border: '1px solid rgba(79,142,247,0.4)',
  background: 'var(--blue-dim)',
  color: 'var(--blue)',
};

const secondaryButtonStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 0',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  cursor: 'pointer',
  border: '1px solid var(--border-bright)',
  background: 'var(--surface2)',
  color: 'var(--text-dim)',
  borderRadius: 4
};

const dangerButtonStyle: React.CSSProperties = {
  flex: 1,
  padding: '5px 0',
  fontSize: 11,
  fontFamily: 'var(--mono)',
  cursor: 'pointer',
  border: '1px solid rgba(255,85,85,0.25)',
  background: 'rgba(255,85,85,0.06)',
  color: 'var(--red)',
  borderRadius: 4
};

const overlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000
};

const modalStyle: React.CSSProperties = {
  background: 'var(--surface)',
  border: '1px solid var(--border-bright)',
  borderRadius: 10,
  padding: 28,
  width: 380,
  boxShadow: '0 20px 60px rgba(0,0,0,0.18)'
};

export default ToolsPage;
