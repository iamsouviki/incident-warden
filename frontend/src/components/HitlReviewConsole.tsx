import React, { useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle, Check, ChevronLeft, Clock, Copy, FileCode2, FileSearch,
  History, Play, Rocket, ShieldAlert, ShieldCheck, Terminal, UserCheck, X, User, Briefcase, Building
} from 'lucide-react';
import { apiGet, apiPost } from '../services/api';
import { Badge, Button, Spinner } from './ui';
import './HitlReviewConsole.css';

interface UserInfo {
  username: string;
  name: string;
  role: string;
  department: string;
}

interface ReviewDetail {
  request: {
    id: string;
    status: 'PENDING' | 'APPROVED' | 'REJECTED';
    requestedBy?: string;
    reviewer?: string;
    decisionReason?: string;
    createdAt?: string;
    decidedAt?: string;
    approvedPlanHash?: string;
  };
  plan: {
    id: string;
    status: string;
    actionName: string;
    target: string;
    sopEvidence?: string;
    confidenceScore: number;
    riskScore: number;
    guardrailStatus: string;
    rollbackPlan?: string;
    planHash?: string;
    attempts?: number;
  };
  incident: {
    id: string;
    subject?: string;
    description?: string;
    priority?: string;
    externalId?: string;
    externalSource?: string;
  };
  assigneeInfo?: UserInfo;
  requestedByInfo?: UserInfo;
  reviewerInfo?: UserInfo;
  action: { actionKey: string; valid: boolean; reason: string; tool: string; mutating: boolean; arguments: string[] };
  script: {
    script: string;
    language?: string;
    source?: 'SOP_TEMPLATE' | 'SOP_GROUNDED' | 'LLM_KNOWLEDGE' | 'NONE';
    scanLevel?: 'PASS' | 'WARN' | 'BLOCK';
    grounded: boolean;
    lineCount: number;
    provenance: string;
  };
  precedent: {
    reference?: string;
    incidentId?: string;
    actionKey?: string;
    similarity?: number;
    matchedTerms?: string[];
    resolutionNote?: string;
    resolvedAt?: string;
  };
  guardrailFindings: string[];
  executions: Array<{
    id: string; mode: string; status: string; output?: string;
    validationResult?: string; completedAt?: string;
  }>;
  canApprove: boolean;
  separationOfDutiesBlocked: boolean;
}

const SOURCE_LABEL: Record<string, string> = {
  SOP_TEMPLATE: 'SOP template',
  SOP_GROUNDED: 'SOP-grounded',
  LLM_KNOWLEDGE: 'LLM knowledge',
  NONE: 'No script',
};

const sourceTone = (source?: string) =>
  source === 'SOP_TEMPLATE' ? 'success' : source === 'SOP_GROUNDED' ? 'info' : source === 'LLM_KNOWLEDGE' ? 'danger' : 'neutral';

const scanTone = (level?: string) => (level === 'PASS' ? 'success' : level === 'WARN' ? 'warning' : 'danger');

function findingTone(finding: string): 'danger' | 'warning' | 'neutral' {
  if (finding.startsWith('BLOCK') || finding.includes('BLOCKED')) return 'danger';
  if (finding.startsWith('WARN') || finding === 'UNGROUNDED_LLM_SCRIPT') return 'warning';
  return 'neutral';
}

const HitlReviewConsole: React.FC<{ requestId: string; onBack: () => void; onChanged: () => void }> = ({ requestId, onBack, onChanged }) => {
  const [detail, setDetail] = useState<ReviewDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  const [acknowledged, setAcknowledged] = useState(false);

  const load = useCallback(async () => {
    try {
      setDetail(await apiGet<ReviewDetail>(`/api/v1/hitl/requests/${requestId}`));
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load this review.');
    } finally {
      setLoading(false);
    }
  }, [requestId]);

  useEffect(() => { setLoading(true); setAcknowledged(false); setReason(''); void load(); }, [load]);

  const act = async (label: string, url: string, body: unknown, success: string) => {
    setBusy(label);
    setNotice(null);
    try {
      const result = await apiPost<{ message?: string }>(url, body);
      setNotice(result?.message || success);
      await load();
      onChanged();
    } catch (cause) {
      setNotice(cause instanceof Error ? cause.message : 'The action failed. Nothing was changed.');
    } finally {
      setBusy(null);
    }
  };

  if (loading) return <div className="review-state"><Spinner /><span>Loading the review…</span></div>;
  if (error || !detail) {
    return (
      <div className="review-state">
        <ShieldAlert size={20} />
        <span>{error || 'This review is unavailable.'}</span>
        <Button variant="secondary" size="sm" onClick={onBack}><ChevronLeft size={14} /> Back to queue</Button>
      </div>
    );
  }

  const { request, plan, incident, action, script, guardrailFindings, executions, precedent, assigneeInfo, requestedByInfo, reviewerInfo } = detail;
  const ungrounded = script.source === 'LLM_KNOWLEDGE';
  const pending = request.status === 'PENDING';
  const simulated = plan.status === 'SIMULATED';
  const hashDrifted = request.status === 'APPROVED' && !!request.approvedPlanHash && request.approvedPlanHash !== plan.planHash;
  const hasPrecedent = !!precedent?.reference;
  const sameAction = hasPrecedent && precedent.actionKey === action.actionKey;

  const approveBlocked = !detail.canApprove || (ungrounded && !acknowledged);

  return (
    <section className="review-console">
      <header className="review-header">
        <Button variant="ghost" size="sm" onClick={onBack}><ChevronLeft size={15} /> Queue</Button>
        <div className="review-heading">
          <h2>{incident.subject || 'Untitled incident'}</h2>
          <div className="review-sub">
            {incident.externalId || incident.id.slice(0, 8)} · {incident.externalSource || 'Internal'} · {incident.priority || 'P3'}
          </div>
        </div>
        <div className="review-header-badges">
          <Badge tone={request.status === 'APPROVED' ? 'success' : pending ? 'warning' : 'danger'}>{request.status}</Badge>
          <Badge tone={plan.guardrailStatus === 'PASS' ? 'success' : 'danger'}>GUARDRAILS {plan.guardrailStatus}</Badge>
        </div>
      </header>

      {ungrounded && (
        <div className="review-alarm">
          <AlertTriangle size={18} />
          <div>
            <strong>No approved procedure authorises this script.</strong>
            <p>{script.provenance} Read every line. If you cannot undo its effect by hand, reject it.</p>
          </div>
        </div>
      )}

      {hashDrifted && (
        <div className="review-alarm">
          <ShieldAlert size={18} />
          <div>
            <strong>This plan changed after it was approved.</strong>
            <p>The approved hash no longer matches the plan. Execution is refused by the server; raise a new plan.</p>
          </div>
        </div>
      )}

      <div className="review-grid">
        <div className="review-main">
          <article className="review-card">
            <div className="review-card-head">
              <span className="review-card-title"><FileCode2 size={15} /> Script under review</span>
              <div className="review-card-badges">
                <Badge tone={sourceTone(script.source) as any}>{SOURCE_LABEL[script.source || 'NONE'] || script.source}</Badge>
                <Badge tone={scanTone(script.scanLevel) as any}>SCAN {script.scanLevel || '—'}</Badge>
                <Badge tone="neutral">{script.language || '—'} · {script.lineCount} lines</Badge>
                <Button variant="ghost" size="sm" title="Copy script"
                        onClick={() => void navigator.clipboard?.writeText(script.script)}>
                  <Copy size={14} />
                </Button>
              </div>
            </div>
            <p className="review-provenance">{script.provenance}</p>
            {script.script
              ? <pre className="review-script"><code>{script.script}</code></pre>
              : <div className="review-empty">No script is attached to this plan, so there is nothing that can execute.</div>}
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><FileSearch size={15} /> Approved SOP evidence</span></div>
            <p className="review-body">{plan.sopEvidence || 'No approved procedure was matched for this incident.'}</p>
          </article>

          <article className="review-card">
            <div className="review-card-head">
              <span className="review-card-title"><History size={15} /> Have we fixed this before?</span>
              {hasPrecedent && (
                <div className="review-card-badges">
                  <Badge tone={(precedent.similarity ?? 0) >= 0.6 ? 'success' : 'warning'}>
                    {Math.round((precedent.similarity ?? 0) * 100)}% wording match
                  </Badge>
                  <Badge tone={sameAction ? 'success' : 'warning'}>
                    {sameAction ? 'SAME ACTION' : 'DIFFERENT ACTION'}
                  </Badge>
                </div>
              )}
            </div>
            {hasPrecedent ? (
              <>
                <dl className="review-facts">
                  <div><dt>Past incident</dt><dd>{precedent.reference}</dd></div>
                  <div><dt>What was approved then</dt><dd>{precedent.actionKey || <em>none pinned</em>}</dd></div>
                  <div><dt>Resolved</dt><dd>{precedent.resolvedAt ? new Date(precedent.resolvedAt).toLocaleString() : '—'}</dd></div>
                </dl>
                {precedent.resolutionNote && <p className="review-body">“{precedent.resolutionNote}”</p>}
                {!!precedent.matchedTerms?.length && (
                  <ul className="review-findings">
                    {precedent.matchedTerms.map(term => <li key={term}><Badge tone="neutral">{term}</Badge></li>)}
                  </ul>
                )}
              </>
            ) : (
              <div className="review-empty">
                No comparable resolved incident in history.
              </div>
            )}
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><Terminal size={15} /> Incident context</span></div>
            <p className="review-body">{incident.description || 'No description provided.'}</p>
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><Clock size={15} /> Execution timeline</span></div>
            {executions.length ? (
              <ol className="review-timeline">
                {executions.map(run => (
                  <li key={run.id}>
                    <div className="timeline-row">
                      <Badge tone={run.status?.includes('BLOCK') || run.status === 'FAILED' ? 'danger' : run.status === 'EXECUTED' ? 'success' : 'info'}>{run.status}</Badge>
                      <Badge tone="neutral">{run.mode}</Badge>
                      <span className="timeline-time">{run.completedAt ? new Date(run.completedAt).toLocaleString() : '—'}</span>
                    </div>
                    {run.output && <pre className="review-output">{run.output}</pre>}
                    {run.validationResult && <p className="timeline-validation">{run.validationResult}</p>}
                  </li>
                ))}
              </ol>
            ) : <div className="review-empty">Nothing has run for this plan yet.</div>}
          </article>
        </div>

        <aside className="review-side">
          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><ShieldCheck size={15} /> Plan details</span></div>
            <dl className="review-facts">
              <div><dt>Action</dt><dd>{plan.actionName || '—'}</dd></div>
              <div><dt>Target</dt><dd>{plan.target || '—'}</dd></div>
              <div><dt>Action key</dt><dd>{action.actionKey || <em>none declared</em>}</dd></div>
              <div><dt>Tool</dt><dd>{action.valid ? `${action.tool}${action.mutating ? ' (mutating)' : ' (read-only)'}` : <span className="fact-bad">{action.reason || 'not runnable'}</span>}</dd></div>
              <div><dt>Confidence</dt><dd>{Math.round(plan.confidenceScore || 0)}%</dd></div>
              <div><dt>Risk</dt><dd>{Math.round(plan.riskScore || 0)}%</dd></div>
              <div><dt>Plan status</dt><dd>{plan.status}</dd></div>
              <div><dt>Plan hash</dt><dd className="fact-hash" title={plan.planHash}>{plan.planHash ? `${plan.planHash.slice(0, 16)}…` : '—'}</dd></div>
            </dl>
          </article>

          {/* Assigned Engineer / Ownership */}
          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><User size={15} /> Assignee & Team</span></div>
            <dl className="review-facts">
              <div><dt>Assignee</dt><dd><strong>{assigneeInfo?.name || 'Unassigned'}</strong></dd></div>
              <div><dt>Username</dt><dd>{assigneeInfo?.username ? `@${assigneeInfo.username}` : '—'}</dd></div>
              <div><dt>Role</dt><dd>{assigneeInfo?.role || 'Operations Specialist'}</dd></div>
              <div><dt>Department</dt><dd>{assigneeInfo?.department || 'IT Operations'}</dd></div>
            </dl>
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><ShieldAlert size={15} /> Guardrail findings</span></div>
            {guardrailFindings.length ? (
              <ul className="review-findings">
                {guardrailFindings.map((finding, index) => (
                  <li key={`${finding}-${index}`}><Badge tone={findingTone(finding)}>{finding.trim()}</Badge></li>
                ))}
              </ul>
            ) : <div className="review-empty">No findings recorded.</div>}
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title"><UserCheck size={15} /> Accountability</span></div>
            <dl className="review-facts">
              <div><dt>Requested by</dt><dd>{requestedByInfo?.name || request.requestedBy || 'agent pipeline'}</dd></div>
              <div><dt>Reviewer</dt><dd>{reviewerInfo?.name || request.reviewer || <em>undecided</em>}</dd></div>
              <div><dt>Decision reason</dt><dd>{request.decisionReason || <em>—</em>}</dd></div>
            </dl>
            {detail.separationOfDutiesBlocked && (
              <p className="review-note review-note-bad">
                You raised this plan, so you cannot approve it. Separation of duties requires a second reviewer.
              </p>
            )}
          </article>

          <article className="review-card">
            <div className="review-card-head"><span className="review-card-title">Rollback plan</span></div>
            <p className="review-body">{plan.rollbackPlan || 'No rollback recorded.'}</p>
          </article>
        </aside>
      </div>

      <footer className="review-actions">
        {pending ? (
          <>
            <input className="review-reason" value={reason} onChange={event => setReason(event.target.value)}
                   placeholder="Approval note, or the reason for rejection (required to reject)…"
                   aria-label="Decision reason" />
            {ungrounded && (
              <label className="review-ack">
                <input type="checkbox" checked={acknowledged} onChange={event => setAcknowledged(event.target.checked)} />
                I read the whole script and accept that no approved procedure backs it.
              </label>
            )}
            <div className="review-buttons">
              <Button variant="danger" size="sm" disabled={!!busy}
                      onClick={() => {
                        if (!reason.trim()) { setNotice('A rejection reason is required: the audit record has to say why.'); return; }
                        void act('reject', `/api/v1/hitl/requests/${request.id}/decision`, { decision: 'REJECT', reason }, 'Plan rejected.');
                      }}>
                {busy === 'reject' ? <Spinner size="sm" /> : <X size={14} />} Reject
              </Button>
              <Button variant="primary" size="sm" disabled={approveBlocked || !!busy}
                      title={approveBlocked ? 'Approval is not available for you on this plan.' : 'Approve this exact script'}
                      onClick={() => void act('approve', `/api/v1/hitl/requests/${request.id}/decision`,
                        { decision: 'APPROVE', reason: reason || 'Approved after review' }, 'Plan approved. Run the dry run next.')}>
                {busy === 'approve' ? <Spinner size="sm" /> : <Check size={14} />} Approve this script
              </Button>
            </div>
          </>
        ) : request.status === 'APPROVED' ? (
          <div className="review-buttons">
            <span className="review-step">
              {simulated ? 'Dry run passed. A real run dispatches this script to the executor agent.'
                         : 'Approved. A dry run must pass before this can run for real.'}
            </span>
            <Button variant="secondary" size="sm" disabled={!!busy || hashDrifted}
                    onClick={() => void act('dry', `/api/v1/hitl/requests/${request.id}/dry-run`, {}, 'Dry run recorded.')}>
              {busy === 'dry' ? <Spinner size="sm" /> : <Play size={14} />} Dry run
            </Button>
            <Button variant="danger" size="sm" disabled={!!busy || !simulated || hashDrifted}
                    title={simulated ? 'Dispatch to the executor agent' : 'A passing dry run is required first'}
                    onClick={() => void act('run', `/api/v1/hitl/requests/${request.id}/execute`, {}, 'Execution recorded.')}>
              {busy === 'run' ? <Spinner size="sm" /> : <Rocket size={14} />} Execute for real
            </Button>
          </div>
        ) : (
          <span className="review-step">This request was {request.status.toLowerCase()} and cannot be acted on.</span>
        )}
      </footer>

      {notice && <div className="review-toast" role="status">{notice}</div>}
    </section>
  );
};

export default HitlReviewConsole;
