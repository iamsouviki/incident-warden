import React, { useState, useEffect } from 'react';
import './IncidentManagementPage.css';
import { Plus, RefreshCw, Search, Calendar, User, ShieldAlert, Clock, MessageSquare, History, Edit, Save, Wrench, Play, Loader } from 'lucide-react';
import { authFetch, getStoredUser } from '../services/api';

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
  email: string;
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

const IncidentManagementPage: React.FC<Props> = ({ showCreateModal = false, setShowCreateModal }) => {
  const currentUser = getStoredUser();
  const currentUsername = currentUser?.username || 'User';

  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [selectedIncident, setSelectedIncident] = useState<Incident | null>(null);
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  
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
  const [updateLoading, setUpdateLoading] = useState(false);

  // Create Incident Form state
  const [newSubject, setNewSubject] = useState('');
  const [newDescription, setNewDescription] = useState('');
  const [newPriority, setNewPriority] = useState('P3');
  const [newAssignee, setNewAssignee] = useState('Unassigned');
  const [newGteam, setNewGteam] = useState('IT Ops');
  const [createLoading, setCreateLoading] = useState(false);
  const [initialComment, setInitialComment] = useState('');
  const [errorMsg, setErrorMsg] = useState('');

  // Teams & AI Suggestion states
  const [teams, setTeams] = useState<Team[]>([]);
  const [statuses, setStatuses] = useState<string[]>(['New', 'In Progress', 'Resolved', 'Closed']);
  const [showAddStatusInput, setShowAddStatusInput] = useState(false);
  const [newStatusName, setNewStatusName] = useState('');
  const [aiSuggestion, setAiSuggestion] = useState<{ suggestedTeam?: string; suggestedResolution?: string } | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [modalAiLoading, setModalAiLoading] = useState(false);

  // Tool matching & creation
  const [matchingTools, setMatchingTools] = useState<{id: string; name: string; language: string; category: string}[]>([]);
  const [toolSearchLoading, setToolSearchLoading] = useState(false);
  const [toolRunOutput, setToolRunOutput] = useState<{stdout: string; stderr: string; exitCode: number} | null>(null);
  const [toolRunning, setToolRunning] = useState<string | null>(null);

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
    setMatchingTools([]);
    setToolRunOutput(null);
    try {
      const res = await authFetch('/api/v1/incidents/analyze', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ subject: sub, description: desc }),
      });
      if (res.ok) {
        const data = await res.json();
        setAiSuggestion(data);
      }

      // Search for matching tools/scripts
      setToolSearchLoading(true);
      try {
        const user = getStoredUser();
        const toolRes = await authFetch(`/api/v1/scripts?tenantId=${user?.tenantId || 'tenant-1'}`);
        if (toolRes.ok) {
          const toolData = await toolRes.json();
          const scripts = toolData.scripts || [];
          const subLower = sub.toLowerCase();
          const matched = scripts.filter((s: any) =>
            s.name?.toLowerCase().includes('tool:') &&
            (s.name?.toLowerCase().includes(subLower.substring(0, 20)) ||
             s.description?.toLowerCase().includes(subLower.substring(0, 20)))
          ).map((s: any) => ({
            id: s.id,
            name: s.name,
            language: s.language || 'bash',
            category: s.category || 'APPLICATION'
          }));
          setMatchingTools(matched);
        }
      } catch (err) {
        console.error('Failed to search tools', err);
      } finally {
        setToolSearchLoading(false);
      }
    } catch (err) {
      console.error('Failed to get AI suggestion', err);
    } finally {
      setAiLoading(false);
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
          setInitialComment(`AI Suggested Resolution:\n${data.suggestedResolution}`);
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
      setEditMode(false);
      setAiSuggestion(null);
      setMatchingTools([]);
      setToolRunOutput(null);

      // Auto-trigger AI suggestion
      getAiSuggestion(selectedIncident.subject, selectedIncident.description);
    }
  }, [selectedIncident]);

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

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newSubject.trim()) {
      setErrorMsg('Subject is required');
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

  const clearFilters = () => {
    setSubject('');
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
    <div className="incident-page" style={{ height: 'calc(100vh - 120px)', display: 'flex', flexDirection: 'column' }}>
      {/* ServiceNow / Freshservice Inspired Dashboard KPI Header */}
      <div className="kpi-grid" style={{ marginBottom: '16px' }}>
        <div className="kpi-card">
          <div className="kpi-title">TOTAL INCIDENTS</div>
          <div className="kpi-value">{total}</div>
        </div>
        <div className="kpi-card kpi-p1">
          <div className="kpi-title">P1 - CRITICAL (8H Due)</div>
          <div className="kpi-value">{p1Count}</div>
        </div>
        <div className="kpi-card kpi-p2">
          <div className="kpi-title">P2 - HIGH (24H Due)</div>
          <div className="kpi-value">{p2Count}</div>
        </div>
        <div className="kpi-card kpi-p3">
          <div className="kpi-title">P3 - MEDIUM (72H Due)</div>
          <div className="kpi-value">{p3Count}</div>
        </div>
      </div>

      {/* Advanced Filters topbar row */}
      <div className="card" style={{ padding: '12px 16px', marginBottom: '16px', background: 'var(--surface2)', display: 'grid', gridTemplateColumns: 'repeat(7, 1fr) auto', gap: '10px', alignItems: 'end', flexShrink: 0 }}>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Subject Search</label>
          <div style={{ position: 'relative' }}>
            <Search size={12} style={{ position: 'absolute', left: '8px', top: '50%', transform: 'translateY(-50%)', color: 'var(--text-muted)' }} />
            <input type="text" placeholder="Search subject..." value={subject} onChange={e => setSubject(e.target.value)} style={{ paddingLeft: '26px', paddingRight: '10px', height: '36px', fontSize: '12px' }} />
          </div>
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Description</label>
          <input type="text" placeholder="Contains..." value={description} onChange={e => setDescription(e.target.value)} style={{ height: '36px', padding: '6px 10px', fontSize: '12px' }} />
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Assignee</label>
          <select
            value={assignee}
            onChange={e => setAssignee(e.target.value)}
            style={{ height: '36px', padding: '6px 10px', fontSize: '12px', cursor: 'pointer' }}
          >
            <option value="">All Assignees</option>
            {assignedGteam ? (
              (teams.find(t => t.name === assignedGteam)?.employees || []).map(emp => (
                <option key={emp.id} value={emp.username}>{emp.username}</option>
              ))
            ) : (
              Array.from(new Map(teams.flatMap(t => t.employees).map(emp => [emp.username, emp])).values()).map(emp => (
                <option key={emp.id} value={emp.username}>{emp.username}</option>
              ))
            )}
          </select>
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Team</label>
          <select
            value={assignedGteam}
            onChange={e => {
              const selectedTeam = e.target.value;
              setAssignedGteam(selectedTeam);
              if (selectedTeam) {
                const teamEmployees = teams.find(t => t.name === selectedTeam)?.employees || [];
                const isAssigneeInTeam = teamEmployees.some(emp => emp.username === assignee);
                if (!isAssigneeInTeam) {
                  setAssignee('');
                }
              }
            }}
            style={{ height: '36px', padding: '6px 10px', fontSize: '12px', cursor: 'pointer' }}
          >
            <option value="">All Teams</option>
            {teams.map(t => <option key={t.id} value={t.name}>{t.name}</option>)}
          </select>
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Priority</label>
          <select value={priority} onChange={e => setPriority(e.target.value)} style={{ height: '36px', padding: '6px 10px', fontSize: '12px', cursor: 'pointer' }}>
            <option value="">All Priorities</option>
            <option value="P1">P1 - Critical</option>
            <option value="P2">P2 - High</option>
            <option value="P3">P3 - Medium</option>
          </select>
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Created Date</label>
          <input type="date" value={createdDate} onChange={e => setCreatedDate(e.target.value)} style={{ height: '36px', padding: '6px 10px', fontSize: '12px' }} />
        </div>
        <div>
          <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Due Date</label>
          <input type="date" value={dueDateFilter} onChange={e => setDueDateFilter(e.target.value)} style={{ height: '36px', padding: '6px 10px', fontSize: '12px' }} />
        </div>
        <div style={{ display: 'flex', gap: '6px', minWidth: '220px' }}>
          <button className="btn-secondary" onClick={clearFilters} style={{ height: '36px', padding: '0 12px', fontSize: '12px', flex: 1 }}>
            Clear
          </button>
          <button className="btn-sync" onClick={handleSync} disabled={syncing} style={{ height: '36px', padding: '0 12px', fontSize: '12px', flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
            <RefreshCw size={12} className={syncing ? 'spin' : ''} /> {syncing ? 'Syncing' : 'Sync'}
          </button>
          <button className="btn-primary" onClick={() => setShowCreateModal?.(true)} style={{ height: '36px', padding: '0 12px', fontSize: '12px', flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', gap: '4px' }}>
            <Plus size={12} /> Create
          </button>
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
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                            <label style={{ display: 'block', fontSize: '10px', fontWeight: 'bold', color: 'var(--text-muted)', textTransform: 'uppercase', marginBottom: '4px' }}>Status</label>
                            <button
                              type="button"
                              onClick={() => {
                                setShowAddStatusInput(!showAddStatusInput);
                                setNewStatusName('');
                              }}
                              style={{
                                background: 'transparent',
                                border: 'none',
                                color: 'var(--michaels-red)',
                                fontSize: '10px',
                                fontWeight: 700,
                                cursor: 'pointer',
                                padding: '0'
                              }}
                            >
                              {showAddStatusInput ? 'Cancel' : '+ Add Status'}
                            </button>
                          </div>

                          {showAddStatusInput ? (
                            <div style={{ display: 'flex', gap: '6px' }}>
                              <input
                                type="text"
                                value={newStatusName}
                                onChange={e => setNewStatusName(e.target.value)}
                                placeholder="New status..."
                                style={{ padding: '4px 8px', fontSize: '11px', height: '32px' }}
                              />
                              <button
                                type="button"
                                onClick={async () => {
                                  if (!newStatusName.trim()) return;
                                  try {
                                    const res = await authFetch('/api/v1/statuses', {
                                      method: 'POST',
                                      headers: { 'Content-Type': 'application/json' },
                                      body: JSON.stringify({ name: newStatusName.trim() })
                                    });
                                    if (res.ok) {
                                      const saved = await res.json();
                                      const updatedStatuses = [...statuses, saved.name];
                                      setStatuses(updatedStatuses);
                                      setNewStatusName('');
                                      setShowAddStatusInput(false);

                                      if (editMode) {
                                        setEditStatus(saved.name);
                                      } else {
                                        const updateRes = await authFetch(`/api/v1/incidents/${selectedIncident.id}?username=${currentUsername}`, {
                                          method: 'PUT',
                                          headers: { 'Content-Type': 'application/json' },
                                          body: JSON.stringify({ ...selectedIncident, status: saved.name })
                                        });
                                        if (updateRes.ok) {
                                          const updated = await updateRes.json();
                                          setSelectedIncident(updated);
                                          fetchIncidents();
                                        }
                                      }
                                    }
                                  } catch (err) {
                                    console.error('Failed to create status', err);
                                  }
                                }}
                                style={{
                                  background: 'var(--michaels-red)',
                                  color: 'white',
                                  border: 'none',
                                  padding: '4px 10px',
                                  borderRadius: '4px',
                                  cursor: 'pointer',
                                  fontSize: '11px',
                                  fontWeight: 600,
                                  height: '32px'
                                }}
                              >
                                Save
                              </button>
                            </div>
                          ) : (
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
                                      body: JSON.stringify({ ...selectedIncident, status: newStatus })
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
                              {statuses.map(s => <option key={s} value={s}>{s}</option>)}
                            </select>
                          )}
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
                                    body: JSON.stringify({ ...selectedIncident, assignee: newAssignee })
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
                            <option value="Unassigned">Unassigned</option>
                            {(teams.find(t => t.name === (editMode ? editGteam : selectedIncident.assignedGteam))?.employees || []).map(emp => (
                              <option key={emp.id} value={emp.username}>{emp.username}</option>
                            ))}
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
                                      ...selectedIncident,
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
                            {teams.map(t => <option key={t.id} value={t.name}>{t.name}</option>)}
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

                      {/* AI Suggestions Card */}
                      <div className="ai-suggestion-card" style={{
                          background: 'var(--surface2)',
                          border: '1px solid var(--border)',
                          borderRadius: '12px',
                          padding: '16px',
                          marginTop: '10px',
                      }}>
                        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '10px', width: '100%', justifyContent: 'space-between' }}>
                          <h4 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--amber)', fontSize: '13px' }}>
                            ✨ AI Incident Copilot
                          </h4>
                          <button 
                            type="button"
                            className="btn-sync" 
                            style={{ padding: '4px 8px', fontSize: '11px', height: '28px' }}
                            onClick={() => getAiSuggestion(selectedIncident.subject, selectedIncident.description)}
                            disabled={aiLoading}
                          >
                            {aiLoading ? 'Analyzing...' : 'Get AI Suggestions'}
                          </button>
                        </div>

                        {aiSuggestion && (
                          <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                            <div>
                              <span style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 600 }}>Suggested Team</span>
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
                              <span style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 600 }}>Suggested Resolution</span>
                              <div style={{ 
                                marginTop: '4px', 
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

                        {/* Tool Action Buttons */}
                        {aiSuggestion && (
                          <div style={{
                            marginTop: '12px',
                            paddingTop: '12px',
                            borderTop: '1px solid var(--border)',
                            display: 'flex',
                            flexDirection: 'column',
                            gap: '8px'
                          }}>
                            <span style={{ fontSize: '10px', color: 'var(--text-muted)', textTransform: 'uppercase', fontWeight: 600 }}>Automation Tools</span>
                            
                            {toolSearchLoading ? (
                              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: 'var(--text-muted)', fontSize: '12px' }}>
                                <Loader size={12} className="spin" /> Searching for matching tools...
                              </div>
                            ) : matchingTools.length > 0 ? (
                              <div style={{ display: 'flex', flexDirection: 'column', gap: '6px' }}>
                                {matchingTools.map(tool => (
                                  <div key={tool.id} style={{
                                    display: 'flex',
                                    alignItems: 'center',
                                    justifyContent: 'space-between',
                                    padding: '8px 12px',
                                    background: 'var(--green-dim)',
                                    border: '1px solid var(--green)',
                                    borderRadius: '8px'
                                  }}>
                                    <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                                      <Wrench size={14} style={{ color: 'var(--green)' }} />
                                      <div>
                                        <div style={{ fontSize: '12.5px', fontWeight: 600, color: 'var(--green)' }}>{tool.name}</div>
                                        <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>{tool.language} • {tool.category}</div>
                                      </div>
                                    </div>
                                    <button
                                      type="button"
                                      className="btn-primary"
                                      style={{ padding: '4px 10px', fontSize: '11px', display: 'flex', alignItems: 'center', gap: '4px', height: '26px' }}
                                      disabled={toolRunning === tool.id}
                                      onClick={async () => {
                                        setToolRunning(tool.id);
                                        setToolRunOutput(null);
                                        try {
                                          const loadRes = await authFetch(`/api/v1/scripts/${tool.id}`);
                                          if (loadRes.ok) {
                                            const script = await loadRes.json();
                                            const execRes = await authFetch('/api/v1/scripts/execute', {
                                              method: 'POST',
                                              headers: { 'Content-Type': 'application/json' },
                                              body: JSON.stringify({
                                                scriptContent: script.scriptContent,
                                                language: script.language,
                                                dryRun: true,
                                                category: script.category,
                                                description: script.description || 'Tool execution',
                                                targetHost: script.targetHost || 'localhost',
                                              })
                                            });
                                            const output = await execRes.json();
                                            setToolRunOutput({
                                              stdout: output.stdout || '',
                                              stderr: output.stderr || output.error || '',
                                              exitCode: output.exitCode ?? -1
                                            });
                                          }
                                        } catch (err) {
                                          console.error(err);
                                          setToolRunOutput({ stdout: '', stderr: 'Failed to execute tool', exitCode: -1 });
                                        } finally {
                                          setToolRunning(null);
                                        }
                                      }}
                                    >
                                      {toolRunning === tool.id ? <><Loader size={12} className="spin" /> Running...</> : <><Play size={12} /> Run Tool</>}
                                    </button>
                                  </div>
                                ))}

                                {/* Tool run output */}
                                {toolRunOutput && (
                                  <div style={{
                                    padding: '10px',
                                    background: 'var(--surface)',
                                    border: '1px solid var(--border)',
                                    borderRadius: '6px',
                                    fontSize: '11.5px',
                                    fontFamily: 'var(--mono)',
                                    whiteSpace: 'pre-wrap',
                                    maxHeight: '180px',
                                    overflowY: 'auto'
                                  }}>
                                    <div style={{ marginBottom: '4px', color: toolRunOutput.exitCode === 0 ? 'var(--green)' : 'var(--red)', fontWeight: 600 }}>
                                      Exit Code: {toolRunOutput.exitCode}
                                    </div>
                                    {toolRunOutput.stdout && <div style={{ color: 'var(--text-dim)' }}>{toolRunOutput.stdout}</div>}
                                    {toolRunOutput.stderr && <div style={{ color: 'var(--red)' }}>{toolRunOutput.stderr}</div>}
                                  </div>
                                )}
                              </div>
                            ) : (
                              <button
                                type="button"
                                className="btn-secondary"
                                style={{
                                  display: 'flex',
                                  alignItems: 'center',
                                  gap: '6px',
                                  padding: '6px 12px',
                                  fontSize: '11px',
                                  width: 'fit-content',
                                  height: '28px'
                                }}
                                onClick={() => {
                                  const remediationPrompt = `Remediation script for: ${selectedIncident.subject}. Resolution: ${aiSuggestion?.suggestedResolution?.substring(0, 500) || ''}`;
                                  window.open(`/?page=tools&desc=${encodeURIComponent(remediationPrompt)}`, '_blank');
                                }}
                              >
                                <Wrench size={12} /> Create Tool in New Tab
                              </button>
                            )}
                          </div>
                        )}
                      </div>
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
                    style={{ padding: '2px 8px', fontSize: '11px', height: 'auto', background: 'rgba(255,255,255,0.05)' }} 
                    onClick={handleModalAiSuggest}
                    disabled={modalAiLoading || !newSubject.trim()}
                  >
                    {modalAiLoading ? 'Analyzing...' : '✨ Run AI Copilot'}
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
