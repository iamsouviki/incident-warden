import React, { useState, useEffect, useRef } from 'react';
import { authFetch, getStoredUser } from '../services/api';
import { Play, CheckCircle, XCircle, Trash2, Plus, Save, Sparkles, AlertTriangle } from 'lucide-react';
import SkillsPanel from '../components/SkillsPanel';

import './ScriptEditorPage.css'; // Reuse or import editor styles

interface SavedScript {
  id: string;
  name: string;
  description: string;
  scriptContent: string;
  language: string;
  category: string;
  targetHost: string;
}

interface ExecutionLog {
  id: string;
  scriptId?: string;
  name: string;
  timestamp: string;
  scriptContent: string;
  status: string;
  exitCode: number;
  stdout: string;
  stderr: string;
}

interface Finding {
  level: string;
  layer: string;
  message: string;
}

const CATEGORIES = [
  'APPLICATION', 'PERFORMANCE', 'INFRASTRUCTURE', 'DATABASE', 'DEPLOYMENT', 'NETWORK',
];

const ToolsPage: React.FC = () => {
  const user = getStoredUser();
  const tenantId = user?.tenantId || 'tenant-1';

  // Skills default: Categorization, Extraction, and Skill Mapping define what actions the platform takes.
  const [mode, setMode] = useState<'skills' | 'scripts'>('skills');
  const isAdmin = user?.role === 'ADMIN';
  const [savedScripts, setSavedScripts] = useState<SavedScript[]>([]);
  const [loadingList, setLoadingList] = useState(false);

  // Right editor workspace state
  const [activeScriptId, setActiveScriptId] = useState<string | null>(null);
  const [promptDescription, setPromptDescription] = useState('');
  const [scriptName, setScriptName] = useState('');
  const [scriptDesc, setScriptDesc] = useState('');
  const [scriptContent, setScriptContent] = useState('');
  const [language, setLanguage] = useState<'bash' | 'powershell'>('bash');
  const [category, setCategory] = useState('APPLICATION');
  const [targetHost, setTargetHost] = useState('localhost');
  const [dryRun, setDryRun] = useState(true);

  // Status & outputs
  const [validating, setValidating] = useState(false);
  const [generating, setGenerating] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [saving, setSaving] = useState(false);
  const [validationLevel, setValidationLevel] = useState('');
  const [findings, setFindings] = useState<Finding[]>([]);
  const [execOutput, setExecOutput] = useState<{ stdout: string; stderr: string; exitCode: number } | null>(null);

  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const lineNumRef = useRef<HTMLDivElement>(null);

  // Load left-hand side list data
  const loadSavedScripts = async () => {
    setLoadingList(true);
    try {
      const res = await authFetch(`/api/v1/scripts?tenantId=${tenantId}`);
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

  // Parse query parameters to pre-fill prompt description
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const descParam = params.get('desc');
    if (descParam) {
      setPromptDescription(descParam);
    }
  }, []);

  // Sync editor line scrolling
  const handleEditorScroll = () => {
    if (textareaRef.current && lineNumRef.current) {
      lineNumRef.current.scrollTop = textareaRef.current.scrollTop;
    }
  };

  const lineCount = scriptContent ? scriptContent.split('\n').length : 1;
  const lineNumbers = Array.from({ length: lineCount }, (_, i) => i + 1).join('\n');

  // Handle Tab spaces
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

  // Reset workspace for new script creation
  const handleNewTool = () => {
    setActiveScriptId(null);
    setScriptName('');
    setScriptDesc('');
    setScriptContent('');
    setPromptDescription('');
    setLanguage('bash');
    setCategory('APPLICATION');
    setTargetHost('localhost');
    setFindings([]);
    setExecOutput(null);
    setValidationLevel('');
  };

  // Load selected script into workspace
  const handleSelectScript = (script: SavedScript) => {
    setActiveScriptId(script.id);
    setScriptName(script.name);
    setScriptDesc(script.description || '');
    setScriptContent(script.scriptContent);
    setLanguage(script.language as 'bash' | 'powershell');
    setCategory(script.category || 'APPLICATION');
    setTargetHost(script.targetHost || 'localhost');
    setPromptDescription(script.description || '');
    setFindings([]);
    setExecOutput(null);
    setValidationLevel('');
  };

  const handleDeleteScript = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm('Delete this tool from database?')) return;
    try {
      const res = await authFetch(`/api/v1/scripts/${id}`, { method: 'DELETE' });
      if (res.ok) {
        if (activeScriptId === id) handleNewTool();
        loadSavedScripts();
      }
    } catch (err) {
      console.error(err);
    }
  };

  // AI Code Generation
  const handleGenerateScript = async () => {
    if (!promptDescription.trim()) return;
    setGenerating(true);
    setFindings([]);
    setExecOutput(null);
    setValidationLevel('');
    try {
      const r = await authFetch('/api/v1/scripts/generate', {
        method: 'POST',
        body: JSON.stringify({
          description: promptDescription.trim(),
          category,
          targetHost,
          os: language === 'powershell' ? 'windows' : 'linux',
        }),
      });
      const data = await r.json();
      if (r.ok && data.script) {
        setScriptContent(data.script);
        if (!scriptName) {
          setScriptName(promptDescription.substring(0, 40));
        }
      }
    } catch (e) {
      console.error(e);
    } finally {
      setGenerating(false);
    }
  };

  // Guardrail validation
  const handleValidateScript = async () => {
    if (!scriptContent.trim()) return;
    setValidating(true);
    setFindings([]);
    try {
      const r = await authFetch('/api/v1/scripts/validate', {
        method: 'POST',
        body: JSON.stringify({ scriptContent, os: language === 'powershell' ? 'windows' : 'linux' }),
      });
      const data = await r.json();
      setValidationLevel(data.level || 'PASS');
      setFindings(data.findings || []);
    } catch (e) {
      console.error(e);
    } finally {
      setValidating(false);
    }
  };

  // Execute Script
  const handleExecuteScript = async () => {
    if (!scriptContent.trim()) return;
    if (!dryRun && (validationLevel !== 'PASS' || findings.some(f => f.level === 'BLOCK'))) {
      setValidationLevel('BLOCK');
      setFindings(current => current.length ? current : [{ level: 'BLOCK', layer: 'EXECUTION GATE', message: 'Run Validate Guardrails and resolve all blocking findings before executing on a live host.' }]);
      return;
    }
    setExecuting(true);
    setExecOutput(null);
    try {
      const name = scriptName.trim() || `Script Run: ${promptDescription.substring(0, 40) || 'Untitled'}`;
      const r = await authFetch('/api/v1/scripts/execute', {
        method: 'POST',
        body: JSON.stringify({
          scriptContent,
          language,
          dryRun,
          category,
          description: name,
          targetHost
        }),
      });
      const data = await r.json();
      setExecOutput({
        stdout: data.stdout || '',
        stderr: data.stderr || '',
        exitCode: data.exitCode ?? -1
      });

      // Save execution to local history
      const logEntry: ExecutionLog = {
        id: Math.random().toString(36).substring(2, 9),
        scriptId: activeScriptId || undefined,
        name: name,
        timestamp: new Date().toISOString(),
        scriptContent,
        status: data.exitCode === 0 ? 'SUCCESS' : 'FAILURE',
        exitCode: data.exitCode ?? -1,
        stdout: data.stdout || '',
        stderr: data.stderr || ''
      };

      const history = JSON.parse(localStorage.getItem('mcp_execution_history') || '[]');
      history.push(logEntry);
      localStorage.setItem('mcp_execution_history', JSON.stringify(history));
      loadExecutionLogs();
    } catch (e) {
      console.error(e);
    } finally {
      setExecuting(false);
    }
  };

  // Save/Update Script in DB
  const handleSaveScript = async () => {
    if (!scriptContent.trim()) return;
    const name = scriptName.trim() || promptDescription.trim().substring(0, 40) || 'Untitled Remediation';
    setSaving(true);
    try {
      const payload = {
        name,
        description: scriptDesc.trim() || promptDescription.trim(),
        scriptContent,
        language,
        category,
        targetHost,
        tenantId
      };
      
      const endpoint = activeScriptId ? `/api/v1/scripts/${activeScriptId}` : '/api/v1/scripts';
      const method = activeScriptId ? 'PUT' : 'POST';

      const res = await authFetch(endpoint, {
        method,
        body: JSON.stringify(payload)
      });
      
      if (res.ok) {
        const data = await res.json();
        if (data.id) setActiveScriptId(data.id);
        setValidationLevel('PASS');
        loadSavedScripts();
      }
    } catch (err) {
      console.error(err);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div style={{ display: 'grid', gap: '16px', width: '100%' }}>

    {/* ── 3 Core Skills vs Custom Scripts ── */}
    <div style={{ display: 'inline-grid', gridTemplateColumns: '1fr 1fr', gap: '6px', background: 'var(--surface2)', padding: '4px', borderRadius: '6px', maxWidth: '420px' }}>
      <button
        onClick={() => setMode('skills')}
        style={{
          border: 'none', background: mode === 'skills' ? 'var(--surface)' : 'transparent',
          color: mode === 'skills' ? 'var(--text)' : 'var(--text-dim)',
          minHeight: '36px', padding: '0 16px', borderRadius: '4px', cursor: 'pointer',
          fontSize: '12px', fontWeight: 600,
        }}
      >
        🎯 3 Core Skills Engine
      </button>
      <button
        onClick={() => setMode('scripts')}
        style={{
          border: 'none', background: mode === 'scripts' ? 'var(--surface)' : 'transparent',
          color: mode === 'scripts' ? 'var(--text)' : 'var(--text-dim)',
          minHeight: '36px', padding: '0 16px', borderRadius: '4px', cursor: 'pointer',
          fontSize: '12px', fontWeight: 600,
        }}
      >
        🛠 Custom Scripts & Sandbox
      </button>
    </div>

    {mode === 'skills' ? <SkillsPanel /> : (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(360px, 1fr)) 1.2fr', gap: '20px', minHeight: 'calc(100vh - 160px)', width: '100%' }}>
      
      {/* ── LEFT PANEL: Saved Tools Directory and execution history ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ display: 'flex', flexDirection: 'column', gap: '12px', borderBottom: '1px solid var(--border)', padding: '16px 20px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: '0.5px' }}>
              Tools Navigator
            </span>
            {/* Create New Tool triggers styled state reset */}
            <button
              onClick={handleNewTool}
              style={{
                display: 'flex', alignItems: 'center', gap: '4px', padding: '6px 12px',
                background: 'var(--michaels-red)', color: 'white', border: 'none', borderRadius: '4px',
                fontSize: '11px', fontWeight: 'bold', cursor: 'pointer', textTransform: 'uppercase'
              }}
            >
              <Plus size={12} /> New Tool
            </button>
          </div>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', padding: '16px' }}>
          {loadingList ? (
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', padding: '20px' }}>Loading tools...</div>
          ) : savedScripts.length === 0 ? (
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px', padding: '20px' }}>
              No saved tools found. Write code on the right and click Save to store it.
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {savedScripts.map(script => (
                <div
                  key={script.id}
                  onClick={() => handleSelectScript(script)}
                  style={{
                    padding: '12px', border: '1px solid var(--border)', borderRadius: '8px', cursor: 'pointer',
                    background: activeScriptId === script.id ? 'var(--surface3)' : 'var(--surface)',
                    transition: 'all 0.2s', borderLeft: activeScriptId === script.id ? '4px solid var(--michaels-red)' : '1px solid var(--border)'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: '240px' }}>
                      {script.name}
                    </span>
                    <button
                      onClick={(e) => handleDeleteScript(script.id, e)}
                      style={{ border: 'none', background: 'transparent', cursor: 'pointer', color: 'var(--red)', padding: '2px' }}
                    >
                      <Trash2 size={13} />
                    </button>
                  </div>
                  <p style={{ fontSize: '11px', color: 'var(--text-muted)', marginTop: '4px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {script.description || 'No description provided.'}
                  </p>
                  <div style={{ display: 'flex', gap: '8px', marginTop: '8px', fontSize: '10px', color: 'var(--text-dim)' }}>
                    <span style={{ background: 'var(--surface2)', padding: '2px 6px', borderRadius: '4px', fontFamily: 'monospace' }}>
                      {script.targetHost}
                    </span>
                    <span style={{ background: 'var(--accent-dim)', color: 'var(--accent)', padding: '2px 6px', borderRadius: '4px', textTransform: 'uppercase' }}>
                      {script.language}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* ── RIGHT PANEL: Unified Script Editor Workspace ── */}
      <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
        <div className="card-header" style={{ borderBottom: '1px solid var(--border)', padding: '16px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <span style={{ fontSize: '14px', fontWeight: 'bold', color: 'var(--text)' }}>
              {activeScriptId ? `Editing Tool: ${scriptName}` : 'Create New Remediation Tool'}
            </span>
          </div>
          <div style={{ display: 'flex', gap: '10px' }}>
            <button
              onClick={handleSaveScript}
              disabled={saving || !scriptContent.trim()}
              className="btn-primary"
              style={{ padding: '8px 16px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '4px' }}
            >
              <Save size={12} /> Save Tool
            </button>
          </div>
        </div>

        <div style={{ padding: '20px', flex: 1, display: 'flex', flexDirection: 'column', gap: '16px', overflowY: 'auto' }}>
          
          {/* AI Generator Block */}
          <div style={{ display: 'flex', gap: '8px', background: 'var(--surface2)', padding: '12px', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <input
              type="text"
              placeholder="Instruct AI to write code... e.g. 'Disk cleanup for tomcat server' or 'Reboot service'"
              value={promptDescription}
              onChange={e => setPromptDescription(e.target.value)}
              style={{ flex: 1, fontSize: '13px', border: '1px solid var(--border)', background: 'var(--surface)', padding: '10px 12px', borderRadius: '4px' }}
              onKeyDown={e => e.key === 'Enter' && handleGenerateScript()}
            />
            <button
              onClick={handleGenerateScript}
              disabled={generating || !promptDescription.trim()}
              style={{
                display: 'flex', alignItems: 'center', gap: '4px', padding: '10px 16px',
                background: 'var(--text)', color: 'white', border: 'none', borderRadius: '4px',
                fontWeight: 'bold', cursor: 'pointer', fontSize: '12px'
              }}
            >
              {generating ? <span className="se-spinner" /> : <Sparkles size={13} />} Generate
            </button>
          </div>

          {/* Config row — clean 1-2 line parameter grid */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '10px', background: 'var(--surface-2)', padding: '12px 14px', borderRadius: '8px', border: '1px solid var(--border)' }}>
            <div>
              <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px', letterSpacing: '0.5px' }}>Tool Name</label>
              <input
                type="text"
                value={scriptName}
                onChange={e => setScriptName(e.target.value)}
                placeholder="e.g. Server Cleanup"
                style={{ height: '34px', padding: '0 10px', fontSize: '12.5px' }}
              />
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px', letterSpacing: '0.5px' }}>Language</label>
              <select
                value={language}
                onChange={e => setLanguage(e.target.value as 'bash' | 'powershell')}
                style={{ height: '34px', padding: '0 10px', fontSize: '12.5px', appearance: 'auto' }}
              >
                <option value="bash">Bash (Linux)</option>
                <option value="powershell">PowerShell (Windows)</option>
              </select>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px', letterSpacing: '0.5px' }}>Category</label>
              <select
                value={category}
                onChange={e => setCategory(e.target.value)}
                style={{ height: '34px', padding: '0 10px', fontSize: '12.5px', appearance: 'auto' }}
              >
                {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <label style={{ display: 'block', fontSize: '10.5px', fontWeight: 700, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: '4px', letterSpacing: '0.5px' }}>Target Host</label>
              <input
                type="text"
                value={targetHost}
                onChange={e => setTargetHost(e.target.value)}
                placeholder="localhost"
                style={{ height: '34px', padding: '0 10px', fontSize: '12.5px' }}
              />
            </div>
          </div>

          {/* Script Code Area */}
          <div style={{ flex: 1, minHeight: '220px', display: 'flex', flexDirection: 'column' }}>
            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '6px' }}>Script Code</label>
            <div className="se-editor-wrap" style={{ flex: 1 }}>
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
                placeholder={`# Write code here...`}
                spellCheck={false}
              />
            </div>
          </div>

          {/* Actions panel */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderTop: '1px solid var(--border)', paddingTop: '16px' }}>
            <div style={{ display: 'flex', gap: '12px' }}>
              <button
                onClick={handleValidateScript}
                disabled={validating || !scriptContent.trim()}
                style={{
                  padding: '10px 18px', background: 'var(--surface2)', color: 'var(--text)',
                  border: '1px solid var(--border)', borderRadius: '6px', cursor: 'pointer',
                  fontSize: '13px', fontWeight: 'bold'
                }}
              >
                {validating ? 'Validating...' : '✓ Validate Guardrails'}
              </button>

              <button
                onClick={handleExecuteScript}
                disabled={executing || !scriptContent.trim()}
                className="btn-primary"
                style={{ padding: '10px 20px', fontSize: '13px' }}
              >
                <Play size={13} style={{ marginRight: '6px', verticalAlign: 'middle' }} />
                {dryRun ? 'Simulate (Dry Run)' : 'Execute Action'}
              </button>
            </div>

            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              <input
                type="checkbox"
                id="editorDryRun"
                checked={dryRun}
                onChange={e => setDryRun(e.target.checked)}
                style={{ width: '16px', height: '16px', cursor: 'pointer' }}
              />
              <label htmlFor="editorDryRun" style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text-dim)', cursor: 'pointer' }}>
                Safety Dry-Run Mode
              </label>
            </div>
          </div>

          {/* Validation finding messages */}
          {findings.length > 0 && (
            <div style={{ background: 'rgba(239, 68, 68, 0.05)', border: '1px solid rgba(239, 68, 68, 0.2)', padding: '14px', borderRadius: '8px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: 'var(--red)', fontWeight: 'bold', fontSize: '13px', marginBottom: '8px' }}>
                <AlertTriangle size={15} /> Safety Findings Detected ({findings.length})
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                {findings.map((f, i) => (
                  <div key={i} style={{ fontSize: '12px', color: 'var(--text-dim)', paddingLeft: '8px', borderLeft: '2px solid var(--red)' }}>
                    <b>[{f.layer}]</b> {f.message}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Console / run logs output */}
          {execOutput && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              <div style={{ fontSize: '11px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                Run Output Console (Status: {execOutput.exitCode === 0 ? 'SUCCESS' : 'FAILED'} - Code: {execOutput.exitCode})
              </div>
              
              <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: '10px' }}>
                {execOutput.stdout && (
                  <div>
                    <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginBottom: '4px' }}>stdout</div>
                    <pre style={{
                      background: '#0f172a', color: '#38bdf8', padding: '12px', borderRadius: '6px',
                      fontFamily: 'monospace', fontSize: '12px', overflowX: 'auto', maxHeight: '180px'
                    }}>
                      {execOutput.stdout}
                    </pre>
                  </div>
                )}
                {execOutput.stderr && (
                  <div>
                    <div style={{ fontSize: '10px', color: 'var(--text-muted)', marginBottom: '4px' }}>stderr</div>
                    <pre style={{
                      background: '#0f172a', color: '#f87171', padding: '12px', borderRadius: '6px',
                      fontFamily: 'monospace', fontSize: '12px', overflowX: 'auto', maxHeight: '180px'
                    }}>
                      {execOutput.stderr}
                    </pre>
                  </div>
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
    )}
    </div>
  );
};

export default ToolsPage;
