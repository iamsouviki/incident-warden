import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ChevronDown, ChevronUp, FileSearch, Play, RefreshCw, Search, ShieldCheck, X } from 'lucide-react';
import { apiGet, apiPost, getStoredUser } from '../services/api';
import { Badge, Button, EmptyState, Spinner } from './ui';
import './HitlApprovalQueue.css';

interface Incident {
  id: string;
  subject?: string;
  description?: string;
  priority?: string;
  externalSource?: string;
  externalId?: string;
}

interface RemediationPlan {
  id: string;
  status: string;
  actionName: string;
  target: string;
  sopEvidence?: string;
  confidenceScore: number;
  riskScore: number;
  guardrailStatus: string;
  guardrailFindings?: string;
  rollbackPlan?: string;
  planHash?: string;
}

interface ApprovalRequest {
  id: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED';
  requestedBy?: string;
  reviewer?: string;
  decisionReason?: string;
  createdAt?: string;
}

interface ReviewItem {
  request: ApprovalRequest;
  plan: RemediationPlan;
  incident: Incident;
}

type Decision = 'APPROVE' | 'REJECT';

const confidenceTone = (score: number) => score >= 90 ? 'success' : score >= 80 ? 'warning' : 'danger';
const requestTone = (status: ApprovalRequest['status']) => status === 'APPROVED' ? 'success' : status === 'PENDING' ? 'warning' : 'danger';

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

function splitFindings(value?: string) {
  return (value || '').split(';').map(item => item.trim()).filter(Boolean);
}

const HitlApprovalQueue: React.FC = () => {
  const [items, setItems] = useState<ReviewItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [reasonById, setReasonById] = useState<Record<string, string>>({});
  const [actingId, setActingId] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const fetchReviewItems = useCallback(async (initial = false) => {
    if (initial) setLoading(true); else setRefreshing(true);
    try {
      const data = await apiGet<ReviewItem[]>('/api/v1/hitl/requests');
      setItems(Array.isArray(data) ? data : []);
    } catch {
      setToast('Could not load the guarded plan queue. No approval state was changed.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { void fetchReviewItems(true); }, [fetchReviewItems]);
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 5000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const filteredItems = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return items;
    return items.filter(({ incident, plan, request }) => [incident.subject, incident.description, incident.externalSource, incident.externalId, plan.actionName, plan.target, request.status]
      .filter(Boolean).join(' ').toLowerCase().includes(normalized));
  }, [items, query]);

  const decide = async (item: ReviewItem, decision: Decision) => {
    const reason = reasonById[item.request.id]?.trim();
    if (decision === 'REJECT' && !reason) {
      setExpandedId(item.request.id);
      setToast('A rejection reason is required to preserve a complete audit record.');
      return;
    }
    setActingId(item.request.id);
    try {
      await apiPost(`/api/v1/hitl/requests/${item.request.id}/decision`, {
        decision,
        reason: reason || `Approved from HITL queue by ${getStoredUser()?.username || 'operator'}`,
      });
      if (decision === 'REJECT') {
        setItems(current => current.filter(value => value.request.id !== item.request.id));
        setToast('Plan rejected. The decision reason was added to the immutable audit trail.');
      } else {
        setItems(current => current.map(value => value.request.id === item.request.id ? {
          ...value,
          request: { ...value.request, status: 'APPROVED', reviewer: getStoredUser()?.username, decisionReason: reason || 'Approved from HITL queue' },
          plan: { ...value.plan, status: 'APPROVED' },
        } : value));
        setExpandedId(item.request.id);
        setToast('Plan approved. Run the required simulation next; no real execution is available.');
      }
    } catch {
      setToast('Decision failed. The plan and incident were not changed.');
    } finally {
      setActingId(null);
    }
  };

  const simulate = async (item: ReviewItem) => {
    setActingId(item.request.id);
    try {
      const result = await apiPost<{ message?: string }>(`/api/v1/hitl/requests/${item.request.id}/dry-run`, {});
      setItems(current => current.filter(value => value.request.id !== item.request.id));
      setToast(result.message || 'Simulation was recorded. No system mutation was performed.');
    } catch {
      setToast('Simulation could not be recorded. This plan remains approved and no action was executed.');
    } finally {
      setActingId(null);
    }
  };

  const pendingCount = items.filter(item => item.request.status === 'PENDING').length;
  if (loading) return <div className="hitl-panel-state"><Spinner /><span>Loading guarded remediation plans…</span></div>;

  return (
    <section className="hitl-workspace">
      <div className="hitl-toolbar">
        <div><div className="hitl-kicker">Human review</div><div className="hitl-count"><strong>{pendingCount}</strong> pending approval{pendingCount === 1 ? '' : 's'}</div></div>
        <div className="hitl-toolbar-actions">
          <label className="hitl-search"><Search size={14} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search plans, incidents, or targets…" aria-label="Search guarded plans" /></label>
          <Button variant="secondary" size="sm" onClick={() => void fetchReviewItems(false)} disabled={refreshing}>{refreshing ? <Spinner size="sm" /> : <RefreshCw size={14} />} Refresh</Button>
        </div>
      </div>

      <div className="hitl-policy-strip"><ShieldCheck size={15} /><span>Only plans with tenant-scoped, approved SOP evidence and a deterministic guardrail pass can be approved. Every execution is a <strong>simulation only</strong>.</span></div>

      {!filteredItems.length ? (
        <div className="hitl-panel"><EmptyState title={query ? 'No matching guarded plans' : 'Queue is clear'} description={query ? 'Try a different search term.' : 'Plans lacking approved SOP evidence are blocked and escalated instead of appearing here.'} /></div>
      ) : (
        <div className="hitl-panel"><div className="hitl-table-wrap"><table className="hitl-table"><thead><tr><th>Incident / proposal</th><th>Risk</th><th>Confidence</th><th>Guardrails</th><th>State</th><th>Action</th></tr></thead>
          <tbody>{filteredItems.map(item => {
            const { incident, plan, request } = item;
            const expanded = expandedId === request.id;
            const busy = actingId === request.id;
            const findings = splitFindings(plan.guardrailFindings);
            return <React.Fragment key={request.id}>
              <tr className={expanded ? 'is-expanded' : ''} onClick={() => setExpandedId(expanded ? null : request.id)}>
                <td><div className="hitl-incident-cell"><button className="hitl-expand" aria-label={`${expanded ? 'Collapse' : 'Expand'} plan for ${incident.subject || incident.id}`}>{expanded ? <ChevronUp size={15} /> : <ChevronDown size={15} />}</button><div><div className="hitl-subject">{incident.subject || 'Untitled incident'}</div><div className="hitl-id">{plan.actionName} → {plan.target} · {formatAge(request.createdAt)}</div></div></div></td>
                <td><Badge tone={plan.riskScore >= 50 ? 'danger' : plan.riskScore >= 25 ? 'warning' : 'neutral'}>{Math.round(plan.riskScore || 0)}%</Badge></td>
                <td><div className="hitl-confidence"><div className="hitl-confidence-bar"><span style={{ width: `${Math.min(100, Math.max(0, plan.confidenceScore || 0))}%` }} /></div><Badge tone={confidenceTone(plan.confidenceScore || 0) as any}>{Math.round(plan.confidenceScore || 0)}%</Badge></div></td>
                <td><Badge tone={plan.guardrailStatus === 'PASS' ? 'success' : 'danger'}>{plan.guardrailStatus}</Badge></td>
                <td><Badge tone={requestTone(request.status) as any}>{request.status === 'APPROVED' ? 'READY TO SIMULATE' : request.status}</Badge></td>
                <td onClick={event => event.stopPropagation()}><div className="hitl-row-actions">{request.status === 'PENDING' ? <><Button variant="primary" size="sm" onClick={() => void decide(item, 'APPROVE')} disabled={busy}>{busy ? <Spinner size="sm" /> : <Check size={14} />} Approve</Button><Button variant="danger" size="sm" onClick={() => setExpandedId(request.id)} disabled={busy}><X size={14} /> Reject</Button></> : <Button variant="primary" size="sm" onClick={() => void simulate(item)} disabled={busy}>{busy ? <Spinner size="sm" /> : <Play size={14} />} Run simulation</Button>}</div></td>
              </tr>
              {expanded && <tr className="hitl-detail-row"><td colSpan={6}><div className="hitl-detail"><div className="hitl-detail-grid"><div><span className="detail-label">Source / priority</span><strong>{incident.externalSource || 'Internal'} · {incident.priority || 'P3'}</strong></div><div><span className="detail-label">Plan hash</span><strong title={plan.planHash}>{plan.planHash ? `${plan.planHash.slice(0, 18)}…` : 'Unavailable'}</strong></div><div><span className="detail-label">Requested by</span><strong>{request.requestedBy || 'agent pipeline'}</strong></div></div><div className="hitl-description"><span className="detail-label">Incident context</span><p>{incident.description || 'No description provided.'}</p></div><div className="hitl-description"><span className="detail-label"><FileSearch size={13} /> Approved SOP evidence</span><p>{plan.sopEvidence || 'No evidence attached. This plan should not be approved.'}</p></div><div className="hitl-description"><span className="detail-label">Guardrail findings</span><p>{findings.length ? findings.join(' · ') : 'No guardrail findings recorded.'}</p></div><div className="hitl-description"><span className="detail-label">Rollback boundary</span><p>{plan.rollbackPlan || 'No mutation has run.'}</p></div>{request.status === 'PENDING' && <div className="hitl-decision"><input value={reasonById[request.id] || ''} onChange={event => setReasonById(current => ({ ...current, [request.id]: event.target.value }))} placeholder="Optional approval note or required rejection reason…" aria-label="Decision reason" /><Button variant="danger" size="sm" onClick={() => void decide(item, 'REJECT')} disabled={busy}><X size={14} /> Reject with reason</Button></div>}{request.status === 'APPROVED' && <div className="hitl-policy-strip"><ShieldCheck size={15} /><span>Approved plan hash is locked. The next step writes a simulated result only; no endpoint can perform a real mutation.</span></div>}</div></td></tr>}
            </React.Fragment>;
          })}</tbody>
        </table></div></div>
      )}
      {toast && <div className="hitl-toast" role="status">{toast}</div>}
    </section>
  );
};

export default HitlApprovalQueue;
