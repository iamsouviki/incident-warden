import { useEffect, useState } from 'react';
import {
  BarChart, Bar, PieChart, Pie, Cell, Tooltip, XAxis, YAxis, ResponsiveContainer, Legend
} from 'recharts';
import { apiGet } from '../services/api';
import './AnalyticsPage.css';

const TENANT_ID = '00000000-0000-0000-0000-000000000001';

const PIE_COLORS = ['#3b82f6','#22c55e','#f59e0b','#ef4444','#8b5cf6','#06b6d4'];

interface OverviewData {
  incidents: Record<string, number>;
  hitl:      Record<string, number>;
  auditEventCount: number;
}

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="an-stat-card" style={{ borderTopColor: color }}>
      <div className="an-stat-value" style={{ color }}>{value.toLocaleString()}</div>
      <div className="an-stat-label">{label}</div>
    </div>
  );
}

export default function AnalyticsPage() {
  const [data, setData]     = useState<OverviewData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError]   = useState('');

  useEffect(() => {
    apiGet<OverviewData>(`/api/v1/analytics/overview/${TENANT_ID}`)
      .then(setData)
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return <div className="an-loading">Loading analytics…</div>;
  if (error)   return <div className="an-error">Error: {error}</div>;
  if (!data)   return null;

  const { incidents, hitl, auditEventCount } = data;

  // Bar chart data — incident breakdown
  const incidentBarData = [
    { name: 'Pending',       value: incidents.totalPending  ?? 0 },
    { name: 'Processing',    value: incidents.processing    ?? 0 },
    { name: 'Auto-Resolved', value: incidents.autoResolved  ?? 0 },
    { name: 'HITL Pending',  value: incidents.hitlPending   ?? 0 },
    { name: 'Escalated',     value: incidents.escalated     ?? 0 },
    { name: 'Resolved',      value: incidents.resolved      ?? 0 },
    { name: 'Failed',        value: incidents.failed        ?? 0 },
  ].filter(d => d.value > 0);

  // Pie chart data — HITL decisions
  const hitlPieData = [
    { name: 'Pending',   value: hitl.pending   ?? 0 },
    { name: 'Approved',  value: hitl.approved  ?? 0 },
    { name: 'Modified',  value: hitl.modified  ?? 0 },
    { name: 'Rejected',  value: hitl.rejected  ?? 0 },
    { name: 'Escalated', value: hitl.escalated ?? 0 },
  ].filter(d => d.value > 0);

  const totalIncidents = Object.values(incidents).reduce((a, b) => a + b, 0);
  const totalHitl      = Object.values(hitl).reduce((a, b) => a + b, 0);

  return (
    <div className="an-root">
      <div className="an-header">
        <h1 className="an-title">Analytics Overview</h1>
        <span className="an-badge">Live data · tenant default</span>
      </div>

      {/* KPI cards */}
      <div className="an-cards">
        <StatCard label="Total Incidents"   value={totalIncidents}  color="#3b82f6" />
        <StatCard label="Auto-Resolved"     value={incidents.autoResolved ?? 0} color="#22c55e" />
        <StatCard label="HITL Decisions"    value={totalHitl}       color="#f59e0b" />
        <StatCard label="Audit Events"      value={auditEventCount} color="#8b5cf6" />
        <StatCard label="Escalated"         value={incidents.escalated ?? 0}   color="#ef4444" />
        <StatCard label="In Processing"     value={incidents.processing ?? 0}  color="#06b6d4" />
      </div>

      {/* Charts row */}
      <div className="an-charts">
        {/* Incident status bar chart */}
        <div className="an-chart-card">
          <h2 className="an-chart-title">Incident Status Breakdown</h2>
          {incidentBarData.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <BarChart data={incidentBarData} margin={{ top: 8, right: 16, bottom: 8, left: 0 }}>
                <XAxis dataKey="name" tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} />
                <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} />
                <Tooltip
                  contentStyle={{ background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 8 }}
                  labelStyle={{ color: '#1e293b' }}
                  itemStyle={{ color: '#475569' }}
                />
                <Bar dataKey="value" radius={[4, 4, 0, 0]}>
                  {incidentBarData.map((_, i) => (
                    <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                  ))}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          ) : (
            <div className="an-empty">No incident data yet</div>
          )}
        </div>

        {/* HITL decisions pie chart */}
        <div className="an-chart-card">
          <h2 className="an-chart-title">HITL Decision Distribution</h2>
          {hitlPieData.length > 0 ? (
            <ResponsiveContainer width="100%" height={260}>
              <PieChart>
                <Pie
                  data={hitlPieData}
                  cx="50%"
                  cy="50%"
                  innerRadius={70}
                  outerRadius={105}
                  dataKey="value"
                  paddingAngle={3}
                >
                  {hitlPieData.map((_, i) => (
                    <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                  ))}
                </Pie>
                <Tooltip
                  contentStyle={{ background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 8 }}
                  itemStyle={{ color: '#475569' }}
                />
                <Legend
                  formatter={(value) => <span style={{ color: '#475569', fontSize: 12 }}>{value}</span>}
                />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <div className="an-empty">No HITL decisions yet</div>
          )}
        </div>
      </div>
    </div>
  );
}
