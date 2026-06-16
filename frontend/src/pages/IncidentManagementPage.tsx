import React, { useState, useEffect } from 'react';
import './IncidentManagementPage.css';
import { Plus, RefreshCw, Filter, Search, Calendar, User, ShieldAlert, CheckCircle, Clock, MessageSquare, History, Edit, Save, ArrowLeft } from 'lucide-react';
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
  fieldName: String;
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
  const [updatedDate, setUpdatedDate] = useState('');
  const [dueDate, setDueDate] = useState('');

  // Details Tab
  const [detailTab, setDetailTab] = useState<'details' | 'comments' | 'history'>('details');
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
  const [aiSuggestion, setAiSuggestion] = useState<{ suggestedTeam?: string; suggestedResolution?: string } | null>(null);
  const [aiLoading, setAiLoading] = useState(false);
  const [modalAiLoading, setModalAiLoading] = useState(false);

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
    fetchTeams();
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
      if (res.ok) {
        const data = await res.json();
        setAiSuggestion(data);
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
      if (updatedDate) queryParams.append('updatedDate', updatedDate);
      if (dueDate) queryParams.append('dueDate', dueDate);

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
  }, [subject, description, assignee, assignedGteam, priority, createdDate, updatedDate, dueDate]);

  // Fetch comments & history when selected incident changes
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
    setUpdatedDate('');
    setDueDate('');
  };

  // Stats
  const total = incidents.length;
  const p1Count = incidents.filter(i => i.priority === 'P1').length;
  const p2Count = incidents.filter(i => i.priority === 'P2').length;
  const p3Count = incidents.filter(i => i.priority === 'P3').length;

  return (
    <div className="incident-page">
      {/* ServiceNow / Freshservice Inspired Dashboard KPI Header */}
      <div className="kpi-grid">
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

      {/* Control panel */}
      <div className="control-bar">
        <div className="search-box">
          <Search size={18} className="search-icon" />
          <input
            type="text"
            placeholder="Search incident subjects..."
            value={subject}
            onChange={e => setSubject(e.target.value)}
          />
        </div>
        <div className="button-group">
          <button className="btn-secondary" onClick={clearFilters}>
            Clear Filters
          </button>
          <button className="btn-sync" onClick={handleSync} disabled={syncing}>
            <RefreshCw size={16} className={syncing ? 'spin' : ''} />
            {syncing ? 'Syncing...' : 'Sync Third-Party'}
          </button>
          <button className="btn-primary" onClick={() => setShowCreateModal?.(true)}>
            <Plus size={16} /> Create Incident
          </button>
        </div>
      </div>

      {/* Filters & Incident List layout */}
      <div className="main-content-layout">
        {/* Advanced Filters sidebar - Hide if incident selected for focus */}
        {!selectedIncident && (
          <aside className="filters-sidebar">
            <h3><Filter size={16} /> Advanced Filters</h3>
            
            <div className="filter-group">
              <label>Description</label>
              <input type="text" value={description} onChange={e => setDescription(e.target.value)} placeholder="Contains..." />
            </div>

            <div className="filter-group">
              <label>Assignee</label>
              <input type="text" value={assignee} onChange={e => setAssignee(e.target.value)} placeholder="e.g. John Doe" />
            </div>

            <div className="filter-group">
              <label>Assigned GTeam</label>
              <input type="text" value={assignedGteam} onChange={e => setAssignedGteam(e.target.value)} placeholder="e.g. IT Ops" />
            </div>

            <div className="filter-group">
              <label>Priority</label>
              <select value={priority} onChange={e => setPriority(e.target.value)}>
                <option value="">All Priorities</option>
                <option value="P1">P1 - Critical</option>
                <option value="P2">P2 - High</option>
                <option value="P3">P3 - Medium</option>
              </select>
            </div>

            <div className="filter-group">
              <label>Created Date</label>
              <input type="date" value={createdDate} onChange={e => setCreatedDate(e.target.value)} />
            </div>

            <div className="filter-group">
              <label>Updated Date</label>
              <input type="date" value={updatedDate} onChange={e => setUpdatedDate(e.target.value)} />
            </div>

            <div className="filter-group">
              <label>Due Date</label>
              <input type="date" value={dueDate} onChange={e => setDueDate(e.target.value)} />
            </div>
          </aside>
        )}

        {/* Incident List */}
        <div className={`incidents-container ${selectedIncident ? 'narrow-view' : ''}`}>
          {loading ? (
            <div className="loader-box">
              <span className="spinner"></span> Loading Incidents...
            </div>
          ) : incidents.length === 0 ? (
            <div className="empty-box">
              <ShieldAlert size={48} style={{ opacity: 0.3, marginBottom: '16px' }} />
              <p>No incidents match the selected filters.</p>
            </div>
          ) : (
            <div className="incidents-table-wrapper">
              <table className="incidents-table">
                <thead>
                  <tr>
                    <th>Ticket</th>
                    <th>Subject</th>
                    {!selectedIncident && <th>Priority</th>}
                    {!selectedIncident && <th>Status</th>}
                    {!selectedIncident && <th>Assignee</th>}
                    {!selectedIncident && <th>Due Date</th>}
                  </tr>
                </thead>
                <tbody>
                  {incidents.map(inc => (
                    <tr 
                      key={inc.id} 
                      className={`priority-${inc.priority.toLowerCase()} ${selectedIncident?.id === inc.id ? 'active-row' : ''}`}
                      onClick={() => setSelectedIncident(inc)}
                      style={{ cursor: 'pointer' }}
                    >
                      <td>
                        <div className="ticket-id">
                          {inc.externalId}
                        </div>
                        <div className={`source-badge source-${inc.externalSource.toLowerCase()}`}>
                          {inc.externalSource}
                        </div>
                      </td>
                      <td>
                        <div className="incident-subject">{inc.subject}</div>
                        {selectedIncident ? (
                          <span className={`priority-badge p-${inc.priority.toLowerCase()}`} style={{ marginRight: '6px', fontSize: '9px', padding: '2px 4px' }}>
                            {inc.priority}
                          </span>
                        ) : (
                          <div className="incident-desc">{inc.description}</div>
                        )}
                        {selectedIncident && (
                          <span className={`status-badge status-${inc.status.toLowerCase().replace(' ', '-')}`} style={{ fontSize: '9px', padding: '2px 4px' }}>
                            {inc.status}
                          </span>
                        )}
                      </td>
                      {!selectedIncident && (
                        <td>
                          <span className={`priority-badge p-${inc.priority.toLowerCase()}`}>
                            {inc.priority}
                          </span>
                        </td>
                      )}
                      {!selectedIncident && (
                        <td>
                          <span className={`status-badge status-${inc.status.toLowerCase().replace(' ', '-')}`}>
                            {inc.status}
                          </span>
                        </td>
                      )}
                      {!selectedIncident && (
                        <td>
                          <div className="assignee-name"><User size={12} style={{ marginRight: '4px', verticalAlign: 'middle' }} /> {inc.assignee}</div>
                          <div className="assignee-team">{inc.assignedGteam}</div>
                        </td>
                      )}
                      {!selectedIncident && (
                        <td>
                          <div className="due-date">
                            <Clock size={12} style={{ marginRight: '4px', verticalAlign: 'middle' }} />
                            {inc.dueDate ? new Date(inc.dueDate).toLocaleDateString() : 'N/A'}
                          </div>
                        </td>
                      )}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Selected Incident Details view */}
        {selectedIncident && (
          <div className="details-panel-container">
            <div className="details-panel-header">
              <button className="back-btn" onClick={() => setSelectedIncident(null)}>
                <ArrowLeft size={16} /> Back to Directory
              </button>
              <div className="header-actions">
                {detailTab === 'details' && (
                  editMode ? (
                    <button className="btn-primary" onClick={handleUpdateIncident} disabled={updateLoading}>
                      <Save size={14} /> {updateLoading ? 'Saving...' : 'Save'}
                    </button>
                  ) : (
                    <button className="btn-secondary" onClick={() => setEditMode(true)}>
                      <Edit size={14} /> Edit Fields
                    </button>
                  )
                )}
              </div>
            </div>

            <div className="details-panel-body">
              {/* Tabs Switcher at top */}
              <div className="tabs-bar" style={{ marginBottom: '16px', borderBottom: '1px solid var(--border)' }}>
                <button 
                  className={`tab-btn ${detailTab === 'details' ? 'active' : ''}`}
                  onClick={() => setDetailTab('details')}
                >
                  <Edit size={14} /> Details
                </button>
                <button 
                  className={`tab-btn ${detailTab === 'comments' ? 'active' : ''}`}
                  onClick={() => setDetailTab('comments')}
                >
                  <MessageSquare size={14} /> Comments ({comments.length})
                </button>
                <button 
                  className={`tab-btn ${detailTab === 'history' ? 'active' : ''}`}
                  onClick={() => setDetailTab('history')}
                >
                  <History size={14} /> Incident History ({historyRecords.length})
                </button>
              </div>

              {detailTab === 'details' && (
                <form onSubmit={handleUpdateIncident} className="fields-form">
                  <div className="detail-row">
                    <div className="detail-field">
                      <label>Ticket ID</label>
                      <div className="value-static monospace">
                        {selectedIncident.externalId}
                      </div>
                    </div>
                    <div className="detail-field">
                      <label>Source Provider</label>
                      <div className="value-static">
                        <span className={`source-badge source-${selectedIncident.externalSource.toLowerCase()}`}>
                          {selectedIncident.externalSource}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div className="detail-field full-width">
                    <label>Subject</label>
                    {editMode ? (
                      <input type="text" value={editSubject} onChange={e => setEditSubject(e.target.value)} required />
                    ) : (
                      <div className="value-static bold">{selectedIncident.subject}</div>
                    )}
                  </div>

                  <div className="detail-field full-width">
                    <label>Description</label>
                    {editMode ? (
                      <textarea rows={3} value={editDescription} onChange={e => setEditDescription(e.target.value)} />
                    ) : (
                      <div className="value-static desc-box">{selectedIncident.description || 'No description provided.'}</div>
                    )}
                  </div>

                  <div className="detail-row">
                    <div className="detail-field">
                      <label>Priority</label>
                      {editMode ? (
                        <select value={editPriority} onChange={e => setEditPriority(e.target.value)}>
                          <option value="P1">P1 - Critical</option>
                          <option value="P2">P2 - High</option>
                          <option value="P3">P3 - Medium</option>
                        </select>
                      ) : (
                        <div className="value-static">
                          <span className={`priority-badge p-${selectedIncident.priority.toLowerCase()}`}>
                            {selectedIncident.priority}
                          </span>
                        </div>
                      )}
                    </div>

                    <div className="detail-field">
                      <label>Status</label>
                      {editMode ? (
                        <select value={editStatus} onChange={e => setEditStatus(e.target.value)}>
                          <option value="New">New</option>
                          <option value="In Progress">In Progress</option>
                          <option value="Resolved">Resolved</option>
                          <option value="Closed">Closed</option>
                        </select>
                      ) : (
                        <div className="value-static">
                          <span className={`status-badge status-${selectedIncident.status.toLowerCase().replace(' ', '-')}`}>
                            {selectedIncident.status}
                          </span>
                        </div>
                      )}
                    </div>
                  </div>

                  <div className="detail-row">
                    <div className="detail-field">
                      <label>Assignee</label>
                      {editMode ? (
                        <select value={editAssignee} onChange={e => setEditAssignee(e.target.value)}>
                          <option value="Unassigned">Unassigned</option>
                          {(teams.find(t => t.name === editGteam)?.employees || []).map(emp => (
                            <option key={emp.id} value={emp.username}>{emp.username}</option>
                          ))}
                        </select>
                      ) : (
                        <div className="value-static"><User size={12} /> {selectedIncident.assignee}</div>
                      )}
                    </div>

                    <div className="detail-field">
                      <label>Assigned GTeam</label>
                      {editMode ? (
                        <select value={editGteam} onChange={e => {
                          const teamName = e.target.value;
                          setEditGteam(teamName);
                          const employees = teams.find(t => t.name === teamName)?.employees || [];
                          setEditAssignee(employees[0]?.username || 'Unassigned');
                        }}>
                          {teams.map(t => <option key={t.id} value={t.name}>{t.name}</option>)}
                        </select>
                      ) : (
                        <div className="value-static">{selectedIncident.assignedGteam}</div>
                      )}
                    </div>
                  </div>

                  <div className="detail-row">
                    <div className="detail-field">
                      <label>Created Date</label>
                      <div className="value-static"><Calendar size={12} /> {new Date(selectedIncident.createdAt).toLocaleString()}</div>
                    </div>
                    <div className="detail-field">
                      <label>Due Date / SLA</label>
                      <div className="value-static"><Clock size={12} /> {selectedIncident.dueDate ? new Date(selectedIncident.dueDate).toLocaleString() : 'N/A'}</div>
                    </div>
                  </div>

                  {/* AI Suggestions Card */}
                  <div className="ai-suggestion-card" style={{
                      background: 'rgba(255, 255, 255, 0.03)',
                      border: '1px solid rgba(255, 255, 255, 0.1)',
                      borderRadius: '12px',
                      padding: '16px',
                      marginTop: '20px',
                      boxShadow: '0 8px 32px 0 rgba(0, 0, 0, 0.2)'
                  }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '10px' }}>
                      <h4 style={{ margin: 0, display: 'flex', alignItems: 'center', gap: '8px', color: '#ffb703' }}>
                        ✨ AI Incident Copilot
                      </h4>
                      <button 
                        type="button"
                        className="btn-sync" 
                        style={{ padding: '4px 8px', fontSize: '12px' }}
                        onClick={() => getAiSuggestion(selectedIncident.subject, selectedIncident.description)}
                        disabled={aiLoading}
                      >
                        {aiLoading ? 'Analyzing...' : 'Get AI Suggestions'}
                      </button>
                    </div>

                    {aiSuggestion && (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                        <div>
                          <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.5)', textTransform: 'uppercase' }}>Suggested Team</span>
                          <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginTop: '4px' }}>
                            <strong style={{ color: '#06d6a0' }}>{aiSuggestion.suggestedTeam}</strong>
                            {selectedIncident.assignedGteam !== aiSuggestion.suggestedTeam && (
                              <button 
                                type="button"
                                className="btn-primary" 
                                style={{ padding: '2px 6px', fontSize: '10px' }}
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
                          <span style={{ fontSize: '11px', color: 'rgba(255,255,255,0.5)', textTransform: 'uppercase' }}>Suggested Resolution</span>
                          <div style={{ 
                            marginTop: '6px', 
                            padding: '10px', 
                            background: 'rgba(0,0,0,0.2)', 
                            borderRadius: '6px', 
                            fontSize: '13px', 
                            whiteSpace: 'pre-wrap',
                            lineHeight: '1.4' 
                          }}>
                            {aiSuggestion.suggestedResolution}
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                </form>
              )}

              {detailTab === 'comments' && (
                <div className="comments-tab" style={{ display: 'flex', flexDirection: 'column', gap: '14px', height: '100%' }}>
                  {/* Comments List */}
                  <div className="comments-list" style={{ flex: 1, overflowY: 'auto', minHeight: '200px', maxHeight: 'none' }}>
                    {comments.length === 0 ? (
                      <div className="empty-tab-box">No comments recorded on this ticket.</div>
                    ) : (
                      comments.map(c => (
                        <div key={c.id} className="comment-bubble-item" style={{ marginBottom: '12px' }}>
                          <div className="comment-header">
                            <span className="comment-author">{c.author}</span>
                            <span className="comment-date">{new Date(c.createdAt).toLocaleString()}</span>
                          </div>
                          <div className="comment-text">{c.commentText}</div>
                        </div>
                      ))
                    )}
                  </div>

                  {/* Add Comment Input */}
                  <form onSubmit={handleAddComment} className="comment-form" style={{ marginTop: 'auto' }}>
                    <input
                      type="text"
                      placeholder="Type an internal comment..."
                      value={newComment}
                      onChange={e => setNewComment(e.target.value)}
                    />
                    <button type="submit" className="btn-primary" disabled={commentLoading || !newComment.trim()}>
                      Add
                    </button>
                  </form>
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
        )}
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
                  style={{ background: 'var(--input-bg)', border: '1px solid var(--border)', borderRadius: '6px', color: 'white', padding: '10px' }}
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
                  <label>Assigned GTeam</label>
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
