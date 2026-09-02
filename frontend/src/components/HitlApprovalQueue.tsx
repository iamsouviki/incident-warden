import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, ChevronRight, FileCode2, RefreshCw, Search, ShieldCheck, User } from 'lucide-react';
import { apiGet } from '../services/api';
import { Badge, Button, EmptyState, Spinner } from './ui';
import './HitlApprovalQueue.css';

interface UserInfo {
  username: string;
  name: string;
  role: string;
  department: string;
}

interface ReviewItem {
  request: { id: string; status: 'PENDING' | 'APPROVED' | 'REJECTED'; requestedBy?: string; createdAt?: string };
  plan: {
    id: string; status: string; actionName: string; target: string;
    riskScore: number; guardrailStatus: string;
    scriptSource?: string; scriptScanLevel?: string; scriptLanguage?: string;
  };
  incident: { id: string; subject?: string; description?: string; priority?: string; externalId?: string; externalSource?: string };
  assigneeInfo?: UserInfo;
  requestedByInfo?: UserInfo;
  reviewerInfo?: UserInfo;
}

const SOURCE_LABEL: Record<string, string> = {
  SOP_TEMPLATE: 'SOP template',
  SOP_GROUNDED: 'SOP-grounded',
  LLM_KNOWLEDGE: 'LLM knowledge',
  NONE: 'No script',
};

const sourceTone = (source?: string) =>
  source === 'SOP_TEMPLATE' ? 'success' : source === 'SOP_GROUNDED' ? 'info' : source === 'LLM_KNOWLEDGE' ? 'danger' : 'neutral';

function formatAge(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const minutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
  if (minutes < 1) return 'now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  return hours < 24 ? `${hours}h ago` : `${Math.floor(hours / 24)}d ago`;
}

const HitlApprovalQueue: React.FC<{ onSelect: (requestId: string) => void; reloadKey?: number }> = ({ onSelect, reloadKey = 0 }) => {
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);

  const fetchReviewItems = useCallback(async (initial = false) => {
    if (initial) setLoading(true); else setRefreshing(true);
    try {
      const data = await apiGet<ReviewItem[]>('/api/v1/hitl/requests');
      setItems(Array.isArray(data) ? data : []);
      setError(null);
    } catch {
      setError('Could not load the review queue. No approval state was changed.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { void fetchReviewItems(true); }, [fetchReviewItems, reloadKey]);

  const filteredItems = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return items;
    return items.filter(({ incident, plan, request, assigneeInfo }) =>
      [incident.subject, incident.description, incident.externalId, incident.externalSource,
       plan.actionName, plan.target, plan.scriptSource, request.status,
       assigneeInfo?.name, assigneeInfo?.role, assigneeInfo?.department]
        .filter(Boolean).join(' ').toLowerCase().includes(normalized));
  }, [items, query]);

  const pendingCount = items.filter(item => item.request.status === 'PENDING').length;
  const ungroundedCount = items.filter(item => item.plan.scriptSource === 'LLM_KNOWLEDGE' && item.request.status === 'PENDING').length;

  if (loading) return <div className="hitl-panel-state"><Spinner /><span>Loading the review queue…</span></div>;

  return (
    <section className="hitl-workspace">
      <div className="hitl-toolbar">
        <div>
          <div className="hitl-kicker">Human review</div>
          <div className="hitl-count"><strong>{pendingCount}</strong> awaiting a decision</div>
        </div>
        <div className="hitl-toolbar-actions">
          <label className="hitl-search">
            <Search size={14} />
            <input value={query} onChange={event => setQuery(event.target.value)}
                   placeholder="Search incidents, assignees, roles…" aria-label="Search the review queue" />
          </label>
          <Button variant="secondary" size="sm" onClick={() => void fetchReviewItems(false)} disabled={refreshing}>
            {refreshing ? <Spinner size="sm" /> : <RefreshCw size={14} />} Refresh
          </Button>
        </div>
      </div>

      {ungroundedCount > 0 && (
        <div className="hitl-policy-strip hitl-policy-warn">
          <AlertTriangle size={15} />
          <span>
            <strong>{ungroundedCount}</strong> pending {ungroundedCount === 1 ? 'plan carries' : 'plans carry'} a script written
            from model knowledge with no approved procedure behind it. Read those line by line.
          </span>
        </div>
      )}

      <div className="hitl-policy-strip">
        <ShieldCheck size={15} />
        <span>
          A plan reaches this queue only after its script passed the deterministic guardrail scan. Approval pins the exact
          script text by hash; a dry run must pass before anything is dispatched to the executor agent.
        </span>
      </div>

      {error && <div className="hitl-policy-strip hitl-policy-warn"><AlertTriangle size={15} /><span>{error}</span></div>}

      {!filteredItems.length ? (
        <div className="hitl-panel">
          <EmptyState
            title={query ? 'No matching plans' : 'Queue is clear'}
            description={query ? 'Try a different search term.'
              : 'Plans whose script is blocked by the guardrails never reach this queue — they are escalated to a human with the reason attached.'} />
        </div>
      ) : (
        <div className="hitl-panel"><div className="hitl-table-wrap">
          <table className="hitl-table">
            <thead><tr>
              <th>Incident / proposal</th><th>Assignee & Department</th><th>Script</th><th>Risk</th><th>State</th><th />
            </tr></thead>
            <tbody>{filteredItems.map(({ incident, plan, request, assigneeInfo }) => (
              <tr key={request.id} onClick={() => onSelect(request.id)} className="hitl-row-clickable">
                <td>
                  <div className="hitl-subject">{incident.subject || 'Untitled incident'}</div>
                  <div className="hitl-id">
                    {incident.externalId || incident.id.slice(0, 8)} · {plan.actionName} → {plan.target} · {formatAge(request.createdAt)}
                  </div>
                </td>
                <td>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '3px' }}>
                    <div style={{ fontWeight: 700, fontSize: '13px', color: 'var(--text-1)', display: 'flex', alignItems: 'center', gap: '5px' }}>
                      <User size={13} style={{ color: 'var(--accent)' }} />
                      {assigneeInfo?.name || 'Unassigned'}
                    </div>
                    {(assigneeInfo?.role || assigneeInfo?.department) && (
                      <div style={{ fontSize: '11px', color: 'var(--text-3)' }}>
                        {assigneeInfo.role} {assigneeInfo.department ? `· ${assigneeInfo.department}` : ''}
                      </div>
                    )}
                  </div>
                </td>
                <td>
                  <div className="hitl-script-cell">
                    <Badge tone={sourceTone(plan.scriptSource) as any}>
                      <FileCode2 size={11} /> {SOURCE_LABEL[plan.scriptSource || 'NONE'] || plan.scriptSource}
                    </Badge>
                    <span className="hitl-script-meta">
                      {plan.scriptLanguage || '—'} · scan {plan.scriptScanLevel || '—'}
                    </span>
                  </div>
                </td>
                <td><Badge tone={plan.riskScore >= 50 ? 'danger' : plan.riskScore >= 25 ? 'warning' : 'neutral'}>{Math.round(plan.riskScore || 0)}%</Badge></td>
                <td>
                  <Badge tone={request.status === 'REJECTED' || plan.status === 'FAILED' ? 'danger'
                             : request.status === 'PENDING' ? 'warning' : 'success'}>
                    {request.status !== 'APPROVED' ? request.status
                      : plan.status === 'SIMULATED' ? 'READY TO RUN'
                      : plan.status === 'EXECUTED' ? 'EXECUTED'
                      : plan.status === 'FAILED' ? 'RUN FAILED'
                      : 'NEEDS DRY RUN'}
                  </Badge>
                </td>
                <td onClick={event => event.stopPropagation()}>
                  <Button variant="primary" size="sm" onClick={() => onSelect(request.id)}>
                    Review <ChevronRight size={14} />
                  </Button>
                </td>
              </tr>
            ))}</tbody>
          </table>
        </div></div>
      )}
    </section>
  );
};

export default HitlApprovalQueue;
