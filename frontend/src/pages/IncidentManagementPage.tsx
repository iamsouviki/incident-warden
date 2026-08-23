import React, { useState, useEffect } from 'react';
import './IncidentManagementPage.css';
import { Plus, RefreshCw, Search, Calendar, ShieldAlert, Clock, Edit, Save, Loader } from 'lucide-react';
import { authFetch, getStoredUser } from '../services/api';
import SearchableSelect from '../components/SearchableSelect';

export interface Incident {
  id: string;
  subject: string;
  description: string;
  assignee: string;
  assignedGteam: string;
  priority: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  dueDate: string;
  externalSource: string;
  externalId: string;
  /** Store this ticket belongs to. Autonomy is inherited per store, not per tenant. */
  storeNumber?: string;
  targetPlatform?: string;
  /** The machine a remediation script would run on. Blank stops a mutating plan. */
  targetHost?: string;
  /** SSH | WINRM | AGENT, or blank for the executor's own default path to the host. */
  connectionMethod?: string;
  /**
   * What the ticket's own words point at, computed by the backend's one extractor. Read-only
   * and never saved by itself — the fields above are what a plan uses, and these are only
   * offered as a prefill so nobody retypes a hostname that is already in the description.
   */
  detectedTargetHost?: string;
  detectedStoreNumber?: string;
}

export interface Comment {
  id: string;
  incidentId: string;
  author: string;
  commentText: string;
  createdAt: string;
}

export interface HistoryRecord {
  id: string;
  incidentId: string;
  fieldName: string;
  oldValue: string;
  newValue: string;
  updatedBy: string;
  updatedAt: string;
}

export interface Employee {
  id: string;
  username: string;
  fullName?: string;
  email: string;
  role?: string;
  department?: string;
}

export interface Team {
  id: string;
  name: string;
  description: string;
  employees: Employee[];
}

interface Props {
  onCreateClick?: () => void;
  showCreateModal?: boolean;
  setShowCreateModal?: (show: boolean) => void;
}

/**
 * Guarantees a <select> can display the value the record actually holds.
 *
 * A select whose value matches none of its options renders as the FIRST option, so an
 * incident the platform resolved by itself (status RESOLVED, which is not in the analyst's
 * picker) was drawn as "New" — and one change to any other field then saved "New" back over
 * it. Same class of bug for an assignee or a team that arrived from a third-party import and
 * is not on the local roster.
 *
 * The current value goes first and is never invented: if the record says RESOLVED, the
 * operator sees RESOLVED.
 */
const withCurrent = (options: string[], current?: string | null): string[] =>
  current && !options.includes(current) ? [current, ...options] : options;

/**
 * How a suggestion's origin is coloured. Green is reserved for the workspace's own approved
 * SOPs, because "your runbook says this" and "a web page said this" are not the same claim
 * and the person deciding whether to act on it is not always an engineer.
 */
const SOURCE_TONE: Record<string, string> = {
  SOP: 'var(--green)',
  WEB: 'var(--amber)',
  AI: 'var(--amber)',
  NONE: 'var(--text-muted)',
};


const IncidentManagementPage: React.FC<Props> = ({ showCreateModal = false, setShowCreateModal }) => {
  const currentUser = getStoredUser();
  const currentUsername = currentUser?.username || 'User';
  // A viewer's plan request is refused by the API, so the button says so instead of failing.
  const isViewer = (currentUser?.role || '').toUpperCase() === 'VIEWER';

  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [importSource, setImportSource] = useState('Freshservice');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importMessage, setImportMessage] = useState('');
  
  // Search & Filter state
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');
  const [assignee, setAssignee] = useState('');
  const [assignedGteam, setAssignedGteam] = useState('');
  const [priority, setPriority] = useState('');
  const [createdDate, setCreatedDate] = useState('');
  const [dueDateFilter, setDueDateFilter] = useState('');

  // Details Tab switcher ('details' or 'history')
  const [detailTab, setDetailTab] = useState<'details' | 'history'>('details');
  const [comments, setComments] = useState<Comment[]>([]);
  const [historyRecords, setHistoryRecords] = useState<HistoryRecord[]>([]);
  const [newComment, setNewComment] = useState('');
  const [commentLoading, setCommentLoading] = useState(false);

  // Edit details state
  const [editMode, setEditMode] = useState(false);
  const [editSubject, setEditSubject] = useState('');
  const [editDescription, setEditDescription] = useState('');
  const [editStatus, setEditStatus] = useState('');
  const [editPriority, setEditPriority] = useState('');
  const [editAssignee, setEditAssignee] = useState('');
  const [editGteam, setEditGteam] = useState('');
  // The remediation target. Saved on its own button rather than with "Edit Fields", because
  // an operator answering "which server?" after a blocked plan is not editing the ticket.
  const [editStoreNumber, setEditStoreNumber] = useState('');
  const [editTargetHost, setEditTargetHost] = useState('');
  const [editConnectionMethod, setEditConnectionMethod] = useState('');
  const [editTargetPlatform, setEditTargetPlatform] = useState('');
  const [targetSaving, setTargetSaving] = useState(false);
  const [updateLoading, setUpdateLoading] = useState(false);

  // Create Incident Form state
  const [newSubject, setNewSubject] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [newPriority, setNewPriority] = useState('P3');
  const [newAssignee, setNewAssignee] = useState('Unassigned');
  const [newGteam, setNewGteam] = useState('IT Ops');
  // Only for a ticket logged on someone else's behalf. Left empty, the server uses the
  // signed-in user's address — it already knows who created the ticket.
  const [newReporterEmail, setNewReporterEmail] = useState('');
  // Which store, and which machine. Asked here because the answer is cheapest to get from
  // the person filing the ticket; the backend re-derives and re-validates both.
  const [newStoreNumber, setNewStoreNumber] = useState('');
  const [newTargetHost, setNewTargetHost] = useState('');
  const [newTargetPlatform, setNewTargetPlatform] = useState('');
  const [serverNudged, setServerNudged] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [initialComment, setInitialComment] = useState('');
  const [errorMsg, setErrorMsg] = useState('');

  // Teams & AI Suggestion states
  const [teams, setTeams] = useState<Team[]>([]);
  const [statuses, setStatuses] = useState<string[]>(['New', 'In Progress', 'Resolved', 'Closed']);
  const [aiSuggestion, setAiSuggestion] = useState<{
    suggestedTeam?: string;
    suggestedResolution?: string;
    /** SOP | WEB | AI | NONE — where the advice came from, decided by the backend. */
    source?: string;
    sourceLabel?: string;
    sourceDetail?: string;
  } | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [modalAiLoading, setModalAiLoading] = useState(false);

  // Guarded remediation-plan creation. The backend owns all execution decisions.
  const [planCreating, setPlanCreating] = useState(false);
  const [planOutcome, setPlanOutcome] = useState<{ route: string; message: string; planId?: string; reason?: string } | null>(null);

  useEffect(() => {
    const fetchTeams = async () => {
      try {
        const res = await authFetch('/api/v1/teams');
        if (res.ok) {
          const data = await res.json();
          setTeams(data);
        }
      } catch (err) {
        console.error('Failed to fetch teams', err);
      }
    };
    const fetchStatuses = async () => {
      try {
        const res = await authFetch('/api/v1/statuses');
        if (res.ok) {
          const data = await res.json();
          if (Array.isArray(data) && data.length > 0) {
            setStatuses(data.map((s: any) => s.name));
          }
        }
      } catch (err) {
        console.error('Failed to fetch statuses', err);
      }
    };
    fetchTeams();
    fetchStatuses();
  }, []);

  const getAiSuggestion = async (sub: string, desc: string) => {
    setAiLoading(true);
    setAiSuggestion(null);
    try {
      const res = await authFetch('/api/v1/incidents/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subject: sub, description: desc }),
      });
      if (res.ok) setAiSuggestion(await res.json());
    } catch (err) {
      console.error('Failed to get AI suggestion', err);
    } finally {
      setAiLoading(false);
    }
  };

  const createGuardedPlan = async () => {
    if (!selectedIncident) return;
    setPlanCreating(true);
    setPlanOutcome(null);
    try {
      const res = await authFetch(`/api/v1/hitl/incidents/${selectedIncident.id}/plan`, { method: 'POST' });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.message || 'The guarded plan could not be created.');
      const plan = data.plan || {};
      const route = data.route || 'ESCALATE';
      setPlanOutcome({
        route,
        planId: plan.id,
        // Kept as its own token, not only inside the prose: the panel below decides whether
        // this is a question the operator can answer inline by matching on TARGET_*.
        reason: data.reason,
        message: route === 'HITL_REQUIRED'
          ? 'A plan was written from your approved SOP and passed every safety check. It is waiting in the approval queue for a person to read and approve. Nothing has run.'
          : `No plan was created and nothing will run. Why: ${data.reason || plan.sopEvidence || 'the required SOP evidence or safety criteria were not met.'}`
            // What to do about it, when the backend knows: naming the server, or the way to
            // reach it, is a fix the operator can apply in the card above without a ticket.
            + (data.action ? `\n\n${data.action}` : ''),
      });
      if (route === 'HITL_REQUIRED') {
        setSelectedIncident(current => current ? { ...current, status: 'PENDING_APPROVAL' } : current);
        fetchIncidents();
      }
    } catch (err) {
      setPlanOutcome({ route: 'ERROR', message: err instanceof Error ? err.message : 'The guarded plan could not be created.' });
    } finally {
      setPlanCreating(false);
    }
  };

  const handleModalAiSuggest = async () => {
    if (!newSubject.trim()) return;
    setModalAiLoading(true);
    try {
      const res = await authFetch('/api/v1/incidents/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subject: newSubject, description: newDescription }),
      });
      if (res.ok) {
        const data = await res.json();
        setNewGteam(data.suggestedTeam);
        const employees = teams.find(t => t.name === data.suggestedTeam)?.employees || [];
        setNewAssignee(employees[0]?.username || 'Unassigned');
        if (data.suggestedResolution) {
          // The origin is part of the note, not a "(Source: RAG …)" tail: whoever picks this
          // ticket up needs to know whether the steps are the company's own procedure or a
          // starting point read off the web.
          setInitialComment(`Suggested first steps — ${data.sourceLabel || 'from the assistant'}:\n${data.suggestedResolution}`);
        }
      }
    } catch (err) {
      console.error(err);
    } finally {
      setModalAiLoading(false);
    }
  };

  const fetchIncidents = async () => {
    setLoading(true);
    try {
      const queryParams = new URLSearchParams();
      if (subject) queryParams.append('subject', subject);
      if (description) queryParams.append('description', description);
      if (assignee) queryParams.append('assignee', assignee);
      if (assignedGteam) queryParams.append('assignedGteam', assignedGteam);
      if (priority) queryParams.append('priority', priority);
      if (createdDate) queryParams.append('createdDate', createdDate);
      if (dueDateFilter) queryParams.append('dueDate', dueDateFilter);

      const res = await authFetch(`/api/v1/incidents?${queryParams.toString()}`);
      if (res.ok) {
        const data = await res.json();
        setIncidents(data);
      }
    } catch (err) {
      console.error('Failed to fetch incidents', err);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchIncidents();
  }, [subject, description, assignee, assignedGteam, priority, createdDate, dueDateFilter]);

  // Fetch comments and history when selected incident changes
  useEffect(() => {
    if (selectedIncident) {
      fetchComments(selectedIncident.id);
      fetchHistory(selectedIncident.id);
      
      // Initialize edit fields
      setEditSubject(selectedIncident.subject);
      setEditDescription(selectedIncident.description);
      setEditStatus(selectedIncident.status);
      setEditPriority(selectedIncident.priority);
      setEditAssignee(selectedIncident.assignee);
      setEditGteam(selectedIncident.assignedGteam);
      // The saved answer if there is one, otherwise what the ticket itself says. The backend
      // reads the host and store out of the subject and description with the same extractor
      // the planner uses, so offering it here saves retyping a hostname that is sitting two
      // inches above the field — and it is still only an offer: nothing is saved until the
      // operator presses "Save target".
      //
      // Only the two facts that are safe to read from prose. The OS is deliberately not
      // prefilled: setting that field is OPERATOR_DECLARED, the top of the platform ladder,
      // and a keyword guess sitting in the box would outrank the machine's own probe reply
      // the moment somebody pressed Save.
      setEditStoreNumber(selectedIncident.storeNumber || selectedIncident.detectedStoreNumber || '');
      setEditTargetHost(selectedIncident.targetHost || selectedIncident.detectedTargetHost || '');
      setEditConnectionMethod(selectedIncident.connectionMethod || '');
      setEditTargetPlatform(selectedIncident.targetPlatform || '');
      setEditMode(false);
      setAiSuggestion(null);
      setPlanOutcome(null);

      // Auto-trigger AI suggestion
      getAiSuggestion(selectedIncident.subject, selectedIncident.description);
    }
    // Keyed on the id, not the object: this resets the panel because a *different* ticket
    // was opened. Keyed on identity it also fired whenever the same ticket was re-fetched
    // (saving the target, or the HITL_REQUIRED status update below), and the setPlanOutcome(null)
    // above then wiped the plan result the operator had just asked for — the plan was created,
    // the confirmation box vanished, and the answer-and-replan panel looked like it did nothing.
  }, [selectedIncident?.id]);

  const fetchComments = async (id: string) => {
    try {
      const res = await authFetch(`/api/v1/incidents/${id}/comments`);
      if (res.ok) {
        const data = await res.json();
        setComments(data);
      }
    } catch (err) {
      console.error('Error fetching comments', err);
    }
  };

  const fetchHistory = async (id: string) => {
    try {
      const res = await authFetch(`/api/v1/incidents/${id}/history`);
      if (res.ok) {
        const data = await res.json();
        setHistoryRecords(data);
      }
    } catch (err) {
      console.error('Error fetching history', err);
    }
  };

  const handleSync = async () => {
    setSyncing(true);
    try {
      const res = await authFetch('/api/v1/incidents/sync', { method: 'POST' });
      if (res.ok) {
        await fetchIncidents();
      }
    } catch (err) {
      console.error('Failed to sync', err);
    } finally {
      setSyncing(false);
    }
  };

  const handleImport = async () => {
    if (!importFile) { setImportMessage('Choose a Freshservice or ServiceNow .csv or .xlsx export first.'); return; }
    setImporting(true); setImportMessage('');
    try {
      const body = new FormData(); body.append('file', importFile);
      const res = await authFetch(`/api/v1/intake/incidents/import?sourceSystem=${encodeURIComponent(importSource)}`, { method: 'POST', body });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || 'Import failed');
      setImportMessage(`Import finished: ${data.created} created, ${data.deduplicated} already known, ${data.rejected} rejected.`);
      setImportFile(null); await fetchIncidents();
    } catch (error) { setImportMessage(error instanceof Error ? error.message : 'Import failed'); }
    finally { setImporting(false); }
  };

  /**
   * Whether the ticket text already names a machine.
   *
   * Deliberately looser than the backend's rule and used only to decide whether to nudge
   * once. The real gate is IncidentTarget on the server, which is what the plan and the
   * executor actually obey — this only spares the filer a round trip.
   */
  const mentionsServer = (text: string) =>
    /(?:hostname|servername|server|host|node|machine|device)\s*(?:name)?\s*[:=]?\s*[A-Za-z0-9][A-Za-z0-9._-]{2,}/i.test(text)
    || /\b[A-Za-z0-9][A-Za-z0-9_-]*(?:\.[A-Za-z0-9][A-Za-z0-9_-]*)+\.[A-Za-z]{2,24}\b/.test(text);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newSubject.trim()) {
      setErrorMsg('Subject is required');
      return;
    }
    // One nudge, never a block. Plenty of real tickets have no server at all ("laptop
    // won't charge"), so refusing to file them would be worse than asking late — and the
    // server is still asked for, unmissably, if a remediation plan needs it.
    if (!newTargetHost.trim() && !mentionsServer(`${newSubject} ${newDescription}`) && !serverNudged) {
      setServerNudged(true);
      setErrorMsg('No server is named in this ticket. Add the server name if this is a machine '
        + 'problem — a remediation script cannot run without one. Submit again to file it anyway.');
      return;
    }
    setCreateLoading(true);
    setErrorMsg('');
    try {
      const res = await authFetch('/api/v1/incidents', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          subject: newSubject,
          description: newDescription,
          priority: newPriority,
          assignee: newAssignee,
          assignedGteam: newGteam,
          reporterEmail: newReporterEmail.trim(),
          storeNumber: newStoreNumber.trim(),
          targetHost: newTargetHost.trim(),
          targetPlatform: newTargetPlatform,
          status: 'New'
        })
      });
      if (res.ok) {
        const created = await res.json();
        if (initialComment.trim()) {
          await authFetch(`/api/v1/incidents/${created.id}/comments`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              author: currentUsername,
              commentText: initialComment
            })
          });
        }
        setNewSubject('');
        setNewDescription('');
        setInitialComment('');
        setNewPriority('P3');
        setNewAssignee('Unassigned');
        setNewGteam('IT Ops');
        setNewReporterEmail('');
        setNewStoreNumber('');
        setNewTargetHost('');
        setNewTargetPlatform('');
        setServerNudged(false);
        if (setShowCreateModal) setShowCreateModal(false);
        fetchIncidents();
      } else {
        setErrorMsg('Failed to create incident');
      }
    } catch (err) {
      setErrorMsg('Network error');
    } finally {
      setCreateLoading(false);
    }
  };

  const handleAddComment = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newComment.trim() || !selectedIncident) return;
    setCommentLoading(true);
    try {
      const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}/comments`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          author: currentUsername,
          commentText: newComment
        })
      });
      if (res.ok) {
        setNewComment('');
        fetchComments(selectedIncident.id);
      }
    } catch (err) {
      console.error('Error adding comment', err);
    } finally {
      setCommentLoading(false);
    }
  };

  const handleUpdateIncident = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedIncident) return;
    setUpdateLoading(true);
    try {
      const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          subject: editSubject,
          description: editDescription,
          status: editStatus,
          priority: editPriority,
          assignee: editAssignee,
          assignedGteam: editGteam
        })
      });
      if (res.ok) {
        const updated = await res.json();
        setSelectedIncident(updated);
        setEditMode(false);
        fetchIncidents();
      }
    } catch (err) {
      console.error('Failed to update incident', err);
    } finally {
      setUpdateLoading(false);
    }
  };

  /**
   * Saves store / server / connection method.
   *
   * Sends only these three fields. Spreading the whole incident sent this pane's copy of
   * every other field back too, and that copy goes stale the moment the remediation lane
   * writes to the ticket — answering "which server?" used to revert the ESCALATED status
   * that had just asked the question. The server treats an absent field as "not supplied".
   * Nothing is validated locally beyond trimming: the server rejects a malformed hostname
   * and says so, and having two hostname rules is how they end up disagreeing.
   */
  const saveTarget = async () => {
    if (!selectedIncident) return false;
    setTargetSaving(true);
    try {
      const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          storeNumber: editStoreNumber.trim(),
          targetHost: editTargetHost.trim(),
          connectionMethod: editConnectionMethod,
          targetPlatform: editTargetPlatform
        })
      });
      if (res.ok) {
        setSelectedIncident(await res.json());
        fetchIncidents();
        return true;
      }
      return false;
    } catch (err) {
      console.error('Failed to save remediation target', err);
      return false;
    } finally {
      setTargetSaving(false);
    }
  };

  const clearFilters = () => {    setSubject('');
    setDescription('');
    setAssignee('');
    setAssignedGteam('');
    setPriority('');
    setCreatedDate('');
    setDueDateFilter('');
  };

  // Stats
  const total = incidents.length;
  const p1Count = incidents.filter(i => i.priority === 'P1').length;
  const p2Count = incidents.filter(i => i.priority === 'P2').length;
  const p3Count = incidents.filter(i => i.priority === 'P3').length;

  return (
    <div className="incident-page" style={{ minHeight: 'calc(100vh - 120px)', display: 'flex', flexDirection: 'column' }}>
      {/* Enterprise Incident KPI Header */}
      <div className="kpi-grid" style={{ marginBottom: '12px' }}>
        <div className="kpi-card">
          <div className="kpi-title">TOTAL ACTIVE</div>
          <div className="kpi-value">{total}</div>
        </div>
        <div className="kpi-card kpi-p1">
          <div className="kpi-title">P1 - CRITICAL</div>
          <div className="kpi-value">{p1Count}</div>
        </div>
        <div className="kpi-card kpi-p2">
          <div className="kpi-title">P2 - HIGH</div>
          <div className="kpi-value">{p2Count}</div>
        </div>
        <div className="kpi-card kpi-p3">
          <div className="kpi-title">P3 - MEDIUM</div>
          <div className="kpi-value">{p3Count}</div>
        </div>
      </div>

      {/* Incident Import Panel */}
      <div className="card" style={{ padding: '14px 18px', marginBottom: '12px', background: 'var(--surface-2)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: '12px', flexWrap: 'wrap' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', flexWrap: 'wrap' }}>
          <strong style={{ fontSize: '13px', color: 'var(--text-1)' }}>Import Incidents</strong>
          <select value={importSource} onChange={e => setImportSource(e.target.value)} style={{ height: '34px', fontSize: '12.5px', minWidth: '160px' }}>
            <option value="Freshservice">Freshservice export</option>
            <option value="ServiceNow">ServiceNow export</option>
            <option value="Custom Import">Custom normalized export</option>
          </select>
          <input type="file" accept=".csv,.xlsx" onChange={e => setImportFile(e.target.files?.[0] || null)} style={{ fontSize: '12px', maxWidth: '240px' }} />
          <button className="btn-primary" onClick={handleImport} disabled={importing || !importFile} style={{ height: '34px', padding: '0 16px', fontSize: '12.5px' }}>
            {importing ? 'Importing…' : 'Import Export'}
          </button>
        </div>
        {importMessage && <span style={{ fontSize: '12px', fontWeight: 600, color: importMessage.startsWith('Import finished') ? 'var(--ok)' : 'var(--crit)' }}>{importMessage}</span>}
        <span style={{ fontSize: '11px', color: 'var(--text-3)' }}>Supports CSV & XLSX (Max 500 records)</span>
      </div>

      {/* Advanced Filters structured 2-row bar */}
      <div className="incident-filters-card">
        {/* Row 1: Search, Description, Assignee, Team */}
        <div className="filters-row-1">
          <div className="filter-item">
            <label className="filter-label">Subject Search</label>
            <div style={{ position: 'relative' }}>
              <Search size={12} style={{ position: 'absolute', left: '8px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
              <input type="text" placeholder="Search subject..." value={subject} onChange={e => setSubject(e.target.value)} style={{ paddingLeft: '26px', paddingRight: '10px', height: '34px', fontSize: '12px' }} />
            </div>
          </div>
          <div className="filter-item">
            <label className="filter-label">Description</label>
            <input type="text" placeholder="Contains..." value={description} onChange={e => setDescription(e.target.value)} style={{ height: '34px', padding: '6px 10px', fontSize: '12px' }} />
          </div>
          <div className="filter-item">
            <label className="filter-label">Assignee</label>
            <SearchableSelect
              id="filter-assignee"
              placeholder="Search assignee..."
              allLabel="All Assignees"
              value={assignee}
              onChange={setAssignee}
              options={(
                assignedGteam
                  ? (teams.find(t => t.name === assignedGteam)?.employees || [])
                  : Array.from(new Map(teams.flatMap(t => t.employees).map(emp => [emp.username, emp])).values())
              ).map(emp => ({ value: emp.username, label: emp.username }))}
            />
          </div>
          <div className="filter-item">
            <label className="filter-label">Team</label>
            <SearchableSelect
              id="filter-team"
              placeholder="Search team..."
              allLabel="All Teams"
              value={assignedGteam}
              onChange={(selectedTeam) => {
                setAssignedGteam(selectedTeam);
                if (selectedTeam) {
                  const teamEmployees = teams.find(t => t.name === selectedTeam)?.employees || [];
                  if (!teamEmployees.some(emp => emp.username === assignee)) setAssignee('');
                }
              }}
              options={teams.map(t => ({ value: t.name, label: t.name }))}
            />
          </div>
        </div>

        {/* Row 2: Priority, Created Date, Due Date, Actions */}
        <div className="filters-row-2">
          <div className="filter-item">
            <label className="filter-label">Priority</label>
            <select value={priority} onChange={e => setPriority(e.target.value)} style={{ height: '34px', padding: '6px 10px', fontSize: '12px', cursor: 'pointer' }}>
              <option value="">All Priorities</option>
              <option value="P1">P1 - Critical</option>
              <option value="P2">P2 - High</option>
              <option value="P3">P3 - Medium</option>
            </select>
          </div>
          <div className="filter-item">
            <label className="filter-label">Created Date</label>
            <input type="date" value={createdDate} onChange={e => setCreatedDate(e.target.value)} style={{ height: '34px', padding: '6px 10px', fontSize: '12px' }} />
          </div>
          <div className="filter-item">
            <label className="filter-label">Due Date</label>
            <input type="date" value={dueDateFilter} onChange={e => setDueDateFilter(e.target.value)} style={{ height: '34px', padding: '6px 10px', fontSize: '12px' }} />
          </div>
          <div className="filter-actions">
            <button className="btn-secondary" onClick={clearFilters} style={{ height: '34px', padding: '0 12px', fontSize: '12px' }}>
              Clear
            </button>
            <button className="btn-sync" onClick={handleSync} disabled={syncing} style={{ height: '34px', padding: '0 12px', fontSize: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
              <RefreshCw size={12} className={syncing ? 'spin' : ''} /> {syncing ? 'Syncing' : 'Sync'}
            </button>
            <button className="btn-primary" onClick={() => setShowCreateModal?.(true)} style={{ height: '34px', padding: '0 12px', fontSize: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
              <Plus size={12} /> Create
            </button>
          </div>
        </div>
      </div>


      {/* Directory Split panel view */}
      <div style={{ display: 'grid', gridTemplateColumns: '360px 1fr', gap: '16px', flex: 1, height: 'calc(100% - 180px)', minHeight: '400px' }}>
        
        {/* LEFT DIRECTORY LIST PANEL */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--surface)' }}>
          <div className="card-header" style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <h1 className="content-title" style={{ margin: 0, fontSize: '16px', fontWeight: 800 }}>
              Incidents ({incidents.length})
            </h1>
          </div>

          <div style={{ flex: 1, overflowY: 'auto', padding: '12px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {loading ? (
              <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
                <span className="spinner"></span> Loading Incidents...
              </div>
            ) : incidents.length === 0 ? (
              <div style={{ padding: '20px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
                No incidents match the filters.
              </div>
            ) : (
              incidents.map(inc => (
                <div
                  key={inc.id}
                  onClick={() => setSelectedIncident(inc)}
                  style={{
                    padding: '12px',
                    border: '1px solid var(--border)',
                    borderRadius: '8px',
                    cursor: 'pointer',
                    background: selectedIncident?.id === inc.id ? 'var(--surface3)' : 'var(--surface)',
                    borderLeft: selectedIncident?.id === inc.id ? '4px solid var(--michaels-red)' : '1px solid var(--border)',
                    transition: 'all 0.2s'
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '6px' }}>
                    <span style={{ fontSize: '11px', fontWeight: 'bold', color: 'var(--text-muted)', fontFamily: 'var(--mono)' }}>{inc.externalId}</span>
                    <span className={`priority-badge p-${inc.priority.toLowerCase()}`} style={{ fontSize: '9px', padding: '1px 4px' }}>
                      {inc.priority}
                    </span>
                  </div>
                  <div style={{ fontWeight: 600, fontSize: '13px', color: 'var(--text)', marginBottom: '6px', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {inc.subject}
                  </div>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '11px' }}>
                    <span style={{ color: 'var(--text-dim)' }}>{inc.assignedGteam}</span>
                    <span className={`status-badge status-${inc.status.toLowerCase().replace(' ', '-')}`} style={{ fontSize: '9px', padding: '1px 4px' }}>
                      {inc.status}
                    </span>
                  </div>
                </div>
              ))
            )}
          </div>
        </div>

        {/* RIGHT DETAILS PANEL CONTAINER */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', height: '100%', background: 'var(--surface)' }}>
          {selectedIncident ? (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <div className="card-header" style={{ borderBottom: '1px solid var(--border)', padding: '12px 20px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <h1 className="content-title" style={{ margin: 0, fontSize: '16px', fontWeight: 800 }}>
                  Details: {selectedIncident.externalId}
                </h1>
                <div style={{ display: 'flex', gap: '8px' }}>
                  {detailTab === 'details' && (
                    editMode ? (
                      <button className="btn-primary" onClick={handleUpdateIncident} disabled={updateLoading} style={{ padding: '6px 12px', fontSize: '12px' }}>
                        <Save size={12} /> {updateLoading ? 'Saving...' : 'Save'}
                      </button>
                    ) : (
                      <button className="btn-secondary" onClick={() => setEditMode(true)} style={{ padding: '6px 12px', fontSize: '12px' }}>
                        <Edit size={12} /> Edit Fields
                      </button>
                    )
                  )}
                </div>
              </div>

              <div style={{ padding: '16px 20px', flex: 1, display: 'flex', flexDirection: 'column', overflowY: 'auto' }}>
                {/* Tabs switcher */}
                <div className="tabs-bar" style={{ marginBottom: '16px', borderBottom: '1px solid var(--border)', display: 'flex', gap: '8px' }}>
                  <button 
                    type="button"
                    className={`tab-btn ${detailTab === 'details' ? 'active' : ''}`}
                    onClick={() => setDetailTab('details')}
                    style={{ background: 'transparent', border: 'none', padding: '8px 16px', cursor: 'pointer', borderBottom: detailTab === 'details' ? '2px solid var(--michaels-red)' : 'none', fontWeight: 600, color: detailTab === 'details' ? 'var(--text)' : 'var(--text-muted)' }}
                  >
                    Details
                  </button>
                  <button 
                    type="button"
                    className={`tab-btn ${detailTab === 'history' ? 'active' : ''}`}
                    onClick={() => setDetailTab('history')}
                    style={{ background: 'transparent', border: 'none', padding: '8px 16px', cursor: 'pointer', borderBottom: detailTab === 'history' ? '2px solid var(--michaels-red)' : 'none', fontWeight: 600, color: detailTab === 'history' ? 'var(--text)' : 'var(--text-muted)' }}
                  >
                    History ({historyRecords.length})
                  </button>
                </div>

                {detailTab === 'details' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
                    <form onSubmit={handleUpdateIncident} className="fields-form" style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Ticket ID</label>
                          <input type="text" value={selectedIncident.externalId} disabled style={{ background: 'var(--surface2)', cursor: 'not-allowed', height: '36px', padding: '6px 12px', fontSize: '13px' }} />
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Source Provider</label>
                          <input type="text" value={selectedIncident.externalSource} disabled style={{ background: 'var(--surface2)', cursor: 'not-allowed', height: '36px', padding: '6px 12px', fontSize: '13px' }} />
                        </div>
                      </div>

                      <div>
                        <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Subject</label>
                        {editMode ? (
                          <input type="text" value={editSubject} onChange={e => setEditSubject(e.target.value)} required style={{ height: '36px', padding: '6px 12px', fontSize: '13px' }} />
                        ) : (
                          <div style={{ padding: '8px 12px', background: 'var(--surface2)', borderRadius: '6px', fontWeight: 'bold', fontSize: '13px' }}>{selectedIncident.subject}</div>
                        )}
                      </div>

                      <div>
                        <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Description</label>
                        {editMode ? (
                          <textarea rows={3} value={editDescription} onChange={e => setEditDescription(e.target.value)} style={{ padding: '8px 12px', fontSize: '13px', fontFamily: 'inherit' }} />
                        ) : (
                          <div style={{ padding: '8px 12px', background: 'var(--surface2)', borderRadius: '6px', whiteSpace: 'pre-wrap', minHeight: '60px', fontSize: '13px', lineHeight: '1.4' }}>{selectedIncident.description || 'No description provided.'}</div>
                        )}
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Priority</label>
                          {editMode ? (
                            <select value={editPriority} onChange={e => setEditPriority(e.target.value)} style={{ height: '36px', padding: '6px 10px', fontSize: '13px', cursor: 'pointer' }}>
                              <option value="P1">P1 - Critical</option>
                              <option value="P2">P2 - High</option>
                              <option value="P3">P3 - Medium</option>
                            </select>
                          ) : (
                            <div style={{ padding: '8px 12px', background: 'var(--surface2)', borderRadius: '6px', fontSize: '13px' }}>{selectedIncident.priority}</div>
                          )}
                        </div>

                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Status</label>
                          <select
                            value={editMode ? editStatus : selectedIncident.status}
                            onChange={async (e) => {
                              const newStatus = e.target.value;
                              if (editMode) {
                                setEditStatus(newStatus);
                              } else {
                                try {
                                  const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
                                    method: 'PUT',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ status: newStatus })
                                  });
                                  if (res.ok) {
                                    const updated = await res.json();
                                    setSelectedIncident(updated);
                                    fetchIncidents();
                                  }
                                } catch (err) { console.error(err); }
                              }
                            }}
                            style={{ cursor: 'pointer', height: '36px', padding: '6px 12px', fontSize: '13px' }}
                          >
                            {withCurrent(statuses, editMode ? editStatus : selectedIncident.status)
                              .map(s => <option key={s} value={s}>{s}</option>)}
                          </select>
                        </div>
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Assignee</label>
                          <select
                            value={editMode ? editAssignee : selectedIncident.assignee}
                            onChange={async (e) => {
                              const newAssignee = e.target.value;
                              if (editMode) {
                                setEditAssignee(newAssignee);
                              } else {
                                try {
                                  const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
                                    method: 'PUT',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({ assignee: newAssignee })
                                  });
                                  if (res.ok) {
                                    const updated = await res.json();
                                    setSelectedIncident(updated);
                                    fetchIncidents();
                                  }
                                } catch (err) { console.error(err); }
                              }
                            }}
                            style={{ cursor: 'pointer', height: '36px', padding: '6px 12px', fontSize: '13px' }}
                          >
                            {(() => {
                              const teamEmps = teams.find(t => t.name === (editMode ? editGteam : selectedIncident.assignedGteam))?.employees || [];
                              const list = withCurrent(
                                ['Unassigned', ...teamEmps.map(emp => emp.username)],
                                editMode ? editAssignee : selectedIncident.assignee,
                              );
                              return list.map(uname => {
                                const matched = teamEmps.find(emp => emp.username === uname);
                                const label = matched?.fullName ? `${matched.fullName} (@${uname})` : uname;
                                return <option key={uname} value={uname}>{label}</option>;
                              });
                            })()}
                          </select>
                        </div>

                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Assigned Team</label>
                          <select
                            value={editMode ? editGteam : selectedIncident.assignedGteam}
                            onChange={async (e) => {
                              const teamName = e.target.value;
                              if (editMode) {
                                setEditGteam(teamName);
                                const employees = teams.find(t => t.name === teamName)?.employees || [];
                                setEditAssignee(employees[0]?.username || 'Unassigned');
                              } else {
                                const employees = teams.find(t => t.name === teamName)?.employees || [];
                                try {
                                  const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
                                    method: 'PUT',
                                    headers: { 'Content-Type': 'application/json' },
                                    body: JSON.stringify({
                                      assignedGteam: teamName,
                                      assignee: employees[0]?.username || 'Unassigned'
                                    })
                                  });
                                  if (res.ok) {
                                    const updated = await res.json();
                                    setSelectedIncident(updated);
                                    fetchIncidents();
                                  }
                                } catch (err) { console.error(err); }
                              }
                            }}
                            style={{ cursor: 'pointer', height: '36px', padding: '6px 12px', fontSize: '13px' }}
                          >
                            {withCurrent(teams.map(t => t.name), editMode ? editGteam : selectedIncident.assignedGteam)
                              .map(name => <option key={name} value={name}>{name}</option>)}
                          </select>
                        </div>
                      </div>

                      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '14px' }}>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Created Date</label>
                          <div style={{ padding: '8px 12px', background: 'var(--surface2)', borderRadius: '6px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}><Calendar size={12} /> {new Date(selectedIncident.createdAt).toLocaleString()}</div>
                        </div>
                        <div>
                          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Due Date / SLA</label>
                          <div style={{ padding: '8px 12px', background: 'var(--surface2)', borderRadius: '6px', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}><Clock size={12} /> {selectedIncident.dueDate ? new Date(selectedIncident.dueDate).toLocaleString() : 'N/A'}</div>
                        </div>
                      </div>

                      {/* Where a remediation script would run. Asked here because this is the
                          pane an operator is looking at when a plan comes back BLOCKED for a
                          missing or unreachable server. */}
                      <div style={{
                          background: 'var(--surface2)',
                          border: `1px solid ${selectedIncident.targetHost ? 'var(--border)' : 'var(--amber)'}`,
                          borderRadius: '12px',
                          padding: '16px',
                          marginTop: '10px',
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
                          <h4 style={{ margin: 0, fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            🖥 Remediation target
                          </h4>
                          <button
                            type="button"
                            className="btn-primary"
                            style={{ padding: '4px 8px', fontSize: '11px', height: '28px' }}
                            onClick={saveTarget}
                            disabled={targetSaving}
                          >
                            {targetSaving ? 'Saving...' : 'Save target'}
                          </button>
                        </div>
                        {/* auto-fit, not a fixed 4-up: this panel lives in a drawer whose width
                            varies, and four fixed columns clipped the last one off the edge. */}
                        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))', gap: '10px' }}>
                          <div>
                            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Store</label>
                            <input type="text" placeholder="0042" value={editStoreNumber}
                              onChange={e => setEditStoreNumber(e.target.value)}
                              style={{ height: '32px', padding: '4px 10px', fontSize: '12px' }} />
                          </div>
                          <div>
                            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Server / Host</label>
                            <input type="text" placeholder="store-0042-pos-01" value={editTargetHost}
                              onChange={e => setEditTargetHost(e.target.value)}
                              style={{ height: '32px', padding: '4px 10px', fontSize: '12px' }} />
                          </div>
                          <div>
                            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Connect via</label>
                            <select value={editConnectionMethod} onChange={e => setEditConnectionMethod(e.target.value)}
                              style={{ height: '32px', padding: '4px 8px', fontSize: '12px', cursor: 'pointer' }}>
                              <option value="">Executor default (try first)</option>
                              <option value="SSH">SSH</option>
                              <option value="WINRM">WinRM</option>
                              <option value="AGENT">Local agent</option>
                            </select>
                          </div>
                          {/* The OS decides whether the approved script is PowerShell or bash. Left on
                              auto-detect it comes from the machine's own probe reply; set, it overrules
                              that, and the reviewer is shown that a person overruled it. */}
                          <div>
                            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Operating system</label>
                            <select value={editTargetPlatform} onChange={e => setEditTargetPlatform(e.target.value)}
                              style={{ height: '32px', padding: '4px 8px', fontSize: '12px', cursor: 'pointer' }}>
                              <option value="">Auto-detect (ask the host)</option>
                              <option value="windows">Windows — PowerShell</option>
                              <option value="linux">Linux — bash</option>
                              <option value="darwin">macOS — bash</option>
                            </select>
                          </div>
                        </div>
                        {/* Prefilled-but-unsaved is its own state and has to look like one:
                            the boxes are full, yet a plan would still be blocked, and without
                            this line that reads as the platform ignoring a host it can see. */}
                        {((!selectedIncident.targetHost && !!selectedIncident.detectedTargetHost)
                          || (!selectedIncident.storeNumber && !!selectedIncident.detectedStoreNumber)) && (
                          <div style={{ marginTop: '8px', padding: '7px 9px', borderRadius: '6px', background: 'var(--surface)', border: '1px dashed var(--amber)', fontSize: '11px', lineHeight: 1.45 }}>
                            <strong>Filled in from this ticket.</strong>{' '}
                            We found{' '}
                            {!selectedIncident.targetHost && selectedIncident.detectedTargetHost
                              ? <>the server <code>{selectedIncident.detectedTargetHost}</code></> : null}
                            {!selectedIncident.targetHost && selectedIncident.detectedTargetHost
                              && !selectedIncident.storeNumber && selectedIncident.detectedStoreNumber ? ' and ' : null}
                            {!selectedIncident.storeNumber && selectedIncident.detectedStoreNumber
                              ? <>store <code>{selectedIncident.detectedStoreNumber}</code></> : null}
                            {' '}written in the subject or description. Check it is right, then press <strong>Save target</strong> — nothing uses these values until you do.
                          </div>
                        )}
                        <small style={{ display: 'block', marginTop: '8px', color: 'var(--text-muted)', fontSize: '11px', lineHeight: 1.45 }}>
                          {selectedIncident.targetHost
                            ? 'A mutating plan is refused unless the executor can reach this host. Leave "Connect via" on the default until a plan reports it unreachable — the executor tries the path it already has first.'
                            : 'No server is set. If the description does not name one, a plan that restarts anything will be blocked rather than guess a machine.'}
                          {' '}Leave the OS on auto-detect unless you know better than the host does — the reachability check is also where the machine says what it is, and that is what decides whether the script is PowerShell or bash.
                          {' '}No password or key is stored here or anywhere in this database — only the method. The credential stays with the executor agent.
                        </small>
                      </div>

                      {/* AI Suggestions Card */}
                      <div className="ai-suggestion-card" style={{
                          background: 'var(--surface2)',
                          border: '1px solid var(--border)',
                          borderRadius: '12px',
                          padding: '16px',
                          marginTop: '10px',
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '6px', width: '100%', justifyContent: 'space-between', gap: '10px' }}>
                          <h4 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--amber)', fontSize: '13px' }}>
                            ✨ AI Incident Copilot
                          </h4>
                          <button
                            type="button"
                            className="btn-sync"
                            style={{ padding: '4px 8px', fontSize: '11px', height: '28px', display: 'flex', alignItems: 'center', gap: '6px', opacity: aiLoading ? 0.55 : 1, cursor: aiLoading ? 'progress' : 'pointer' }}
                            onClick={() => getAiSuggestion(selectedIncident.subject, selectedIncident.description)}
                            disabled={aiLoading}
                            aria-busy={aiLoading}
                          >
                            {aiLoading
                              ? <><Loader size={12} className="spin" /> Analysing…</>
                              : aiSuggestion ? 'Regenerate suggestion' : 'Get suggestion'}
                          </button>
                        </div>
                        <p style={{ margin: '0 0 10px', fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: 1.45 }}>
                          Reads this ticket, checks your own approved SOPs for a procedure that covers it, and searches
                          the public web only when your SOPs have nothing for it. It suggests — it never changes anything.
                        </p>

                        {/* An empty card during a slow model call reads as a broken button, so the
                            wait says which of the two searches is happening. */}
                        {aiLoading && (
                          <div style={{ padding: '10px', borderRadius: '6px', background: 'var(--surface)', border: '1px dashed var(--border)', fontSize: '12px', color: 'var(--text-muted)' }}>
                            Looking for a matching procedure in your approved SOPs…
                          </div>
                        )}

                        {aiSuggestion && !aiLoading && (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                            <div>
                              <span style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 600 }}>Best team for this</span>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginTop: '4px' }}>
                                <strong style={{ color: 'var(--green)', fontSize: '13px' }}>{aiSuggestion.suggestedTeam}</strong>
                                {selectedIncident.assignedGteam !== aiSuggestion.suggestedTeam && (
                                  <button 
                                    type="button"
                                    className="btn-primary" 
                                    style={{ padding: '2px 6px', fontSize: '10px', height: '22px' }}
                                    onClick={async () => {
                                      try {
                                        const res = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
                                          method: 'PUT',
                                          headers: { 'Content-Type': 'application/json' },
                                          body: JSON.stringify({
                                            ...selectedIncident,
                                            assignedGteam: aiSuggestion.suggestedTeam
                                          })
                                        });
                                        if (res.ok) {
                                          const updated = await res.json();
                                          setSelectedIncident(updated);
                                          fetchIncidents();
                                        }
                                      } catch (err) {
                                        console.error(err);
                                      }
                                    }}
                                  >
                                    Apply Assignment
                                  </button>
                                )}
                              </div>
                            </div>
                            <div>
                              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
                                <span style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 600 }}>What to try</span>
                                {/* The origin, in the reader's words. This used to be "(Source: RAG
                                    Knowledge Base)" glued onto the end of the answer — a phrase that
                                    means nothing to the person on the service desk and buried the one
                                    thing that decides how much to trust it. */}
                                <span style={{
                                  fontSize: '10px', fontWeight: 700, textTransform: 'uppercase', letterSpacing: '0.3px',
                                  padding: '2px 7px', borderRadius: '999px',
                                  color: SOURCE_TONE[aiSuggestion.source || 'NONE'] || 'var(--text-muted)',
                                  border: `1px solid ${SOURCE_TONE[aiSuggestion.source || 'NONE'] || 'var(--border)'}`,
                                }}>
                                  {aiSuggestion.sourceLabel || 'Suggested'}
                                </span>
                              </div>
                              {aiSuggestion.sourceDetail && (
                                <div style={{ marginTop: '4px', fontSize: '11px', color: 'var(--text-muted)', lineHeight: 1.45 }}>
                                  {aiSuggestion.sourceDetail}
                                </div>
                              )}
                              <div style={{
                                marginTop: '6px',
                                padding: '10px',
                                background: 'var(--surface)',
                                border: '1px solid var(--border)',
                                borderRadius: '6px',
                                fontSize: '12.5px',
                                whiteSpace: 'pre-wrap',
                                lineHeight: '1.4'
                              }}>
                                {aiSuggestion.suggestedResolution}
                              </div>
                            </div>
                          </div>
                        )}
                      </div>

                      {/* Its own card, not a strip glued under the copilot's answer. Nested there
                          it read as part of the suggestion — a fifth paragraph of AI output with a
                          button — when it is the opposite: the deterministic, human-approved lane.
                          Same reason the wording below dropped "nine deterministic guardrails". */}
                      {selectedIncident && (
                        <div style={{
                          background: 'var(--surface2)',
                          border: '1px solid var(--border)',
                          borderRadius: '12px',
                          padding: '16px',
                          marginTop: '10px',
                          display: 'flex',
                          flexDirection: 'column',
                          gap: '10px',
                        }}>
                          <h4 style={{ margin: 0, fontSize: '13px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <ShieldAlert size={14} /> Fix it with approval
                          </h4>
                          <p style={{ margin: 0, fontSize: '12px', color: 'var(--text-muted)', lineHeight: 1.5 }}>
                            This does not touch any server. It writes a proposal — the exact commands, the machine they
                            would run on, and the checks they must pass — and puts it in the approval queue for a person
                            to read. Nothing runs until someone approves it there.
                          </p>
                          <ul style={{ margin: 0, paddingLeft: '18px', fontSize: '11.5px', color: 'var(--text-muted)', lineHeight: 1.6 }}>
                            <li>A matching approved SOP is required. No SOP, no plan.</li>
                            <li>The server must be named and reachable before anything that changes it is allowed.</li>
                            <li>The approved commands are locked; a plan that is edited after approval is refused.</li>
                          </ul>
                          <button
                            type="button"
                            className="btn-primary"
                            style={{ width: 'fit-content', padding: '7px 12px', fontSize: '11px', display: 'flex', alignItems: 'center', gap: '6px' }}
                            disabled={planCreating || isViewer || selectedIncident.status === 'PENDING_APPROVAL'}
                            title={isViewer ? 'Your access is read-only. Ask an analyst or admin to create the plan.' : undefined}
                            onClick={() => void createGuardedPlan()}
                          >
                            {planCreating ? <><Loader size={12} className="spin" /> Checking your SOPs and safety rules…</> : <><ShieldAlert size={12} /> Create plan for approval</>}
                          </button>
                          {isViewer && (
                            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                              Your access is read-only. An analyst or admin can create the plan for this incident.
                            </span>
                          )}
                          {selectedIncident.status === 'PENDING_APPROVAL' && !planCreating && (
                            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>
                              A plan for this incident is already waiting in the approval queue.
                            </span>
                          )}
                          {planOutcome && (
                            <div style={{ padding: '9px 10px', borderRadius: '6px', fontSize: '11.5px', lineHeight: 1.45, whiteSpace: 'pre-wrap', background: planOutcome.route === 'HITL_REQUIRED' ? 'var(--green-dim)' : 'var(--red-dim)', border: `1px solid ${planOutcome.route === 'HITL_REQUIRED' ? 'var(--green)' : 'var(--red)'}` }}>
                              <strong>{planOutcome.route === 'HITL_REQUIRED' ? 'Plan ready for HITL review.' : planOutcome.route === 'ESCALATE' ? 'Plan blocked and escalated.' : 'Plan creation failed.'}</strong><br />
                              {planOutcome.message}
                            </div>
                          )}
                          {/* The agent asked a question it cannot answer itself. Answer it here
                              and re-plan in one click, rather than hunting for the target card
                              further up the page and remembering to press the button twice. */}
                          {planOutcome?.reason?.startsWith('TARGET_') && (
                            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', alignItems: 'flex-end', padding: '9px 10px', borderRadius: '6px', border: '1px dashed var(--border)' }}>
                              <div style={{ flex: '1 1 190px' }}>
                                <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Server / host</label>
                                <input value={editTargetHost} onChange={e => setEditTargetHost(e.target.value)}
                                  placeholder="store-0042-app-01"
                                  style={{ width: '100%', height: '32px', padding: '4px 8px', fontSize: '12px' }} />
                              </div>
                              <div style={{ flex: '0 1 150px' }}>
                                <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Connect via</label>
                                <select value={editConnectionMethod} onChange={e => setEditConnectionMethod(e.target.value)}
                                  style={{ width: '100%', height: '32px', padding: '4px 8px', fontSize: '12px', cursor: 'pointer' }}>
                                  <option value="">Executor default (try first)</option>
                                  <option value="SSH">SSH</option>
                                  <option value="WINRM">WinRM</option>
                                  <option value="AGENT">Local agent</option>
                                </select>
                              </div>
                              <div style={{ flex: '0 1 150px' }}>
                                <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Operating system</label>
                                <select value={editTargetPlatform} onChange={e => setEditTargetPlatform(e.target.value)}
                                  style={{ width: '100%', height: '32px', padding: '4px 8px', fontSize: '12px', cursor: 'pointer' }}>
                                  <option value="">Auto-detect</option>
                                  <option value="windows">Windows</option>
                                  <option value="linux">Linux</option>
                                  <option value="darwin">macOS</option>
                                </select>
                              </div>
                              <button
                                type="button"
                                className="btn-primary"
                                style={{ height: '32px', padding: '0 12px', fontSize: '11px' }}
                                disabled={targetSaving || planCreating || !editTargetHost.trim()}
                                onClick={async () => { if (await saveTarget()) await createGuardedPlan(); }}
                              >
                                {targetSaving || planCreating ? 'Saving…' : 'Save answer and plan again'}
                              </button>
                            </div>
                          )}
                        </div>
                      )}
                    </form>

                    {/* COMMENTS LIST & ADD FORM SHIFTED TO DETAILS PAGE UNDER COPILOT */}
                    <div style={{ borderTop: '1px solid var(--border)', paddingTop: '20px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
                      <h3 style={{ fontSize: '13px', fontWeight: 'bold', color: 'var(--text-dim)', textTransform: 'uppercase', margin: 0 }}>Comments ({comments.length})</h3>
                      <div className="comments-list" style={{ overflowY: 'auto', maxHeight: '380px' }}>
                        {comments.length === 0 ? (
                          <div className="empty-tab-box">No comments recorded on this ticket.</div>
                        ) : (
                          comments.map(c => (
                            <div key={c.id} className="comment-bubble-item" style={{ marginBottom: '12px' }}>
                              <div className="comment-header" style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11px', marginBottom: '4px' }}>
                                <span className="comment-author" style={{ fontWeight: 'bold' }}>{c.author}</span>
                                <span className="comment-date" style={{ color: 'var(--text-muted)' }}>{new Date(c.createdAt).toLocaleString()}</span>
                              </div>
                              <div className="comment-text" style={{ fontSize: '12.5px', color: 'var(--text-dim)' }}>{c.commentText}</div>
                            </div>
                          ))
                        )}
                      </div>

                      <form onSubmit={handleAddComment} className="comment-form" style={{ display: 'flex', gap: '10px', marginTop: '8px' }}>
                        <input
                          type="text"
                          placeholder="Type an internal comment..."
                          value={newComment}
                          onChange={e => setNewComment(e.target.value)}
                          style={{ flex: 1, height: '36px', fontSize: '12px' }}
                        />
                        <button type="submit" className="btn-primary" disabled={commentLoading || !newComment.trim()} style={{ height: '36px', padding: '0 16px', fontSize: '12px' }}>
                          Add
                        </button>
                      </form>
                    </div>
                  </div>
                )}

                {detailTab === 'history' && (
                  <div className="history-tab" style={{ overflowY: 'auto', minHeight: '200px' }}>
                    {historyRecords.length === 0 ? (
                      <div className="empty-tab-box">No incident history found.</div>
                    ) : (
                      <div className="history-timeline">
                        {historyRecords.map(h => (
                          <div key={h.id} className="history-timeline-item">
                            <div className="history-time">{new Date(h.updatedAt).toLocaleString()}</div>
                            <div className="history-details">
                              <span className="updater-name">{h.updatedBy}</span> updated{' '}
                              <strong className="field-tag">{h.fieldName}</strong> from{' '}
                              <span className="old-val">"{h.oldValue || 'null'}"</span> to{' '}
                              <span className="new-val">"{h.newValue}"</span>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', height: '100%', padding: '40px', textAlign: 'center', color: 'var(--text-muted)' }}>
              <ShieldAlert size={48} style={{ opacity: 0.3, marginBottom: '16px' }} />
              <h3>Select an Incident</h3>
              <p style={{ fontSize: '13px', marginTop: '6px', maxWidth: '300px', lineHeight: '1.4' }}>
                Choose an incident from the directory list on the left to view details, notes, AI-assisted suggestions, and automation utilities.
              </p>
            </div>
          )}
        </div>

      </div>

      {/* Creation Modal */}
      {showCreateModal && (
        <div className="modal-backdrop">
          <div className="modal-panel">
            <div className="modal-header">
              <h2>New Incident Record</h2>
              <button className="close-btn" onClick={() => setShowCreateModal?.(false)}>×</button>
            </div>
            <form onSubmit={handleCreate} className="modal-form">
              {errorMsg && <div className="error-alert">{errorMsg}</div>}
              
              <div className="form-field">
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <label>Subject *</label>
                  <button 
                    type="button" 
                    className="btn-sync" 
                    style={{ padding: '2px 8px', fontSize: '11px', height: 'auto', background: 'rgba(255,255,255,0.05)', opacity: modalAiLoading ? 0.55 : 1, cursor: modalAiLoading ? 'progress' : 'pointer' }}
                    onClick={handleModalAiSuggest}
                    disabled={modalAiLoading || !newSubject.trim()}
                    aria-busy={modalAiLoading}
                  >
                    {modalAiLoading ? 'Analysing…' : initialComment ? '✨ Regenerate suggestion' : '✨ Suggest team and first steps'}
                  </button>
                </div>
                <input
                  type="text"
                  placeholder="Short, summary description of the issue"
                  value={newSubject}
                  onChange={e => setNewSubject(e.target.value)}
                  required
                />
              </div>

              <div className="form-field">
                <label>Description</label>
                <textarea
                  rows={4}
                  placeholder="Provide detailed diagnostic steps, errors, or troubleshooting logs..."
                  value={newDescription}
                  onChange={e => setNewDescription(e.target.value)}
                />
              </div>

              <div className="form-field">
                <label>Initial Comment / Suggested Resolution (Optional)</label>
                <textarea
                  rows={3}
                  placeholder="Add a first comment or auto-filled suggestion..."
                  value={initialComment}
                  onChange={e => setInitialComment(e.target.value)}
                  style={{ background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: '6px', color: 'var(--text)', padding: '10px' }}
                />
              </div>

              <div className="form-row">
                <div className="form-field">
                  <label>Priority</label>
                  <select value={newPriority} onChange={e => setNewPriority(e.target.value)}>
                    <option value="P1">P1 - Critical (8 Hours SLA)</option>
                    <option value="P2">P2 - High (24 Hours SLA)</option>
                    <option value="P3">P3 - Medium (72 Hours SLA)</option>
                  </select>
                </div>

                <div className="form-field">
                  <label>Assigned Team</label>
                  <select value={newGteam} onChange={e => {
                    const teamName = e.target.value;
                    setNewGteam(teamName);
                    const employees = teams.find(t => t.name === teamName)?.employees || [];
                    setNewAssignee(employees[0]?.username || 'Unassigned');
                  }}>
                    {teams.map(t => <option key={t.id} value={t.name}>{t.name}</option>)}
                  </select>
                </div>
              </div>

              <div className="form-field">
                <label>Assignee</label>
                <select value={newAssignee} onChange={e => setNewAssignee(e.target.value)}>
                  <option value="Unassigned">Unassigned</option>
                  {(teams.find(t => t.name === newGteam)?.employees || []).map(emp => (
                    <option key={emp.id} value={emp.username}>{emp.username}</option>
                  ))}
                </select>
              </div>

              <div className="form-field">
                <label>Store Number (Optional)</label>
                <input
                  type="text"
                  placeholder="e.g. 0042"
                  value={newStoreNumber}
                  onChange={e => setNewStoreNumber(e.target.value)}
                />
                <small style={{ color: 'var(--text-muted)', fontSize: '11px' }}>
                  Automation is proven per store. Once a fix has been approved and has worked at
                  this store, the same fix can run automatically for the same problem here —
                  and only here.
                </small>
              </div>

              <div className="form-field">
                <label>Server / Host {newTargetHost.trim() ? '' : '(Optional)'}</label>
                <input
                  type="text"
                  placeholder="e.g. store-0042-pos-01"
                  value={newTargetHost}
                  onChange={e => setNewTargetHost(e.target.value)}
                />
                <small style={{ color: 'var(--text-muted)', fontSize: '11px' }}>
                  The machine with the problem. Leave it empty if the description already names
                  one, or if this is not a server issue — nothing can be restarted until a
                  server is named, and nothing will be guessed.
                </small>
              </div>

              <div className="form-field">
                <label>Operating system (Optional)</label>
                <select value={newTargetPlatform} onChange={e => setNewTargetPlatform(e.target.value)}>
                  <option value="">Auto-detect — ask the machine</option>
                  <option value="windows">Windows — PowerShell</option>
                  <option value="linux">Linux — bash</option>
                  <option value="darwin">macOS — bash</option>
                </select>
                <small style={{ color: 'var(--text-muted)', fontSize: '11px' }}>
                  Only answer this if you know it. Left on auto-detect, the machine is asked what
                  it is when it is checked for reachability, and the script is written in that
                  language. Answering here overrules that — useful when the check cannot run, or
                  when you know the detection is wrong.
                </small>
              </div>

              <div className="form-field">
                <label>Reporter Email (Optional)</label>
                <input
                  type="email"
                  placeholder="Only if you are logging this on someone else's behalf"
                  value={newReporterEmail}
                  onChange={e => setNewReporterEmail(e.target.value)}
                />
                <small style={{ color: 'var(--text-muted)', fontSize: '11px' }}>
                  Left empty, updates are emailed to your own address. This person is notified
                  of every update and of any action the platform takes automatically.
                </small>
              </div>

              <div className="modal-footer">
                <button type="button" className="btn-secondary" onClick={() => setShowCreateModal?.(false)}>Cancel</button>
                <button type="submit" className="btn-primary" disabled={createLoading}>
                  {createLoading ? 'Creating...' : 'Create Ticket'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default IncidentManagementPage;
