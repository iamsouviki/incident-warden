import React, { useState, useEffect, useCallback } from 'react';

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

  const fetchAll = useCallback(async () => {
    try {
      const [sR, iR, hR] = await Promise.all([
        fetch(`/api/v1/incidents/stats/${tenantId}`),
        fetch(`/api/v1/incidents?tenantId=${tenantId}&limit=20`),
        fetch(`/api/v1/hitl/pending?tenantId=${tenantId}`),
      ]);
      if (sR.ok) setStats(await sR.json());
      if (iR.ok) { const d = await iR.json(); setIncidents(Array.isArray(d) ? d : d.incidents || []); }
      if (hR.ok) { const d = await hR.json(); setHitl(d.requests || []); }
      setError(null);
    } catch { setError('Cannot reach backend on port 8080'); }
  }, [tenantId]);

  useEffect(() => { fetchAll(); const t = setInterval(fetchAll, 15000); return () => clearInterval(t); }, [fetchAll]);

  const handleProcess = async (incidentId: string) => {
    setProcessing(incidentId);
    try {
      const r = await fetch(`/api/v1/incidents/${incidentId}/process?tenantId=${tenantId}`, { method: 'POST' });
      const d = await r.json();
      await fetchAll();
      alert(`Pipeline result: ${d.decision || d.status || JSON.stringify(d)}`);
    } catch { alert('Pipeline error'); }
    finally { setProcessing(null); }
  };

  const handleApprove = async (hitlId: string) => {
    try {
      await fetch(`/api/v1/hitl/${hitlId}/approve?decidedBy=dashboard-user&reason=Approved+via+dashboard`, { method: 'POST' });
      await fetchAll();
    } catch { alert('Approve failed'); }
  };

  const handleReject = async (hitlId: string) => {
    const reason = prompt('Rejection reason:', 'Insufficient confidence');
    if (!reason) return;
    try {
      await fetch(`/api/v1/hitl/${hitlId}/reject?decidedBy=dashboard-user&reason=${encodeURIComponent(reason)}`, { method: 'POST' });
      await fetchAll();
    } catch { alert('Reject failed'); }
  };

  // Compute derived stats
  const total = stats.totalPending + stats.processing + stats.hitlPending + stats.autoResolved + stats.escalated;
  const autoRate = total > 0 ? Math.round((stats.autoResolved / total) * 100) : 0;
  const p1Count = incidents.filter(i => i.severity === 'P1').length;
  const p2Count = incidents.filter(i => i.severity === 'P2').length;

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
            <div className="card-title">Incidents by Outcome — Last 7 Days</div>
            <div style={{display:'flex',gap:12,fontSize:10,fontFamily:'var(--mono)'}}>
              <span style={{color:'var(--blue)'}}>■ AUTO</span>
              <span style={{color:'var(--amber)'}}>■ HITL</span>
              <span style={{color:'var(--red)'}}>■ ESCALATED</span>
            </div>
          </div>
          <div className="card-body">
            <div className="bar-chart">
              {[
                { label:'MON', auto:46, hitl:12, esc:8 },
                { label:'TUE', auto:62, hitl:12, esc:4 },
                { label:'WED', auto:38, hitl:16, esc:8 },
                { label:'THU', auto:70, hitl:10, esc:6 },
                { label:'FRI', auto:55, hitl:14, esc:10 },
                { label:'SAT', auto:30, hitl:8, esc:4 },
                { label:'SUN', auto:42, hitl:12, esc:6 },
              ].map(d => (
                <div key={d.label} className="bar-group">
                  <div className="bar-stack">
                    <div className="bar auto" style={{height:`${d.auto}px`}}></div>
                    <div className="bar hitl" style={{height:`${d.hitl}px`}}></div>
                    <div className="bar esc" style={{height:`${d.esc}px`}}></div>
                    <span className="bar-label">{d.label}</span>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>
        <div className="card">
          <div className="card-header">
            <div className="card-title">HITL Decision Breakdown</div>
          </div>
          <div className="card-body">
            <div className="donut-container">
              <div className="donut-wrap">
                <svg width="110" height="110" viewBox="0 0 110 110">
                  <circle cx="55" cy="55" r="40" fill="none" stroke="#e2e8f0" strokeWidth="16"/>
                  <circle cx="55" cy="55" r="40" fill="none" stroke="#059669" strokeWidth="16"
                    strokeDasharray="180.8 68.9" strokeDashoffset="62.8" transform="rotate(-90 55 55)"/>
                  <circle cx="55" cy="55" r="40" fill="none" stroke="#d97706" strokeWidth="16"
                    strokeDasharray="45.2 205.6" strokeDashoffset="-118" transform="rotate(-90 55 55)"/>
                  <circle cx="55" cy="55" r="40" fill="none" stroke="#dc2626" strokeWidth="16"
                    strokeDasharray="25.1 225.7" strokeDashoffset="-163.2" transform="rotate(-90 55 55)"/>
                </svg>
                <div className="donut-label">
                  <div className="donut-pct">72%</div>
                  <div className="donut-sub">APPROVED</div>
                </div>
              </div>
              <div className="legend">
                <div className="legend-item"><div className="legend-dot" style={{background:'var(--green)'}}></div>Approved<span className="legend-pct">72%</span></div>
                <div className="legend-item"><div className="legend-dot" style={{background:'var(--amber)'}}></div>Modified<span className="legend-pct">18%</span></div>
                <div className="legend-item"><div className="legend-dot" style={{background:'var(--red)'}}></div>Rejected<span className="legend-pct">10%</span></div>
              </div>
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
