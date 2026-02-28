import React, { useState, useEffect } from 'react';

interface Sop {
  id: string; title: string; category: string; scope: string;
  status: string; reliabilityScore: number; successCount: number;
  failureCount: number; actionPlanJson?: string; description?: string;
  ownerTeam?: string; createdAt: string; version?: string;
}

const CATEGORY_COLORS: Record<string, string> = {
  DATABASE: 'rgba(79,142,247,0.15)', NETWORK: 'rgba(48,217,156,0.1)',
  SECURITY: 'rgba(255,85,85,0.1)', INFRASTRUCTURE: 'rgba(181,123,255,0.1)',
  DEPLOYMENT: 'rgba(245,166,35,0.1)', MEMORY: 'rgba(245,166,35,0.1)',
  STORAGE: 'rgba(136,136,170,0.1)', CACHE: 'rgba(181,123,255,0.1)',
};

const CATEGORY_TEXT: Record<string, string> = {
  DATABASE: 'var(--blue)', NETWORK: 'var(--green)', SECURITY: 'var(--red)',
  INFRASTRUCTURE: 'var(--purple)', DEPLOYMENT: 'var(--amber)',
  MEMORY: 'var(--amber)', STORAGE: 'var(--text-dim)', CACHE: 'var(--purple)',
};

const SopPage: React.FC<{ tenantId: string }> = ({ tenantId }) => {
  const [sops, setSops] = useState<Sop[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Sop | null>(null);
  const [filter, setFilter] = useState('ALL');

  const fetchSops = async () => {
    try {
      const r = await fetch(`/api/v1/sops?tenantId=${tenantId}`);
      if (r.ok) { const d = await r.json(); setSops(Array.isArray(d) ? d : d.sops || []); setError(null); }
      else setError('Failed to load SOPs');
    } catch { setError('Cannot connect to backend'); }
    finally { setLoading(false); }
  };

  useEffect(() => { fetchSops(); }, [tenantId]);

  const categories = ['ALL', ...Array.from(new Set(sops.map(s => s.category)))];
  const filtered = filter === 'ALL' ? sops : sops.filter(s => s.category === filter);

  const getRelClass = (r: number) => r >= 0.85 ? '' : r >= 0.65 ? 'amber' : 'red';
  const getSopStatusClass = (s: string) => s === 'ACTIVE' ? 'sop-active' : s === 'DRAFT' ? 'sop-draft' : 'sop-stale';

  if (loading) return <div className="loading-state" style={{padding:80}}>Loading SOPs…</div>;

  return (
    <div className="content">
      <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',marginBottom:24}}>
        <div>
          <div style={{fontFamily:'var(--mono)',fontSize:11,color:'var(--text-muted)',letterSpacing:2,textTransform:'uppercase',marginBottom:4}}>KNOWLEDGE BASE</div>
          <div style={{fontFamily:'var(--mono)',fontSize:20,fontWeight:700,color:'var(--text)'}}>{sops.length} Standard Operating Procedures</div>
        </div>
        <button className="btn btn-modify" onClick={fetchSops} style={{fontSize:11}}>⟳ REFRESH</button>
      </div>

      {/* Category filter tabs */}
      <div className="tabs" style={{marginBottom:20}}>
        {categories.map(cat => (
          <div key={cat} className={`tab ${filter === cat ? 'active' : ''}`} onClick={() => setFilter(cat)}>{cat}</div>
        ))}
      </div>

      {error && <div className="error-banner">⚠ {error}</div>}

      {/* Table view + Detail split */}
      <div className="sop-layout">
        <div>
          {filtered.length === 0 ? (
            <div className="empty-state-msg">No SOPs for this category.</div>
          ) : filtered.map(sop => (
            <div
              key={sop.id}
              className={`sop-list-item ${selected?.id === sop.id ? 'selected' : ''}`}
              onClick={() => setSelected(sop)}
            >
              <div className="sop-list-title">{sop.title}</div>
              <div className="sop-list-meta">
                <span
                  className="sop-category-tag"
                  style={{background: CATEGORY_COLORS[sop.category] || 'var(--surface3)', color: CATEGORY_TEXT[sop.category] || 'var(--text-dim)'}}
                >{sop.category}</span>
                <span className={`sop-status ${getSopStatusClass(sop.status)}`}>{sop.status}</span>
                <span style={{fontFamily:'var(--mono)',fontSize:10,color:(sop.reliabilityScore||0) >= 0.85 ? 'var(--green)' : (sop.reliabilityScore||0) >= 0.65 ? 'var(--amber)' : 'var(--red)'}}>
                  ★ {((sop.reliabilityScore||0)*100).toFixed(0)}%
                </span>
              </div>
            </div>
          ))}
        </div>

        <div className="sop-detail-panel">
          {!selected ? (
            <div className="sop-detail-empty">← Select a SOP to view details</div>
          ) : (
            <>
              <div className="sop-detail-header">
                <div className="sop-detail-title">{selected.title}</div>
                <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
                  <span className="sop-category-tag" style={{background: CATEGORY_COLORS[selected.category] || 'var(--surface3)', color: CATEGORY_TEXT[selected.category] || 'var(--text-dim)', fontSize:10, padding:'3px 8px', borderRadius:4}}>{selected.category}</span>
                  <span className={`sop-status ${getSopStatusClass(selected.status)}`}>{selected.status}</span>
                  {selected.version && <span style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>v{selected.version}</span>}
                </div>
              </div>
              <div className="sop-detail-body">
                {/* Reliability bar */}
                <div style={{background:'var(--surface2)',borderRadius:8,padding:'16px 20px',marginBottom:16,border:'1px solid var(--border)'}}>
                  <div style={{display:'flex',justifyContent:'space-between',marginBottom:8}}>
                    <span style={{fontFamily:'var(--mono)',fontSize:9,letterSpacing:2,color:'var(--text-muted)',textTransform:'uppercase'}}>Reliability Score</span>
                    <span style={{fontFamily:'var(--mono)',fontSize:16,fontWeight:700,color:(selected.reliabilityScore||0) >= 0.85 ? 'var(--green)' : (selected.reliabilityScore||0) >= 0.65 ? 'var(--amber)' : 'var(--red)'}}>
                      {((selected.reliabilityScore||0)*100).toFixed(0)}%
                    </span>
                  </div>
                  <div style={{background:'var(--surface3)',borderRadius:4,height:8,overflow:'hidden'}}>
                    <div style={{height:'100%',borderRadius:4,width:`${(selected.reliabilityScore||0)*100}%`,background:(selected.reliabilityScore||0)>=0.85?'var(--green)':(selected.reliabilityScore||0)>=0.65?'var(--amber)':'var(--red)',transition:'width 0.8s ease'}}></div>
                  </div>
                  <div style={{display:'flex',justifyContent:'space-between',marginTop:8}}>
                    <span style={{fontSize:12,color:'var(--green)'}}>✓ {selected.successCount} Succeeded</span>
                    <span style={{fontSize:12,color:'var(--red)'}}>✗ {selected.failureCount} Failed</span>
                  </div>
                </div>

                <div className="detail-row"><span className="detail-label">Scope:</span><span className="detail-val">{selected.scope || '—'}</span></div>
                {selected.ownerTeam && <div className="detail-row"><span className="detail-label">Owner Team:</span><span className="detail-val">{selected.ownerTeam}</span></div>}
                <div className="detail-row"><span className="detail-label">Created:</span><span className="detail-val">{new Date(selected.createdAt).toLocaleDateString()}</span></div>
                {selected.description && (
                  <div style={{background:'var(--surface2)',borderRadius:6,padding:'12px 14px',marginTop:12,marginBottom:12,fontSize:13,color:'var(--text-dim)',lineHeight:1.6}}>
                    {selected.description}
                  </div>
                )}

                {selected.actionPlanJson && (
                  <div className="sop-action-plan">
                    <h4>Action Plan</h4>
                    <pre>{(() => { try { return JSON.stringify(JSON.parse(selected.actionPlanJson), null, 2); } catch { return selected.actionPlanJson; } })()}</pre>
                  </div>
                )}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Summary table */}
      <div className="card" style={{marginTop:24,marginBottom:40}}>
        <div className="card-header">
          <div className="card-title">SOP Library — All Procedures</div>
          <div style={{fontFamily:'var(--mono)',fontSize:10,color:'var(--text-muted)'}}>{sops.length} TOTAL</div>
        </div>
        <table className="data-table">
          <thead>
            <tr>
              <th>Title</th><th>Category</th><th>Reliability</th>
              <th>Success</th><th>Failed</th><th>Status</th>
            </tr>
          </thead>
          <tbody>
            {sops.map(sop => (
              <tr key={sop.id} onClick={() => setSelected(sop)} style={{cursor:'pointer'}}>
                <td style={{color:'var(--text)',fontWeight:500}}>{sop.title}</td>
                <td>
                  <span style={{fontFamily:'var(--mono)',fontSize:9,color: CATEGORY_TEXT[sop.category] || 'var(--text-dim)'}}>
                    {sop.category}
                  </span>
                </td>
                <td>
                  <div className="reliability">
                    <div className="rel-bar"><div className={`rel-fill ${getRelClass(sop.reliabilityScore||0)}`} style={{width:`${(sop.reliabilityScore||0)*100}%`}}></div></div>
                    <span style={{fontFamily:'var(--mono)',fontSize:11,color:(sop.reliabilityScore||0)>=0.85?'var(--green)':(sop.reliabilityScore||0)>=0.65?'var(--amber)':'var(--red)'}}>
                      {((sop.reliabilityScore||0)*100).toFixed(0)}%
                    </span>
                  </div>
                </td>
                <td style={{fontFamily:'var(--mono)',color:'var(--green)'}}>{sop.successCount}</td>
                <td style={{fontFamily:'var(--mono)',color:'var(--red)'}}>{sop.failureCount}</td>
                <td><span className={`sop-status ${getSopStatusClass(sop.status)}`}>{sop.status}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default SopPage;
