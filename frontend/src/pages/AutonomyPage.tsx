import React, { useCallback, useEffect, useState } from 'react';
import { Activity, Bot, CheckCircle2, Play, Radio, ShieldCheck, TriangleAlert } from 'lucide-react';
import { authFetch } from '../services/api';
import './AutonomyPage.css';

type AutonomyStatus = {
  enabled: boolean;
  executionMode: string;
  pollIntervalMs: number;
  batchSize: number;
  activeCandidates: number;
  cycleRunning: boolean;
  allowP1: boolean;
};

type Trace = {
  id: string;
  incidentId?: string;
  agent?: string;
  phase?: string;
  validationStatus?: string;
  name: string;
  timestamp?: string;
  status: string;
  stdout?: string;
  stderr?: string;
};

type Telemetry = {
  id: string;
  deviceId: string;
  storeId: string;
  deviceType?: string;
  eventType: string;
  severity?: string;
  message?: string;
  status?: string;
  receivedAt?: string;
};

const stages = [
  ['01', 'Detect', 'Telemetry gateway receives a device event'],
  ['02', 'Understand', 'Incident is enriched and scored'],
  ['03', 'Decide', 'Policy and confidence gates select the route'],
  ['04', 'Act', 'Approved safe action runs automatically'],
  ['05', 'Verify', 'Validation closes or escalates the incident'],
];

export default function AutonomyPage() {
  const [status, setStatus] = useState<AutonomyStatus | null>(null);
  const [traces, setTraces] = useState<Trace[]>([]);
  const [telemetry, setTelemetry] = useState<Telemetry[]>([]);
  const [learning, setLearning] = useState<{ validationRuns: number; passed: number; failed: number; passRate: number } | null>(null);
  const [running, setRunning] = useState(false);
  const [message, setMessage] = useState('');

  const load = useCallback(async () => {
    const [statusRes, tracesRes, telemetryRes, learningRes] = await Promise.all([
      authFetch('/api/v1/autonomy/status'),
      authFetch('/api/v1/autonomy/traces?limit=20'),
      authFetch('/api/v1/telemetry/events'),
      authFetch('/api/v1/autonomy/learning'),
    ]);
    if (statusRes.ok) setStatus(await statusRes.json());
    if (tracesRes.ok) setTraces(await tracesRes.json());
    if (telemetryRes.ok) setTelemetry(await telemetryRes.json());
    if (learningRes.ok) setLearning(await learningRes.json());
  }, []);

  useEffect(() => {
    load();
    const id = window.setInterval(load, 5000);
    return () => window.clearInterval(id);
  }, [load]);

  const runNow = async () => {
    setRunning(true);
    setMessage('Cycle started. Agents are evaluating eligible incidents.');
    try {
      const response = await authFetch('/api/v1/autonomy/run', { method: 'POST' });
      const result = await response.json();
      setMessage(`${result.status}: ${result.processed ?? 0} processed, ${result.resolved ?? 0} resolved, ${result.blocked ?? 0} held by policy.`);
      await load();
    } finally {
      setRunning(false);
    }
  };

  return <div className="autonomy-page">
    <section className="autonomy-hero">
      <div>
        <div className="eyebrow"><Activity size={13} /> AUTONOMOUS OPERATIONS</div>
        <h2>Detect. Decide. Remediate. Verify.</h2>
        <p>One controlled agent loop for every store device and every connected incident source. Humans see the evidence, policy gates protect production, and successful fixes close the loop automatically.</p>
      </div>
      <div className="autonomy-controls">
        <div className={`autonomy-state ${status?.enabled ? 'on' : 'off'}`}><span />{status?.enabled ? 'AUTOPILOT ENABLED' : 'AUTOPILOT OFF'}</div>
        <button className="enterprise-primary-button" onClick={runNow} disabled={running}><Play size={14} /> {running ? 'Running cycle…' : 'Run cycle now'}</button>
      </div>
    </section>

    <section className="autonomy-stage-grid">
      {stages.map(([number, title, description]) => <div className="autonomy-stage" key={number}><span className="stage-number">{number}</span><div><strong>{title}</strong><p>{description}</p></div></div>)}
    </section>

    {message && <div className="autonomy-message"><CheckCircle2 size={15} />{message}</div>}

    <section className="autonomy-metrics">
      <div className="autonomy-card"><span>Eligible candidates</span><strong>{status?.activeCandidates ?? '—'}</strong><small>Auto-resolved or approved</small></div>
      <div className="autonomy-card"><span>Execution mode</span><strong>{status?.executionMode ?? '—'}</strong><small>Production adapter should be explicit</small></div>
      <div className="autonomy-card"><span>Policy posture</span><strong>{status?.allowP1 ? 'P1 allowed' : 'P1 held'}</strong><small>High-risk actions require governance</small></div>
      <div className="autonomy-card"><span>Agent cadence</span><strong>{status ? `${status.pollIntervalMs / 1000}s` : '—'}</strong><small>Batch size {status?.batchSize ?? '—'}</small></div>
      <div className="autonomy-card"><span>Validation pass rate</span><strong>{learning ? `${learning.passRate}%` : '—'}</strong><small>{learning?.validationRuns ?? 0} closed-loop validations</small></div>
    </section>

    <div className="autonomy-columns">
      <section className="enterprise-panel"><div className="panel-heading"><div><div className="eyebrow"><Bot size={13} /> LIVE AGENT TRACE</div><h3>What the system is doing</h3></div><span className="panel-live"><span /> streaming</span></div>
        {traces.length === 0 ? <div className="empty-ops">No agent runs yet. Send telemetry or create an eligible incident to start the loop.</div> : <div className="trace-list">{traces.map(trace => <div className="trace-row" key={trace.id}><div className={`trace-dot ${trace.validationStatus?.toLowerCase()}`} /><div className="trace-main"><div><strong>{trace.agent || 'agent'}</strong><span>{trace.phase || 'phase'}</span></div><p>{trace.stdout || trace.stderr || trace.status}</p><small>{trace.incidentId ? `Incident ${trace.incidentId.slice(0, 8)} · ` : ''}{trace.timestamp ? new Date(trace.timestamp).toLocaleString() : 'now'}</small></div><b className={`trace-status ${trace.status.toLowerCase()}`}>{trace.status}</b></div>)}</div>}
      </section>

      <section className="enterprise-panel"><div className="panel-heading"><div><div className="eyebrow"><Radio size={13} /> DEVICE SIGNALS</div><h3>Latest telemetry</h3></div><span className="panel-count">{telemetry.length}</span></div>
        {telemetry.length === 0 ? <div className="empty-ops">No device events received. POST a normalized event to <code>/api/v1/telemetry/events</code>.</div> : <div className="telemetry-list">{telemetry.slice(0, 8).map(event => <div className="telemetry-row" key={event.id}><div><strong>{event.storeId}</strong><span>{event.deviceId} · {event.eventType}</span></div><b className={`severity ${String(event.severity).toLowerCase()}`}>{event.severity || 'INFO'}</b></div>)}</div>}
      </section>
    </div>

    <div className="autonomy-footnote"><ShieldCheck size={15} /><span><strong>Safety boundary:</strong> this branch runs the autonomous adapter in <code>SIMULATED</code> mode by default. Set a reviewed production execution adapter and enable autopilot explicitly before affecting real devices.</span><TriangleAlert size={15} /></div>
  </div>;
}
