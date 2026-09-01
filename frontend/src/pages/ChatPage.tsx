import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle, AlertTriangle, ArrowRight, BotMessageSquare, Check, ChevronDown, ChevronRight, Edit2, HelpCircle, History, Info, Loader2,
  Lock, LogIn, MessageSquare, PanelLeft, Play, Plus, Send, ShieldAlert, Sparkles, Terminal, Trash2, User, X,
} from 'lucide-react';
import { AuthUser, authFetch, extractApiError, getStoredUser, login } from '../services/api';
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

interface SessionItem {
  id: string;
  tenantId?: string;
  username?: string;
  title: string;
  createdAt: string;
  updatedAt: string;
  isArchived?: boolean;
}

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
export interface ToolPlan {
  requestId: string;
  incidentRef: string;
  actionKey: string;
  tool: string;
  target: string;
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';
  commandPreview: string;
  language?: string;
  parameters: Record<string, any>;
  findings: Array<{ check: string; status: string; detail: string }>;
  planHash: string;
  confidence: number;
  risk: number;
  canApprove: boolean;
  sodBlocked: boolean;
}

interface RunStage {
  title: string;
  state: 'pending' | 'active' | 'ok' | 'fail';
  detail?: string;
  log?: string;
}

interface RunLogState {
  open: boolean;
  content?: string;
}

interface Message {
  id: string;
  role: 'user' | 'bot';
  text?: string;
  loading?: boolean;
  error?: boolean;
  /** SQL stats block for public queries like "how many open". */
  stats?: PublicStats;
  /** Public table preview for "show p1s". */
  rows?: PublicRow[];
  /** More than one ticket matched, so the user picks instead of the code guessing. */
  choices?: IncidentChoice[];
  /** Proposed action ready for the user to review. */
  plan?: ToolPlan;
  /** Set once the user has answered the run question, so the buttons cannot be clicked twice. */
  decisionMade?: boolean;
  /** An executed run and its live stages. */
  run?: {
    requestId: string;
    stages: RunStage[];
    terminal?: boolean;
    success?: boolean;
  };
  /** Remediation could not be planned; the operator must do it manually. */
  escalation?: {
    reason: string;
    action: string;
  };
  /** When not signed in, remediation asks to sign in first. */
  signin?: string;
  /** Dynamic missing information inputs card */
  missingInfo?: MissingInfoCardState;
}

interface MissingInfoCardState {
  incidentId: string;
  incidentRef: string;
  actionKey: string;
  tool: string;
  detail: any;
  fields: MissingParamField[];
  values: Record<string, string>;
  validationError?: string;
}

interface IncidentChoice {
  id: string;
  ref: string;
  subject: string;
  status: string;
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
const ACTIVE_SESSION_KEY = 'iw_active_session_id';

const ChatPage: React.FC<Props> = ({ user, onLogin }) => {
  const navigate = useNavigate();
  const activeUser = user || getStoredUser();
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>(() => {
    try {
      const saved = sessionStorage.getItem(STORAGE_KEY);
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => {
    return sessionStorage.getItem(ACTIVE_SESSION_KEY) || null;
  });
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingSessionId, setEditingSessionId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');

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

  const loadSessions = async () => {
    if (!activeUser) return;
    try {
      const res = await authFetch('/api/v1/chat/sessions');
      if (res.ok) {
        const list = await res.json();
        setSessions(list);
      }
    } catch {}
  };

  useEffect(() => {
    if (activeUser) {
      loadSessions();
    }
  }, [activeUser]);

  useEffect(() => {
    const handleLogoutEvent = () => {
      setMessages([]);
      setActiveSessionId(null);
      setSessions([]);
      try {
        sessionStorage.removeItem(STORAGE_KEY);
        sessionStorage.removeItem(ACTIVE_SESSION_KEY);
      } catch {}
    };
    window.addEventListener('mcp:logout', handleLogoutEvent);
    return () => window.removeEventListener('mcp:logout', handleLogoutEvent);
  }, []);

  const selectSession = async (id: string) => {
    try {
      setLoading(true);
      const res = await authFetch(`/api/v1/chat/sessions/${id}`);
      if (res.ok) {
        const data = await res.json();
        setActiveSessionId(id);
        sessionStorage.setItem(ACTIVE_SESSION_KEY, id);
        if (Array.isArray(data.messages)) {
          const loaded: Message[] = data.messages.map((m: any) => {
            let metaObj: any = {};
            if (m.metadata) {
              try { metaObj = JSON.parse(m.metadata); } catch {}
            }
            return {
              id: m.id || `${Date.now()}-${Math.random()}`,
              role: (m.role === 'assistant' || m.role === 'bot') ? 'bot' : 'user',
              text: m.content || '',
              ...metaObj
            };
          });
          setMessages(loaded);
        }
      }
    } catch {} finally {
      setLoading(false);
      setDrawerOpen(false);
    }
  };

  const createNewSession = () => {
    setActiveSessionId(null);
    setMessages([]);
    try {
      sessionStorage.removeItem(STORAGE_KEY);
      sessionStorage.removeItem(ACTIVE_SESSION_KEY);
    } catch {}
    setDrawerOpen(false);
  };

  const saveSessionTitle = async (id: string) => {
    if (!editingTitle.trim()) {
      setEditingSessionId(null);
      return;
    }
    try {
      const res = await authFetch(`/api/v1/chat/sessions/${id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title: editingTitle.trim() }),
      });
      if (res.ok) {
        setEditingSessionId(null);
        loadSessions();
      }
    } catch {}
  };

  const deleteSession = async (e: React.MouseEvent, id: string) => {
    e.stopPropagation();
    if (!window.confirm('Delete this conversation history?')) return;
    try {
      const res = await authFetch(`/api/v1/chat/sessions/${id}`, { method: 'DELETE' });
      if (res.ok) {
        if (activeSessionId === id) {
          createNewSession();
        }
        loadSessions();
      }
    } catch {}
  };

  const sessionGroups = useMemo(() => {
    const now = new Date();
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    const yesterday = today - 86400000;
    const last7Days = today - 7 * 86400000;

    const groups: { title: string; items: SessionItem[] }[] = [
      { title: 'Today', items: [] },
      { title: 'Yesterday', items: [] },
      { title: 'Previous 7 Days', items: [] },
      { title: 'Older', items: [] },
    ];

    sessions.forEach(s => {
      const t = new Date(s.updatedAt || s.createdAt).getTime();
      if (t >= today) {
        groups[0].items.push(s);
      } else if (t >= yesterday) {
        groups[1].items.push(s);
      } else if (t >= last7Days) {
        groups[2].items.push(s);
      } else {
        groups[3].items.push(s);
      }
    });

    return groups.filter(g => g.items.length > 0);
  }, [sessions]);

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

  const findLastMentionedIncidentRef = (): string => {
    for (let idx = messages.length - 1; idx >= 0; idx--) {
      const text = messages[idx].text || '';
      const refMatch = text.match(INCIDENT_REF)?.[0];
      if (refMatch) return refMatch;
    }
    return '';
  };

  /**
   * Which ticket does "fix the printer one" or "how to fix it" mean?
   * Searches explicit tickets, keywords, previous chat context, or returns recent open tickets.
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

    if (!all.length) return [];

    // 1. Explicit ticket reference in current question
    const reference = question.match(INCIDENT_REF)?.[0];
    if (reference) {
      const digits = reference.replace(/\D/g, '');
      const hit = all.filter(i => (i.externalId || '').replace(/\D/g, '').endsWith(digits));
      if (hit.length) return hit.map(choice);
    }

    // 2. Keyword match in current question
    const words = contentWords(question);
    if (words.length) {
      const scored = all
        .map(i => ({
          incident: i,
          score: words.filter(w => `${i.subject} ${i.externalId}`.toLowerCase().includes(w)).length,
        }))
        .filter(s => s.score > 0)
        .sort((a, b) => b.score - a.score);
      const best = scored.length ? scored[0].score : 0;
      if (best > 0) {
        return scored.filter(s => s.score === best).slice(0, 5).map(s => choice(s.incident));
      }
    }

    // 3. Look at recent conversation history (e.g. user asked about ticket earlier in chat)
    for (let idx = messages.length - 1; idx >= 0; idx--) {
      const text = messages[idx].text || '';
      const refMatch = text.match(INCIDENT_REF)?.[0];
      if (refMatch) {
        const digits = refMatch.replace(/\D/g, '');
        const hit = all.filter(i => (i.externalId || '').replace(/\D/g, '').endsWith(digits));
        if (hit.length) return hit.map(choice);
      }
      const prevWords = contentWords(text);
      if (prevWords.length) {
        const scored = all
          .map(i => ({
            incident: i,
            score: prevWords.filter(w => `${i.subject} ${i.externalId}`.toLowerCase().includes(w)).length,
          }))
          .filter(s => s.score > 0)
          .sort((a, b) => b.score - a.score);
        const best = scored.length ? scored[0].score : 0;
        if (best > 0) {
          return scored.filter(s => s.score === best).slice(0, 5).map(s => choice(s.incident));
        }
      }
    }

    // 4. Default: return top 5 open/active incidents so operator can choose directly
    return all.slice(0, 5).map(choice);
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
    patchStage(messageId, 0, { state: 'ok', detail: `Approved by ${activeUser?.username}. Plan hash pinned.` });

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
      if (!activeUser) {
        await answerAnonymously(q, botId);
        return;
      }
      if (includesAny(q.toLowerCase(), SOLVE_TERMS) || q.toLowerCase().includes('fix') || q.toLowerCase().includes('resolve') || q.toLowerCase().includes('remediate')) {
        await startSolve(q, botId);
        return;
      }

      let currentSessionId = activeSessionId;
      if (!currentSessionId) {
        try {
          const sessionTitle = q.length > 40 ? q.substring(0, 37) + '…' : q;
          const sRes = await authFetch('/api/v1/chat/sessions', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: sessionTitle }),
          });
          if (sRes.ok) {
            const sData = await sRes.json();
            currentSessionId = sData.id;
            setActiveSessionId(sData.id);
            sessionStorage.setItem(ACTIVE_SESSION_KEY, sData.id);
          }
        } catch (e) {
          console.warn('Could not initialize session:', e);
        }
      }

      const res = await authFetch('/api/v1/rag/chat', {
        method: 'POST',
        body: JSON.stringify({
          question: q,
          tenantId: activeUser.tenantId,
          sessionId: currentSessionId || undefined,
        }),
      });
      if (res.ok) {
        const data = await res.json();
        updateMessage(botId, { loading: false, text: data.answer });
        loadSessions();
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
          <thead>
            <tr>
              <th>Ticket</th>
              <th>Subject &amp; Summary</th>
              <th>Status</th>
              <th>Priority</th>
              <th>Updated</th>
            </tr>
          </thead>
          <tbody>
            {msg.rows?.map(row => (
              <tr key={row.externalId}>
                <td><code>{row.externalId}</code></td>
                <td>
                  <strong>{row.subject}</strong>
                  {row.description && <p>{row.description}</p>}
                </td>
                <td><span className={`chat-status-badge status-${row.status.toLowerCase()}`}>{row.status}</span></td>
                <td><span className={`chat-priority-badge priority-${row.priority.toLowerCase()}`}>{row.priority}</span></td>
                <td>{shortDate(row.updatedAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {!activeUser && <div className="chat-note">Public preview: IPs, credentials and PII are masked (****). Sign in for full incident context and remediation.</div>}
    </div>
  );

  const renderMissingInfoCard = (msg: Message, missing: MissingInfoCardState) => {
    return (
      <div className="chat-missing-card">
        <div className="chat-missing-head">
          <AlertCircle size={16} />
          <span>Provide Missing Parameters to Execute</span>
        </div>
        <p className="chat-missing-sub">
          The remediation script for <strong>{missing.incidentRef}</strong> requires additional system details to proceed safely.
        </p>

        {missing.validationError && (
          <div className="chat-missing-error">
            <AlertTriangle size={14} />
            <span>{missing.validationError}</span>
          </div>
        )}

        <div className="chat-missing-grid">
          {missing.fields.map(field => (
            <div key={field.key} className="chat-missing-field">
              <label htmlFor={`field-${msg.id}-${field.key}`}>
                {field.label} {field.required && <span className="req-star">*</span>}
              </label>
              <input
                id={`field-${msg.id}-${field.key}`}
                type="text"
                value={missing.values[field.key] || ''}
                onChange={e => handleMissingParamChange(msg.id, field.key, e.target.value)}
                placeholder={field.placeholder}
              />
            </div>
          ))}
        </div>

        <div className="chat-missing-actions">
          <button
            className="chat-btn-missing-submit"
            onClick={() => handleMissingParamsSubmit(msg.id)}
          >
            <Sparkles size={14} /> Update &amp; Review Remediation Plan
          </button>
        </div>
      </div>
    );
  };

  const renderPlanCard = (msg: Message) => {
    const plan = msg.plan!;
    return (
      <div className="chat-plan-card">
        <div className="chat-plan-head">
          <div className="chat-plan-title">
            <Terminal size={15} />
            <span>Review &amp; Run Proposed Action</span>
          </div>
          <span className={`chat-risk-badge risk-${plan.riskLevel.toLowerCase()}`}>
            {plan.riskLevel} RISK
          </span>
        </div>

        <div className="chat-plan-body">
          <div className="chat-plan-detail-row">
            <span className="detail-label">Target Host:</span>
            <code>{plan.target}</code>
          </div>
          <div className="chat-plan-detail-row">
            <span className="detail-label">Remediation Action:</span>
            <code>{plan.actionKey || plan.tool}</code>
          </div>
          {plan.language && (
            <div className="chat-plan-detail-row">
              <span className="detail-label">Engine:</span>
              <span>{plan.language}</span>
            </div>
          )}
        </div>

        <div className="chat-plan-actions">
          <button
            className="chat-btn-review"
            onClick={() => {
              setReview({ messageId: msg.id, plan });
              setShowExplain(false);
            }}
          >
            Review &amp; Run Script
          </button>
        </div>
      </div>
    );
  };

  const renderRun = (run: NonNullable<Message['run']>) => {
    return (
      <div className="chat-run-card">
        <div className="chat-run-stages">
          {run.stages.map((stage, idx) => (
            <div key={idx} className={`chat-stage stage-${stage.state}`}>
              <div className="stage-indicator">
                {stage.state === 'pending' && <span className="stage-dot" />}
                {stage.state === 'active' && <Loader2 size={13} className="spin" />}
                {stage.state === 'ok' && <Check size={13} />}
                {stage.state === 'fail' && <X size={13} />}
              </div>
              <div className="stage-content">
                <span className="stage-title">{stage.title}</span>
                {stage.detail && <span className="stage-detail">{stage.detail}</span>}
              </div>
            </div>
          ))}
        </div>
      </div>
    );
  };

  const renderBot = (msg: Message) => (
    <div className="chat-msg chat-msg-bot">
      <div className="chat-avatar"><BotMessageSquare size={16} /></div>
      <div className="chat-bubble chat-bubble-bot">
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
            <p>{activeUser ? 'Ready to resolve this incident with SOP automation.' : msg.signin}</p>
            {activeUser ? (
              <button className="chat-signin-btn" onClick={() => handleSend('how to fix ' + (findLastMentionedIncidentRef() || 'FS-1001'))}>
                <Sparkles size={14} /> Fix Incident
              </button>
            ) : (
              <button className="chat-signin-btn" onClick={() => setShowLoginModal(true)}>
                <LogIn size={14} /> Sign in to continue
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );

  const suggestions = activeUser ? SUGGESTIONS_SIGNED_IN : SUGGESTIONS_ANON;
  const currentSession = sessions.find(s => s.id === activeSessionId);
  const activeTitle = currentSession?.title || (messages.length > 0 ? 'Active Conversation' : 'New Chat');

  return (
    <div className="chat-page">
      {/* Sessions Toolbar */}
      <div className="chat-toolbar">
        <div className="chat-toolbar-left">
          {activeUser && (
            <button
              className="chat-toolbar-btn"
              onClick={() => {
                setDrawerOpen(true);
                loadSessions();
              }}
              aria-label="View conversation history"
            >
              <History size={14} /> History {sessions.length > 0 && `(${sessions.length})`}
            </button>
          )}
          <div className="chat-toolbar-title">
            <span>Session:</span> <strong>{activeTitle}</strong>
          </div>
        </div>

        {activeUser && (
          <button
            className="chat-toolbar-btn"
            onClick={createNewSession}
            title="Start a new conversation"
          >
            <Plus size={14} /> New Chat
          </button>
        )}
      </div>

      {/* Sessions Slide-over Drawer */}
      {drawerOpen && (
        <div className="chat-sessions-backdrop" onClick={() => setDrawerOpen(false)} />
      )}
      <aside className={`chat-sessions-drawer ${drawerOpen ? 'open' : ''}`} aria-label="Conversation history">
        <div className="chat-sessions-head">
          <h3><MessageSquare size={16} /> Chat History</h3>
          <button
            className="chat-session-action-btn"
            onClick={() => setDrawerOpen(false)}
            aria-label="Close drawer"
          >
            <X size={16} />
          </button>
        </div>

        <button
          className="chat-sessions-new-btn"
          onClick={createNewSession}
        >
          <Plus size={15} /> + New Conversation
        </button>

        <div className="chat-sessions-list">
          {sessions.length === 0 ? (
            <div style={{ padding: '24px 12px', textAlign: 'center', color: 'var(--text-muted)', fontSize: '13px' }}>
              No saved sessions yet. Start asking questions to build your history.
            </div>
          ) : (
            sessionGroups.map(group => (
              <div key={group.title} className="chat-sessions-group">
                <div className="chat-sessions-group-title">{group.title}</div>
                {group.items.map(s => (
                  <div
                    key={s.id}
                    className={`chat-session-item ${s.id === activeSessionId ? 'active' : ''}`}
                    onClick={() => selectSession(s.id)}
                  >
                    {editingSessionId === s.id ? (
                      <div
                        style={{ display: 'flex', alignItems: 'center', gap: '4px', width: '100%' }}
                        onClick={e => e.stopPropagation()}
                      >
                        <input
                          type="text"
                          className="chat-session-edit-input"
                          value={editingTitle}
                          onChange={e => setEditingTitle(e.target.value)}
                          onKeyDown={e => {
                            if (e.key === 'Enter') saveSessionTitle(s.id);
                            if (e.key === 'Escape') setEditingSessionId(null);
                          }}
                          autoFocus
                        />
                        <button
                          className="chat-session-action-btn"
                          onClick={() => saveSessionTitle(s.id)}
                          title="Save title"
                        >
                          <Check size={13} style={{ color: 'var(--green, #22c55e)' }} />
                        </button>
                        <button
                          className="chat-session-action-btn"
                          onClick={() => setEditingSessionId(null)}
                          title="Cancel"
                        >
                          <X size={13} />
                        </button>
                      </div>
                    ) : (
                      <>
                        <span className="chat-session-title" title={s.title}>{s.title}</span>
                        <div className="chat-session-actions" onClick={e => e.stopPropagation()}>
                          <button
                            className="chat-session-action-btn"
                            onClick={() => {
                              setEditingSessionId(s.id);
                              setEditingTitle(s.title);
                            }}
                            title="Rename"
                          >
                            <Edit2 size={12} />
                          </button>
                          <button
                            className="chat-session-action-btn danger"
                            onClick={e => deleteSession(e, s.id)}
                            title="Delete"
                          >
                            <Trash2 size={12} />
                          </button>
                        </div>
                      </>
                    )}
                  </div>
                ))}
              </div>
            ))
          )}
        </div>
      </aside>

      <div className="chat-stream">
        <div className="chat-column">
          {messages.length === 0 ? (
            <div className="chat-empty">
              <div className="chat-empty-mark"><BotMessageSquare size={26} /></div>
              <h2>{activeUser ? `What can I look into, ${activeUser.fullName?.split(' ')[0] || activeUser.username}?` : 'Ask about an incident'}</h2>
              <p>
                {activeUser
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
              placeholder={activeUser ? 'Ask about an incident, or say “fix FS-1001”…' : 'Ask how many incidents are open…'}
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
            Grounded in approved SOPs and incident records. AI-generated insights should be verified for mission-critical operations.
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
