import React, { useState, useEffect, useCallback } from 'react';
import { authFetch } from '../services/api';

interface KbEntry {
  id: string;
  incidentId?: string;
  sourceTicketId?: string;
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
  comments?: { author: string; role: string; text: string; ts: string }[];
  resolutionSteps?: { step: number; action: string; tool?: string; result?: string }[];
  embeddingIngested?: boolean;
  matchedSopTitle?: string;
  tags?: string[];
}

interface SearchResult {
  results: KbEntry[];
  ragHints: { doc_type: string; snippet: string }[];
  vectorStoreActive: boolean;
}

interface SuggestionResult {
  suggestion: string;
  sources: { doc_type: string; snippet: string }[];
  fullRagAvailable: boolean;
  sourcesFound: number;
}

interface KbStats {
  totalEntries: number;
  pendingEmbedding: number;
  byCategory: Record<string, number>;
  vectorStoreActive: boolean;
  fullRagAvailable: boolean;
}

const SEV_COLOR: Record<string, string> = {
  P1: '#ef4444', P2: '#f97316', P3: '#eab308', P4: '#22c55e',
};

const STATUS_COLOR: Record<string, string> = {
  AUTO_RESOLVED: '#22c55e',
  HITL_RESOLVED: '#3b82f6',
  ESCALATED: '#f97316',
  GUARDRAILS_BLOCKED: '#ef4444',
};

const TENANT_ID = '00000000-0000-0000-0000-000000000001';

const KnowledgeBasePage: React.FC = () => {
  const [entries, setEntries]       = useState<KbEntry[]>([]);
  const [stats, setStats]           = useState<KbStats | null>(null);
  const [selected, setSelected]     = useState<KbEntry | null>(null);
  const [loading, setLoading]       = useState(false);
  const [searchQuery, setSearchQuery] = useState('');
  const [suggestion, setSuggestion] = useState<SuggestionResult | null>(null);
  const [suggestLoading, setSuggestLoading] = useState(false);
  const [commentText, setCommentText] = useState('');
  const [commentAuthor, setCommentAuthor] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [severityFilter, setSeverityFilter] = useState('');
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  // ── Fetch entries ─────────────────────────────────────────────────────────
  const fetchEntries = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({
        tenantId: TENANT_ID,
        page: String(page),
        size: '20',
      });
      if (categoryFilter) params.set('category', categoryFilter);
      if (severityFilter) params.set('severity', severityFilter);
      const r = await authFetch(`/api/v1/kb?${params.toString()}`);
      if (r.ok) {
        const d = await r.json();
        setEntries(d.items ?? []);
        setTotalPages(d.totalPages ?? 0);
      }
    } finally {
      setLoading(false);
    }
  }, [page, categoryFilter, severityFilter]);

  const fetchStats = useCallback(async () => {
    try {
      const r = await authFetch(`/api/v1/kb/stats?tenantId=${TENANT_ID}`);
      if (r.ok) setStats(await r.json());
    } catch {}
  }, []);

  useEffect(() => { fetchEntries(); fetchStats(); }, [fetchEntries, fetchStats]);

  // ── Keyword + semantic search ─────────────────────────────────────────────
  const handleSearch = async () => {
    if (!searchQuery.trim()) { fetchEntries(); return; }
    setLoading(true);
    try {
      const r = await authFetch('/api/v1/kb/search', {
        method: 'POST',
        body: JSON.stringify({ tenantId: TENANT_ID, query: searchQuery, topK: 10 }),
      });
      if (r.ok) {
        const d: SearchResult = await r.json();
        setEntries(d.results);
        setTotalPages(1);
      }
    } finally {
      setLoading(false);
    }
  };

  // ── Combined SOP + KB suggestion ──────────────────────────────────────────
  const handleSuggest = async () => {
    if (!searchQuery.trim()) return;
    setSuggestLoading(true);
    setSuggestion(null);
    try {
      const r = await authFetch('/api/v1/kb/suggest', {
        method: 'POST',
        body: JSON.stringify({ incidentDescription: searchQuery }),
      });
      if (r.ok) setSuggestion(await r.json());
    } finally {
      setSuggestLoading(false);
    }
  };

  // ── Add comment ───────────────────────────────────────────────────────────
  const handleAddComment = async () => {
    if (!selected || !commentText.trim()) return;
    try {
      const r = await authFetch(`/api/v1/kb/${selected.id}/comments`, {
        method: 'POST',
        body: JSON.stringify({ author: commentAuthor || 'operator', role: 'OPERATOR', text: commentText }),
      });
      if (r.ok) {
        const updated: KbEntry = await r.json();
        setSelected(updated);
        setEntries(prev => prev.map(e => e.id === updated.id ? updated : e));
        setCommentText('');
        setCommentAuthor('');
      }
    } catch {}
  };

  // ─────────────────────────────────────────────────────────────────────────
  return (
    <div style={{ display: 'flex', gap: 16, height: 'calc(100vh - 80px)', overflow: 'hidden' }}>

      {/* ── Left panel: list ───────────────────────────────────────────────── */}
      <div style={{ width: 420, minWidth: 320, display: 'flex', flexDirection: 'column', gap: 10 }}>

        {/* Stats bar */}
        {stats && (
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <StatChip label="Total KB Entries" value={String(stats.totalEntries)} color="#3b82f6" />
            <StatChip label="Pending Embed" value={String(stats.pendingEmbedding)} color="#f97316" />
            <StatChip label="Vector Store" value={stats.vectorStoreActive ? 'ACTIVE' : 'OFF'} color={stats.vectorStoreActive ? '#22c55e' : '#6b7280'} />
            <StatChip label="Full RAG" value={stats.fullRagAvailable ? 'ACTIVE' : 'OFF'} color={stats.fullRagAvailable ? '#22c55e' : '#6b7280'} />
          </div>
        )}

        {/* Search bar */}
        <div style={{ display: 'flex', gap: 6 }}>
          <input
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleSearch()}
            placeholder="Search resolved incidents…"
            style={inputStyle}
          />
          <button onClick={handleSearch} style={btnStyle('#3b82f6')}>Search</button>
          <button onClick={handleSuggest} disabled={suggestLoading || !searchQuery.trim()} style={btnStyle('#7c3aed')} title="Get combined SOP + KB suggestion">
            {suggestLoading ? '…' : '⚡ Suggest'}
          </button>
        </div>

        {/* Filters */}
        <div style={{ display: 'flex', gap: 6 }}>
          <select value={categoryFilter} onChange={e => { setCategoryFilter(e.target.value); setPage(0); }} style={selectStyle}>
            <option value="">All Categories</option>
            <option value="DATABASE">DATABASE</option>
            <option value="APPLICATION">APPLICATION</option>
            <option value="PERFORMANCE">PERFORMANCE</option>
            <option value="NETWORK">NETWORK</option>
            <option value="SECURITY">SECURITY</option>
          </select>
          <select value={severityFilter} onChange={e => { setSeverityFilter(e.target.value); setPage(0); }} style={selectStyle}>
            <option value="">All Severities</option>
            <option value="P1">P1</option>
            <option value="P2">P2</option>
            <option value="P3">P3</option>
            <option value="P4">P4</option>
          </select>
        </div>

        {/* Suggestion panel */}
        {suggestion && (
          <div style={{ background: 'rgba(124,58,237,0.06)', border: '1px solid rgba(124,58,237,0.2)', borderRadius: 8, padding: 12 }}>
            <div style={{ color: '#7c3aed', fontSize: 11, fontWeight: 700, marginBottom: 6, letterSpacing: 1 }}>
              ⚡ COMBINED SOP + KB SUGGESTION
              {!suggestion.fullRagAvailable && <span style={{ color: '#d97706', marginLeft: 8 }}>(LLM offline — semantic docs only)</span>}
            </div>
            {suggestion.suggestion ? (
              <pre style={{ color: '#1e293b', fontSize: 12, whiteSpace: 'pre-wrap', margin: 0 }}>{suggestion.suggestion}</pre>
            ) : (
              <div style={{ color: '#475569', fontSize: 12 }}>
                {suggestion.sourcesFound} source document(s) retrieved. Enable a ChatClient for LLM analysis.
              </div>
            )}
            {suggestion.sources.length > 0 && (
              <div style={{ marginTop: 8, borderTop: '1px solid rgba(124,58,237,0.15)', paddingTop: 8 }}>
                <div style={{ color: '#7c3aed', fontSize: 10, fontWeight: 700, marginBottom: 4 }}>SOURCES</div>
                {suggestion.sources.map((s, i) => (
                  <div key={i} style={{ fontSize: 11, color: '#64748b', marginBottom: 4 }}>
                    <span style={{ color: s.doc_type === 'SOP' ? '#059669' : '#2563eb', fontWeight: 700 }}>
                      [{s.doc_type}]
                    </span>{' '}
                    {s.snippet}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Entry list */}
        <div style={{ flex: 1, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 6 }}>
          {loading && <div style={{ color: '#94a3b8', fontSize: 13, padding: 8 }}>Loading…</div>}
          {!loading && entries.length === 0 && (
            <div style={{ color: '#64748b', fontSize: 13, padding: 8 }}>No KB entries found.</div>
          )}
          {entries.map(e => (
            <div
              key={e.id}
              onClick={() => setSelected(e)}
              style={{
                background: selected?.id === e.id ? '#eff6ff' : '#ffffff',
                border: `1px solid ${selected?.id === e.id ? '#2563eb' : '#e2e8f0'}`,
                borderRadius: 8, padding: '10px 12px', cursor: 'pointer',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
                <span style={{ background: SEV_COLOR[e.severity] || '#6b7280', color: '#fff', fontSize: 10, fontWeight: 700, borderRadius: 4, padding: '2px 6px' }}>
                  {e.severity}
                </span>
                <span style={{ color: STATUS_COLOR[e.originalStatus ?? ''] || '#94a3b8', fontSize: 10, fontWeight: 600 }}>
                  {e.originalStatus}
                </span>
              </div>
              <div style={{ color: '#1e293b', fontSize: 13, fontWeight: 600, marginBottom: 2 }}>{e.title}</div>
              {e.resolutionSummary && (
                <div style={{ color: '#64748b', fontSize: 11, overflow: 'hidden', maxHeight: 32 }}>
                  {e.resolutionSummary}
                </div>
              )}
              <div style={{ display: 'flex', gap: 8, marginTop: 4, flexWrap: 'wrap' }}>
                {e.category && <Tag text={e.category} />}
                {e.resolvedBy && <Tag text={`by ${e.resolvedBy}`} color="#f1f5f9" textColor="#64748b" />}
                {e.embeddingIngested && <Tag text="EMBEDDED" color="#d1fae5" textColor="#065f46" />}
              </div>
            </div>
          ))}
        </div>

        {/* Pagination */}
        {totalPages > 1 && (
          <div style={{ display: 'flex', gap: 6, justifyContent: 'center' }}>
            <button disabled={page === 0} onClick={() => setPage(p => p - 1)} style={btnStyle('#2563eb', page === 0)}>← Prev</button>
            <span style={{ color: '#64748b', fontSize: 12, alignSelf: 'center' }}>{page + 1} / {totalPages}</span>
            <button disabled={page >= totalPages - 1} onClick={() => setPage(p => p + 1)} style={btnStyle('#2563eb', page >= totalPages - 1)}>Next →</button>
          </div>
        )}
      </div>

      {/* ── Right panel: detail ────────────────────────────────────────────── */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '0 4px' }}>
        {!selected ? (
          <div style={{ color: '#475569', fontSize: 14, marginTop: 40, textAlign: 'center' }}>
            Select a KB entry to view full resolution details
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

            {/* Header */}
            <div>
              <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 6 }}>
                <span style={{ background: SEV_COLOR[selected.severity] || '#6b7280', color: '#fff', fontSize: 11, fontWeight: 700, borderRadius: 4, padding: '2px 8px' }}>
                  {selected.severity}
                </span>
                {selected.category && <Tag text={selected.category} />}
                <span style={{ color: STATUS_COLOR[selected.originalStatus ?? ''] || '#94a3b8', fontSize: 11, fontWeight: 700 }}>
                  {selected.originalStatus}
                </span>
                {selected.embeddingIngested && <Tag text="VECTOR INDEXED" color="#d1fae5" textColor="#065f46" />}
              </div>
              <h2 style={{ color: '#1e293b', fontSize: 18, fontWeight: 700, margin: 0, marginBottom: 4 }}>{selected.title}</h2>
              <div style={{ color: '#64748b', fontSize: 12 }}>
                {selected.sourceTicketId && <span>Ticket: {selected.sourceTicketId} · </span>}
                {selected.resolvedAt && <span>Resolved: {new Date(selected.resolvedAt).toLocaleString()} · </span>}
                {selected.resolvedBy && <span>By: {selected.resolvedBy}</span>}
              </div>
            </div>

            {/* Description */}
            {selected.description && (
              <Section title="DESCRIPTION">
                <p style={{ color: '#475569', fontSize: 13, margin: 0 }}>{selected.description}</p>
              </Section>
            )}

            {/* Root Cause */}
            {selected.rootCause && (
              <Section title="ROOT CAUSE">
                <p style={{ color: '#b45309', fontSize: 13, margin: 0 }}>{selected.rootCause}</p>
              </Section>
            )}

            {/* Resolution Summary */}
            {selected.resolutionSummary && (
              <Section title="RESOLUTION SUMMARY">
                <p style={{ color: '#065f46', fontSize: 13, margin: 0 }}>{selected.resolutionSummary}</p>
              </Section>
            )}

            {/* Resolution Steps */}
            {selected.resolutionSteps && selected.resolutionSteps.length > 0 && (
              <Section title="RESOLUTION STEPS">
                <ol style={{ margin: 0, paddingLeft: 20 }}>
                  {selected.resolutionSteps.map((s, i) => (
                    <li key={i} style={{ color: '#1e293b', fontSize: 12, marginBottom: 8 }}>
                      <span style={{ fontWeight: 700 }}>{s.action}</span>
                      {s.tool && <span style={{ color: '#7c3aed', marginLeft: 8 }}>[{s.tool}]</span>}
                      {s.result && <div style={{ color: '#64748b', marginTop: 2 }}>Result: {s.result}</div>}
                    </li>
                  ))}
                </ol>
              </Section>
            )}

            {/* Matched SOP */}
            {selected.matchedSopTitle && (
              <Section title="MATCHED SOP">
                <span style={{ color: '#059669', fontSize: 13 }}>⊞ {selected.matchedSopTitle}</span>
              </Section>
            )}

            {/* Comments */}
            <Section title={`OPERATOR COMMENTS (${selected.comments?.length ?? 0})`}>
              {(selected.comments ?? []).length === 0 && (
                <div style={{ color: '#475569', fontSize: 12 }}>No comments yet.</div>
              )}
              {(selected.comments ?? []).map((c, i) => (
                <div key={i} style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 6, padding: '8px 10px', marginBottom: 6 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                    <span style={{ color: '#2563eb', fontSize: 11, fontWeight: 700 }}>{c.author}</span>
                    <span style={{ color: '#94a3b8', fontSize: 10 }}>{c.role} · {new Date(c.ts).toLocaleString()}</span>
                  </div>
                  <div style={{ color: '#1e293b', fontSize: 13 }}>{c.text}</div>
                </div>
              ))}

              {/* Add comment form */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8 }}>
                <input
                  value={commentAuthor}
                  onChange={e => setCommentAuthor(e.target.value)}
                  placeholder="Your name (optional)"
                  style={{ ...inputStyle, fontSize: 12 }}
                />
                <textarea
                  value={commentText}
                  onChange={e => setCommentText(e.target.value)}
                  placeholder="Add a comment about this resolution…"
                  rows={3}
                  style={{ ...inputStyle, resize: 'vertical' }}
                />
                <button
                  onClick={handleAddComment}
                  disabled={!commentText.trim()}
                  style={btnStyle('#3b82f6', !commentText.trim())}
                >
                  + Add Comment
                </button>
              </div>
            </Section>

          </div>
        )}
      </div>
    </div>
  );
};

// ── Sub-components ─────────────────────────────────────────────────────────────

const Section: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div style={{ background: '#f8fafc', border: '1px solid #e2e8f0', borderRadius: 8, padding: 14 }}>
    <div style={{ color: '#475569', fontSize: 10, fontWeight: 700, letterSpacing: 1.5, marginBottom: 8 }}>{title}</div>
    {children}
  </div>
);

const Tag: React.FC<{ text: string; color?: string; textColor?: string }> = ({
  text, color = '#1e293b', textColor = '#94a3b8',
}) => (
  <span style={{ background: color, color: textColor, fontSize: 10, fontWeight: 600, borderRadius: 3, padding: '2px 6px' }}>
    {text}
  </span>
);

const StatChip: React.FC<{ label: string; value: string; color: string }> = ({ label, value, color }) => (
  <div style={{ background: '#f1f5f9', border: `1px solid ${color}66`, borderRadius: 6, padding: '6px 10px', minWidth: 90 }}>
    <div style={{ color: '#475569', fontSize: 9, fontWeight: 700, letterSpacing: 1 }}>{label}</div>
    <div style={{ color, fontSize: 16, fontWeight: 700 }}>{value}</div>
  </div>
);

// ── Shared styles ──────────────────────────────────────────────────────────────

const inputStyle: React.CSSProperties = {
  flex: 1, background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 6,
  color: '#1e293b', padding: '7px 10px', fontSize: 13, outline: 'none',
};

const selectStyle: React.CSSProperties = {
  flex: 1, background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 6,
  color: '#1e293b', padding: '6px 8px', fontSize: 12, outline: 'none',
};

const btnStyle = (bg: string, disabled = false): React.CSSProperties => ({
  background: disabled ? '#e2e8f0' : bg,
  color: disabled ? '#94a3b8' : '#fff',
  border: 'none', borderRadius: 6, padding: '7px 12px', fontSize: 12,
  fontWeight: 700, cursor: disabled ? 'not-allowed' : 'pointer', letterSpacing: 0.5,
});

export default KnowledgeBasePage;
