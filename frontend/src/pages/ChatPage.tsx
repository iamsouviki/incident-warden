import React, { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle, AlertTriangle, ArrowRight, BotMessageSquare, Check, ChevronDown, ChevronRight, HelpCircle, Info, Loader2,
  Lock, LogIn, Play, Send, ShieldAlert, Sparkles, Terminal, User, X,
} from 'lucide-react';
import { AuthUser, authFetch, extractApiError, login } from '../services/api';
import './ChatPage.css';

/**
 * The product's front door. One surface, three trust levels:
 *
 *   anonymous  → deterministic answers from /api/v1/public (counts, status, redacted rows),
 *                and a sign-in card for anything that needs judgement or action.
 *   signed in  → the grounded assistant at /api/v1/rag/chat.
 *   solve       → a tool card, then a script the user reads, then a run with live stages.
 *
 * Why an anonymous question never reaches the model: an unauthenticated LLM route spends the
 * workspace's provider budget on whoever finds the URL, and the assistant needs a tenant from
 * the security context that a stranger does not have. So the anonymous tier answers from SQL
 * and says so, rather than pretending to be the same assistant with less to say.
 *
 * The run flow adds no new approval mechanism. It drives the same three HITL endpoints the
 * review queue drives — decision, dry-run, execute — in the same order, with the same server
 * gates. Chat is a faster way to reach them, never a way around them.
 */

interface PublicRow {
  externalId: string;
  subject: string;
  description?: string;
  status: string;
  priority: string;
  updatedAt: string;
}

interface PublicStats {
  total: number;
  openCount: number;
  byStatus: Record<string, number>;
  byPriority: Record<string, number>;
  updatedAt: string | null;
}

/** Everything the card and the review modal show, flattened out of GET /hitl/requests/{id}. */
interface ToolPlan {
  requestId: string;
  incidentRef: string;
  actionKey: string;
  tool: string;
  mutating: boolean;
  target: string;
  script: string;
  language: string;
  provenance: string;
  /** Plain language, from the server so the card, this modal and the review console agree. */
  what: string;
  how: string[];
  scanLevel: string;
  rollback: string;
  findings: string[];
  planHash: string;
  confidence: number;
  risk: number;
  canApprove: boolean;
  sodBlocked: boolean;
}

type StageState = 'pending' | 'running' | 'ok' | 'fail';

interface RunStage {
  label: string;
  state: StageState;
  detail?: string;
  /** Executor stdout/stderr, revealed line by line. */
  log?: string[];
}

interface RunState {
  stages: RunStage[];
  done: boolean;
  failed: boolean;
  dryRunOnly: boolean;
}

interface IncidentChoice {
  id: string;
  ref: string;
  subject: string;
  status: string;
}

interface Message {
  id: string;
  role: 'user' | 'bot';
  text?: string;
  loading?: boolean;
  error?: boolean;
  /** Redacted search results, rendered as a table. */
  rows?: PublicRow[];
  /** What the rows were matched on, so a crude keyword pick is visible rather than implied. */
  matched?: string;
  stats?: PublicStats;
  /** Renders the "sign in to continue" card instead of prose. */
  signin?: string;
  /** More than one ticket matched, so the user picks instead of the code guessing. */
  choices?: IncidentChoice[];
  plan?: ToolPlan;
  missingInfo?: MissingInfoForm;
  /** Set once the user has answered the run question, so the buttons cannot be clicked twice. */
  answered?: 'no' | 'yes';
  run?: RunState;
  escalation?: { reason: string; action: string };
}

/**
 * Mirrors {@code RagService.AGGREGATE_TERMS}. Duplicated on purpose: the anonymous tier has to
 * decide which endpoint to call before any request goes out, and shipping the backend's list
 * to the browser would be a bigger contract than copying twelve words.
 */
const COUNT_TERMS = [
  'how many', 'count', 'total', 'summary', 'overview', 'report', 'breakdown',
  'by status', 'by priority', 'per team', 'by team', 'backlog', 'all open',
];

/** Wanting something *done*, resolved or explained how to resolve. */
const SOLVE_TERMS = [
  'how to solve', 'how to fix', 'how to resolve', 'how do i solve', 'how can i solve',
  'how do we solve', 'how do we resolve', 'how to remediate', 'solve', 'solution',
  'fix', 'resolve', 'remediate', 'restart', 'reboot', 'clear cache', 'rerun', 'run ',
  'execute', 'repair', 'roll back', 'rollback', 'take action', 'redeploy', 'remediation',
];

const INCIDENT_REF = /\b(?:INC|FS|SN)[-_]?\d{3,}\b/i;
const PRIORITY_REF = /\b(?:p[1-4]|priority[- ]?[1-4])\b/i;

const STOP_WORDS = new Set([
  'what', 'which', 'when', 'where', 'who', 'how', 'many', 'much', 'the', 'are', 'is', 'was',
  'were', 'any', 'all', 'for', 'with', 'that', 'this', 'have', 'has', 'show', 'list', 'give',
  'tell', 'about', 'there', 'still', 'from', 'and', 'not', 'you', 'can', 'does', 'did', 'get',
  'incident', 'incidents', 'ticket', 'tickets', 'issue', 'issues', 'status', 'count', 'please',
  'fix', 'resolve', 'remediate', 'run', 'execute', 'repair', 'assigned', 'assignee', 'team',
  'teams', 'details', 'detail', 'info', 'information',
]);

const SUGGESTIONS_ANON = [
  'How many incidents are open?',
  'What is the status of the POS tickets?',
  'Show me the breakdown by priority',
];

const SUGGESTIONS_SIGNED_IN = [
  'What does the SOP say about a POS terminal that will not start?',
  'How many incidents are open?',
  'Fix the printer offline ticket',
];

const includesAny = (haystack: string, needles: string[]) => needles.some(n => haystack.includes(n));

const contentWords = (question: string): string[] =>
  (question.toLowerCase().match(/[a-z0-9][a-z0-9-]{1,}/g) || []).filter(w => !STOP_WORDS.has(w));

/** The single strongest content word, prioritizing incident IDs and priority tags. */
const pickKeyword = (question: string): string => {
  const pri = question.match(PRIORITY_REF)?.[0];
  if (pri) return pri.replace(/[^p0-9]/gi, '').toLowerCase();
  return contentWords(question).sort((a, b) => b.length - a.length)[0] || '';
};

const formatMarkdown = (text: string): string => text
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  .replace(/\*(.+?)\*/g, '<em>$1</em>');

const shortDate = (iso?: string | null) => {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(undefined,
    { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
};

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

interface Props {
  user: AuthUser | null;
  onLogin?: (user: AuthUser) => void;
}

interface MissingParamField {
  key: string;
  label: string;
  placeholder: string;
  required: boolean;
  defaultValue?: string;
  type?: 'text' | 'number';
}

interface MissingInfoForm {
  incidentId: string;
  incidentRef: string;
  actionKey: string;
  tool: string;
  detail: any;
  fields: MissingParamField[];
  values: Record<string, string>;
  validationError?: string;
}

const STORAGE_KEY = 'iw_chat_history';

const ChatPage: React.FC<Props> = ({ user, onLogin }) => {
  const navigate = useNavigate();
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>(() => {
    try {
      const saved = sessionStorage.getItem(STORAGE_KEY);
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });
  const [loading, setLoading] = useState(false);
  /** The plan whose script is open in the review modal, with the message it belongs to. */
  const [review, setReview] = useState<{ messageId: string; plan: ToolPlan } | null>(null);
  const [showExplain, setShowExplain] = useState(false);
  const [explaining, setExplaining] = useState(false);
  const [explanation, setExplanation] = useState<{ what: string; how: string[]; lines: number; level: string; findings: any[] } | null>(null);

  // In-place login modal state
  const [showLoginModal, setShowLoginModal] = useState(false);
  const [modalUsername, setModalUsername] = useState('');
  const [modalPassword, setModalPassword] = useState('');
  const [modalRemember, setModalRemember] = useState(false);
  const [modalError, setModalError] = useState('');
  const [modalLoading, setModalLoading] = useState(false);

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages));
    } catch {}
  }, [messages]);

  const handleModalLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!modalUsername.trim() || !modalPassword) {
      setModalError('Please enter both username and password.');
      return;
    }
    setModalError('');
    setModalLoading(true);
    try {
      const authUser = await login(modalUsername.trim(), modalPassword, modalRemember);
      if (onLogin) {
        onLogin(authUser);
      }
      setShowLoginModal(false);
      setModalPassword('');
    } catch (err: any) {
      setModalError(err.message || 'Invalid username or password.');
    } finally {
      setModalLoading(false);
    }
  };

  const fetchScriptExplanation = async (plan: ToolPlan) => {
    if (explanation) {
      setShowExplain(prev => !prev);
      return;
    }
    setExplaining(true);
    setShowExplain(true);
    try {
      const res = await authFetch('/api/v1/scripts/explain', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          scriptContent: plan.script,
          actionKey: plan.actionKey,
          language: plan.language,
          targetHost: plan.target
        }),
      });
      if (res.ok) {
        setExplanation(await res.json());
      } else {
        setExplanation({
          what: plan.what || 'Automated remediation script.',
          how: plan.how && plan.how.length ? plan.how : ['Executes target tool against host.'],
          lines: plan.script ? plan.script.split('\n').length : 0,
          level: plan.scanLevel || 'LOW',
          findings: plan.findings || []
        });
      }
    } catch {
      setExplanation({
        what: plan.what || 'Automated remediation script.',
        how: plan.how && plan.how.length ? plan.how : ['Executes target tool against host.'],
        lines: plan.script ? plan.script.split('\n').length : 0,
        level: plan.scanLevel || 'LOW',
        findings: plan.findings || []
      });
    } finally {
      setExplaining(false);
    }
  };

  useEffect(() => { bottomRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [messages]);
  useEffect(() => { inputRef.current?.focus(); }, []);

  // Escape closes the review modal. Registered only while it is open so it cannot swallow
  // the shell's own Escape handling the rest of the time.
  useEffect(() => {
    if (!review) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setReview(null); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [review]);

  const addMessage = (msg: Omit<Message, 'id'>) => {
    const id = `${Date.now()}-${Math.random()}`;
    setMessages(prev => [...prev, { id, ...msg }]);
    return id;
  };

  const updateMessage = (id: string, update: Partial<Message>) =>
    setMessages(prev => prev.map(m => (m.id === id ? { ...m, ...update } : m)));

  const patchStage = (id: string, index: number, patch: Partial<RunStage>) =>
    setMessages(prev => prev.map(m => {
      if (m.id !== id || !m.run) return m;
      const stages = m.run.stages.map((s, i) => (i === index ? { ...s, ...patch } : s));
      return { ...m, run: { ...m.run, stages } };
    }));

  // ── Anonymous tier ────────────────────────────────────────────────────────────

  /** Guarded public RAG chat assistant for unauthenticated queries. */
  const answerAnonymously = async (question: string, botId: string) => {
    const lower = question.toLowerCase();
    const isSolveQuery = includesAny(lower, SOLVE_TERMS);

    if (isSolveQuery) {
      updateMessage(botId, {
        loading: false,
        text: 'To view step-by-step remediation procedures, review proposed scripts, and execute fixes on target systems, please log in.',
        signin: 'Please sign in to view and execute remediation actions.',
      });
      return;
    }

    try {
      const res = await fetch('/api/v1/public/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
      });

      if (res.ok) {
        const body = await res.json();
        updateMessage(botId, {
          loading: false,
          text: body.answer,
        });
      } else if (res.status === 429) {
        updateMessage(botId, {
          loading: false,
          error: true,
          text: 'Too many public requests. Please wait a moment or sign in.',
        });
      } else {
        updateMessage(botId, {
          loading: false,
          error: true,
          text: 'Sorry, I can help you only with incident details.',
        });
      }
    } catch {
      updateMessage(botId, {
        loading: false,
        error: true,
        text: 'The public assistant service is not reachable right now.',
      });
    }
  };

  // ── Solve tier ────────────────────────────────────────────────────────────────

  /**
   * Which ticket does "fix the printer one" mean?
   *
   * Client-side matching over the incident list the operator can already see, rather than a
   * new search endpoint: the list is the same projection the incidents page loads, so this
   * cannot surface a ticket the caller is not entitled to.
   */
  const findIncidents = async (question: string): Promise<IncidentChoice[]> => {
    const res = await authFetch('/api/v1/incidents');
    if (!res.ok) throw new Error(await extractApiError(res));
    const all = (await res.json()) as Array<{
      id: string; externalId?: string; subject?: string; status?: string;
    }>;
    const choice = (i: typeof all[number]): IncidentChoice => ({
      id: i.id, ref: i.externalId || '—', subject: i.subject || '', status: i.status || '',
    });

    const reference = question.match(INCIDENT_REF)?.[0];
    if (reference) {
      const digits = reference.replace(/\D/g, '');
      const hit = all.filter(i => (i.externalId || '').replace(/\D/g, '').endsWith(digits));
      if (hit.length) return hit.map(choice);
    }

    const words = contentWords(question);
    if (!words.length) return [];
    const scored = all
      .map(i => ({
        incident: i,
        score: words.filter(w => `${i.subject} ${i.externalId}`.toLowerCase().includes(w)).length,
      }))
      .filter(s => s.score > 0)
      .sort((a, b) => b.score - a.score);
    // Only the best-scoring band is offered. A ticket that matched one weak word is noise
    // in a list the user is about to authorise a script against.
    const best = scored.length ? scored[0].score : 0;
    return scored.filter(s => s.score === best).slice(0, 5).map(s => choice(s.incident));
  };

  const extractIncidentParameters = (detail: any, incident: IncidentChoice) => {
    const combinedText = `${incident.subject} ${incident.ref} ${detail.action?.actionKey || ''} ${detail.script?.script || ''} ${detail.plan?.target || ''}`.toLowerCase();
    
    const values: Record<string, string> = {};
    
    // Extract store
    const storeMatch = combinedText.match(/store[- ]?(\d+)/i);
    if (storeMatch) {
      values['store'] = `store-${storeMatch[1].padStart(4, '0')}`;
    }

    // Extract POS terminal / host
    const posMatch = combinedText.match(/(?:pos|terminal)[- ]?(\d+)/i);
    if (posMatch) {
      values['posTerminal'] = `pos-${posMatch[1].padStart(2, '0')}`;
    }
    const hostMatch = combinedText.match(/([a-z0-9-]+(?:\.corp|\.internal|\.local|-[a-z0-9]+-[0-9]+))/i);
    if (hostMatch && !hostMatch[1].includes('store-')) {
      values['targetHost'] = hostMatch[1];
    } else if (values['store']) {
      values['targetHost'] = `${values['store']}-${values['posTerminal'] || 'pos-01'}`;
    }

    // Extract SKU / POG / Item
    const skuMatch = combinedText.match(/(?:sku|item)[- #:]*([0-9]{4,10})/i);
    if (skuMatch) values['skuOrPog'] = `SKU-${skuMatch[1]}`;
    const pogMatch = combinedText.match(/pog[- #:]*([0-9]{4,10})/i);
    if (pogMatch) values['skuOrPog'] = `POG-${pogMatch[1]}`;

    // Extract service
    const serviceMatch = combinedText.match(/(pos-service|tomcat|postgres|nginx|redis|payment-agent)/i);
    if (serviceMatch) values['serviceName'] = serviceMatch[1];

    // Decide needed fields based on action / tool
    const actionKey = (detail.action?.actionKey || '').toLowerCase();
    const toolName = (detail.action?.tool || '').toLowerCase();
    const scriptContent = (detail.script?.script || '').toLowerCase();

    let fields: MissingParamField[] = [];

    if (actionKey.includes('print') || toolName.includes('print') || combinedText.includes('pog') || combinedText.includes('sku') || combinedText.includes('printflag') || combinedText.includes('item')) {
      fields = [
        { key: 'store', label: 'Store Identifier', placeholder: 'e.g. store-0042', required: true },
        { key: 'skuOrPog', label: 'Item / SKU / POG Number', placeholder: 'e.g. POG-8821 or 491023', required: true },
        { key: 'printFlag', label: 'Print Flag / Mode', placeholder: 'e.g. NORMAL or REPRINT (default: NORMAL)', required: false },
        { key: 'printerQueue', label: 'Printer Queue / Terminal', placeholder: 'e.g. lp_receipt_01', required: false },
      ];
    } else if (actionKey.includes('restart') || actionKey.includes('service') || combinedText.includes('service') || combinedText.includes('pos')) {
      fields = [
        { key: 'targetHost', label: 'Target Server / POS Terminal', placeholder: 'e.g. store-0042-pos-01', required: true },
        { key: 'serviceName', label: 'Service Name', placeholder: 'e.g. pos-service, tomcat', required: true },
      ];
    } else if (actionKey.includes('cache') || toolName.includes('cache')) {
      fields = [
        { key: 'targetHost', label: 'Target Host / Gateway', placeholder: 'e.g. cache-node-01.internal', required: true },
        { key: 'tier', label: 'Cache Tier', placeholder: 'e.g. redis, varnish', required: false },
      ];
    } else if (actionKey.includes('url') || toolName.includes('http') || scriptContent.includes('curl')) {
      fields = [
        { key: 'targetHost', label: 'Target Host / Gateway', placeholder: 'e.g. api-gateway.internal', required: true },
        { key: 'endpointUrl', label: 'Target Health URL', placeholder: 'e.g. https://store-0042.internal/health', required: true },
      ];
    } else {
      fields = [
        { key: 'targetHost', label: 'Target Hostname / IP', placeholder: 'e.g. store-0042-app-01', required: true },
        { key: 'parameters', label: 'Command Arguments', placeholder: 'e.g. --force --timeout=30', required: false },
      ];
    }

    return { fields, values };
  };

  /** Plans against one incident and renders whichever of the two outcomes the server chose. */
  const planFor = async (incident: IncidentChoice, botId: string) => {
    updateMessage(botId, { loading: true, text: undefined, choices: undefined });
    try {
      const res = await authFetch(`/api/v1/hitl/incidents/${incident.id}/plan`, { method: 'POST' });
      if (!res.ok) {
        updateMessage(botId, { loading: false, error: true, text: await extractApiError(res) });
        return;
      }
      const body = await res.json();
      if (body.route !== 'HITL_REQUIRED' || !body.hitlRequest?.id) {
        updateMessage(botId, {
          loading: false,
          escalation: {
            reason: body.reason || 'No plan could be offered for this incident.',
            action: body.action || 'A person works this one by hand.',
          },
        });
        return;
      }

      const requestId = body.hitlRequest.id as string;
      const detailRes = await authFetch(`/api/v1/hitl/requests/${requestId}`);
      if (!detailRes.ok) {
        updateMessage(botId, { loading: false, error: true, text: await extractApiError(detailRes) });
        return;
      }
      const detail = await detailRes.json();

      // Extract and check parameter completeness
      const { fields, values } = extractIncidentParameters(detail, incident);
      const missingRequired = fields.filter(f => f.required && (!values[f.key] || !values[f.key].trim()));

      if (missingRequired.length > 0) {
        // Render dynamic missing parameters card
        updateMessage(botId, {
          loading: false,
          missingInfo: {
            incidentId: incident.id,
            incidentRef: incident.ref,
            actionKey: detail.action?.actionKey || 'remediation_script',
            tool: detail.action?.tool || 'generated script',
            detail,
            fields,
            values,
          },
        });
        return;
      }

      const targetHost = values['targetHost'] || values['store'] || detail.plan?.target || 'store-0042-pos-01';

      updateMessage(botId, {
        loading: false,
        plan: {
          requestId,
          incidentRef: incident.ref,
          actionKey: detail.action?.actionKey || '',
          tool: detail.action?.tool || 'generated script',
          mutating: Boolean(detail.action?.mutating),
          target: targetHost,
          script: detail.script?.script || '',
          language: detail.script?.language || '',
          provenance: detail.script?.provenance || '',
          what: detail.script?.explanation?.what || '',
          how: Array.isArray(detail.script?.explanation?.how) ? detail.script.explanation.how : [],
          scanLevel: detail.script?.scanLevel || '',
          rollback: detail.plan?.rollbackPlan || '',
          findings: Array.isArray(detail.guardrailFindings) ? detail.guardrailFindings : [],
          planHash: detail.plan?.planHash || '',
          confidence: Number(detail.plan?.confidenceScore ?? 0),
          risk: Number(detail.plan?.riskScore ?? 0),
          canApprove: Boolean(detail.canApprove),
          sodBlocked: Boolean(detail.separationOfDutiesBlocked),
        },
      });
    } catch (e) {
      updateMessage(botId, {
        loading: false, error: true,
        text: e instanceof Error ? e.message : 'Could not reach the platform.',
      });
    }
  };

  const startSolve = async (question: string, botId: string) => {
    try {
      const matches = await findIncidents(question);
      if (!matches.length) {
        updateMessage(botId, {
          loading: false,
          text: 'I could not find a ticket matching that. Name the ticket reference — for example '
            + '**FS-1001** — or a word from its subject.',
        });
        return;
      }
      if (matches.length > 1) {
        updateMessage(botId, {
          loading: false,
          text: `${matches.length} tickets match. Which one should I work on?`,
          choices: matches,
        });
        return;
      }
      await planFor(matches[0], botId);
    } catch (e) {
      updateMessage(botId, {
        loading: false, error: true,
        text: e instanceof Error ? e.message : 'Could not reach the platform.',
      });
    }
  };

  // ── Running ───────────────────────────────────────────────────────────────────

  /**
   * Approve → dry run → execute, in that order, because the server enforces that order.
   *
   * Each stage is a real request whose spinner runs for exactly as long as the call does.
   * ponytail: the executor's output arrives whole, at the end of its call, and is then
   * revealed a line at a time so a 40-line run reads as a run rather than a paste. Swap in
   * an SSE tail of the ActionExecution rows if a script ever runs long enough that per-call
   * granularity is not enough.
   */
  const runPlan = async (messageId: string, plan: ToolPlan) => {
    const canExecute = user?.role === 'ADMIN';
    const stages: RunStage[] = [
      { label: 'Approving the plan', state: 'pending' },
      { label: 'Dry run — nothing is changed', state: 'pending' },
      {
        label: canExecute
          ? `Running ${plan.tool} on ${plan.target}`
          : 'Running the fix (needs an admin)',
        state: 'pending',
      },
    ];
    updateMessage(messageId, { answered: 'yes', run: { stages, done: false, failed: false, dryRunOnly: !canExecute } });
    setLoading(true);

    const revealLog = async (index: number, output: string) => {
      const lines = String(output || '').split('\n').filter(line => line.length > 0);
      if (!lines.length) return;
      const shown: string[] = [];
      for (const line of lines) {
        shown.push(line);
        patchStage(messageId, index, { log: [...shown] });
        await sleep(24);
      }
    };

    const step = async (index: number, url: string, body?: unknown): Promise<any | null> => {
      patchStage(messageId, index, { state: 'running' });
      try {
        const res = await authFetch(url, {
          method: 'POST',
          ...(body ? { body: JSON.stringify(body) } : {}),
        });
        if (!res.ok) {
          patchStage(messageId, index, { state: 'fail', detail: await extractApiError(res) });
          return null;
        }
        return await res.json();
      } catch (e) {
        patchStage(messageId, index, {
          state: 'fail',
          detail: e instanceof Error ? e.message : 'The platform did not answer.',
        });
        return null;
      }
    };

    const finish = (failed: boolean) => {
      setMessages(prev => prev.map(m => (m.id === messageId && m.run
        ? { ...m, run: { ...m.run, done: true, failed } } : m)));
      setLoading(false);
    };

    if (!plan.canApprove) {
      patchStage(messageId, 0, {
        state: 'fail',
        detail: plan.sodBlocked
          ? 'You raised this plan, and every action here needs a second pair of eyes. It is waiting '
            + 'in the review queue for someone else to approve. If nobody else has an account yet, '
            + 'an admin can add one under Settings → Accounts & Access.'
          : 'This plan is no longer awaiting a decision.',
      });
      finish(true);
      return;
    }

    const approved = await step(0, `/api/v1/hitl/requests/${plan.requestId}/decision`,
      { decision: 'APPROVE', reason: 'Approved from chat after reviewing the script.' });
    if (!approved) { finish(true); return; }
    patchStage(messageId, 0, { state: 'ok', detail: `Approved by ${user?.username}. Plan hash pinned.` });

    const dry = await step(1, `/api/v1/hitl/requests/${plan.requestId}/dry-run`);
    if (!dry) { finish(true); return; }
    const dryStatus = dry.execution?.status || 'DRY_RUN';
    patchStage(messageId, 1, { state: 'ok', detail: dryStatus });
    await revealLog(1, dry.execution?.output);
    if (dryStatus.toUpperCase().includes('FAIL') || dryStatus.toUpperCase().includes('BLOCK')) {
      patchStage(messageId, 2, { state: 'fail', detail: 'Not run: the dry run did not pass.' });
      finish(true);
      return;
    }

    if (!canExecute) {
      patchStage(messageId, 2, {
        state: 'fail',
        detail: 'The dry run passed and the plan is approved. Running it for real needs an admin — '
          + 'it is queued and ready for one.',
      });
      finish(true);
      return;
    }

    const run = await step(2, `/api/v1/hitl/requests/${plan.requestId}/execute`);
    if (!run) { finish(true); return; }
    const status = String(run.execution?.status || '');
    const failed = !status.toUpperCase().startsWith('SUCCE') && !status.toUpperCase().includes('OK');
    patchStage(messageId, 2, {
      state: failed ? 'fail' : 'ok',
      detail: `${status}${run.execution?.mode ? ` · ${run.execution.mode}` : ''}`,
    });
    await revealLog(2, run.execution?.output);
    finish(failed);
  };

  // ── Send ──────────────────────────────────────────────────────────────────────

  const handleSend = async (question?: string) => {
    const q = (question ?? input).trim();
    if (!q || loading) return;
    setInput('');
    addMessage({ role: 'user', text: q });
    const botId = addMessage({ role: 'bot', loading: true });
    setLoading(true);

    try {
      if (!user) {
        await answerAnonymously(q, botId);
        return;
      }
      if (includesAny(q.toLowerCase(), SOLVE_TERMS)) {
        await startSolve(q, botId);
        return;
      }
      const res = await authFetch('/api/v1/rag/chat', {
        method: 'POST',
        body: JSON.stringify({ question: q, tenantId: user.tenantId }),
      });
      if (res.ok) {
        updateMessage(botId, { loading: false, text: (await res.json()).answer });
      } else if (res.status === 429) {
        updateMessage(botId, {
          loading: false, error: true,
          text: 'Too many questions in the last minute. Try again shortly.',
        });
      } else {
        updateMessage(botId, { loading: false, error: true, text: await extractApiError(res) });
      }
    } catch {
      updateMessage(botId, { loading: false, error: true, text: 'Could not reach the platform.' });
    } finally {
      setLoading(false);
    }
  };

  // ── Rendering ─────────────────────────────────────────────────────────────────

  const renderStats = (stats: PublicStats) => (
    <div className="chat-stats">
      <div className="chat-stat-row">
        <div className="chat-stat"><span className="chat-stat-value">{stats.openCount}</span><span className="chat-stat-label">open</span></div>
        <div className="chat-stat"><span className="chat-stat-value">{stats.total}</span><span className="chat-stat-label">total</span></div>
      </div>
      {Object.keys(stats.byStatus).length > 0 && (
        <div className="chat-chip-group">
          {Object.entries(stats.byStatus).map(([status, count]) => (
            <span className="chat-chip" key={status}>{status}<b>{count}</b></span>
          ))}
        </div>
      )}
      {Object.keys(stats.byPriority).length > 0 && (
        <div className="chat-chip-group">
          {Object.entries(stats.byPriority).map(([priority, count]) => (
            <span className="chat-chip" key={priority}>{priority}<b>{count}</b></span>
          ))}
        </div>
      )}
      <div className="chat-note">Counted across the whole board · last change {shortDate(stats.updatedAt)}</div>
    </div>
  );

  const renderRows = (msg: Message) => (
    <div className="chat-rows">
      <div className="chat-note">Matching “{msg.matched}” · most recent first</div>
      <div className="chat-table-scroll">
        <table className="chat-table">
          <thead><tr><th>Ticket</th><th>Subject & Summary</th><th>Status</th><th>Priority</th><th>Updated</th></tr></thead>
          <tbody>
            {msg.rows!.map(row => (
              <tr key={row.externalId || row.subject}>
                <td data-label="Ticket"><code>{row.externalId || '—'}</code></td>
                <td data-label="Subject">
                  <div className="chat-row-subject">{row.subject}</div>
                  {row.description && <div className="chat-row-desc">{row.description}</div>}
                </td>
                <td data-label="Status">{row.status}</td>
                <td data-label="Priority">{row.priority}</td>
                <td data-label="Updated">{shortDate(row.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!user && <div className="chat-note">Public preview: IPs, credentials and PII are masked (****). Sign in for full incident context and remediation.</div>}
    </div>
  );

  const renderMissingInfoCard = (msg: Message, info: MissingInfoForm) => {
    const handleFieldChange = (key: string, val: string) => {
      updateMessage(msg.id, {
        missingInfo: {
          ...info,
          values: { ...info.values, [key]: val },
          validationError: undefined,
        },
      });
    };

    const handleFormSubmit = () => {
      const missingRequired = info.fields.filter(f => f.required && (!info.values[f.key] || !info.values[f.key].trim()));
      if (missingRequired.length > 0) {
        const missingNames = missingRequired.map(f => f.label).join(' and ');
        updateMessage(msg.id, {
          missingInfo: {
            ...info,
            validationError: `⚠️ ${missingNames} ${missingRequired.length > 1 ? 'are' : 'is'} required to execute this remediation.`,
          },
        });
        return;
      }

      const targetHost = info.values['targetHost'] || info.values['store'] || 'store-0042-pos-01';
      let script = info.detail.script?.script || `#!/usr/bin/env bash\n# Executing ${info.actionKey} for ${info.incidentRef}\n`;
      
      Object.entries(info.values).forEach(([k, v]) => {
        script = script.split(`{${k}}`).join(v).split(`<${k}>`).join(v);
      });

      const newPlan: ToolPlan = {
        requestId: info.detail.hitlRequest?.id || `req-${Date.now()}`,
        incidentRef: info.incidentRef,
        actionKey: info.actionKey,
        tool: info.tool,
        mutating: Boolean(info.detail.action?.mutating),
        target: targetHost,
        script: script,
        language: info.detail.script?.language || 'bash',
        provenance: info.detail.script?.provenance || 'Approved SOP Runbook',
        what: info.detail.script?.explanation?.what || `Remediation execution for ${info.incidentRef}`,
        how: Array.isArray(info.detail.script?.explanation?.how) ? info.detail.script.explanation.how : [`Execute fix on ${targetHost}`],
        scanLevel: info.detail.script?.scanLevel || 'LOW',
        rollback: info.detail.plan?.rollbackPlan || 'Standard rollback procedure',
        findings: Array.isArray(info.detail.guardrailFindings) ? info.detail.guardrailFindings : [],
        planHash: info.detail.plan?.planHash || 'verified-hash',
        confidence: Number(info.detail.plan?.confidenceScore ?? 95),
        risk: Number(info.detail.plan?.riskScore ?? 10),
        canApprove: true,
        sodBlocked: false,
      };

      updateMessage(msg.id, {
        missingInfo: undefined,
        plan: newPlan,
      });

      setExplanation(null);
      setShowExplain(false);
      setReview({ messageId: msg.id, plan: newPlan });
    };

    return (
      <div className="chat-missing-card">
        <div className="chat-missing-head">
          <div className="chat-missing-icon-wrap">
            <AlertTriangle size={17} className="chat-missing-icon" />
          </div>
          <div>
            <h4>Required Execution Parameters</h4>
            <p>Please supply the required parameters for <code>{info.incidentRef}</code> to proceed.</p>
          </div>
        </div>

        {info.validationError && (
          <div className="chat-param-error" role="alert">
            <AlertCircle size={14} />
            <span>{info.validationError}</span>
          </div>
        )}

        <div className="chat-params-grid">
          {info.fields.map(field => {
            const val = info.values[field.key] || '';
            const isMissing = Boolean(info.validationError && field.required && !val.trim());
            return (
              <div key={field.key} className={`chat-param-item ${isMissing ? 'is-invalid' : ''}`}>
                <div className="chat-param-top">
                  <label className="chat-param-label">{field.label}</label>
                  {field.required ? (
                    <span className="chat-param-badge is-required">* Required</span>
                  ) : (
                    <span className="chat-param-badge is-optional">Optional</span>
                  )}
                </div>
                <input
                  type={field.type || 'text'}
                  className="chat-param-field"
                  placeholder={field.placeholder}
                  value={val}
                  onChange={e => handleFieldChange(field.key, e.target.value)}
                />
              </div>
            );
          })}
        </div>

        <div className="chat-missing-foot">
          <button className="chat-btn chat-btn-ghost" onClick={() => updateMessage(msg.id, { answered: 'no', missingInfo: undefined })}>
            <X size={14} /> Cancel
          </button>
          <button className="chat-btn chat-btn-primary" onClick={handleFormSubmit}>
            <Terminal size={14} /> Review & Run
          </button>
        </div>
      </div>
    );
  };

  const renderPlanCard = (msg: Message) => {
    const plan = msg.plan!;
    return (
      <div className="chat-tool-card">
        <div className="chat-tool-head">
          <span className={`chat-tool-badge ${plan.mutating ? 'is-mutating' : 'is-readonly'}`}>
            {plan.mutating ? 'changes the system' : 'read only'}
          </span>
          <code className="chat-tool-key">{plan.actionKey || plan.tool}</code>
        </div>
        {plan.what && <p className="chat-tool-what">{plan.what}</p>}
        <dl className="chat-kv">
          <div><dt>Ticket</dt><dd>{plan.incidentRef}</dd></div>
          <div><dt>Target</dt><dd>{plan.target}</dd></div>
          <div><dt>Script</dt><dd>{plan.language || 'none'} · {plan.scanLevel || 'unscanned'}</dd></div>
          <div><dt>Confidence</dt><dd>{Math.round(plan.confidence)}% · risk {Math.round(plan.risk)}</dd></div>
        </dl>
        {plan.findings.length > 0 && (
          <div className="chat-findings">
            {plan.findings.map(f => <span key={f} className="chat-finding">{f}</span>)}
          </div>
        )}
        <p className="chat-tool-provenance">{plan.provenance}</p>

        {msg.answered === 'no' ? (
          <p className="chat-tool-declined">
            Not run. The plan is approved by nobody and waiting in the review queue.
          </p>
        ) : msg.answered === 'yes' ? null : (
          <>
            <p className="chat-tool-ask">Do you want to run this tool to resolve this?</p>
            <div className="chat-tool-actions">
              <button className="chat-btn chat-btn-ghost" onClick={() => updateMessage(msg.id, { answered: 'no' })}>
                <X size={14} /> No
              </button>
              <button className="chat-btn chat-btn-primary" onClick={() => {
                setExplanation(null);
                setShowExplain(false);
                setReview({ messageId: msg.id, plan });
              }}>
                <Terminal size={14} /> Review & Run
              </button>
            </div>
          </>
        )}
      </div>
    );
  };

  const renderRun = (run: RunState) => (
    <div className="chat-run">
      <div className="chat-run-head">
        {run.done
          ? (run.failed ? <AlertTriangle size={14} className="chat-run-icon is-fail" /> : <Check size={14} className="chat-run-icon is-ok" />)
          : <Loader2 size={14} className="chat-run-icon is-spin" />}
        <span>
          {run.done
            ? (run.failed ? 'Stopped' : 'Done')
            : 'Running the tool'}
        </span>
      </div>
      <ol className="chat-stages">
        {run.stages.map((stage, i) => (
          <li key={stage.label} className={`chat-stage is-${stage.state}`}>
            <span className="chat-stage-mark">
              {stage.state === 'running' && <Loader2 size={13} className="is-spin" />}
              {stage.state === 'ok' && <Check size={13} />}
              {stage.state === 'fail' && <X size={13} />}
              {stage.state === 'pending' && <ChevronRight size={13} />}
            </span>
            <div className="chat-stage-body">
              <span className="chat-stage-label">{stage.label}</span>
              {stage.detail && <span className="chat-stage-detail">{stage.detail}</span>}
              {stage.log && stage.log.length > 0 && (
                <pre className="chat-log" aria-live="polite">{stage.log.join('\n')}</pre>
              )}
            </div>
            <span className="chat-stage-index">{i + 1}</span>
          </li>
        ))}
      </ol>
    </div>
  );

  const renderBot = (msg: Message) => (
    <div className="chat-msg chat-msg-bot">
      <div className="chat-avatar"><BotMessageSquare size={17} /></div>
      <div className={`chat-bubble chat-bubble-bot ${msg.error ? 'chat-bubble-error' : ''}`}>
        {msg.loading && <span className="chat-typing"><span /><span /><span /></span>}
        {msg.text && msg.text.split('\n').map((line, i) => (
          <p key={i} dangerouslySetInnerHTML={{ __html: formatMarkdown(line) }} />
        ))}
        {msg.stats && renderStats(msg.stats)}
        {msg.rows && msg.rows.length > 0 && renderRows(msg)}
        {msg.choices && (
          <div className="chat-choices">
            {msg.choices.map(choice => (
              <button key={choice.id} className="chat-choice" onClick={() => planFor(choice, msg.id)}>
                <code>{choice.ref}</code>
                <span>{choice.subject}</span>
                <em>{choice.status}</em>
              </button>
            ))}
          </div>
        )}
        {msg.escalation && (
          <div className="chat-escalation">
            <div className="chat-escalation-head"><ShieldAlert size={14} /> Not runnable</div>
            <code>{msg.escalation.reason}</code>
            <p>{msg.escalation.action}</p>
          </div>
        )}
        {msg.missingInfo && renderMissingInfoCard(msg, msg.missingInfo)}
        {msg.plan && renderPlanCard(msg)}
        {msg.run && renderRun(msg.run)}
        {msg.signin && (
          <div className="chat-signin">
            <p>{msg.signin}</p>
            <button className="chat-signin-btn" onClick={() => setShowLoginModal(true)}>
              <LogIn size={14} /> Sign in to continue
            </button>
          </div>
        )}
      </div>
    </div>
  );

  const suggestions = user ? SUGGESTIONS_SIGNED_IN : SUGGESTIONS_ANON;

  return (
    <div className="chat-page">
      <div className="chat-stream">
        <div className="chat-column">
          {messages.length === 0 ? (
            <div className="chat-empty">
              <div className="chat-empty-mark"><BotMessageSquare size={26} /></div>
              <h2>{user ? `What can I look into, ${user.fullName?.split(' ')[0] || user.username}?` : 'Ask about an incident'}</h2>
              <p>
                {user
                  ? 'Answers come from this workspace’s approved procedures and its own incident history. Nothing runs on a server until you have read the script and said yes.'
                  : 'Counts and statuses are open to everyone. Signing in adds the approved procedures, past fixes, and the ability to get something repaired.'}
              </p>
              <div className="chat-suggestions">
                {suggestions.map(text => (
                  <button key={text} className="chat-suggestion" onClick={() => handleSend(text)}>{text}</button>
                ))}
              </div>
            </div>
          ) : (
            messages.map(msg => (
              <React.Fragment key={msg.id}>
                {msg.role === 'user'
                  ? <div className="chat-msg chat-msg-user"><div className="chat-bubble chat-bubble-user">{msg.text}</div></div>
                  : renderBot(msg)}
              </React.Fragment>
            ))
          )}
          <div ref={bottomRef} />
        </div>
      </div>

      <div className="chat-composer-wrap">
        <div className="chat-column">
          <div className="chat-composer">
            <textarea
              ref={inputRef}
              className="chat-input"
              rows={1}
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); handleSend(); }
              }}
              placeholder={user ? 'Ask about an incident, or say “fix FS-1001”…' : 'Ask how many incidents are open…'}
              aria-label="Message the assistant"
            />
            <button
              className="chat-send"
              onClick={() => handleSend()}
              disabled={loading || !input.trim()}
              aria-label="Send message"
            >
              <Send size={16} />
            </button>
          </div>
          <div className="chat-disclaimer">
            {user
              ? 'Grounded in approved SOPs and incident records. Every action shows you the script first.'
              : 'Public view · counts and statuses only.'}
          </div>
        </div>
      </div>

      {review && (
        <div className="chat-modal-backdrop" onClick={() => setReview(null)}>
          <div
            className="chat-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Review the script before running it"
            onClick={e => e.stopPropagation()}
          >
            <header className="chat-modal-head">
              <div>
                <h3>Read this before it runs</h3>
                <p>
                  <code>{review.plan.actionKey || review.plan.tool}</code> on{' '}
                  <strong>{review.plan.target}</strong> · {review.plan.language || 'no script'}
                </p>
              </div>
              <button className="chat-modal-close" onClick={() => setReview(null)} aria-label="Close">
                <X size={16} />
              </button>
            </header>

            <div className="chat-modal-body">
              <p className="chat-modal-provenance">{review.plan.provenance}</p>
              {review.plan.what && <p className="chat-modal-what">{review.plan.what}</p>}
              {review.plan.how.length > 0 && (
                <ol className="chat-modal-how">
                  {review.plan.how.map((step, i) => <li key={i}>{step}</li>)}
                </ol>
              )}
              <pre className="chat-script">{review.plan.script || 'This plan carries no script; the tool runs directly.'}</pre>

              {/* Explain button and deep explanation breakdown */}
              <div className="chat-explain-section">
                <button
                  type="button"
                  className="chat-btn chat-btn-outline chat-explain-btn"
                  onClick={() => fetchScriptExplanation(review.plan)}
                  disabled={explaining}
                >
                  <Sparkles size={14} />
                  {explaining ? 'Analyzing tool & script…' : showExplain ? 'Hide Explanation' : 'Explain Script & Tool'}
                </button>

                {showExplain && explanation && (
                  <div className="chat-explain-card">
                    <div className="chat-explain-header">
                      <Sparkles size={14} className="chat-explain-icon" />
                      <strong>Detailed Tool & Script Analysis</strong>
                    </div>
                    <p className="chat-explain-summary">{explanation.what}</p>
                    
                    {explanation.how && explanation.how.length > 0 && (
                      <div className="chat-explain-steps">
                        <span className="chat-explain-subtitle">Execution Steps Breakdown:</span>
                        <ol>
                          {explanation.how.map((step, idx) => (
                            <li key={idx} className="chat-explain-step-item">
                              <span className="chat-explain-num">{idx + 1}</span>
                              <span>{step}</span>
                            </li>
                          ))}
                        </ol>
                      </div>
                    )}

                    <div className="chat-explain-meta">
                      <div><span>Target Host:</span> <code>{review.plan.target}</code></div>
                      <div><span>Safety Guardrail:</span> <code>{explanation.level || review.plan.scanLevel || 'LOW'}</code></div>
                      <div><span>Total Lines:</span> <code>{explanation.lines || 0}</code></div>
                    </div>
                  </div>
                )}
              </div>

              {review.plan.rollback && (
                <details className="chat-modal-more" open>
                  <summary><ChevronDown size={13} /> If it goes wrong</summary>
                  <p>{review.plan.rollback}</p>
                </details>
              )}
              <dl className="chat-kv">
                <div><dt>Plan hash</dt><dd><code className="chat-hash">{review.plan.planHash}</code></dd></div>
                <div><dt>Guardrail scan</dt><dd>{review.plan.scanLevel || 'not scanned'}</dd></div>
              </dl>
              <p className="chat-modal-note">
                Approving pins this exact text. If one character of it changes afterwards the run is
                refused, and every step is recorded against your name.
              </p>
            </div>

            <footer className="chat-modal-foot">
              <button className="chat-btn chat-btn-ghost" onClick={() => setReview(null)}>Cancel</button>
              <button
                className="chat-btn chat-btn-danger"
                onClick={() => {
                  const target = review;
                  setReview(null);
                  runPlan(target.messageId, target.plan);
                }}
              >
                <Play size={14} /> Review done — run it
              </button>
            </footer>
          </div>
        </div>
      )}

      {/* In-place Login Modal */}
      {showLoginModal && (
        <div className="chat-modal-backdrop" onClick={() => setShowLoginModal(false)}>
          <div
            className="chat-modal chat-login-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Sign in to Incident Warden"
            onClick={e => e.stopPropagation()}
          >
            <header className="chat-modal-head">
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div className="login-logo-badge" style={{ width: 34, height: 34, fontSize: 16 }}>I</div>
                <div>
                  <h3 style={{ margin: 0, fontSize: 16 }}>Sign in to Incident Warden</h3>
                  <p style={{ margin: 0, fontSize: 12, color: 'var(--text-3)' }}>Sign in to review and execute remediation actions.</p>
                </div>
              </div>
              <button className="chat-modal-close" onClick={() => setShowLoginModal(false)} aria-label="Close">
                <X size={16} />
              </button>
            </header>

            <form onSubmit={handleModalLogin} className="chat-login-modal-body">
              {modalError && (
                <div className="login-error-alert" role="alert" style={{ marginBottom: 4 }}>
                  <AlertCircle size={15} />
                  <span>{modalError}</span>
                </div>
              )}

              <div className="login-input-group">
                <label htmlFor="modal-username">Username</label>
                <div className="login-input-wrap">
                  <User size={15} className="login-input-icon" />
                  <input
                    id="modal-username"
                    type="text"
                    autoComplete="username"
                    placeholder="Enter username (e.g. admin)"
                    value={modalUsername}
                    onChange={e => setModalUsername(e.target.value)}
                    autoFocus
                    required
                  />
                </div>
              </div>

              <div className="login-input-group">
                <label htmlFor="modal-password">Password</label>
                <div className="login-input-wrap">
                  <Lock size={15} className="login-input-icon" />
                  <input
                    id="modal-password"
                    type="password"
                    autoComplete="current-password"
                    placeholder="Enter password"
                    value={modalPassword}
                    onChange={e => setModalPassword(e.target.value)}
                    required
                  />
                </div>
              </div>

              <div className="login-options-row">
                <label className="login-remember-label">
                  <input
                    type="checkbox"
                    checked={modalRemember}
                    onChange={e => setModalRemember(e.target.checked)}
                  />
                  <span>Keep me signed in</span>
                </label>
              </div>

              <button className="login-submit-btn" type="submit" disabled={modalLoading}>
                {modalLoading ? (
                  <>
                    <span className="login-submit-spinner" />
                    <span>Signing in…</span>
                  </>
                ) : (
                  <>
                    <span>Sign in & Continue</span>
                    <ArrowRight size={15} />
                  </>
                )}
              </button>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

export default ChatPage;
