import React, { useState, useEffect, useRef, useCallback } from 'react';
import { authFetch, SIMPLE_ERROR_MESSAGE } from '../services/api';
import './ScriptEditorPage.css';

/* ═══════════════════════════════════════════════════════════════════════════
   ScriptEditorPage — Full-featured script editor with:
     • AI-powered generation from natural language
     • 5-layer guardrail validation with inline findings
     • Live execution with dry-run toggle
     • Workspace: save / load / delete scripts from DB
   ═══════════════════════════════════════════════════════════════════════════ */

interface Finding {
  level: string;
  layer: string;
  message: string;
}

interface SavedScript {
  id: string;
  name: string;
  description: string;
  scriptContent: string;
  language: string;
  category: string;
  targetHost: string;
  status: string;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

const CATEGORIES = [
  'APPLICATION', 'PERFORMANCE', 'INFRASTRUCTURE', 'DATABASE', 'DEPLOYMENT', 'NETWORK',
];

const ScriptEditorPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  // ── State ──────────────────────────────────────────────────────────────────
  const [description, setDescription]   = useState('');
  const [scriptContent, setScriptContent] = useState('');
  const [language, setLanguage]         = useState<'bash'|'powershell'>('bash');
  const [category, setCategory]         = useState('APPLICATION');
  const [targetHost, setTargetHost]     = useState('localhost');
  const [dryRun, setDryRun]             = useState(true);

  // Findings & Output
  const [findings, setFindings]         = useState<Finding[]>([]);
  const [validationLevel, setValidationLevel] = useState<string>('');
  const [execOutput, setExecOutput]     = useState<{stdout:string; stderr:string; exitCode:number; message:string} | null>(null);

  // Workspace
  const [savedScripts, setSavedScripts] = useState<SavedScript[]>([]);
  const [activeScriptId, setActiveScriptId] = useState<string|null>(null);
  const [showSaveModal, setShowSaveModal] = useState(false);
  const [saveName, setSaveName]         = useState('');
  const [saveDesc, setSaveDesc]         = useState('');

  // Loading states
  const [generating, setGenerating]     = useState(false);
  const [validating, setValidating]     = useState(false);
  const [executing, setExecuting]       = useState(false);
  const [saving, setSaving]             = useState(false);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lineNumRef  = useRef<HTMLDivElement>(null);

  // ── Load saved scripts ─────────────────────────────────────────────────────
  const loadSavedScripts = useCallback(async () => {
    try {
      const r = await authFetch(`/api/v1/scripts?tenantId=${tenantId}`);
      if (r.ok) {
        const data = await r.json();
        setSavedScripts(data.scripts || []);
      }
    } catch { /* ignore */ }
  }, [tenantId]);

  useEffect(() => { loadSavedScripts(); }, [loadSavedScripts]);

  // ── Sync line numbers scroll with textarea ─────────────────────────────────
  const handleEditorScroll = () => {
    if (textareaRef.current && lineNumRef.current) {
      lineNumRef.current.scrollTop = textareaRef.current.scrollTop;
    }
  };

  // ── Line numbers ───────────────────────────────────────────────────────────
  const lineCount = scriptContent ? scriptContent.split('\n').length : 1;
  const lineNumbers = Array.from({ length: lineCount }, (_, i) => i + 1).join('\n');

  // ── Handle Tab in textarea ─────────────────────────────────────────────────
  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Tab') {
      e.preventDefault();
      const ta = e.currentTarget;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const val = ta.value;
      setScriptContent(val.substring(0, start) + '    ' + val.substring(end));
      setTimeout(() => { ta.selectionStart = ta.selectionEnd = start + 4; }, 0);
    }
  };

  // ═══════════════════════════════════════════════════════════════════════════
  // API CALLS
  // ═══════════════════════════════════════════════════════════════════════════

  // ── Generate script from description ───────────────────────────────────────
  const handleGenerate = async () => {
    if (!description.trim()) return;
    setGenerating(true);
    setFindings([]);
    setExecOutput(null);
    setValidationLevel('');
    try {
      const r = await authFetch('/api/v1/scripts/generate', {
        method: 'POST',
        body: JSON.stringify({
          description: description.trim(),
          category,
          targetHost,
          os: language === 'powershell' ? 'windows' : 'linux',
        }),
      });
      const data = await r.json();
      if (!r.ok) {
        setFindings([{ level: 'BLOCK', layer: 'Generation', message: data.error || SIMPLE_ERROR_MESSAGE }]);
        setValidationLevel('BLOCK');
        return;
      }
      if (data.script) {
        setScriptContent(data.script);
        setValidationLevel('PASS');
        setFindings([]);
      }
      if (data.error) {
        setFindings([{ level: 'BLOCK', layer: 'Generation', message: data.error }]);
        setValidationLevel('BLOCK');
      }
    } catch {
      setFindings([{ level: 'BLOCK', layer: 'Error', message: SIMPLE_ERROR_MESSAGE }]);
    } finally {
      setGenerating(false);
    }
  };

  // ── Validate script ────────────────────────────────────────────────────────
  const handleValidate = async () => {
    if (!scriptContent.trim()) return;
    setValidating(true);
    setExecOutput(null);
    try {
      const r = await authFetch('/api/v1/scripts/validate', {
        method: 'POST',
        body: JSON.stringify({
          scriptContent,
          category,
          os: language === 'powershell' ? 'windows' : 'linux',
          description: description || 'User script',
        }),
      });
      const data = await r.json();
      if (!r.ok) {
        setFindings([{ level: 'BLOCK', layer: 'Error', message: data.error || SIMPLE_ERROR_MESSAGE }]);
        setValidationLevel('BLOCK');
        return;
      }
      setValidationLevel(data.level || 'PASS');
      setFindings(data.findings || []);
    } catch {
      setFindings([{ level: 'BLOCK', layer: 'Error', message: SIMPLE_ERROR_MESSAGE }]);
      setValidationLevel('BLOCK');
    } finally {
      setValidating(false);
    }
  };

  // ── Execute script ─────────────────────────────────────────────────────────
  const handleExecute = async () => {
    if (!scriptContent.trim()) return;
    setExecuting(true);
    setExecOutput(null);
    try {
      const r = await authFetch('/api/v1/scripts/execute', {
        method: 'POST',
        body: JSON.stringify({
          scriptContent,
          language,
          dryRun,
          category,
          description: description || 'User script execution',
          targetHost,
        }),
      });
      const data = await r.json();
      if (!r.ok) {
        setExecOutput({
          stdout: '',
          stderr: data.error || SIMPLE_ERROR_MESSAGE,
          exitCode: -1,
          message: 'Error',
        });
        return;
      }
      setExecOutput({
        stdout:   data.stdout || '',
        stderr:   data.stderr || '',
        exitCode: data.exitCode ?? -1,
        message:  data.message || '',
      });
      if (data.blocked) {
        setValidationLevel('BLOCK');
        setFindings([{ level: 'BLOCK', layer: 'Guardrails', message: data.stderr || 'Blocked' }]);
      }
    } catch {
      setExecOutput({ stdout: '', stderr: SIMPLE_ERROR_MESSAGE, exitCode: -1, message: 'Error' });
    } finally {
      setExecuting(false);
    }
  };

  // ── Save script ────────────────────────────────────────────────────────────
  const handleSave = async () => {
    if (!scriptContent.trim() || !saveName.trim()) return;
    setSaving(true);
    try {
      const payload = {
        name: saveName.trim(),
        description: saveDesc.trim(),
        scriptContent,
        language,
        category,
        targetHost,
        tenantId,
      };
      let r;
      if (activeScriptId) {
        r = await authFetch(`/api/v1/scripts/${activeScriptId}`, {
          method: 'PUT',
          body: JSON.stringify(payload),
        });
      } else {
        r = await authFetch('/api/v1/scripts', {
          method: 'POST',
          body: JSON.stringify(payload),
        });
      }
      if (r.ok) {
        const data = await r.json();
        if (data.id) setActiveScriptId(data.id);
        setShowSaveModal(false);
        loadSavedScripts();
      }
    } catch { /* ignore */ }
    finally { setSaving(false); }
  };

  // ── Load a saved script ────────────────────────────────────────────────────
  const handleLoadScript = async (id: string) => {
    try {
      const r = await authFetch(`/api/v1/scripts/${id}`);
      if (r.ok) {
        const s: SavedScript = await r.json();
        setScriptContent(s.scriptContent);
        setLanguage((s.language || 'bash') as 'bash'|'powershell');
        setCategory(s.category || 'APPLICATION');
        setTargetHost(s.targetHost || 'localhost');
        setDescription(s.description || '');
        setSaveName(s.name);
        setSaveDesc(s.description || '');
        setActiveScriptId(s.id);
        setFindings([]);
        setExecOutput(null);
        setValidationLevel('');
      }
    } catch { /* ignore */ }
  };

  // ── Delete a saved script ──────────────────────────────────────────────────
  const handleDeleteScript = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await authFetch(`/api/v1/scripts/${id}`, { method: 'DELETE' });
      if (activeScriptId === id) {
        setActiveScriptId(null);
        setScriptContent('');
        setSaveName('');
        setSaveDesc('');
      }
      loadSavedScripts();
    } catch { /* ignore */ }
  };

  // ── New script ─────────────────────────────────────────────────────────────
  const handleNew = () => {
    setActiveScriptId(null);
    setScriptContent('');
    setDescription('');
    setSaveName('');
    setSaveDesc('');
    setFindings([]);
    setExecOutput(null);
    setValidationLevel('');
  };

  // ── Open save modal ────────────────────────────────────────────────────────
  const openSaveModal = () => {
    if (!saveName && description) {
      setSaveName(description.substring(0, 80));
    }
    if (!saveDesc && description) {
      setSaveDesc(description);
    }
    setShowSaveModal(true);
  };

  // ═══════════════════════════════════════════════════════════════════════════
  // RENDER
  // ═══════════════════════════════════════════════════════════════════════════

  return (
    <div className="script-editor-page">

      {/* ── Top bar: Description + Settings + Generate ──────────────── */}
      <div className="se-topbar">
        <input
          type="text"
          placeholder="Describe what the script should do… e.g., 'Restart Tomcat and verify health'"
          value={description}
          onChange={e => setDescription(e.target.value)}
          onKeyDown={e => e.key === 'Enter' && handleGenerate()}
        />
        <select value={language} onChange={e => setLanguage(e.target.value as 'bash'|'powershell')}>
          <option value="bash">Bash</option>
          <option value="powershell">PowerShell</option>
        </select>
        <select value={category} onChange={e => setCategory(e.target.value)}>
          {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
        <button className="se-btn primary" onClick={handleGenerate} disabled={generating || !description.trim()}>
          {generating ? <span className="se-spinner" /> : '⚡'} Generate
        </button>
        <button className="se-btn ghost" onClick={handleNew} title="New blank script">
          ✚ New
        </button>
      </div>

      {/* ── Main: Editor + Sidebar ──────────────────────────────────── */}
      <div className="se-main">

        {/* ── Editor column ──────────────────────────────────────────── */}
        <div className="se-editor-col">

          {/* Code editor */}
          <div className="se-editor-wrap">
            <div className="se-line-numbers" ref={lineNumRef}>
              {lineNumbers}
            </div>
            <textarea
              ref={textareaRef}
              className="se-textarea"
              value={scriptContent}
              onChange={e => { setScriptContent(e.target.value); setValidationLevel(''); }}
              onScroll={handleEditorScroll}
              onKeyDown={handleKeyDown}
              placeholder={`# Write or generate a ${language === 'powershell' ? 'PowerShell' : 'Bash'} script...\n# Use the Generate button above to create one from a description.\n# Then Validate → Execute.`}
              spellCheck={false}
            />
          </div>

          {/* Action bar */}
          <div className="se-action-bar">
            <button className="se-btn green" onClick={handleValidate}
                    disabled={validating || !scriptContent.trim()}>
              {validating ? <span className="se-spinner" /> : '✓'} Validate
            </button>

            <div className="se-dryrun-toggle">
              <input type="checkbox" id="dryRunToggle" checked={dryRun}
                     onChange={e => setDryRun(e.target.checked)} />
              <label htmlFor="dryRunToggle">Dry Run</label>
            </div>

            <button className="se-btn amber" onClick={handleExecute}
                    disabled={executing || !scriptContent.trim()}>
              {executing ? <span className="se-spinner" /> : '▶'} {dryRun ? 'Dry Run' : 'Execute'}
            </button>

            <div className="se-spacer" />

            {validationLevel && (
              <span className={`se-status-chip ${validationLevel.toLowerCase()}`}>
                {validationLevel}
              </span>
            )}

            <button className="se-btn primary" onClick={openSaveModal}
                    disabled={!scriptContent.trim()}>
              💾 Save
            </button>
          </div>

          {/* Findings panel */}
          {findings.length > 0 && (
            <div className="se-output-panel">
              <div className="se-output-label">Validation Findings ({findings.length})</div>
              <div className="se-findings">
                {findings.map((f, i) => (
                  <div key={i} className={`se-finding ${f.level}`}>
                    <span className={`se-finding-level ${f.level}`}>{f.level}</span>
                    <span className="se-finding-layer">{f.layer}</span>
                    <span>{f.message}</span>
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Execution output panel */}
          {execOutput && (
            <div className="se-output-panel">
              <div className="se-output-label">
                Execution Output
                <span style={{marginLeft: 12, fontWeight: 400}}>
                  Exit Code: <b style={{ color: execOutput.exitCode === 0 ? '#30d99c' : '#ff5555' }}>
                    {execOutput.exitCode}
                  </b>
                </span>
              </div>
              {execOutput.stdout && (
                <div className="se-output-stdout">{execOutput.stdout}</div>
              )}
              {execOutput.stderr && (
                <div className="se-output-stderr">{execOutput.stderr}</div>
              )}
              {!execOutput.stdout && !execOutput.stderr && (
                <div className="se-output-info">{execOutput.message || 'No output'}</div>
              )}
            </div>
          )}
        </div>

        {/* ── Sidebar: Saved scripts ────────────────────────────────── */}
        <div className="se-sidebar-col">
          <div className="se-saved-panel">
            <div className="se-saved-header">
              <span>Saved Scripts</span>
              <span style={{color: 'rgba(255,255,255,0.2)'}}>{savedScripts.length}</span>
            </div>
            <div className="se-saved-list">
              {savedScripts.length === 0 && (
                <div className="se-saved-empty">No saved scripts yet.<br/>Generate or write one, then save.</div>
              )}
              {savedScripts.map(s => (
                <div
                  key={s.id}
                  className={`se-saved-item ${activeScriptId === s.id ? 'active' : ''}`}
                  onClick={() => handleLoadScript(s.id)}
                >
                  <span className="se-saved-item-name" title={s.name}>{s.name}</span>
                  <span className="se-saved-item-lang">{s.language}</span>
                  <span className="se-saved-item-delete"
                        title="Delete"
                        onClick={(e) => handleDeleteScript(s.id, e)}>
                    ✕
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ── Save Modal ──────────────────────────────────────────────── */}
      {showSaveModal && (
        <div className="se-modal-overlay" onClick={() => setShowSaveModal(false)}>
          <div className="se-modal" onClick={e => e.stopPropagation()}>
            <h3>{activeScriptId ? '✏️ Update Script' : '💾 Save Script'}</h3>

            <label>Name</label>
            <input type="text" value={saveName} onChange={e => setSaveName(e.target.value)}
                   placeholder="e.g., Restart Tomcat" />

            <label>Description</label>
            <textarea value={saveDesc} onChange={e => setSaveDesc(e.target.value)}
                      placeholder="What does this script do?" rows={3} />

            <label>Language</label>
            <select value={language} onChange={e => setLanguage(e.target.value as 'bash'|'powershell')}>
              <option value="bash">Bash</option>
              <option value="powershell">PowerShell</option>
            </select>

            <label>Category</label>
            <select value={category} onChange={e => setCategory(e.target.value)}>
              {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
            </select>

            <label>Target Host</label>
            <input type="text" value={targetHost} onChange={e => setTargetHost(e.target.value)}
                   placeholder="localhost" />

            <div className="se-modal-actions">
              <button className="se-btn ghost" onClick={() => setShowSaveModal(false)}>Cancel</button>
              <button className="se-btn primary" onClick={handleSave}
                      disabled={saving || !saveName.trim()}>
                {saving ? <span className="se-spinner" /> : null}
                {activeScriptId ? 'Update' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default ScriptEditorPage;
