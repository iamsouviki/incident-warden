import React, { useState, useEffect, useCallback } from 'react';
import { authFetch, extractApiError, SIMPLE_ERROR_MESSAGE } from '../services/api';

interface Stats {
  totalPending: number; processing: number;
  hitlPending: number; autoResolved: number; escalated: number;
}

interface Incident {
  id: string; title: string; category: string | null;
  severity: string; status: string; createdAt: string;
  confidenceScore: number | null;
}

interface HitlRequest {
  id: string; incidentId: string; status: string;
  approvalPayload: string; expiresAt: string; createdAt: string;
}

interface SettingsResponse {
  incidentDefaults?: {
    defaultSourceSystem?: string;
    autoProcessOnCreate?: boolean;
  };
  incidentSources?: { name?: string; enabled?: boolean }[];
}

interface IncidentDraft {
  sourceSystem: string;
  sourceTicketId: string;
  title: string;
  description: string;
  category: string;
  subCategory: string;
  severity: string;
  affectedSystems: string;
}

const getStatusClass = (s: string) => {
  if (s === 'AUTO_RESOLVED') return 's-auto';
  if (s === 'HITL_PENDING') return 's-hitl';
  if (s === 'ESCALATED') return 's-escalated';
  if (s === 'PROCESSING') return 's-processing';
  if (s === 'FAILED') return 's-failed';
  return 's-pending';
};

const getStatusLabel = (s: string) => {
  if (s === 'AUTO_RESOLVED') return 'AUTO-RESOLVED';
  if (s === 'HITL_PENDING') return 'HITL PENDING';
  return s.replace(/_/g, ' ');
};

const getPriorityClass = (sev: string) => sev?.toLowerCase() || 'p4';

const OverviewPage: React.FC<{
  onNavigate: (p: any) => void;
  tenantId: string;
}> = ({ onNavigate, tenantId }) => {
  const [stats, setStats] = useState<Stats>({ totalPending: 0, processing: 0, hitlPending: 0, autoResolved: 0, escalated: 0 });
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [hitl, setHitl] = useState<HitlRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [processing, setProcessing] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState('OVERVIEW');
  const [creatingIncident, setCreatingIncident] = useState(false);
  const [incidentMessage, setIncidentMessage] = useState<string | null>(null);
  const [showCreateIncidentModal, setShowCreateIncidentModal] = useState(false);
  const [sourceOptions, setSourceOptions] = useState<string[]>(['manual']);
  const [incidentDefaults, setIncidentDefaults] = useState({
    defaultSourceSystem: 'manual',
    autoProcessOnCreate: false,
  });
  const [incidentDraft, setIncidentDraft] = useState<IncidentDraft>({
    sourceSystem: 'manual',
    sourceTicketId: '',
    title: '',
    description: '',
    category: '',
    subCategory: '',
    severity: 'P3',
    affectedSystems: '',
  });

  const fetchAll = useCallback(async () => {
    try {
      const [sR, iR, hR] = await Promise.all([
        authFetch(`/api/v1/incidents/stats/${tenantId}`),
        authFetch(`/api/v1/incidents?tenantId=${tenantId}&limit=20`),
        authFetch(`/api/v1/hitl/pending?tenantId=${tenantId}`),
      ]);
      if (sR.ok) setStats(await sR.json());
      if (iR.ok) { const d = await iR.json(); setIncidents(Array.isArray(d) ? d : d.incidents || []); }
      if (hR.ok) { const d = await hR.json(); setHitl(d.requests || []); }
      if (sR.ok && iR.ok && hR.ok) {
        setError(null);
      } else {
        const failed = !sR.ok ? sR : !iR.ok ? iR : hR;
        setError(await extractApiError(failed));
      }
    } catch { setError(SIMPLE_ERROR_MESSAGE); }
  }, [tenantId]);

  useEffect(() => { fetchAll(); const t = setInterval(fetchAll, 15000); return () => clearInterval(t); }, [fetchAll]);

  useEffect(() => {
    const openCreateIncident = () => {
      setIncidentDraft(current => ({ ...current, sourceSystem: incidentDefaults.defaultSourceSystem || 'manual' }));
      setShowCreateIncidentModal(true);
    };
    const checkUrl = () => {
      const url = new URL(window.location.href);
      if (url.pathname === '/overview' && url.searchParams.get('createIncident') === '1') {
        openCreateIncident();
        url.searchParams.delete('createIncident');
        window.history.replaceState(null, '', url.pathname + (url.search ? url.search : ''));
      }
    };
    window.addEventListener('mcp:create-incident', openCreateIncident);
    checkUrl();
    return () => window.removeEventListener('mcp:create-incident', openCreateIncident);
  }, [incidentDefaults.defaultSourceSystem]);

  useEffect(() => {
    const loadSettings = async () => {
      try {
        const response = await authFetch(`/api/v1/settings?tenantId=${tenantId}`);
        if (!response.ok) {
          return;
        }
        const data: SettingsResponse = await response.json();
        const options = ['manual', ...(data.incidentSources || [])
          .filter(source => source.enabled !== false && source.name?.trim())
          .map(source => source.name!.trim())];
        const defaultSource = data.incidentDefaults?.defaultSourceSystem || 'manual';
        setSourceOptions(options);
        setIncidentDefaults({
          defaultSourceSystem: defaultSource,
          autoProcessOnCreate: Boolean(data.incidentDefaults?.autoProcessOnCreate),
        });
        setIncidentDraft(current => ({ ...current, sourceSystem: defaultSource }));
      } catch {}
    };
    loadSettings();
  }, [tenantId]);

  const handleProcess = async (incidentId: string) => {
    setProcessing(incidentId);
    try {
      const r = await authFetch(`/api/v1/incidents/${incidentId}/process?tenantId=${tenantId}`, { method: 'POST' });
      if (!r.ok) {
        alert(await extractApiError(r));
        return;
      }
      const d = await r.json();
      await fetchAll();
      alert(`Pipeline result: ${d.decision || d.status || JSON.stringify(d)}`);
    } catch { alert(SIMPLE_ERROR_MESSAGE); }
    finally { setProcessing(null); }
  };

  const handleApprove = async (hitlId: string) => {
    try {
      const r = await authFetch(`/api/v1/hitl/${hitlId}/approve?decidedBy=dashboard-user&reason=Approved+via+dashboard`, { method: 'POST' });
      if (!r.ok) {
        alert(await extractApiError(r));
        return;
      }
      await fetchAll();
    } catch { alert(SIMPLE_ERROR_MESSAGE); }
  };

  const handleReject = async (hitlId: string) => {
    const reason = prompt('Rejection reason:', 'Insufficient confidence');
    if (!reason) return;
    try {
      const r = await authFetch(`/api/v1/hitl/${hitlId}/reject?decidedBy=dashboard-user&reason=${encodeURIComponent(reason)}`, { method: 'POST' });
      if (!r.ok) {
        alert(await extractApiError(r));
        return;
      }
      await fetchAll();
    } catch { alert(SIMPLE_ERROR_MESSAGE); }
  };

  const handleCreateIncident = async () => {
    if (!incidentDraft.title.trim()) {
      setError('Incident title is required.');
      return;
    }

    setCreatingIncident(true);
    setIncidentMessage(null);
    try {
      const sourceTicketId = incidentDraft.sourceTicketId.trim() || `manual-${Date.now()}`;
      const response = await authFetch('/api/v1/incidents', {
        method: 'POST',
        body: JSON.stringify({
          tenantId,
          sourceSystem: incidentDraft.sourceSystem || incidentDefaults.defaultSourceSystem || 'manual',
          sourceTicketId,
          title: incidentDraft.title.trim(),
          description: incidentDraft.description.trim(),
          category: incidentDraft.category.trim() || null,
          subCategory: incidentDraft.subCategory.trim() || null,
          severity: incidentDraft.severity,
          affectedSystems: incidentDraft.affectedSystems.split(',').map(item => item.trim()).filter(Boolean),
          status: 'PENDING',
        }),
      });
      if (!response.ok) {
        setError(await extractApiError(response));
        return;
      }

      const created = await response.json();
      if (incidentDefaults.autoProcessOnCreate && created?.id) {
        await authFetch(`/api/v1/incidents/${created.id}/process?tenantId=${tenantId}`, { method: 'POST' });
      }

      setIncidentMessage(`Incident created: ${sourceTicketId}`);
      setError(null);
      setShowCreateIncidentModal(false);
      setIncidentDraft({
        sourceSystem: incidentDefaults.defaultSourceSystem || 'manual',
        sourceTicketId: '',
        title: '',
        description: '',
        category: '',
        subCategory: '',
        severity: 'P3',
        affectedSystems: '',
      });
      await fetchAll();
    } catch {
      setError(SIMPLE_ERROR_MESSAGE);
    } finally {
      setCreatingIncident(false);
    }
  };

  // Compute derived stats
  const total = stats.totalPending + stats.processing + stats.hitlPending + stats.autoResolved + stats.escalated;
  const autoRate = total > 0 ? Math.round((stats.autoResolved / total) * 100) : 0;
  const p1Count = incidents.filter(i => i.severity === 'P1').length;
  const p2Count = incidents.filter(i => i.severity === 'P2').length;
  const p3Count = incidents.filter(i => i.severity === 'P3').length;
  const p4Count = incidents.filter(i => i.severity === 'P4' || !i.severity).length;
  const statusSnapshot = [
    { label: 'Pending', value: stats.totalPending, color: 'var(--text-muted)' },
    { label: 'Processing', value: stats.processing, color: 'var(--blue)' },
    { label: 'HITL Pending', value: stats.hitlPending, color: 'var(--amber)' },
    { label: 'Auto-Resolved', value: stats.autoResolved, color: 'var(--green)' },
    { label: 'Escalated', value: stats.escalated, color: 'var(--red)' },
  ];
  const severitySnapshot = [
    { label: 'P1', value: p1Count, color: 'var(--red)' },
    { label: 'P2', value: p2Count, color: 'var(--amber)' },
    { label: 'P3', value: p3Count, color: 'var(--blue)' },
    { label: 'P4', value: p4Count, color: 'var(--green)' },
  ];
  const maxStatusValue = Math.max(1, ...statusSnapshot.map(item => item.value));
  const maxSeverityValue = Math.max(1, ...severitySnapshot.map(item => item.value));

  const parsePayload = (raw: string) => { try { return JSON.parse(raw); } catch { return {}; } };

  return (
    <div className="content">
      <div className="tabs">
        {['OVERVIEW','HITL QUEUE','SOP LIBRARY','ANALYTICS','SYSTEM HEALTH'].map(t => (
          <div key={t} className={`tab ${activeTab === t ? 'active' : ''}`} onClick={() => {
            setActiveTab(t);
            if (t === 'HITL QUEUE') onNavigate('hitl');
            if (t === 'SOP LIBRARY') onNavigate('sop');
            if (t === 'SYSTEM HEALTH') onNavigate('health');
          }}>{t}</div>
        ))}
      </div>

      {error && <div className="error-banner">⚠ {error}</div>}

      {incidentMessage && (
        <div style={{ marginBottom: 12, padding: '10px 12px', borderRadius: 8, border: '1px solid rgba(48,217,156,0.28)', background: 'rgba(48,217,156,0.08)', color: 'var(--green)', fontSize: 12 }}>
          {incidentMessage}
        </div>
      )}

      {showCreateIncidentModal && (
        <div style={modalOverlayStyle} onClick={() => !creatingIncident && setShowCreateIncidentModal(false)}>
          <div style={modalCardStyle} onClick={(e) => e.stopPropagation()}>
            <div className="card-header" style={{ padding: 0, marginBottom: 14 }}>
              <div className="card-title">Create New Incident</div>
              <div style={{ fontFamily:'var(--mono)', fontSize:10, color:'var(--text-muted)' }}>
                Popup intake
              </div>
            </div>
            <div style={{ display:'grid', gridTemplateColumns:'repeat(3, minmax(0, 1fr))', gap:12, marginBottom:12 }}>
              <select value={incidentDraft.sourceSystem} onChange={e => setIncidentDraft(current => ({ ...current, sourceSystem: e.target.value }))} style={overviewInputStyle}>
                {sourceOptions.map(option => <option key={option} value={option}>{option}</option>)}
              </select>
              <input value={incidentDraft.sourceTicketId} onChange={e => setIncidentDraft(current => ({ ...current, sourceTicketId: e.target.value }))} placeholder="Source ticket ID (optional)" style={overviewInputStyle} />
              <select value={incidentDraft.severity} onChange={e => setIncidentDraft(current => ({ ...current, severity: e.target.value }))} style={overviewInputStyle}>
                {['P1', 'P2', 'P3', 'P4'].map(option => <option key={option} value={option}>{option}</option>)}
              </select>
            </div>
            <input value={incidentDraft.title} onChange={e => setIncidentDraft(current => ({ ...current, title: e.target.value }))} placeholder="Incident title" style={{ ...overviewInputStyle, marginBottom:12 }} />
            <textarea value={incidentDraft.description} onChange={e => setIncidentDraft(current => ({ ...current, description: e.target.value }))} rows={4} placeholder="Describe the incident" style={{ ...overviewInputStyle, resize:'vertical', marginBottom:12 }} />
            <div style={{ display:'grid', gridTemplateColumns:'repeat(3, minmax(0, 1fr))', gap:12 }}>
              <input value={incidentDraft.category} onChange={e => setIncidentDraft(current => ({ ...current, category: e.target.value }))} placeholder="Category" style={overviewInputStyle} />
              <input value={incidentDraft.subCategory} onChange={e => setIncidentDraft(current => ({ ...current, subCategory: e.target.value }))} placeholder="Sub-category" style={overviewInputStyle} />
              <input value={incidentDraft.affectedSystems} onChange={e => setIncidentDraft(current => ({ ...current, affectedSystems: e.target.value }))} placeholder="Affected systems, comma separated" style={overviewInputStyle} />
            </div>
            <div style={{ display:'flex', justifyContent:'space-between', alignItems:'center', marginTop:14, gap:12, flexWrap:'wrap' }}>
              <div style={{ fontSize:12, color:'var(--text-muted)' }}>
                Default source: {incidentDefaults.defaultSourceSystem} · Auto-process: {incidentDefaults.autoProcessOnCreate ? 'ON' : 'OFF'}
              </div>
              <div style={{ display:'flex', gap:10 }}>
                <button className="btn" onClick={() => setShowCreateIncidentModal(false)} disabled={creatingIncident}>
                  CANCEL
                </button>
                <button className="btn btn-modify" disabled={creatingIncident} onClick={handleCreateIncident}>
                  {creatingIncident ? '⏳ CREATING' : '+ CREATE INCIDENT'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}

      {/* ─── KPI CARDS ─── */}
      <div className="kpi-grid">
        <div className="kpi-card blue">
          <div className="kpi-label">Auto-Resolve Rate</div>
          <div className="kpi-value">{autoRate}<span style={{fontSize:14,fontWeight:400,marginLeft:4}}>%</span></div>
          <div className="kpi-delta up">↑ Pipeline coverage</div>
          <div className="sparkline">
            {[40,55,50,65,60,75,70,80,85,autoRate].map((h,i) => (
              <div key={i} className="spark-bar" style={{height:`${h}%`}}></div>
            ))}
          </div>
        </div>
        <div className="kpi-card amber">
          <div className="kpi-label">HITL Pending</div>
          <div className="kpi-value">{stats.hitlPending}</div>
          <div className="kpi-delta neutral">Awaiting human review</div>
          <div className="sparkline">
            {[70,50,80,60,90,40,75,85,55,100].map((h,i) => (
              <div key={i} className="spark-bar" style={{height:`${h}%`,background:i===9?'var(--amber)':'rgba(245,166,35,0.2)'}}></div>
            ))}
          </div>
        </div>
        <div className="kpi-card green">
          <div className="kpi-label">Auto-Resolved</div>
          <div className="kpi-value">{stats.autoResolved}</div>
          <div className="kpi-delta up">↑ Fully automated</div>
          <div className="sparkline">
            {[20,35,45,55,60,68,72,75,80,stats.autoResolved > 0 ? 90 : 10].map((h,i) => (
              <div key={i} className="spark-bar" style={{height:`${h}%`,background:i===9?'var(--green)':'rgba(48,217,156,0.2)'}}></div>
            ))}
          </div>
        </div>
        <div className="kpi-card red">
          <div className="kpi-label">Escalated</div>
          <div className="kpi-value">{stats.escalated}</div>
          <div className="kpi-delta down">Requires manual action</div>
          <div className="sparkline">
            {[55,70,50,60,45,40,35,30,28,stats.escalated > 0 ? 60 : 10].map((h,i) => (
              <div key={i} className="spark-bar" style={{height:`${Math.max(h,4)}%`,background:i===9?'var(--red)':'rgba(255,85,85,0.2)'}}></div>
            ))}
          </div>
        </div>
      </div>

      {/* ─── MINI METRICS ─── */}
      <div className="mini-metrics">
        <div className="mini-metric">
          <div className="v">{incidents.length}</div>
          <div className="l">Incidents Loaded</div>
        </div>
        <div className="mini-metric">
          <div className="v" style={{color:'var(--red)'}}>{p1Count}</div>
          <div className="l">P1 Active</div>
        </div>
        <div className="mini-metric">
          <div className="v" style={{color:'var(--amber)'}}>{p2Count}</div>
          <div className="l">P2 Active</div>
        </div>
        <div className="mini-metric">
          <div className="v" style={{color:'var(--blue)'}}>{stats.processing}</div>
          <div className="l">Processing</div>
        </div>
      </div>

      {/* ─── CHARTS ROW ─── */}
      <div className="chart-row">
        <div className="card">
          <div className="card-header">
            <div className="card-title">Current Incident Status</div>
          </div>
          <div className="card-body">
            <div style={{display:'grid',gap:14}}>
              {statusSnapshot.map(item => (
                <div key={item.label}>
                  <div style={{display:'flex',justifyContent:'space-between',marginBottom:6}}>
                    <span style={{fontSize:12,color:'var(--text-dim)'}}>{item.label}</span>
                    <span style={{fontFamily:'var(--mono)',fontSize:12,color:item.color}}>{item.value}</span>
                  </div>
                  <div style={{background:'var(--surface2)',borderRadius:999,height:10,overflow:'hidden'}}>
                    <div style={{
                      width:`${item.value === 0 ? 0 : Math.max(10, (item.value / maxStatusValue) * 100)}%`,
                      height:'100%',
                      borderRadius:999,
                      background:item.color,
                      transition:'width 0.3s ease'
                    }}></div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="card">
          <div className="card-header">
            <div className="card-title">Loaded Incident Severity Mix</div>
          </div>
          <div className="card-body">
            <div style={{display:'grid',gap:14}}>
              {severitySnapshot.map(item => (
                <div key={item.label}>
                  <div style={{display:'flex',justifyContent:'space-between',marginBottom:6}}>
                    <span style={{fontSize:12,color:'var(--text-dim)'}}>{item.label}</span>
                    <span style={{fontFamily:'var(--mono)',fontSize:12,color:item.color}}>{item.value}</span>
                  </div>
                  <div style={{background:'var(--surface2)',borderRadius:999,height:10,overflow:'hidden'}}>
                    <div style={{
                      width:`${item.value === 0 ? 0 : Math.max(10, (item.value / maxSeverityValue) * 100)}%`,
                      height:'100%',
                      borderRadius:999,
                      background:item.color,
                      transition:'width 0.3s ease'
                    }}></div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* ─── HITL QUEUE PREVIEW ─── */}
      {hitl.length > 0 && (
        <>
          <div className="section-header">
            <div className="section-title"><span className="live-dot"></span>HITL Pending Approvals</div>
            <div className="see-all" onClick={() => onNavigate('hitl')}>View all {hitl.length} →</div>
          </div>
          <div className="hitl-section">
            {hitl.slice(0,2).map((req, idx) => {
              const payload = parsePayload(req.approvalPayload);
              const conf = payload.confidence ?? 0;
              const confPct = Math.round(conf * 100);
              const inc = incidents.find(i => i.id === req.incidentId);
              const sev = inc?.severity || 'P2';
              return (
                <div key={req.id} className={`hitl-request ${sev === 'P1' ? 'urgent' : ''}`}>
                  <div className="hitl-header">
                    <div>
                      <div className="hitl-inc">#{req.incidentId.substring(0,8).toUpperCase()}</div>
                      <div className="hitl-title">{inc?.title || payload.incidentTitle || 'Incident pending review'}</div>
                    </div>
                    <div style={{display:'flex',flexDirection:'column',alignItems:'flex-end',gap:6}}>
                      <span className={`priority-badge ${sev.toLowerCase()}`}>{sev}</span>
                      <span className="hitl-timer">⏱ PENDING</span>
                    </div>
                  </div>
                  <div className="hitl-stats-row">
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">Confidence</div>
                      <div className="hitl-stat-val" style={{color: confPct > 70 ? 'var(--green)' : confPct > 40 ? 'var(--blue)' : 'var(--amber)'}}>{confPct}%</div>
                    </div>
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">Category</div>
                      <div className="hitl-stat-val" style={{fontSize:11,color:'var(--text-dim)'}}>{payload.classification || inc?.category || '—'}</div>
                    </div>
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">Decision</div>
                      <div className="hitl-stat-val" style={{fontSize:11,color:'var(--amber)'}}>HITL REQ.</div>
                    </div>
                  </div>
                  <div className="confidence-bar-wrap">
                    <div className="confidence-fill" style={{width:`${confPct}%`}}></div>
                  </div>
                  <div style={{fontSize:11,color:'var(--text-muted)',marginBottom:10,fontFamily:'var(--mono)'}}>
                    Expires: {new Date(req.expiresAt).toLocaleString()}
                  </div>
                  <div className="hitl-actions">
                    <button className="btn btn-approve" onClick={() => handleApprove(req.id)}>✓ APPROVE</button>
                    <button className="btn btn-modify">✎ MODIFY</button>
                    <button className="btn btn-reject" onClick={() => handleReject(req.id)}>✗ REJECT</button>
                  </div>
                </div>
              );
            })}
          </div>
        </>
      )}

      {/* ─── LIVE INCIDENT FEED ─── */}
      <div className="section-header">
        <div className="section-title"><span className="live-dot"></span>Live Incident Feed</div>
        <div className="see-all" onClick={() => fetch(`/api/v1/incidents?tenantId=${tenantId}&limit=50`).then(r=>r.json()).then(d => alert(JSON.stringify(d,null,2)))}>All incidents →</div>
      </div>
      <div className="card" style={{marginBottom:24}}>
        {incidents.length === 0 ? (
          <div className="empty-state-msg">No incidents yet.</div>
        ) : incidents.slice(0, 8).map(inc => (
          <div key={inc.id} className="feed-item">
            <span className="inc-id">#{inc.id.substring(0,8).toUpperCase()}</span>
            <div>
              <div className="inc-title">{inc.title}</div>
              <div className="inc-meta">
                {inc.category ? `${inc.category} · ` : ''}
                {inc.confidenceScore != null ? `Confidence ${Math.round(inc.confidenceScore*100)}% · ` : ''}
                {new Date(inc.createdAt).toLocaleString()}
              </div>
            </div>
            <span className={`priority-badge ${(inc.severity||'P4').toLowerCase()}`}>{inc.severity || 'P4'}</span>
            <div style={{display:'flex',gap:8,alignItems:'center'}}>
              <span className={`status-badge ${getStatusClass(inc.status)}`}>{getStatusLabel(inc.status)}</span>
              {inc.status === 'PENDING' && (
                <button
                  className="btn btn-modify"
                  disabled={processing === inc.id}
                  onClick={() => handleProcess(inc.id)}
                  style={{fontSize:10,padding:'4px 10px'}}
                >
                  {processing === inc.id ? '⏳' : '▶ RUN'}
                </button>
              )}
            </div>
          </div>
        ))}
      </div>

      {/* ─── QUEUE STATUS ─── */}
      <div className="card" style={{marginBottom:24}}>
        <div className="card-header">
          <div className="card-title">Processing Queue Status</div>
          <div style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>LIVE · auto-refresh 15s</div>
        </div>
        <div className="card-body" style={{display:'flex',gap:32,alignItems:'center',flexWrap:'wrap'}}>
          <div style={{textAlign:'center'}}>
            <div style={{fontFamily:'var(--mono)',fontSize:28,fontWeight:700,color:'var(--amber)'}}>{stats.totalPending}</div>
            <div style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>PENDING</div>
          </div>
          <div style={{textAlign:'center'}}>
            <div style={{fontFamily:'var(--mono)',fontSize:28,fontWeight:700,color:'var(--blue)'}}>{stats.processing}</div>
            <div style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>PROCESSING</div>
          </div>
          <div style={{textAlign:'center'}}>
            <div style={{fontFamily:'var(--mono)',fontSize:28,fontWeight:700,color:'var(--amber)'}}>{stats.hitlPending}</div>
            <div style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>HITL PENDING</div>
          </div>
          <div style={{textAlign:'center'}}>
            <div style={{fontFamily:'var(--mono)',fontSize:28,fontWeight:700,color:'var(--green)'}}>{stats.autoResolved}</div>
            <div style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>AUTO-RESOLVED</div>
          </div>
          <div style={{textAlign:'center'}}>
            <div style={{fontFamily:'var(--mono)',fontSize:28,fontWeight:700,color:'var(--red)'}}>{stats.escalated}</div>
            <div style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>ESCALATED</div>
          </div>
          <div style={{marginLeft:'auto',textAlign:'right'}}>
            <div style={{fontSize:12,color:'var(--green)',fontWeight:600}}>All Systems Nominal</div>
            <div style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>Backend: port 8080 · DB: PostgreSQL</div>
          </div>
        </div>
      </div>

      <div style={{height:40}}></div>
    </div>
  );
};

export default OverviewPage;

const overviewInputStyle: React.CSSProperties = {
  width: '100%',
  background: 'var(--surface2)',
  border: '1px solid var(--border)',
  borderRadius: 8,
  color: 'var(--text)',
  padding: '10px 12px',
  fontSize: 12,
  outline: 'none',
  boxSizing: 'border-box',
};

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.6)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 1000,
  padding: 24,
};

const modalCardStyle: React.CSSProperties = {
  width: 'min(960px, 100%)',
  background: 'var(--surface)',
  border: '1px solid var(--border-bright)',
  borderRadius: 12,
  padding: 20,
  boxShadow: '0 24px 64px rgba(0,0,0,0.28)',
};
