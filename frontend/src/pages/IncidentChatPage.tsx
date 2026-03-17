import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { authFetch, extractApiError, SIMPLE_ERROR_MESSAGE } from '../services/api';

interface ConversationThread {
  id: string;
  title: string;
  status: string;
  currentAttempt: number;
  incidentId?: string;
}

interface ConversationMessage {
  id: string;
  role: string;
  messageType: string;
  content: string;
  createdAt: string;
}

interface ScriptProposal {
  id: string;
  attemptNo: number;
  shellType: string;
  scriptContent: string;
  explanation?: string;
  riskLevel: string;
  approvalRequired: boolean;
  status: string;
  rollbackPlan?: string;
  validationPlanJson?: string[];
}

const IncidentChatPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [threads, setThreads] = useState<ConversationThread[]>([]);
  const [activeThreadId, setActiveThreadId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ConversationMessage[]>([]);
  const [proposals, setProposals] = useState<ScriptProposal[]>([]);
  const [newTitle, setNewTitle] = useState('');
  const [composer, setComposer] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const activeProposal = proposals[0] ?? null;

  const loadThreads = useCallback(async () => {
    try {
      const res = await authFetch(`/api/v1/conversations?tenantId=${tenantId}`);
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      const data = await res.json();
      const next = data.threads || [];
      setThreads(next);
      setActiveThreadId(current => current || next[0]?.id || null);
      setError(null);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setLoading(false);
    }
  }, [tenantId]);

  const loadThread = useCallback(async (threadId: string) => {
    try {
      const res = await authFetch(`/api/v1/conversations/${threadId}`);
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      const data = await res.json();
      setMessages(data.messages || []);
      setProposals(data.proposals || []);
      setError(null);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    }
  }, []);

  useEffect(() => { loadThreads(); }, [loadThreads]);
  useEffect(() => { if (activeThreadId) loadThread(activeThreadId); }, [activeThreadId, loadThread]);

  const createThread = async () => {
    setBusy(true);
    try {
      const res = await authFetch('/api/v1/conversations', {
        method: 'POST',
        body: JSON.stringify({
          tenantId,
          title: newTitle.trim() || 'Incident Collaboration Thread',
          createdBy: 'dashboard-user',
        }),
      });
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      const data = await res.json();
      const threadId = data.thread?.id;
      setNewTitle('');
      await loadThreads();
      if (threadId) {
        setActiveThreadId(threadId);
        await loadThread(threadId);
      }
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setBusy(false);
    }
  };

  const sendMessage = async () => {
    if (!activeThreadId || !composer.trim()) return;
    setBusy(true);
    try {
      const res = await authFetch(`/api/v1/conversations/${activeThreadId}/messages`, {
        method: 'POST',
        body: JSON.stringify({
          role: 'user',
          messageType: 'constraint',
          content: composer.trim(),
          structuredPayload: {
            operatorHint: composer.trim(),
          },
        }),
      });
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      setComposer('');
      await loadThread(activeThreadId);
      await loadThreads();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setBusy(false);
    }
  };

  const approveProposal = async () => {
    if (!activeProposal) return;
    setBusy(true);
    try {
      const res = await authFetch(`/api/v1/conversations/proposals/${activeProposal.id}/approve?approvedBy=dashboard-user`, {
        method: 'POST',
      });
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      if (activeThreadId) await loadThread(activeThreadId);
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setBusy(false);
    }
  };

  const validateProposal = async (resolved: boolean) => {
    if (!activeThreadId) return;
    setBusy(true);
    try {
      const res = await authFetch(`/api/v1/conversations/${activeThreadId}/validation`, {
        method: 'POST',
        body: JSON.stringify({
          resolved,
          confirmedBy: 'dashboard-user',
          comment: resolved
            ? 'Validated from incident chat page'
            : 'Validation failed, need another script',
        }),
      });
      if (!res.ok) {
        setError(await extractApiError(res));
        return;
      }
      await loadThread(activeThreadId);
      await loadThreads();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setBusy(false);
    }
  };

  const emptyState = useMemo(() => !loading && threads.length === 0, [loading, threads.length]);

  return (
    <div className="content" style={{ paddingBottom: 40 }}>
      <div style={{ display: 'grid', gridTemplateColumns: '280px minmax(0, 1fr) 360px', gap: 20 }}>
        <section style={panelStyle}>
          <div style={eyebrowStyle}>THREADS</div>
          <div style={titleStyle}>Incident Collaboration</div>
          <div style={{ display: 'grid', gap: 10, marginTop: 16 }}>
            <input value={newTitle} onChange={e => setNewTitle(e.target.value)} placeholder="New thread title" style={inputStyle} />
            <button onClick={createThread} disabled={busy} style={buttonStyle}>{busy ? 'CREATING...' : 'NEW THREAD'}</button>
          </div>
          <div style={{ display: 'grid', gap: 10, marginTop: 18 }}>
            {threads.map(thread => (
              <button
                key={thread.id}
                onClick={() => setActiveThreadId(thread.id)}
                style={{
                  ...threadButtonStyle,
                  borderColor: activeThreadId === thread.id ? 'var(--blue)' : 'var(--border)',
                  background: activeThreadId === thread.id ? 'rgba(79,142,247,0.12)' : 'var(--surface2)',
                }}
              >
                <div style={{ fontWeight: 700 }}>{thread.title}</div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 6 }}>
                  Attempt {thread.currentAttempt} · {thread.status}
                </div>
              </button>
            ))}
            {emptyState && <div style={{ color: 'var(--text-muted)', fontSize: 12 }}>No collaboration threads yet.</div>}
          </div>
        </section>

        <section style={panelStyle}>
          <div style={eyebrowStyle}>CHAT</div>
          <div style={titleStyle}>Operator Review Workspace</div>
          {error && <div className="error-banner" style={{ marginTop: 12 }}>{error}</div>}
          <div style={{ display: 'grid', gap: 12, marginTop: 16 }}>
            {messages.map(message => (
              <div
                key={message.id}
                style={{
                  ...bubbleStyle,
                  marginLeft: message.role === 'user' ? 72 : 0,
                  marginRight: message.role === 'user' ? 0 : 72,
                  background: message.role === 'user' ? 'rgba(79,142,247,0.12)' : 'var(--surface2)',
                }}
              >
                <div style={{ fontSize: 10, color: 'var(--text-muted)', textTransform: 'uppercase', letterSpacing: 1 }}>
                  {message.role} · {message.messageType}
                </div>
                <div style={{ marginTop: 6, lineHeight: 1.55 }}>{message.content}</div>
              </div>
            ))}
            {!activeThreadId && <div style={{ color: 'var(--text-muted)' }}>Create or select a thread to begin.</div>}
          </div>
          <div style={{ marginTop: 16, borderTop: '1px solid var(--border)', paddingTop: 16 }}>
            <textarea
              value={composer}
              onChange={e => setComposer(e.target.value)}
              rows={5}
              style={{ ...inputStyle, resize: 'vertical', minHeight: 120 }}
              placeholder="Reply with corrections, API lookup steps, DB lookup hints, shell preference, or risk constraints..."
            />
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, marginTop: 10 }}>
              <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>
                Example: "Use PowerShell and call the device inventory API before generating the final script."
              </div>
              <button onClick={sendMessage} disabled={busy || !activeThreadId} style={buttonStyle}>{busy ? 'SENDING...' : 'SEND'}</button>
            </div>
          </div>
        </section>

        <section style={panelStyle}>
          <div style={eyebrowStyle}>PROPOSAL</div>
          <div style={titleStyle}>Script Review</div>
          {!activeProposal ? (
            <div style={{ marginTop: 16, color: 'var(--text-muted)' }}>No script proposal yet.</div>
          ) : (
            <div style={{ display: 'grid', gap: 14, marginTop: 16 }}>
              <div style={infoStyle}><strong>Risk:</strong> {activeProposal.riskLevel} · <strong>Status:</strong> {activeProposal.status}</div>
              <div style={infoStyle}>{activeProposal.explanation || 'No explanation provided.'}</div>
              <pre style={codeStyle}>{activeProposal.scriptContent}</pre>
              <div style={infoStyle}><strong>Rollback:</strong> {activeProposal.rollbackPlan || 'Not specified.'}</div>
              <div style={infoStyle}>
                <strong>Validation:</strong>
                {(activeProposal.validationPlanJson || []).map(item => <div key={item}>• {item}</div>)}
              </div>
              <button
                onClick={approveProposal}
                disabled={busy || !activeProposal.approvalRequired || activeProposal.status === 'APPROVED'}
                style={buttonStyle}
              >
                {activeProposal.status === 'APPROVED' ? 'APPROVED' : 'APPROVE SCRIPT'}
              </button>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                <button onClick={() => validateProposal(true)} disabled={busy || !activeThreadId} style={buttonStyle}>
                  MARK RESOLVED
                </button>
                <button
                  onClick={() => validateProposal(false)}
                  disabled={busy || !activeThreadId}
                  style={{ ...buttonStyle, borderColor: 'rgba(255,85,85,0.35)', color: 'var(--red)' }}
                >
                  NEED NEW SCRIPT
                </button>
              </div>
            </div>
          )}
        </section>
      </div>
    </div>
  );
};

const panelStyle: React.CSSProperties = {
  background: 'var(--surface)',
  border: '1px solid var(--border)',
  borderRadius: 14,
  padding: 18,
};

const eyebrowStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)',
  fontSize: 11,
  color: 'var(--text-muted)',
  letterSpacing: 2,
  textTransform: 'uppercase',
};

const titleStyle: React.CSSProperties = {
  marginTop: 4,
  fontFamily: 'var(--mono)',
  fontSize: 18,
  fontWeight: 700,
  color: 'var(--text)',
};

const inputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--surface2)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  color: 'var(--text)',
  padding: '12px 14px',
  fontSize: 13,
  boxSizing: 'border-box',
};

const buttonStyle: React.CSSProperties = {
  border: '1px solid rgba(79,142,247,0.35)',
  borderRadius: 10,
  background: 'var(--blue-dim)',
  color: 'var(--blue)',
  padding: '10px 14px',
  fontSize: 12,
  fontFamily: 'var(--mono)',
  fontWeight: 700,
  cursor: 'pointer',
};

const threadButtonStyle: React.CSSProperties = {
  textAlign: 'left',
  color: 'var(--text)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  padding: 12,
  cursor: 'pointer',
};

const bubbleStyle: React.CSSProperties = {
  border: '1px solid var(--border)',
  borderRadius: 12,
  padding: 14,
  color: 'var(--text)',
};

const infoStyle: React.CSSProperties = {
  background: 'var(--surface2)',
  border: '1px solid var(--border)',
  borderRadius: 10,
  padding: 12,
  color: 'var(--text)',
  lineHeight: 1.55,
};

const codeStyle: React.CSSProperties = {
  ...infoStyle,
  margin: 0,
  fontFamily: 'var(--mono)',
  whiteSpace: 'pre-wrap',
  overflowX: 'auto',
};

export default IncidentChatPage;
