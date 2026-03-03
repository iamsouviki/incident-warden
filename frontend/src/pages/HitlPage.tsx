import React, { useState, useEffect, useCallback } from 'react';
import { authFetch } from '../services/api';

interface HitlRequest {
  id: string; incidentId: string; status: string;
  approvalPayload: string; expiresAt: string; createdAt: string;
  decidedBy?: string; decisionReason?: string;
}

const HitlPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [items, setItems] = useState<HitlRequest[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionIn, setActionIn] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);

  const fetchItems = useCallback(async () => {
    try {
      const r = await authFetch(`/api/v1/hitl/pending?tenantId=${tenantId}`);
      if (r.ok) { const d = await r.json(); setItems(d.requests || []); setError(null); }
      else setError('Failed to load HITL queue');
    } catch { setError('Cannot connect to backend'); }
    finally { setLoading(false); }
  }, [tenantId]);

  useEffect(() => { fetchItems(); const t = setInterval(fetchItems, 10000); return () => clearInterval(t); }, [fetchItems]);

  const handleApprove = async (hitlId: string) => {
    setActionIn(hitlId);
    try {
      const r = await authFetch(`/api/v1/hitl/${hitlId}/approve?decidedBy=dashboard-user&reason=Approved+via+dashboard`, { method: 'POST' });
      if (r.ok) { setItems(prev => prev.filter(i => i.id !== hitlId)); }
      else { const e = await r.json(); alert('Error: ' + (e.error || JSON.stringify(e))); }
    } catch { alert('Failed to approve'); }
    finally { setActionIn(null); }
  };

  const handleReject = async (hitlId: string) => {
    const reason = prompt('Rejection reason:', 'Insufficient confidence — manual review required');
    if (!reason) return;
    setActionIn(hitlId);
    try {
      const r = await authFetch(`/api/v1/hitl/${hitlId}/reject?decidedBy=dashboard-user&reason=${encodeURIComponent(reason)}`, { method: 'POST' });
      if (r.ok) { setItems(prev => prev.filter(i => i.id !== hitlId)); }
      else { const e = await r.json(); alert('Error: ' + (e.error || JSON.stringify(e))); }
    } catch { alert('Failed to reject'); }
    finally { setActionIn(null); }
  };

  const parsePayload = (raw: string) => { try { return JSON.parse(raw); } catch { return {}; } };

  return (
    <div className="content">
      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:24}}>
        <div>
          <div style={{fontFamily:'var(--mono)',fontSize:11,color:'var(--text-muted)',letterSpacing:2,textTransform:'uppercase',marginBottom:4}}>APPROVAL QUEUE</div>
          <div style={{display:'flex',alignItems:'center',gap:12}}>
            <span style={{fontFamily:'var(--mono)',fontSize:20,fontWeight:700,color:'var(--text)'}}>{items.length} Pending Approvals</span>
            {items.length > 0 && <span style={{background:'var(--amber-dim)',color:'var(--amber)',border:'1px solid rgba(245,166,35,0.3)',fontFamily:'var(--mono)',fontSize:11,padding:'3px 10px',borderRadius:20}}>{items.length} AWAITING</span>}
          </div>
        </div>
        <button className="btn btn-modify" onClick={fetchItems} style={{fontSize:11}}>⟳ REFRESH</button>
      </div>

      {error && <div className="error-banner">⚠ {error}</div>}

      {loading ? (
        <div className="loading-state">Loading HITL queue…</div>
      ) : items.length === 0 ? (
        <div style={{background:'var(--surface)',border:'1px solid var(--border)',borderRadius:12,padding:48,textAlign:'center'}}>
          <div style={{fontSize:32,marginBottom:12}}>✅</div>
          <div style={{fontFamily:'var(--mono)',fontSize:14,color:'var(--green)',marginBottom:8}}>NO PENDING APPROVALS</div>
          <div style={{fontSize:13,color:'var(--text-muted)'}}>The pipeline is processing all incidents automatically.</div>
        </div>
      ) : (
        <div className="hitl-full-grid">
          {items.map(item => {
            const payload = parsePayload(item.approvalPayload);
            const conf = payload.confidence ?? 0;
            const confPct = Math.round(conf * 100);
            const violations: string[] = payload.guardrailViolations || [];
            const isOpen = expanded === item.id;
            const confColor = confPct > 70 ? 'var(--green)' : confPct > 40 ? 'var(--blue)' : 'var(--amber)';
            return (
              <div key={item.id} className={`hitl-full-card ${confPct < 50 ? 'urgent' : ''}`}>
                <div className="hitl-card-top">
                  <div style={{flex:1}}>
                    <div style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--blue)',marginBottom:4}}>
                      #{item.incidentId.substring(0,8).toUpperCase()} · {new Date(item.createdAt).toLocaleString()}
                    </div>
                    <div style={{fontSize:14,fontWeight:600,color:'var(--text)'}}>
                      {payload.classification || 'Incident'} / {payload.subCategory || 'Unknown Sub-Category'}
                    </div>
                  </div>
                  <div style={{display:'flex',flexDirection:'column',alignItems:'flex-end',gap:6}}>
                    <span style={{fontFamily:'var(--mono)',fontSize:18,fontWeight:700,color:confColor}}>{confPct}%</span>
                    <span style={{fontFamily:'var(--mono)',fontSize:9,color:'var(--text-muted)'}}>CONFIDENCE</span>
                  </div>
                </div>

                <div className="hitl-card-body">
                  <div className="confidence-bar-wrap" style={{marginBottom:16}}>
                    <div className="confidence-fill" style={{width:`${confPct}%`,background:`linear-gradient(90deg, var(--blue), ${confPct > 70 ? 'var(--green)' : confPct > 40 ? 'var(--blue)' : 'var(--amber)'})`}}></div>
                  </div>

                  <div className="hitl-stats-row">
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">Decision</div>
                      <div className="hitl-stat-val" style={{fontSize:12,color:'var(--amber)'}}>HITL_REQ</div>
                    </div>
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">SOP Match</div>
                      <div className="hitl-stat-val" style={{fontSize:11,color:'var(--text-dim)'}}>{payload.sopTitle ? payload.sopTitle.substring(0,15)+'…' : 'N/A'}</div>
                    </div>
                    <div className="hitl-stat">
                      <div className="hitl-stat-label">Expires</div>
                      <div className="hitl-stat-val" style={{fontSize:10,color:'var(--amber)'}}>{new Date(item.expiresAt).toLocaleDateString()}</div>
                    </div>
                  </div>

                  {violations.length > 0 && (
                    <div style={{background:'var(--amber-dim)',border:'1px solid rgba(245,166,35,0.2)',borderRadius:6,padding:'8px 12px',marginBottom:12}}>
                      <div style={{fontFamily:'var(--mono)',fontSize:9,color:'var(--amber)',letterSpacing:1,marginBottom:4}}>GUARDRAIL VIOLATIONS</div>
                      {violations.map((v,i) => <div key={i} style={{fontSize:11,color:'var(--amber)'}}>⚠ {v}</div>)}
                    </div>
                  )}

                  <div
                    style={{cursor:'pointer',display:'flex',alignItems:'center',gap:6,fontSize:11,color:'var(--text-muted)',fontFamily:'var(--mono)',marginBottom:isOpen?12:0}}
                    onClick={() => setExpanded(isOpen ? null : item.id)}
                  >
                    {isOpen ? '▲ HIDE' : '▼ SHOW'} FULL PAYLOAD
                  </div>

                  {isOpen && (
                    <pre style={{background:'var(--surface2)',border:'1px solid var(--border)',borderRadius:6,padding:12,fontSize:10,fontFamily:'var(--mono)',color:'var(--text-dim)',overflow:'auto',maxHeight:200,whiteSpace:'pre-wrap'}}>
                      {JSON.stringify(payload, null, 2)}
                    </pre>
                  )}
                </div>

                <div className="hitl-card-footer">
                  <button className="btn btn-approve" disabled={actionIn === item.id} onClick={() => handleApprove(item.id)}>
                    {actionIn === item.id ? '⏳' : '✓ APPROVE'}
                  </button>
                  <button className="btn btn-modify" disabled={actionIn === item.id}>✎ MODIFY</button>
                  <button className="btn btn-reject" disabled={actionIn === item.id} onClick={() => handleReject(item.id)}>
                    {actionIn === item.id ? '⏳' : '✗ REJECT'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}
      <div style={{height:40}}></div>
    </div>
  );
};

export default HitlPage;
