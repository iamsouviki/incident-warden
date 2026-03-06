import { useEffect, useState } from 'react';
import { apiGet } from '../services/api';
import './AuditLogPage.css';

interface AuditEvent {
  id: string;
  incidentId: string | null;
  tenantId: string;
  traceId: string | null;
  agentId: string | null;
  eventType: string;
  eventPayload: string | null;
  recordHash: string | null;
  createdAt: string;
}

function truncate(s: string | null | undefined, max = 40): string {
  if (!s) return '—';
  return s.length > max ? s.slice(0, max) + '…' : s;
}

function formatTs(ts: string) {
  try {
    return new Date(ts).toLocaleString();
  } catch {
    return ts;
  }
}

const EVENT_COLORS: Record<string, string> = {
  INCIDENT_CREATED:    '#3b82f6',
  CONFIDENCE_SCORED:   '#8b5cf6',
  SOP_MATCHED:         '#06b6d4',
  ACTION_EXECUTED:     '#22c55e',
  HITL_REQUIRED:       '#f59e0b',
  HITL_APPROVED:       '#22d3ee',
  HITL_REJECTED:       '#f87171',
  HITL_ESCALATED:      '#fb923c',
  GUARDRAIL_TRIGGERED: '#ef4444',
  ESCALATED:           '#f97316',
};

export default function AuditLogPage({ tenantId }: { tenantId: string }) {
  const [events, setEvents]   = useState<AuditEvent[]>([]);
  const [filtered, setFiltered] = useState<AuditEvent[]>([]);
  const [filter, setFilter]   = useState('ALL');
  const [loading, setLoading] = useState(true);
  const [error, setError]     = useState('');

  useEffect(() => {
    apiGet<AuditEvent[]>(`/api/v1/audit/tenant/${tenantId}`)
      .then(data => {
        setEvents(data);
        setFiltered(data);
      })
      .catch(err => setError(err.message))
      .finally(() => setLoading(false));
  }, [tenantId]);

  useEffect(() => {
    if (filter === 'ALL') {
      setFiltered(events);
    } else {
      setFiltered(events.filter(e => e.eventType === filter));
    }
  }, [filter, events]);

  const eventTypes = ['ALL', ...Array.from(new Set(events.map(e => e.eventType))).sort()];

  if (loading) return <div className="al-loading">Loading audit log…</div>;
  if (error)   return <div className="al-error">Error: {error}</div>;

  return (
    <div className="al-root">
      <div className="al-header">
        <h1 className="al-title">Audit Log</h1>
        <span className="al-badge">{filtered.length} events</span>
      </div>

      {/* Filter chips */}
      <div className="al-filters">
        {eventTypes.map(type => (
          <button
            key={type}
            className={`al-chip ${filter === type ? 'al-chip--active' : ''}`}
            onClick={() => setFilter(type)}
          >
            {type === 'ALL' ? 'All Events' : type.replace(/_/g, ' ')}
          </button>
        ))}
      </div>

      {/* Table */}
      {filtered.length === 0 ? (
        <div className="al-empty">No audit events found</div>
      ) : (
        <div className="al-table-wrap">
          <table className="al-table">
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>Event Type</th>
                <th>Agent</th>
                <th>Incident ID</th>
                <th>Trace ID</th>
                <th>Hash</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map(ev => (
                <tr key={ev.id}>
                  <td className="al-ts">{formatTs(ev.createdAt)}</td>
                  <td>
                    <span
                      className="al-badge-type"
                      style={{ background: (EVENT_COLORS[ev.eventType] ?? '#64748b') + '22',
                               color: EVENT_COLORS[ev.eventType] ?? '#94a3b8',
                               border: `1px solid ${EVENT_COLORS[ev.eventType] ?? '#475569'}` }}
                    >
                      {ev.eventType.replace(/_/g, ' ')}
                    </span>
                  </td>
                  <td className="al-mono">{ev.agentId ?? '—'}</td>
                  <td className="al-mono">{truncate(ev.incidentId, 8)}</td>
                  <td className="al-mono">{truncate(ev.traceId, 16)}</td>
                  <td className="al-mono al-hash" title={ev.recordHash ?? ''}>
                    {truncate(ev.recordHash, 12)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
