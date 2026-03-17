import React, { useState, useRef, useEffect } from 'react';
import './ChatbotWidget.css';
import { authFetch } from '../services/api';

interface KbEntry {
  id: string;
  title: string;
  description?: string;
  category?: string;
  severity: string;
  originalStatus?: string;
  resolutionSummary?: string;
  rootCause?: string;
  resolvedBy?: string;
  confidenceScore?: number;
  resolvedAt?: string;
  matchedSopTitle?: string;
  resolutionSteps?: { step: number; action: string; tool?: string; result?: string }[];
}

interface SuggestionResult {
  suggestion: string;
  sources: { doc_type: string; snippet: string }[];
  fullRagAvailable: boolean;
  sourcesFound: number;
}

interface Message {
  id: string;
  role: 'user' | 'bot';
  text?: string;
  results?: KbEntry[];
  suggestion?: SuggestionResult;
  loading?: boolean;
  error?: boolean;
}

const SEV_COLOR: Record<string, string> = {
  P1: '#dc2626', P2: '#d97706', P3: '#2563eb', P4: '#059669',
};

const STATUS_RESOLVED = new Set(['AUTO_RESOLVED', 'HITL_RESOLVED', 'RESOLVED']);

interface Props {
  tenantId: string;
}

const ChatbotWidget: React.FC<Props> = ({ tenantId }) => {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      role: 'bot',
      text: "👋 Hi! I'm the Incident Assistant. Ask me about any incident — I'll tell you if it was resolved and how to fix it.\n\nTry: *\"database connection pool exhausted\"* or *\"Redis OOM\"*",
    },
  ]);
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 150);
  }, [open]);

  const addMessage = (msg: Omit<Message, 'id'>) => {
    const id = `${Date.now()}-${Math.random()}`;
    setMessages(prev => [...prev, { id, ...msg }]);
    return id;
  };

  const updateMessage = (id: string, update: Partial<Message>) => {
    setMessages(prev => prev.map(m => m.id === id ? { ...m, ...update } : m));
  };

  const handleSend = async () => {
    const q = input.trim();
    if (!q || loading) return;
    setInput('');
    addMessage({ role: 'user', text: q });

    const botId = addMessage({ role: 'bot', loading: true });
    setLoading(true);

    try {
      // Run KB search + suggest in parallel
      const [searchRes, suggestRes] = await Promise.allSettled([
        authFetch('/api/v1/kb/search', {
          method: 'POST',
          body: JSON.stringify({ tenantId, query: q, topK: 5 }),
        }),
        authFetch('/api/v1/kb/suggest', {
          method: 'POST',
          body: JSON.stringify({ incidentDescription: q }),
        }),
      ]);

      let results: KbEntry[] = [];
      let suggestion: SuggestionResult | undefined;

      if (searchRes.status === 'fulfilled' && searchRes.value.ok) {
        const d = await searchRes.value.json();
        results = d.results ?? [];
      }
      if (suggestRes.status === 'fulfilled' && suggestRes.value.ok) {
        suggestion = await suggestRes.value.json();
      }

      if (results.length === 0 && !suggestion) {
        updateMessage(botId, {
          loading: false,
          text: `No incidents found for **"${q}"**. Try different keywords or check the Resolved Incidents page.`,
        });
      } else {
        updateMessage(botId, { loading: false, results, suggestion });
      }
    } catch {
      updateMessage(botId, {
        loading: false,
        error: true,
        text: 'Failed to connect to the backend. Please ensure the server is running.',
      });
    } finally {
      setLoading(false);
    }
  };

  const renderBotMessage = (msg: Message) => {
    if (msg.loading) {
      return (
        <div className="cb-msg cb-msg-bot">
          <div className="cb-avatar">🤖</div>
          <div className="cb-bubble cb-bubble-bot">
            <span className="cb-typing"><span /><span /><span /></span>
          </div>
        </div>
      );
    }

    if (msg.text && !msg.results && !msg.suggestion) {
      return (
        <div className="cb-msg cb-msg-bot">
          <div className="cb-avatar">🤖</div>
          <div className={`cb-bubble cb-bubble-bot ${msg.error ? 'cb-bubble-error' : ''}`}>
            {msg.text.split('\n').map((line, i) => (
              <p key={i} dangerouslySetInnerHTML={{ __html: formatMarkdown(line) }} />
            ))}
          </div>
        </div>
      );
    }

    return (
      <div className="cb-msg cb-msg-bot cb-msg-results">
        <div className="cb-avatar">🤖</div>
        <div className="cb-results-wrap">
          {/* AI Suggestion */}
          {msg.suggestion && msg.suggestion.suggestion && (
            <div className="cb-suggestion">
              <div className="cb-suggestion-header">
                <span>⚡ AI Suggestion</span>
                {msg.suggestion.fullRagAvailable && <span className="cb-rag-badge">RAG ACTIVE</span>}
              </div>
              <p>{msg.suggestion.suggestion}</p>
              {msg.suggestion.sources && msg.suggestion.sources.length > 0 && (
                <div className="cb-sources">
                  {msg.suggestion.sources.slice(0, 2).map((s, i) => (
                    <div key={i} className="cb-source-chip">{s.doc_type}: {s.snippet.slice(0, 80)}…</div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* KB Results */}
          {msg.results && msg.results.length > 0 && (
            <>
              <div className="cb-results-label">
                📋 {msg.results.length} similar incident{msg.results.length > 1 ? 's' : ''} found
              </div>
              {msg.results.map(entry => {
                const resolved = STATUS_RESOLVED.has(entry.originalStatus || '');
                return (
                  <div key={entry.id} className={`cb-incident-card ${resolved ? 'cb-resolved' : 'cb-unresolved'}`}>
                    {/* Header */}
                    <div className="cb-card-top">
                      <div className="cb-card-title">{entry.title}</div>
                      <div className="cb-card-badges">
                        {entry.severity && (
                          <span className="cb-badge" style={{ background: `${SEV_COLOR[entry.severity] || '#6b7280'}18`, color: SEV_COLOR[entry.severity] || '#6b7280', border: `1px solid ${SEV_COLOR[entry.severity] || '#6b7280'}40` }}>
                            {entry.severity}
                          </span>
                        )}
                        <span className={`cb-status-badge ${resolved ? 'cb-status-resolved' : 'cb-status-pending'}`}>
                          {resolved ? '✓ RESOLVED' : '⚠ UNRESOLVED'}
                        </span>
                      </div>
                    </div>

                    {entry.description && (
                      <p className="cb-card-desc">{entry.description}</p>
                    )}

                    {/* Resolution Summary */}
                    {entry.resolutionSummary && (
                      <div className="cb-resolution">
                        <div className="cb-resolution-label">Resolution</div>
                        <p>{entry.resolutionSummary}</p>
                      </div>
                    )}

                    {/* Root Cause */}
                    {entry.rootCause && (
                      <div className="cb-resolution">
                        <div className="cb-resolution-label">Root Cause</div>
                        <p>{entry.rootCause}</p>
                      </div>
                    )}

                    {/* Resolution Steps */}
                    {entry.resolutionSteps && entry.resolutionSteps.length > 0 && (
                      <div className="cb-steps">
                        <div className="cb-resolution-label">How to Resolve</div>
                        {entry.resolutionSteps.map(step => (
                          <div key={step.step} className="cb-step">
                            <span className="cb-step-num">{step.step}</span>
                            <div className="cb-step-body">
                              <span>{step.action}</span>
                              {step.tool && <span className="cb-step-tool">{step.tool}</span>}
                              {step.result && <span className="cb-step-result">→ {step.result}</span>}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}

                    {/* SOP Match */}
                    {entry.matchedSopTitle && (
                      <div className="cb-sop-match">
                        📄 Matched SOP: <strong>{entry.matchedSopTitle}</strong>
                      </div>
                    )}

                    {/* Meta */}
                    <div className="cb-card-meta">
                      {entry.category && <span>{entry.category}</span>}
                      {entry.resolvedBy && <span>by {entry.resolvedBy}</span>}
                      {entry.resolvedAt && <span>{new Date(entry.resolvedAt).toLocaleDateString()}</span>}
                      {typeof entry.confidenceScore === 'number' && (
                        <span style={{ color: entry.confidenceScore >= 0.8 ? '#059669' : '#d97706' }}>
                          {(entry.confidenceScore * 100).toFixed(0)}% confidence
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </>
          )}
        </div>
      </div>
    );
  };

  const openCreateIncident = () => {
    const url = new URL(window.location.href);
    url.pathname = '/overview';
    url.searchParams.set('createIncident', '1');
    window.history.pushState(null, '', url.pathname + url.search);
    window.dispatchEvent(new PopStateEvent('popstate'));
  };

  return (
    <>
      <button
        className="cb-toggle cb-toggle-create"
        onClick={openCreateIncident}
        title="Create Incident"
      >
        <span>＋</span>
        <span className="cb-toggle-label">Create Incident</span>
      </button>

      {/* Floating button */}
      <button
        className={`cb-toggle ${open ? 'cb-toggle-open' : ''}`}
        onClick={() => setOpen(o => !o)}
        title="Incident Assistant"
      >
        {open ? '✕' : '💬'}
        {!open && <span className="cb-toggle-label">Incident Search</span>}
      </button>

      {/* Chat panel */}
      {open && (
        <div className="cb-panel">
          {/* Header */}
          <div className="cb-header">
            <div className="cb-header-left">
              <span className="cb-header-icon">🤖</span>
              <div>
                <div className="cb-header-title">Incident Assistant</div>
                <div className="cb-header-sub">Search resolved incidents & get resolution guidance</div>
              </div>
            </div>
            <button className="cb-close-btn" onClick={() => setOpen(false)}>✕</button>
          </div>

          {/* Messages */}
          <div className="cb-messages">
            {messages.map(msg => (
              <div key={msg.id}>
                {msg.role === 'user' ? (
                  <div className="cb-msg cb-msg-user">
                    <div className="cb-bubble cb-bubble-user">{msg.text}</div>
                  </div>
                ) : (
                  renderBotMessage(msg)
                )}
              </div>
            ))}
            <div ref={bottomRef} />
          </div>

          {/* Input */}
          <div className="cb-input-area">
            <input
              ref={inputRef}
              className="cb-input"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleSend()}
              placeholder="e.g. database connection pool exhausted…"
              disabled={loading}
            />
            <button
              className="cb-send-btn"
              onClick={handleSend}
              disabled={loading || !input.trim()}
            >
              {loading ? '…' : '↑'}
            </button>
          </div>

          {/* Quick suggestions */}
          <div className="cb-quick-chips">
            {['Pod CrashLoopBackOff', 'Redis OOM', 'Memory leak', 'Disk usage 80%'].map(q => (
              <button key={q} className="cb-chip" onClick={() => { setInput(q); inputRef.current?.focus(); }}>
                {q}
              </button>
            ))}
          </div>
        </div>
      )}
    </>
  );
};

function formatMarkdown(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>');
}

export default ChatbotWidget;
