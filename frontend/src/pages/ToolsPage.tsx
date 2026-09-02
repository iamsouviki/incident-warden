import React, { useState, useEffect, useRef } from 'react';
import { authFetch, extractApiError } from '../services/api';
import { Plus, Save, Trash2, Sparkles, X, Loader2 } from 'lucide-react';
import { Modal, Button } from '../components/ui';

import './ScriptEditorPage.css';

interface SavedScript {
  id: string;
  name: string;
  description: string;
  scriptContent: string;
  language: string;
  category: string;
  requiredInputData?: string;
  validatedInDryRun?: boolean;
}

const ToolsPage: React.FC = () => {
  const [savedScripts, setSavedScripts] = useState<SavedScript[]>([]);
  const [loadingList, setLoadingList] = useState(false);
  const [searchQuery, setSearchQuery] = useState('');

  // Tool Authoring / Edit Modal State
  const [showToolModal, setShowToolModal] = useState(false);
  const [activeScriptId, setActiveScriptId] = useState<string | null>(null);
  const [existingSkillKey, setExistingSkillKey] = useState<string | null>(null);
  const [promptDescription, setPromptDescription] = useState('');
  const [scriptName, setScriptName] = useState('');
  const [scriptDesc, setScriptDesc] = useState('');
  const [scriptContent, setScriptContent] = useState('');
  const [language, setLanguage] = useState<'python' | 'sh' | 'ps1'>('python');
  const [category, setCategory] = useState('POG_ISSUE');
  const [requiredInputData, setRequiredInputData] = useState('');
  const [validatedInDryRun, setValidatedInDryRun] = useState(true);
  const [classificationRules, setClassificationRules] = useState('');
  const [extractionRules, setExtractionRules] = useState('');
  const [resolutionRules, setResolutionRules] = useState('');

  // Status & outputs
  const [generating, setGenerating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lineNumRef = useRef<HTMLDivElement>(null);

  const loadSavedScripts = async () => {
    setLoadingList(true);
    try {
      const res = await authFetch('/api/v1/scripts');
      if (res.ok) {
        const data = await res.json();
        setSavedScripts(data.scripts || []);
      }
    } catch (err) {
      console.error('Failed to load scripts', err);
    } finally {
      setLoadingList(false);
    }
  };

  useEffect(() => {
    loadSavedScripts();
  }, []);

  const openNewToolModal = () => {
    setActiveScriptId(null);
    setExistingSkillKey(null);
    setScriptName('');
    setScriptDesc('');
    setPromptDescription('');
    setScriptContent('# Python 3 Remediation Tool\n# Define remediation logic below\n');
    setLanguage('python');
    setCategory('POG_ISSUE');
    setRequiredInputData('');
    setValidatedInDryRun(true);
    setClassificationRules('');
    setExtractionRules('');
    setResolutionRules('');
    setMessage(null);
    setShowToolModal(true);
  };

  const openEditToolModal = async (script: SavedScript) => {
    setActiveScriptId(script.id);
    setExistingSkillKey(null);
    setScriptName(script.name);
    setScriptDesc(script.description || '');
    setScriptContent(script.scriptContent);
    setLanguage((script.language as any) || 'python');
    setCategory(script.category || 'POG_ISSUE');
    setRequiredInputData(script.requiredInputData || '');
    setValidatedInDryRun(script.validatedInDryRun !== false);
    setClassificationRules('');
    setExtractionRules('');
    setResolutionRules('');
    setMessage(null);
    setShowToolModal(true);
    try {
      const res = await authFetch('/api/v1/skills');
      if (!res.ok) return;
      const rows = await res.json();
      const categorySkill = rows.find((row: any) => row.kind === 'CATEGORIZATION'
        && (row.skillKey === script.category || row.actionKey === script.category
          || row.actionKey?.split(':')[0] === script.category));
      const actionKey = categorySkill?.actionKey || '';
      setExistingSkillKey(actionKey ? actionKey.split(':')[0] : categorySkill?.skillKey || null);
      const extractionSkill = rows.find((row: any) => row.kind === 'EXTRACTION' && row.skillKey === script.category);
      setClassificationRules((categorySkill?.pattern || '').split(/[,;]+/).map((value: string) => value.trim()).filter(Boolean).join('\n'));
      setExtractionRules(extractionSkill?.definitionJson || '{"fields":[]}');
      setResolutionRules(categorySkill?.definitionJson || '{}');
    } catch { /* The modal remains usable; save validation explains what is missing. */ }
  };

  const [pendingDelete, setPendingDelete] = useState<SavedScript | null>(null);
  const [deleting, setDeleting] = useState(false);

  const requestDelete = (script: SavedScript, e: React.MouseEvent) => {
    e.stopPropagation();
    setPendingDelete(script);
  };

  const confirmDelete = async () => {
    if (!pendingDelete) return;
    setDeleting(true);
    try {
      const res = await authFetch(`/api/v1/scripts/${pendingDelete.id}`, { method: 'DELETE' });
      if (res.ok) {
        setSavedScripts(prev => prev.filter(s => s.id !== pendingDelete.id));
        setMessage({ type: 'success', text: 'Tool deleted successfully' });
        setPendingDelete(null);
      } else {
        const detail = await extractApiError(res);
        setMessage({ type: 'error', text: `Failed to delete tool: ${detail}` });
      }
    } catch (err) {
      setMessage({ type: 'error', text: `Network error deleting tool: ${err instanceof Error ? err.message : 'unknown'}` });
    } finally {
      setDeleting(false);
    }
  };

  const handleGenerateScript = async () => {
    if (!promptDescription.trim()) return;
    setGenerating(true);
    setMessage(null);
    try {
      const res = await authFetch('/api/v1/scripts/generate', {
        method: 'POST',
        body: JSON.stringify({ prompt: promptDescription, language, category }),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'AI generation failed.');
      if (data.script) {
        setScriptContent(data.script);
      }
      if (data.name) setScriptName(data.name);
      if (data.description) setScriptDesc(data.description);
      // Map "inputs" -> Section 2 (Information required). Convert the LLM
      // string into a stable JSON envelope {fields:[...]} so the runtime
      // EXTRACTION skill can index each input independently.
      if (data.inputs) {
        const fields = parseInputsString(data.inputs);
        setExtractionRules(JSON.stringify({ fields }, null, 2));
        setRequiredInputData(data.inputs);
      }
      if (data.resolution) {
        setResolutionRules(typeof data.resolution === 'string'
          ? data.resolution
          : JSON.stringify(data.resolution, null, 2));
      }
      // Map "issues" -> Section 1 (Issues this tool handles). One phrase per
      // line keeps the textarea readable.
      if (data.issues) {
        const issues = data.issues
          .split(/[,;]+/)
          .map((v: string) => v.trim())
          .filter(Boolean);
        if (issues.length) setClassificationRules(issues.join('\n'));
      }
    } catch (e) {
      console.error(e);
      setMessage({ type: 'error', text: 'AI generation failed. Please write the script manually.' });
    } finally {
      setGenerating(false);
    }
  };

  // Parse the LLM "inputs" string into structured {name,type,required} fields.
  const parseInputsString = (raw: string): { name: string; type: string; required: boolean }[] => {
    if (!raw) return [];
    const out: { name: string; type: string; required: boolean }[] = [];
    for (const part of raw.split(/[,;]+/)) {
      const m = /^\s*([A-Za-z_][A-Za-z0-9_\-]*)\s*(?::\s*([A-Za-z_][A-Za-z0-9_\-]*))?\s*\(\s*(Required|Optional)\s*\)\s*$/i.exec(part);
      if (!m) continue;
      out.push({ name: m[1], type: (m[2] || 'string').toLowerCase(), required: m[3].toLowerCase() === 'required' });
    }
    return out;
  };

  const handleSaveScript = async () => {
    if (!scriptContent.trim() || !classificationRules.trim() || !extractionRules.trim() || !resolutionRules.trim()) {
      setMessage({ type: 'error', text: 'Complete all three tool behavior sections before saving.' });
      return;
    }
    try {
      const extraction = JSON.parse(extractionRules);
      const resolution = JSON.parse(resolutionRules);
      if (!Array.isArray(extraction.fields) || extraction.fields.length === 0) throw new Error('Add at least one extraction field.');
      if (!resolution.script_path || !resolution.success_status || !resolution.failure_status) throw new Error('Add script_path, success_status, and failure_status.');
    } catch (e) {
      setMessage({ type: 'error', text: e instanceof Error ? e.message : 'Extraction and resolution rules must be valid JSON.' });
      return;
    }
    const name = scriptName.trim() || promptDescription.trim().substring(0, 40) || 'Untitled Remediation';
    // Never send an empty category — DB column is NOT NULL. Fall back to a safe default.
    const safeCategory = (category || '').trim() || 'POG_ISSUE';
    setSaving(true);
    setMessage(null);
    try {
      const payload = {
        id: activeScriptId || undefined,
        name,
        description: scriptDesc.trim() || promptDescription.trim(),
        scriptContent,
        language,
        category: safeCategory,
        requiredInputData: requiredInputData.trim(),
        validatedInDryRun,
      };

      // Save all three required skills as one tool definition.
      const toolSkillKey = existingSkillKey || name.toUpperCase().replace(/[^A-Z0-9]/g, '_');
      const skillPayloads = [
        {
          kind: 'EXECUTION',
          skillKey: toolSkillKey,
          argCount: (extractionRules.match(/"key"\s*:/g) || []).length || 2,
          mutating: true,
          enabled: true,
          description: `Remediation tool script for ${safeCategory}: ${name}`,
        },
        {
          kind: 'CATEGORIZATION',
          skillKey: toolSkillKey,
          pattern: classificationRules.split('\n').map(v => v.trim()).filter(Boolean).join(', '),
          actionKey: toolSkillKey,
          enabled: true,
          description: `Issues that use ${name}`,
          definitionJson: resolutionRules,
        },
        {
          kind: 'EXTRACTION',
          skillKey: toolSkillKey,
          pattern: '',
          enabled: true,
          description: `Required and optional inputs for ${name}`,
          definitionJson: extractionRules,
        },
      ];
      const res = await authFetch('/api/v1/scripts/bundle', {
        method: 'POST',
        body: JSON.stringify({ script: payload, skills: skillPayloads }),
      });
      if (!res.ok) {
        const detail = await extractApiError(res);
        setMessage({ type: 'error', text: `Tool bundle was not saved: ${detail}` });
        return;
      }

      setMessage({ type: 'success', text: 'Tool & LLM Skills saved to DB successfully' });
      setShowToolModal(false);
      loadSavedScripts();
    } catch (e) {
      setMessage({ type: 'error', text: `Network error saving tool: ${e instanceof Error ? e.message : 'unknown'}` });
    } finally {
      setSaving(false);
    }
  };

  const syncScroll = () => {
    if (textareaRef.current && lineNumRef.current) {
      lineNumRef.current.scrollTop = textareaRef.current.scrollTop;
    }
  };

  const lineCount = Math.max(1, scriptContent.split('\n').length);
  const lineNumbers = Array.from({ length: lineCount }, (_, i) => i + 1);

  const filteredScripts = savedScripts.filter(s => {
    const q = searchQuery.toLowerCase().trim();
    return !q || s.name.toLowerCase().includes(q) || (s.description && s.description.toLowerCase().includes(q)) || s.category.toLowerCase().includes(q);
  });

  return (
    <div style={{ maxWidth: '1000px', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: '20px', width: '100%' }}>
      
      {/* Header Card */}
      <div className="card" style={{ padding: '20px 24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '16px' }}>
        <div>
          <h2 style={{ fontSize: '18px', fontWeight: 800, color: 'var(--text-1)', margin: '0 0 4px' }}>
            Remediation Tools & Scripts
          </h2>
          <p style={{ fontSize: '12.5px', color: 'var(--text-3)', margin: 0 }}>
            Configure incident category skills, required input parameters, and executable scripts for human-approved remediation.
          </p>
        </div>
        <button
          onClick={openNewToolModal}
          className="btn-primary"
          style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '9px 18px', fontSize: '13px', fontWeight: 700 }}
        >
          <Plus size={15} /> New Tool
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

      {/* Main Tool Grid Card */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', minHeight: '400px' }}>
        <div className="card-header" style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
          <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-3)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
            Tool Registry ({filteredScripts.length})
          </span>
          <input
            type="text"
            placeholder="Search tools by name or category..."
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            style={{ width: '280px', padding: '6px 12px', fontSize: '13px', height: '34px' }}
          />
        </div>

        <div style={{ padding: '20px', flex: 1 }}>
          {loadingList ? (
            <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '40px' }}>Loading tools...</div>
          ) : filteredScripts.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--text-3)', fontSize: '13px', padding: '40px' }}>
              No remediation tools found. Click <strong>"New Tool"</strong> above to create a custom script.
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '16px' }}>
              {filteredScripts.map(script => (
                <div
                  key={script.id}
                  onClick={() => openEditToolModal(script)}
                  style={{
                    padding: '16px', border: '1px solid var(--border)', borderRadius: '8px', cursor: 'pointer',
                    background: 'var(--surface-1)', transition: 'all 0.2s', display: 'flex', flexDirection: 'column', gap: '8px'
                  }}
                  onMouseEnter={e => (e.currentTarget.style.borderColor = 'var(--accent)')}
                  onMouseLeave={e => (e.currentTarget.style.borderColor = 'var(--border)')}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                    <span style={{ fontWeight: 700, fontSize: '14px', color: 'var(--text-1)', wordBreak: 'break-word' }}>
                      {script.name}
                    </span>
                    <button
                      onClick={(e) => requestDelete(script, e)}
                      style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--crit)', padding: '2px' }}
                      title="Delete Tool"
                      aria-label={`Delete tool ${script.name}`}
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                  <p style={{ fontSize: '12px', color: 'var(--text-3)', margin: 0, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden', lineHeight: '1.5' }}>
                    {script.description || 'No description provided.'}
                  </p>
                  <div style={{ display: 'flex', gap: '8px', marginTop: 'auto', paddingTop: '6px', fontSize: '10.5px' }}>
                    <span style={{ background: 'var(--surface-2)', color: 'var(--text-2)', padding: '2px 8px', borderRadius: '4px', fontWeight: 700 }}>
                      {script.category}
                    </span>
                    <span style={{ background: 'var(--accent-dim)', color: 'var(--accent)', padding: '2px 8px', borderRadius: '4px', fontWeight: 700, textTransform: 'uppercase' }}>
                      {script.language || 'python'}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* ── CREATE / EDIT TOOL MODAL ── */}
      {showToolModal && (
        <div className="modal-backdrop" onClick={() => setShowToolModal(false)}>
          <div className="modal-panel" onClick={e => e.stopPropagation()} style={{ width: '840px', maxWidth: '95vw', maxHeight: '90vh', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
            <div className="modal-header" style={{ position: 'sticky', top: 0, zIndex: 5, padding: '16px 20px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                <h2 style={{ fontSize: '16px', fontWeight: 800, color: 'var(--text-1)', margin: 0 }}>
                  {activeScriptId ? `Editing Tool: ${scriptName}` : 'Create New Remediation Tool'}
                </h2>
              </div>
              <button className="close-btn" onClick={() => setShowToolModal(false)} aria-label="Close" style={{ background: 'transparent', border: 'none', color: 'var(--text-3)', cursor: 'pointer', display: 'flex', alignItems: 'center', padding: '4px', borderRadius: '6px' }}>
                <X size={18} />
              </button>
            </div>

            <div style={{ padding: '20px', overflowY: 'auto', flex: 1, display: 'flex', flexDirection: 'column', gap: '16px' }}>

                <div style={{ padding: '12px 14px', background: 'var(--accent-dim)', borderRadius: '6px', fontSize: '12px', color: 'var(--text-2)', border: '1px solid var(--border)' }}>
                  <strong>Tool behavior rules</strong><br />These three sections are required. They tell the LLM when to use this tool, what to collect, and how to interpret the result.
                </div>

                <div style={{ display: 'grid', gap: '12px', padding: '14px', border: '1px solid var(--border)', borderRadius: '8px' }}>
                  <strong style={{ fontSize: '13px' }}>1. Issues this tool handles <span style={{ color: 'var(--crit)' }}>*</span></strong>
                  <span style={{ fontSize: '11.5px', color: 'var(--text-3)' }}>Describe the issues, symptoms, or error patterns in plain text. The AI handles free-form text or lists.</span>
                  <textarea value={classificationRules} onChange={e => setClassificationRules(e.target.value)} placeholder="e.g. Printer offline, Label print missing, POG generation failure..." style={{ minHeight: '80px', padding: '9px 10px', fontSize: '12.5px', resize: 'vertical' }} />
                </div>

                <div style={{ display: 'grid', gap: '12px', padding: '14px', border: '1px solid var(--border)', borderRadius: '8px' }}>
                  <strong style={{ fontSize: '13px' }}>2. Information required to run it <span style={{ color: 'var(--crit)' }}>*</span></strong>
                  <span style={{ fontSize: '11.5px', color: 'var(--text-3)' }}>List required inputs in plain text (e.g. StoreNumber, PogLocation) or JSON. AI parses free-form text dynamically.</span>
                  <textarea value={extractionRules} onChange={e => setExtractionRules(e.target.value)} placeholder="e.g. StoreNumber (Required), PogLocation (Required), LabelPrintIssueFlag (Boolean)" style={{ minHeight: '100px', padding: '9px 10px', fontSize: '12px', resize: 'vertical' }} />
                </div>

                <div style={{ display: 'grid', gap: '12px', padding: '14px', border: '1px solid var(--border)', borderRadius: '8px' }}>
                  <strong style={{ fontSize: '13px' }}>3. How to interpret the result <span style={{ color: 'var(--crit)' }}>*</span></strong>
                  <span style={{ fontSize: '11.5px', color: 'var(--text-3)' }}>Describe expected outcome and escalation paths in plain text or JSON. AI interprets free-form rules seamlessly.</span>
                  <textarea value={resolutionRules} onChange={e => setResolutionRules(e.target.value)} placeholder="e.g. If success mark resolved; if failed escalate to L2 Store Ops" style={{ minHeight: '100px', padding: '9px 10px', fontSize: '12px', resize: 'vertical' }} />
                </div>
                  {/* AI prompt generator bar */}
              <div style={{ display: 'flex', gap: '8px', background: 'var(--surface-2)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border)' }}>
                <input
                  type="text"
                  placeholder={`Instruct AI to generate code in ${language === 'python' ? 'Python 3' : language === 'sh' ? 'Shell Script' : 'PowerShell'}... e.g. 'Disk cleanup for tomcat server'`}
                  value={promptDescription}
                  onChange={e => setPromptDescription(e.target.value)}
                  style={{ flex: 1, fontSize: '13px', padding: '8px 12px' }}
                  onKeyDown={e => e.key === 'Enter' && handleGenerateScript()}
                />
                <button
                  type="button"
                  onClick={handleGenerateScript}
                  disabled={generating || !promptDescription.trim()}
                  className="btn-primary"
                  style={{ display: 'flex', alignItems: 'center', gap: '6px', padding: '8px 14px', fontSize: '12px' }}
                >
                  <Sparkles size={14} /> {generating ? 'Generating...' : 'Generate AI Code'}
                </button>
              </div>

              {/* Tool metadata fields */}
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '12px' }}>
                <div>
                  <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Tool Name <span style={{ color: 'var(--crit)' }}>*</span>
                  </label>
                  <input type="text" placeholder="e.g. Restart Production Service" value={scriptName} onChange={e => setScriptName(e.target.value)} style={{ width: '100%', padding: '8px 10px', fontSize: '13px' }} />
                </div>
                <div>
                  <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                    Script Language <span style={{ color: 'var(--crit)' }}>*</span>
                  </label>
                  <select value={language} onChange={e => setLanguage(e.target.value as any)} style={{ width: '100%', padding: '8px 10px', fontSize: '13px' }}>
                    <option value="python">Python 3 (.py)</option>
                    <option value="sh">Shell Script (.sh)</option>
                    <option value="ps1">PowerShell (.ps1)</option>
                  </select>
                </div>
              </div>

              {/* Description */}
              <div>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>Description</label>
                <input type="text" placeholder="Operational purpose of this tool..." value={scriptDesc} onChange={e => setScriptDesc(e.target.value)} style={{ width: '100%', padding: '8px 10px', fontSize: '13px' }} />
              </div>

              {/* Code Editor */}
              <div>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 800, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px' }}>
                  Script Code <span style={{ color: 'var(--crit)' }}>*</span>
                </label>
                <div style={{ border: '1px solid var(--border)', borderRadius: '8px', overflow: 'hidden', display: 'flex', height: '220px', background: '#0f172a' }}>
                  <div ref={lineNumRef} style={{ width: '40px', background: '#1e293b', color: '#64748b', padding: '10px 0', textAlign: 'right', paddingRight: '10px', fontSize: '12px', fontFamily: 'var(--font-mono)', userSelect: 'none', overflowY: 'hidden' }}>
                    {lineNumbers.map(n => <div key={n}>{n}</div>)}
                  </div>
                  <textarea
                    ref={textareaRef}
                    value={scriptContent}
                    onChange={e => setScriptContent(e.target.value)}
                    onScroll={syncScroll}
                    style={{ flex: 1, background: 'transparent', color: '#e2e8f0', border: 'none', padding: '10px', fontSize: '12.5px', fontFamily: 'var(--font-mono)', resize: 'none', lineHeight: '1.5', outline: 'none' }}
                  />
                </div>
              </div>

              {/* Validated in Dry Run Checkbox */}
              <div style={{ background: 'var(--surface-2)', padding: '12px 16px', borderRadius: '8px', border: '1px solid var(--border)' }}>
                <label style={{ display: 'flex', alignItems: 'center', gap: '10px', cursor: 'pointer', fontSize: '13px', fontWeight: 700, color: 'var(--text-1)' }}>
                  <input
                    type="checkbox"
                    checked={validatedInDryRun}
                    onChange={e => setValidatedInDryRun(e.target.checked)}
                    style={{ width: '16px', height: '16px', cursor: 'pointer' }}
                  />
                  Script has been validated in dry-run system environment
                </label>
                <span style={{ fontSize: '11.5px', color: 'var(--text-3)', display: 'block', marginTop: '4px', marginLeft: '26px' }}>
                  Confirms that the script syntax and parameter handling have been verified before offering to operators.
                </span>
              </div>

              {/* Modal Save Footer (sticky bottom) */}
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '10px', borderTop: '1px solid var(--border)', padding: '14px 20px', background: 'var(--surface-1)', position: 'sticky', bottom: 0, marginTop: 'auto', zIndex: 4 }}>
                <button type="button" onClick={() => setShowToolModal(false)} className="btn-secondary" style={{ padding: '8px 16px', fontSize: '12.5px' }}>
                  Cancel
                </button>
                <button type="button" onClick={handleSaveScript} disabled={saving || !scriptContent.trim()} className="btn-primary" style={{ padding: '8px 18px', fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                  {saving ? <Loader2 size={14} className="spin" /> : <Save size={14} />} {saving ? 'Saving...' : 'Save Tool'}
                </button>
              </div>

            </div>
          </div>
        </div>
      )}

      {/* ── DELETE CONFIRMATION MODAL ── */}
      <Modal
        open={!!pendingDelete}
        title="Delete tool?"
        onClose={() => !deleting && setPendingDelete(null)}
        footer={
          <>
            <Button variant="secondary" onClick={() => setPendingDelete(null)} disabled={deleting}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmDelete} disabled={deleting} style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              {deleting ? <Loader2 size={14} className="spin" /> : <Trash2 size={14} />}
              {deleting ? 'Deleting…' : 'Delete tool'}
            </Button>
          </>
        }
      >
        {pendingDelete && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            <p style={{ margin: 0, color: 'var(--text-2)', fontSize: '13px' }}>
              This will remove <strong style={{ color: 'var(--text-1)' }}>{pendingDelete.name}</strong> from the
              workspace registry, along with its CATEGORIZATION, EXTRACTION, and EXECUTION skills.
            </p>
            <p style={{ margin: 0, color: 'var(--text-3)', fontSize: '12px' }}>
              Any plan that already references this tool will need to be re-approved.
            </p>
          </div>
        )}
      </Modal>

    </div>
  );
};

export default ToolsPage;
