import React, { useState, useEffect } from 'react';
import './IncidentManagementPage.css';
import { RefreshCw, Search, Calendar, Layers, Download, Server, AlertCircle, CheckCircle2, User, Building, Share2 } from 'lucide-react';
import { authFetch } from '../services/api';

export interface Incident {
  id: string;
  subject: string;
  description: string;
  assignee?: string;
  assignedGteam?: string;
  priority: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  dueDate?: string;
  externalSource?: string;
  externalServiceName?: string;
  externalId?: string;
  storeNumber?: string;
  targetPlatform?: string;
  targetHost?: string;
  connectionMethod?: string;
  detectedTargetHost?: string;
  detectedStoreNumber?: string;
  reporterEmail?: string;
  attachments?: string;
}

export interface Comment {
  id: string;
  incidentId: string;
  author: string;
  commentText: string;
  createdAt: string;
}

/** GET /api/v1/incidents/{id}/graph — see IncidentGraphService. */
export interface IncidentGraph {
  nodes: Array<{ key: string; type: string; label: string }>;
  edges: Array<{ source: string; edge: string; target: string }>;
  truncated: boolean;
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

/** The edge names the view emits, in the words an operator uses. */
const RELATION_WORDS: Record<string, string> = {
  OCCURRED_ON: 'Host',
  AT_STORE: 'Site',
  CLASSIFIED_AS: 'Category',
  PLANNED: 'Remediation planned',
  GROUNDED_IN: 'Approved procedure',
  PRECEDENT: 'Precedent incident',
};

/**
 * The incident's neighbourhood, as a grouped list.
 *
 * ponytail: a list, not a diagram. The question the panel answers — "what is this attached
 * to, and has it happened elsewhere?" — is answered by names, and a force-directed canvas
 * would be a dependency plus a layout to tune for a dozen nodes. Swap in an <svg> if someone
 * asks to see the shape rather than read the names.
 */
function IncidentGraphPanel({ graph, rootKey }: { graph: IncidentGraph | null; rootKey: string }) {
  const empty = (text: string) => (
    <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', background: 'var(--surface2)', borderRadius: '8px', border: '1px dashed var(--border)', fontSize: '13px' }}>
      {text}
    </div>
  );
  if (!graph) return empty('Loading relationships…');
  if (!graph.edges.length) return empty('No mapped relationships yet. A host, a site, an approved procedure or a plan creates them.');

  const label = (key: string) => graph.nodes.find(n => n.key === key)?.label || key;
  const direct = graph.edges.filter(e => e.source === rootKey || e.target === rootKey);
  const indirect = graph.edges.filter(e => e.source !== rootKey && e.target !== rootKey);

  // touchesRoot: one end is this incident, so naming the other end is enough. Otherwise the
  // edge is between two other things and both ends have to be named.
  const group = (edges: IncidentGraph['edges'], touchesRoot: boolean) => {
    const byRelation = new Map<string, string[]>();
    for (const e of edges) {
      const other = e.source === rootKey ? e.target : e.source;
      const text = touchesRoot ? label(other) : `${label(e.source)} → ${label(e.target)}`;
      const bucket = byRelation.get(e.edge) || [];
      if (!bucket.includes(text)) bucket.push(text);
      byRelation.set(e.edge, bucket);
    }
    return [...byRelation.entries()];
  };

  const section = (title: string, edges: IncidentGraph['edges'], touchesRoot: boolean) => edges.length > 0 && (
    <div>
      <h4 style={{ margin: '0 0 8px', fontSize: '11px', textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--text-muted)', fontWeight: 700 }}>{title}</h4>
      <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
        {group(edges, touchesRoot).map(([relation, items]) => (
          <div key={relation} style={{ padding: '10px 14px', borderRadius: '8px', background: 'var(--surface2)', border: '1px solid var(--border)' }}>
            <div style={{ fontSize: '11px', fontWeight: 700, color: 'var(--accent)', marginBottom: '5px' }}>
              {RELATION_WORDS[relation] || relation}
            </div>
            <ul style={{ margin: 0, padding: '0 0 0 16px', fontSize: '12.5px', lineHeight: 1.6 }}>
              {items.map(item => <li key={item}>{item}</li>)}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
      {section('This incident', direct, true)}
      {section('Shared with other incidents', indirect, false)}
      {graph.truncated && (
        <p style={{ margin: 0, fontSize: '11.5px', color: 'var(--warn, #b45309)' }}>
          Showing the first 500 relationships. This incident sits on a busy host or site.
        </p>
      )}
    </div>
  );
}

const IncidentManagementPage: React.FC = () => {

  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [syncFeedback, setSyncFeedback] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Search & Filter state
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [priorityFilter, setPriorityFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');

  // Tab & Details state
  const [detailTab, setDetailTab] = useState<'details' | 'notes' | 'history' | 'graph'>('details');
  const [comments, setComments] = useState<Comment[]>([]);
  const [commentsLoading, setCommentsLoading] = useState(false);
  const [historyRecords, setHistoryRecords] = useState<HistoryRecord[]>([]);
  const [graph, setGraph] = useState<IncidentGraph | null>(null);

  // CSV/Dump Import state
  const [showImportModal, setShowImportModal] = useState(false);
  const [importSource, setImportSource] = useState('ServiceNow');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importing, setImporting] = useState(false);
  const [importMessage, setImportMessage] = useState('');
  const [integrationEnabled, setIntegrationEnabled] = useState(false);

  useEffect(() => {
    fetchIncidents();
    authFetch('/api/v1/integrations/settings')
      .then(res => res.ok ? res.json() : null)
      .then(d => d && setIntegrationEnabled(Boolean(d.integrationEnabled)))
      .catch(() => {});
  }, []);

  useEffect(() => {
    if (selectedIncident) {
      fetchComments(selectedIncident.id);
      fetchHistory(selectedIncident.id);
      fetchGraph(selectedIncident.id);
    }
  }, [selectedIncident]);

  const fetchIncidents = async () => {
    setLoading(true);
    try {
      const res = await authFetch('/api/v1/incidents');
      if (res.ok) {
        const data: Incident[] = await res.json();
        setIncidents(data);
        if (data.length > 0 && !selectedIncident) {
          setSelectedIncident(data[0]);
        }
      }
    } catch (err) {
      console.error('Failed to fetch incidents:', err);
    } finally {
      setLoading(false);
    }
  };

  const fetchComments = async (id: string) => {
    setCommentsLoading(true);
    try {
      const res = await authFetch(`/api/v1/incidents/${id}/comments`);
      if (res.ok) {
        const data = await res.json();
        setComments(data);
      }
    } catch (err) {
      console.error('Failed to fetch comments:', err);
    } finally {
      setCommentsLoading(false);
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
      console.error('Failed to fetch history:', err);
    }
  };

  const fetchGraph = async (id: string) => {
    setGraph(null);
    try {
      const res = await authFetch(`/api/v1/incidents/${id}/graph`);
      if (res.ok) setGraph(await res.json());
    } catch (err) {
      console.error('Failed to fetch incident graph:', err);
    }
  };

  const handleSyncIntegrations = async () => {
    setSyncing(true);
    setSyncFeedback(null);
    try {
      const res = await authFetch('/api/v1/integrations/sync', { method: 'POST' });
      const data = await res.json();
      if (data.status === 'SUCCESS') {
        setSyncFeedback({
          type: 'success',
          text: `Sync successful: ${data.totalSynced ?? 0} incidents retrieved from active ITSM sources.`,
        });
        await fetchIncidents();
      } else {
        setSyncFeedback({
          type: 'error',
          text: data.error || 'Sync encountered errors. Check integration settings.',
        });
      }
    } catch (err) {
      setSyncFeedback({ type: 'error', text: 'Network failure during integration sync.' });
    } finally {
      setSyncing(false);
    }
  };

  const handleFileImport = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!importFile) return;
    setImporting(true);
    setImportMessage('');
    const formData = new FormData();
    formData.append('file', importFile);
    try {
      const res = await authFetch(`/api/v1/intake/incidents/import?sourceSystem=${encodeURIComponent(importSource)}`, {
        method: 'POST',
        body: formData,
      });
      if (res.ok) {
        const data = await res.json();
        setImportMessage(`Successfully ingested ${data.totalImported || 0} incidents (Duplicates skipped: ${data.skippedDuplicates || 0}).`);
        setImportFile(null);
        await fetchIncidents();
        setTimeout(() => setShowImportModal(false), 2000);
      } else {
        const err = await res.json();
        setImportMessage(err.error || 'Import failed. Check file format.');
      }
    } catch (err) {
      setImportMessage('Network error during file upload.');
    } finally {
      setImporting(false);
    }
  };

  const handleDownloadAttachment = async (incidentId: string) => {
    try {
      const res = await authFetch(`/api/v1/integrations/incidents/${incidentId}/attachments/default`);
      if (res.ok) {
        const blob = await res.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `incident-${selectedIncident?.externalId || incidentId}-attachment.log`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
      } else {
        alert('Attachment could not be downloaded.');
      }
    } catch (err) {
      console.error('Attachment download failed:', err);
    }
  };

  const filteredIncidents = incidents.filter(inc => {
    const q = searchQuery.toLowerCase().trim();
    const matchesQ = !q ||
      inc.subject.toLowerCase().includes(q) ||
      (inc.description && inc.description.toLowerCase().includes(q)) ||
      (inc.externalId && inc.externalId.toLowerCase().includes(q)) ||
      (inc.targetHost && inc.targetHost.toLowerCase().includes(q));
    const matchesStatus = !statusFilter || inc.status.toLowerCase() === statusFilter.toLowerCase();
    const matchesPriority = !priorityFilter || inc.priority.toLowerCase() === priorityFilter.toLowerCase();
    const serviceName = inc.externalServiceName || inc.externalSource || '';
    const matchesSource = !sourceFilter || serviceName.toLowerCase() === sourceFilter.toLowerCase();
    return matchesQ && matchesStatus && matchesPriority && matchesSource;
  });

  return (
    <div className="incident-page-root" style={{ height: 'calc(100vh - 120px)', display: 'flex', flexDirection: 'column' }}>
      {/* TOP CONTROLS & HEADER */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px', flexWrap: 'wrap', gap: '10px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div style={{ position: 'relative', width: '280px' }}>
            <Search size={14} style={{ position: 'absolute', left: '10px', top: '10px', color: 'var(--text-muted)' }} />
            <input
              type="text"
              placeholder="Filter incidents by ticket, host, text…"
              value={searchQuery}
              onChange={e => setSearchQuery(e.target.value)}
              style={{ width: '100%', height: '34px', paddingLeft: '32px', paddingRight: '10px', fontSize: '12.5px', borderRadius: '6px' }}
            />
          </div>
          <select
            value={statusFilter}
            onChange={e => setStatusFilter(e.target.value)}
            style={{ height: '34px', padding: '0 10px', fontSize: '12.5px', borderRadius: '6px' }}
          >
            <option value="">All Statuses</option>
            <option value="New">New</option>
            <option value="In Progress">In Progress</option>
            <option value="PENDING_ANALYSIS">Pending Analysis</option>
            <option value="RESOLVED">Resolved</option>
            <option value="CLOSED">Closed</option>
          </select>
          <select
            value={priorityFilter}
            onChange={e => setPriorityFilter(e.target.value)}
            style={{ height: '34px', padding: '0 10px', fontSize: '12.5px', borderRadius: '6px' }}
          >
            <option value="">All Priorities</option>
            <option value="P1">P1 - Critical</option>
            <option value="P2">P2 - High</option>
            <option value="P3">P3 - Medium</option>
            <option value="P4">P4 - Low</option>
          </select>
          <select
            value={sourceFilter}
            onChange={e => setSourceFilter(e.target.value)}
            style={{ height: '34px', padding: '0 10px', fontSize: '12.5px', borderRadius: '6px' }}
          >
            <option value="">All Sources</option>
            <option value="ServiceNow">ServiceNow</option>
            <option value="Freshservice">Freshservice</option>
            <option value="Jira">Jira</option>
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          {integrationEnabled && (
            <button
              type="button"
              className="btn-sync"
              onClick={handleSyncIntegrations}
              disabled={syncing}
              style={{ display: 'flex', alignItems: 'center', gap: '6px', height: '34px', padding: '0 12px', fontSize: '12.5px' }}
            >
              <RefreshCw size={13} className={syncing ? 'spin' : ''} />
              {syncing ? 'Syncing ITSM…' : 'Sync ITSM Feeds'}
            </button>
          )}
          <button
            type="button"
            className="btn-secondary"
            onClick={() => setShowImportModal(true)}
            style={{ display: 'flex', alignItems: 'center', gap: '6px', height: '34px', padding: '0 12px', fontSize: '12.5px' }}
          >
            <Layers size={13} />
            Upload Dump File
          </button>
        </div>
      </div>

      {syncFeedback && (
        <div style={{
          padding: '8px 12px',
          borderRadius: '6px',
          marginBottom: '10px',
          fontSize: '12.5px',
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          background: syncFeedback.type === 'success' ? 'var(--green-dim, rgba(34,197,94,0.15))' : 'var(--red-dim, rgba(239,68,68,0.15))',
          color: syncFeedback.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)',
          border: `1px solid ${syncFeedback.type === 'success' ? 'var(--green, #22c55e)' : 'var(--red, #ef4444)'}`
        }}>
          {syncFeedback.type === 'success' ? <CheckCircle2 size={15} /> : <AlertCircle size={15} />}
          {syncFeedback.text}
        </div>
      )}

      {/* SPLIT MASTER-DETAIL LAYOUT */}
      <div style={{ display: 'grid', gridTemplateColumns: '380px 1fr', gap: '16px', flex: 1, minHeight: 0 }}>
        {/* LEFT MASTER LIST */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', padding: 0, borderRadius: '10px' }}>
          <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--border)', background: 'var(--surface2)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <span style={{ fontSize: '12px', fontWeight: 700, textTransform: 'uppercase', color: 'var(--text-muted)' }}>
              Open Incidents ({filteredIncidents.length})
            </span>
            <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Read-Only Feed</span>
          </div>

          <div style={{ flex: 1, overflowY: 'auto', padding: '6px' }}>
            {loading ? (
              <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>Loading incidents…</div>
            ) : filteredIncidents.length === 0 ? (
              <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>No matching incidents found.</div>
            ) : (
              filteredIncidents.map(inc => {
                const isSelected = selectedIncident?.id === inc.id;
                const service = inc.externalServiceName || inc.externalSource || 'ITSM';
                return (
                  <div
                    key={inc.id}
                    onClick={() => setSelectedIncident(inc)}
                    style={{
                      padding: '12px 14px',
                      borderRadius: '8px',
                      marginBottom: '4px',
                      cursor: 'pointer',
                      background: isSelected ? 'var(--surface2, #1e293b)' : 'transparent',
                      border: `1px solid ${isSelected ? 'var(--accent, #3b82f6)' : 'transparent'}`,
                      transition: 'all 0.15s ease',
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <span style={{ fontWeight: 700, fontSize: '12.5px', color: isSelected ? 'var(--accent)' : 'var(--text)' }}>
                          {inc.externalId || inc.id.substring(0, 8)}
                        </span>
                        <span style={{ fontSize: '10px', padding: '1px 5px', borderRadius: '3px', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-muted)' }}>
                          {service}
                        </span>
                      </div>
                      <span className={`priority-badge ${inc.priority.toLowerCase()}`} style={{ fontSize: '10px', padding: '2px 6px' }}>
                        {inc.priority}
                      </span>
                    </div>

                    <div style={{ fontSize: '13px', fontWeight: 600, color: 'var(--text)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', marginBottom: '4px' }}>
                      {inc.subject}
                    </div>

                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '11px', color: 'var(--text-muted)' }}>
                      <span>Status: <strong style={{ color: 'var(--text)' }}>{inc.status}</strong></span>
                      <span>{new Date(inc.updatedAt || inc.createdAt).toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}</span>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </div>

        {/* RIGHT DETAIL PANE (STRICTLY READ-ONLY, NO AI CARDS, SHOW NOTES) */}
        <div className="card" style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', padding: 0, borderRadius: '10px' }}>
          {selectedIncident ? (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              {/* DETAIL HEADER */}
              <div style={{ padding: '16px 20px', borderBottom: '1px solid var(--border)', background: 'var(--surface2)', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '12px' }}>
                <div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '6px' }}>
                    <span style={{ fontSize: '14px', fontWeight: 800, color: 'var(--accent)' }}>
                      {selectedIncident.externalId || selectedIncident.id}
                    </span>
                    <span style={{ fontSize: '11px', fontWeight: 700, padding: '2px 8px', borderRadius: '4px', background: 'var(--surface)', border: '1px solid var(--border)' }}>
                      Source: {selectedIncident.externalServiceName || selectedIncident.externalSource || 'ServiceNow'}
                    </span>
                    <span className={`priority-badge ${selectedIncident.priority.toLowerCase()}`}>
                      {selectedIncident.priority}
                    </span>
                    <span style={{ fontSize: '11.5px', padding: '2px 8px', borderRadius: '4px', background: 'var(--surface)', border: '1px solid var(--border)', color: 'var(--text-muted)' }}>
                      Status: <strong style={{ color: 'var(--text)' }}>{selectedIncident.status}</strong>
                    </span>
                  </div>
                  <h2 style={{ margin: 0, fontSize: '16px', fontWeight: 700, color: 'var(--text)' }}>
                    {selectedIncident.subject}
                  </h2>
                </div>

                <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                  <button
                    type="button"
                    className="btn-secondary"
                    onClick={() => handleDownloadAttachment(selectedIncident.id)}
                    title="Download attached diagnostic logs from external ITSM"
                    style={{ display: 'flex', alignItems: 'center', gap: '5px', height: '32px', padding: '0 10px', fontSize: '12px' }}
                  >
                    <Download size={13} />
                    Download Logs
                  </button>
                </div>
              </div>

              {/* TABS HEADER */}
              <div style={{ display: 'flex', borderBottom: '1px solid var(--border)', background: 'var(--surface)', padding: '0 20px' }}>
                <button
                  type="button"
                  onClick={() => setDetailTab('details')}
                  style={{
                    padding: '10px 16px',
                    fontSize: '13px',
                    fontWeight: 600,
                    border: 'none',
                    background: 'transparent',
                    borderBottom: `2px solid ${detailTab === 'details' ? 'var(--accent)' : 'transparent'}`,
                    color: detailTab === 'details' ? 'var(--accent)' : 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  Incident Overview
                </button>
                <button
                  type="button"
                  onClick={() => setDetailTab('notes')}
                  style={{
                    padding: '10px 16px',
                    fontSize: '13px',
                    fontWeight: 600,
                    border: 'none',
                    background: 'transparent',
                    borderBottom: `2px solid ${detailTab === 'notes' ? 'var(--accent)' : 'transparent'}`,
                    color: detailTab === 'notes' ? 'var(--accent)' : 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  Work Notes & Comments ({comments.length})
                </button>
                <button
                  type="button"
                  onClick={() => setDetailTab('history')}
                  style={{
                    padding: '10px 16px',
                    fontSize: '13px',
                    fontWeight: 600,
                    border: 'none',
                    background: 'transparent',
                    borderBottom: `2px solid ${detailTab === 'history' ? 'var(--accent)' : 'transparent'}`,
                    color: detailTab === 'history' ? 'var(--accent)' : 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  Audit History
                </button>
                <button
                  type="button"
                  onClick={() => setDetailTab('graph')}
                  style={{
                    padding: '10px 16px',
                    fontSize: '13px',
                    fontWeight: 600,
                    border: 'none',
                    background: 'transparent',
                    borderBottom: `2px solid ${detailTab === 'graph' ? 'var(--accent)' : 'transparent'}`,
                    color: detailTab === 'graph' ? 'var(--accent)' : 'var(--text-muted)',
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '6px',
                  }}
                >
                  <Share2 size={13} /> Relationships
                </button>
              </div>

              {/* TAB CONTENTS */}
              <div style={{ flex: 1, overflowY: 'auto', padding: '20px' }}>
                {detailTab === 'details' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
                    {/* METADATA GRID */}
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '12px' }}>
                      <div style={{ padding: '10px 12px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                        <span style={{ fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 700, display: 'block', marginBottom: '3px' }}>Assignee</span>
                        <div style={{ fontSize: '13px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <User size={14} /> {selectedIncident.assignee || 'Unassigned'}
                        </div>
                      </div>
                      <div style={{ padding: '10px 12px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                        <span style={{ fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 700, display: 'block', marginBottom: '3px' }}>Assigned Team</span>
                        <div style={{ fontSize: '13px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <Building size={14} /> {selectedIncident.assignedGteam || 'IT Ops'}
                        </div>
                      </div>
                      <div style={{ padding: '10px 12px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                        <span style={{ fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 700, display: 'block', marginBottom: '3px' }}>Created</span>
                        <div style={{ fontSize: '12.5px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <Calendar size={13} /> {new Date(selectedIncident.createdAt).toLocaleString()}
                        </div>
                      </div>
                      <div style={{ padding: '10px 12px', background: 'var(--surface2)', borderRadius: '8px', border: '1px solid var(--border)' }}>
                        <span style={{ fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 700, display: 'block', marginBottom: '3px' }}>Target Infrastructure</span>
                        <div style={{ fontSize: '12.5px', fontWeight: 600, display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <Server size={13} /> {selectedIncident.targetHost || selectedIncident.detectedTargetHost || 'Auto-extracted'}
                          {selectedIncident.storeNumber && <span style={{ color: 'var(--text-muted)', fontSize: '11px' }}>(Store #{selectedIncident.storeNumber})</span>}
                        </div>
                      </div>
                    </div>

                    {/* DESCRIPTION CARD */}
                    <div style={{ padding: '16px', borderRadius: '8px', background: 'var(--surface2)', border: '1px solid var(--border)' }}>
                      <span style={{ fontSize: '11px', textTransform: 'uppercase', color: 'var(--text-muted)', fontWeight: 700, display: 'block', marginBottom: '8px' }}>
                        Incident Description & Technical Details
                      </span>
                      <div style={{ fontSize: '13px', lineHeight: 1.6, color: 'var(--text)', whiteSpace: 'pre-wrap' }}>
                        {selectedIncident.description || 'No additional technical description provided in the ticket.'}
                      </div>
                    </div>
                  </div>
                )}

                {detailTab === 'notes' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-muted)', textTransform: 'uppercase' }}>
                        Work Notes from External Incident Feed
                      </span>
                      <span style={{ fontSize: '11.5px', color: 'var(--text-muted)' }}>
                        Read-only work log
                      </span>
                    </div>

                    {commentsLoading ? (
                      <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)' }}>Loading notes…</div>
                    ) : comments.length === 0 ? (
                      <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', background: 'var(--surface2)', borderRadius: '8px', border: '1px dashed var(--border)', fontSize: '13px' }}>
                        No work notes or comments logged for this incident yet.
                      </div>
                    ) : (
                      comments.map(c => (
                        <div key={c.id} style={{ padding: '12px 14px', borderRadius: '8px', background: 'var(--surface2)', border: '1px solid var(--border)' }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '11.5px', marginBottom: '6px' }}>
                            <strong style={{ color: 'var(--accent)' }}>{c.author}</strong>
                            <span style={{ color: 'var(--text-muted)' }}>{new Date(c.createdAt).toLocaleString()}</span>
                          </div>
                          <div style={{ fontSize: '13px', color: 'var(--text)', lineHeight: 1.5, whiteSpace: 'pre-wrap' }}>
                            {c.commentText}
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}

                {detailTab === 'history' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                    {historyRecords.length === 0 ? (
                      <div style={{ padding: '30px', textAlign: 'center', color: 'var(--text-muted)', background: 'var(--surface2)', borderRadius: '8px', border: '1px dashed var(--border)', fontSize: '13px' }}>
                        No audit history modifications recorded.
                      </div>
                    ) : (
                      historyRecords.map(h => (
                        <div key={h.id} style={{ padding: '10px 14px', borderRadius: '8px', background: 'var(--surface2)', border: '1px solid var(--border)', fontSize: '12.5px' }}>
                          <div style={{ color: 'var(--text-muted)', fontSize: '11px', marginBottom: '4px' }}>
                            {new Date(h.updatedAt).toLocaleString()}
                          </div>
                          <div>
                            <strong>{h.updatedBy}</strong> modified <span style={{ color: 'var(--accent)' }}>{h.fieldName}</span>: "{h.oldValue || 'none'}" → "<strong>{h.newValue}</strong>"
                          </div>
                        </div>
                      ))
                    )}
                  </div>
                )}

                {detailTab === 'graph' && (
                  <IncidentGraphPanel graph={graph} rootKey={`INCIDENT:${selectedIncident.id}`} />
                )}
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', alignItems: 'center', height: '100%', color: 'var(--text-muted)', textAlign: 'center', padding: '30px' }}>
              <Layers size={42} style={{ opacity: 0.3, marginBottom: '12px' }} />
              <h3 style={{ margin: 0, fontSize: '15px', color: 'var(--text)' }}>Select an incident</h3>
              <p style={{ margin: '4px 0 0', fontSize: '12.5px' }}>Choose a ticket from the left list to review read-only operational context and notes.</p>
            </div>
          )}
        </div>
      </div>

      {/* DUMP IMPORT MODAL */}
      {showImportModal && (
        <div className="modal-backdrop" style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000 }}>
          <div className="card" style={{ width: '480px', padding: '24px', borderRadius: '12px', background: 'var(--surface, #0f172a)' }}>
            <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 700, marginBottom: '14px' }}>
              Import Incident Dump
            </h3>
            <p style={{ fontSize: '13px', color: 'var(--text-muted)', marginBottom: '16px', lineHeight: 1.45 }}>
              Upload ServiceNow, Freshservice, or Jira ticket dumps (CSV or JSON). Duplicate ticket IDs will be automatically skipped.
            </p>

            <form onSubmit={handleFileImport} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Source Format</label>
                <select
                  value={importSource}
                  onChange={e => setImportSource(e.target.value)}
                  style={{ width: '100%', height: '36px', padding: '0 8px', fontSize: '13px', borderRadius: '6px' }}
                >
                  <option value="ServiceNow">ServiceNow (Incidents Export)</option>
                  <option value="Freshservice">Freshservice (Tickets Export)</option>
                  <option value="Jira">Jira (Issues Export)</option>
                  <option value="Custom Import">Generic ITSM CSV</option>
                </select>
              </div>

              <div>
                <label style={{ display: 'block', fontSize: '11px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '4px' }}>Select File</label>
                <input
                  type="file"
                  accept=".csv,.json,.txt"
                  onChange={e => setImportFile(e.target.files?.[0] || null)}
                  style={{ width: '100%', fontSize: '12.5px' }}
                />
              </div>

              {importMessage && (
                <div style={{ padding: '8px 12px', borderRadius: '6px', fontSize: '12.5px', background: 'var(--surface2)', border: '1px solid var(--border)' }}>
                  {importMessage}
                </div>
              )}

              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: '8px', marginTop: '10px' }}>
                <button
                  type="button"
                  className="btn-secondary"
                  onClick={() => setShowImportModal(false)}
                  style={{ height: '34px', padding: '0 14px', fontSize: '12.5px' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={importing || !importFile}
                  style={{ height: '34px', padding: '0 16px', fontSize: '12.5px' }}
                >
                  {importing ? 'Importing…' : 'Start Import'}
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
