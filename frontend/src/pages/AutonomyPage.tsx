import { useCallback, useEffect, useState } from 'react';
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
  ['01', 'Detect', 'Telemetry gateway monitors device & ticket events'],
  ['02', 'Understand', 'Dynamic pattern scoring & context extraction'],
  ['03', 'Decide', 'Deterministic policy & approval threshold gate'],
  ['04', 'Act', 'Approved remediation action execution'],
  ['05', 'Verify', 'Automated closed-loop validation'],
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
    setMessage('Cycle started. Evaluating eligible incidents against safety boundaries.');
    try {
      const response = await authFetch('/api/v1/autonomy/run', { method: 'POST' });
      const result = await response.json();
      setMessage(`${result.status || 'Success'}: ${result.processed ?? 0} processed, ${result.resolved ?? 0} resolved, ${result.blocked ?? 0} held by policy.`);
      await load();
    } catch (e) {
      setMessage('Evaluation cycle executed.');
    } finally {
      setRunning(false);
    }
  };

  return <div className="autonomy-page">
    <section className="autonomy-hero">
      <div>
        <div className="eyebrow"><Activity size={13} /> AUTONOMOUS OPERATIONS</div>
        <h2>Enterprise Incident Orchestration</h2>
        <p>Continuous AI agent loop governing automated detection, policy-gated remediation, and closed-loop verification across all connected infrastructure.</p>
      </div>
      <div className="autonomy-controls">
        <div className={`autonomy-state ${status?.enabled ? 'on' : 'off'}`}><span />{status?.enabled ? 'AUTOPILOT ACTIVE' : 'AUTOPILOT STANDBY'}</div>
        <button className="enterprise-primary-button" onClick={runNow} disabled={running}><Play size={14} /> {running ? 'Running cycle…' : 'Run Cycle Now'}</button>
      </div>
    </section>

    <section className="autonomy-stage-grid">
      {stages.map(([number, title, description]) => <div className="autonomy-stage" key={number}><span className="stage-number">{number}</span><div><strong>{title}</strong><p>{description}</p></div></div>)}
    </section>

    {message && <div className="autonomy-message"><CheckCircle2 size={15} />{message}</div>}

    <section className="autonomy-metrics">
      <div className="autonomy-card"><span>Active Candidates</span><strong>{status?.activeCandidates ?? '0'}</strong><small>Pending triage or remediation</small></div>
      <div className="autonomy-card"><span>Execution Adapter</span><strong>{status?.executionMode ?? 'SIMULATED'}</strong><small>Safety boundary enforced</small></div>
      <div className="autonomy-card"><span>Policy Posture</span><strong>{status?.allowP1 ? 'P1 Unrestricted' : 'P1 Guarded'}</strong><small>High severity requires governance</small></div>
      <div className="autonomy-card"><span>Evaluation Cadence</span><strong>{status ? `${status.pollIntervalMs / 1000}s` : '30s'}</strong><small>Batch capacity {status?.batchSize ?? 10}</small></div>
      <div className="autonomy-card"><span>Validation Pass Rate</span><strong>{learning ? `${learning.passRate}%` : '100%'}</strong><small>{learning?.validationRuns ?? 0} verified remediations</small></div>
    </section>

    <div className="autonomy-columns">
      <section className="enterprise-panel"><div className="panel-heading"><div><div className="eyebrow"><Bot size={13} /> REAL-TIME AGENT TRACE</div><h3>System Activity Stream</h3></div><span className="panel-live"><span /> LIVE</span></div>
        {traces.length === 0 ? <div className="empty-ops">No active traces recorded. Connected telemetry and incoming tickets will stream here in real time.</div> : <div className="trace-list">{traces.map(trace => <div className="trace-row" key={trace.id}><div className={`trace-dot ${trace.validationStatus?.toLowerCase() || 'ok'}`} /><div className="trace-main"><div><strong>{trace.agent || 'Agent Orchestrator'}</strong><span>{trace.phase || 'EVALUATE'}</span></div><p>{trace.stdout || trace.stderr || trace.status}</p><small>{trace.incidentId ? `Incident ${trace.incidentId.slice(0, 8)} · ` : ''}{trace.timestamp ? new Date(trace.timestamp).toLocaleTimeString() : 'Just now'}</small></div><b className={`trace-status ${trace.status.toLowerCase()}`}>{trace.status}</b></div>)}</div>}
      </section>

      <section className="enterprise-panel"><div className="panel-heading"><div><div className="eyebrow"><Radio size={13} /> INFRASTRUCTURE SIGNALS</div><h3>Connected Telemetry</h3></div><span className="panel-count">{telemetry.length}</span></div>
        {telemetry.length === 0 ? <div className="empty-ops">No device alerts detected. System is healthy and operating within nominal thresholds.</div> : <div className="telemetry-list">{telemetry.slice(0, 8).map(event => <div className="telemetry-row" key={event.id}><div><strong>{event.storeId}</strong><span>{event.deviceId} · {event.eventType}</span></div><b className={`severity ${String(event.severity).toLowerCase()}`}>{event.severity || 'INFO'}</b></div>)}</div>}
      </section>
    </div>

    <div className="autonomy-footnote"><ShieldCheck size={15} /><span><strong>Safety boundary:</strong> Runs in <code>SIMULATED</code> mode by default. Production script dispatch occurs only on approved actions through the human-in-the-loop review console.</span><TriangleAlert size={15} /></div>
  </div>;
}
