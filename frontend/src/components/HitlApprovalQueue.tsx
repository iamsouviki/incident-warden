import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { Check, ChevronDown, ChevronUp, Clock3, RefreshCw, Search, ShieldCheck, X } from 'lucide-react';
import { apiGet, apiPost, getStoredUser } from '../services/api';
import { Badge, Button, EmptyState, Spinner } from './ui';
import './HitlApprovalQueue.css';

interface Incident {
  id: string;
  subject: string;
  description?: string;
  priority: string;
  status: string;
  confidenceScore?: number;
  category?: string;
  externalSource?: string;
  externalId?: string;
  createdAt?: string;
  dueDate?: string;
  assignee?: string;
}

type Decision = 'APPROVE' | 'REJECT';

const priorityTone = (priority: string) => priority === 'P1' ? 'danger' : priority === 'P2' ? 'warning' : priority === 'P3' ? 'info' : 'neutral';
const confidenceTone = (score: number) => score >= 90 ? 'success' : score >= 80 ? 'warning' : 'danger';

function formatAge(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const minutes = Math.max(0, Math.floor((Date.now() - date.getTime()) / 60000));
  if (minutes < 1) return 'now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}

function formatCountdown(value?: string) {
  if (!value) return 'No SLA';
  const remaining = new Date(value).getTime() - Date.now();
  if (remaining <= 0) return 'Overdue';
  const minutes = Math.floor(remaining / 60000);
  if (minutes < 60) return `${minutes}m left`;
  return `${Math.floor(minutes / 60)}h ${minutes % 60}m left`;
}

const HitlApprovalQueue: React.FC = () => {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState('');
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [reasonById, setReasonById] = useState<Record<string, string>>({});
  const [actingId, setActingId] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);
  const [now, setNow] = useState(Date.now());

  const fetchPendingApprovals = useCallback(async (initial = false) => {
    if (initial) setLoading(true); else setRefreshing(true);
    try {
      const data = await apiGet<Incident[]>('/api/v1/incidents?status=PENDING_APPROVAL');
      setIncidents(data.filter(incident => incident.status === 'PENDING_APPROVAL'));
    } catch {
      setToast('Could not load the approval queue. Try again.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => { void fetchPendingApprovals(true); }, [fetchPendingApprovals]);
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30000);
    return () => window.clearInterval(timer);
  }, []);
  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(() => setToast(null), 4000);
    return () => window.clearTimeout(timer);
  }, [toast]);

  const filteredIncidents = useMemo(() => {
    const normalized = query.trim().toLowerCase();
    if (!normalized) return incidents;
    return incidents.filter(incident => [incident.subject, incident.description, incident.externalSource, incident.externalId, incident.category].filter(Boolean).join(' ').toLowerCase().includes(normalized));
  }, [incidents, query, now]);

  const decide = async (incident: Incident, decision: Decision) => {
    const reason = reasonById[incident.id]?.trim();
    if (decision === 'REJECT' && !reason) {
      setExpandedId(incident.id);
      setToast('Add a rejection reason before rejecting this proposal.');
      return;
    }
    setActingId(incident.id);
    try {
      await apiPost(`/api/v1/incidents/${incident.id}/decision`, {
        decision,
        reason: reason || `Approved from HITL queue by ${getStoredUser()?.username || 'operator'}`,
      });
      setIncidents(current => current.filter(item => item.id !== incident.id));
      setExpandedId(null);
      setToast(decision === 'APPROVE' ? 'Remediation approved and queued for execution.' : 'Proposal rejected and returned to the incident workflow.');
    } catch {
      setToast('Decision failed. The incident was not changed.');
    } finally {
      setActingId(null);
    }
  };

  if (loading) return <div className="hitl-panel-state"><Spinner /><span>Loading approval queue…</span></div>;

  return (
    <section className="hitl-workspace">
      <div className="hitl-toolbar">
        <div><div className="hitl-kicker">Human review</div><div className="hitl-count"><strong>{incidents.length}</strong> pending proposals</div></div>
        <div className="hitl-toolbar-actions">
          <label className="hitl-search"><Search size={14} /><input value={query} onChange={event => setQuery(event.target.value)} placeholder="Search incidents…" aria-label="Search incidents" /></label>
          <Button variant="secondary" size="sm" onClick={() => void fetchPendingApprovals(false)} disabled={refreshing}>{refreshing ? <Spinner size="sm" /> : <RefreshCw size={14} />} Refresh</Button>
        </div>
      </div>

      <div className="hitl-policy-strip"><ShieldCheck size={15} /><span>Only incidents routed as <strong>PENDING_APPROVAL</strong> appear here. Approvals are written to the incident history and audit trail.</span></div>

      {!filteredIncidents.length ? (
        <div className="hitl-panel"><EmptyState title={query ? 'No matching proposals' : 'Queue is clear'} description={query ? 'Try a different search term.' : 'New medium-risk or uncertain remediations will appear here.'} /></div>
      ) : (
        <div className="hitl-panel">
          <div className="hitl-table-wrap">
            <table className="hitl-table"><thead><tr><th>Incident</th><th>Source</th><th>Priority</th><th>Confidence</th><th>SLA</th><th>Action</th></tr></thead>
              <tbody>{filteredIncidents.map(incident => {
                const expanded = expandedId === incident.id;
                const score = Math.round(incident.confidenceScore || 0);
                const busy = actingId === incident.id;
                return <React.Fragment key={incident.id}>
                  <tr className={expanded ? 'is-expanded' : ''} onClick={() => setExpandedId(expanded ? null : incident.id)}>
                    <td><div className="hitl-incident-cell"><button className="hitl-expand" aria-label={`${expanded ? 'Collapse' : 'Expand'} ${incident.subject}`}>{expanded ? <ChevronUp size={15} /> : <ChevronDown size={15} />}</button><div><div className="hitl-subject">{incident.subject || 'Untitled incident'}</div><div className="hitl-id">{incident.externalId || incident.id} · {formatAge(incident.createdAt)}</div></div></div></td>
                    <td><Badge tone="neutral">{incident.externalSource || 'Internal'}</Badge></td>
                    <td><Badge tone={priorityTone(incident.priority) as any}>{incident.priority || 'P3'}</Badge></td>
                    <td><div className="hitl-confidence"><div className="hitl-confidence-bar"><span style={{ width: `${Math.min(100, score)}%` }} /></div><Badge tone={confidenceTone(score) as any}>{score}%</Badge></div></td>
                    <td><span className={`hitl-sla ${incident.dueDate && new Date(incident.dueDate).getTime() < Date.now() ? 'overdue' : ''}`}><Clock3 size={13} />{formatCountdown(incident.dueDate)}</span></td>
                    <td onClick={event => event.stopPropagation()}><div className="hitl-row-actions"><Button variant="primary" size="sm" onClick={() => void decide(incident, 'APPROVE')} disabled={busy}>{busy ? <Spinner size="sm" /> : <Check size={14} />} Approve</Button><Button variant="danger" size="sm" onClick={() => { setExpandedId(incident.id); }} disabled={busy}><X size={14} /> Reject</Button></div></td>
                  </tr>
                  {expanded && <tr className="hitl-detail-row"><td colSpan={6}><div className="hitl-detail"><div className="hitl-detail-grid"><div><span className="detail-label">Category</span><strong>{incident.category || 'General'}</strong></div><div><span className="detail-label">Assigned team</span><strong>{incident.assignee || 'Unassigned'}</strong></div><div><span className="detail-label">Confidence route</span><strong>{score}% · configurable HITL band</strong></div></div><div className="hitl-description"><span className="detail-label">Incident context</span><p>{incident.description || 'No description provided.'}</p></div><div className="hitl-decision"><input value={reasonById[incident.id] || ''} onChange={event => setReasonById(current => ({ ...current, [incident.id]: event.target.value }))} placeholder="Optional approval note or required rejection reason…" aria-label="Decision reason" /><Button variant="danger" size="sm" onClick={() => void decide(incident, 'REJECT')} disabled={busy}><X size={14} /> Reject with reason</Button></div></div></td></tr>}
                </React.Fragment>;
              })}</tbody>
            </table>
          </div>
        </div>
      )}

      {toast && <div className="hitl-toast" role="status">{toast}</div>}
    </section>
  );
};

export default HitlApprovalQueue;
