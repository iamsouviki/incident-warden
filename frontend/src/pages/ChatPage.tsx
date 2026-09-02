import React, { useEffect, useMemo, useRef, useState } from 'react';

import {
  AlertCircle, AlertTriangle, ArrowRight, BotMessageSquare, Check, ChevronDown, Edit2, History,
  Lock, LogIn, MessageSquare, Play, Plus, Send, ShieldAlert, Sparkles, Terminal, Trash2, User, X,
  Loader2,
} from 'lucide-react';
import { AuthUser, authFetch, extractApiError, login } from '../services/api';
import Markdown from '../components/Markdown';
import './ChatPage.css';

const maskSensitiveText = (value: string): string => value
  .replace(/(\b(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|private[_-]?key)\b\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]')
  .replace(/(\b(?:username|user|login)\b\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]')
  .replace(/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi, '[REDACTED]')
  .replace(/\b(?:\d{1,3}\.){3}\d{1,3}\b/g, '[REDACTED]')
  .replace(/\bbearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]')
  .replace(/\bAKIA[0-9A-Z]{16}\b/g, '[REDACTED]');

/**
 * The product's front door. One surface, three trust levels:
 *
 *   anonymous  → deterministic answers from /api/v1/public (counts, status, redacted rows),
 *                and a sign-in card for anything that needs judgement or action.
 *   signed in  → the grounded assistant at /api/v1/rag/chat.
 *   solve       → a tool card, then a script the user reads, then a run with live stages.
 *
 * Why an anonymous question never reaches the model: an unauthenticated LLM route spends the
 * workspace's provider budget on whoever finds the URL, and the assistant needs a signed-in
 * identity that a stranger does not have. So the anonymous tier answers from SQL and says so,
 * rather than pretending to be the same assistant with less to say.
 *
 * The run flow adds no new approval mechanism. It drives the same three HITL endpoints the
 * review queue drives — decision, dry-run, execute — in the same order, with the same server
 * gates. Chat is a faster way to reach them, never a way around them.
 */

interface SessionItem {
  id: string;
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
  /** The incident's own id. Needed to push the resolved status back to the source system. */
  incidentId: string;
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
  risk: number;
  canApprove: boolean;
  sodBlocked: boolean;
  /** Human-readable summary of what this action does. */
  what?: string;
  /** Ordered steps the script executes. */
  how?: string[];
  /** The raw shell/python script. */
  script?: string;
  /** Guardrail scan level applied. */
  scanLevel?: string;
  /** Provenance of the SOP this plan was derived from. */
  provenance?: string;
  /** Whether this action mutates system state (restart, delete, write). */
  mutating?: boolean;
  /** Rollback procedure if the action needs to be reversed. */
  rollback?: string;
}

interface RunStage {
  title?: string;
  label?: string;
  state: 'pending' | 'running' | 'active' | 'ok' | 'fail';
  detail?: string;
  log?: string[];
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
  /** Set once the user has confirmed or dismissed the run prompt. */
  answered?: string;
  /** Keyword the search was matched against, for display. */
  matched?: string;
  /** An executed run and its live stages. */
  run?: {
    requestId?: string;
    stages: RunStage[];
    done?: boolean;
    failed?: boolean;
    dryRunOnly?: boolean;
    terminal?: boolean;
    success?: boolean;
  };
  /** Remediation could not be planned; the operator must do it manually. */
  escalation?: {
    reason: string;
    action: string;
  };
  /** After a successful run: ask the operator to verify, then offer to close the ticket. */
  resolve?: {
    incidentId: string;
    incidentRef: string;
    answered?: 'yes' | 'no';
    /** Set once the source system has been told, success or failure. */
    result?: string;
    failed?: boolean;
  };
  /** Nothing to run, so the answer is words: what is wrong and the steps to take by hand. */
  analysis?: {
    loading?: boolean;
    team?: string;
    sourceLabel?: string;
    sourceDetail?: string;
    steps?: string;
    error?: string;
  };
  /** When not signed in, remediation asks to sign in first. */
  signin?: string;
  /** Dynamic missing information inputs card */
  missingInfo?: MissingInfoCardState;
}

interface MissingInfoCardState {
  requestId: string;
  incidentId: string;
  incidentRef: string;
  actionKey: string;
  tool: string;
  detail: any;
  fields: MissingParamField[];
  values: Record<string, string>;
  validationError?: string;
  /** Set on submit: the form is spent, so its inputs and buttons go read-only. */
  submitted?: boolean;
}

interface IncidentChoice {
  id: string;
  ref: string;
  subject: string;
  status: string;
  description?: string;
}

// ponytail: COUNT_TERMS removed — routing is now done server-side

/** Wanting something *done*, resolved or explained how to resolve. */
const SOLVE_TERMS = [
  'how to solve', 'how to fix', 'how to resolve', 'how do i solve', 'how can i solve',
  'how do we solve', 'how do we resolve', 'how to remediate', 'solve', 'solution',
  'fix', 'resolve', 'remediate', 'restart', 'reboot', 'clear cache', 'rerun', 'run ',
  'execute', 'repair', 'roll back', 'rollback', 'take action', 'redeploy', 'remediation',
];

const INCIDENT_REF = /\b(?:INC|FS|SN)[-_]?\d{3,}\b/i;
// ponytail: PRIORITY_REF removed with pickKeyword

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

// ponytail: pickKeyword removed — keyword selection moved to backend RAG

const shortDate = (iso?: string | null) => {
  if (!iso) return '—';
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString(undefined,
    { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
};

/**
 * A failure the operator can act on: what was being attempted, what it means, what to try.
 *
 * The server's own message becomes the last line rather than the whole reply. A bare
 * `Request failed with status 500` — or, against a stubbed dev backend, something like
 * `not stubbed: /api/v1/rag/chat` — is a diagnostic, and shown alone it reads as the app
 * talking to itself. Keeping it is still right: it is what makes a support ticket useful.
 */
const friendlyError = (attempt: string, detail: string, suggestion: string) =>
  `**I could not ${attempt}.**\n`
  + `${suggestion}\n`
  + `If it keeps happening, quote this to whoever runs the platform — it names the exact step `
  + `that failed: *${detail}*`;

/** The suggestion half of `friendlyError`, for the two failures that dominate. */
const RETRY_HINT = 'Nothing was changed, so it is safe to ask again. '
  + 'The platform may be starting up, or its connection to the incident database may be down.';
const OFFLINE_HINT = 'Nothing was changed. The platform did not answer at all, which usually '
  + 'means the backend is not running or the network dropped between here and it. '
  + 'Wait a few seconds and ask again.';

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * One HITL request detail → the plan card's state.
 *
 * Shared by both routes into the card: the direct one (the incident already carried every
 * parameter) and the one through the missing-parameters form. They used to build it
 * separately, and the form's copy did not exist — it merged into `msg.plan`, which the
 * missing-info route never set, so submitting the form emptied the bubble.
 */
const planFrom = (
  detail: any,
  incident: IncidentChoice,
  requestId: string,
  values: Record<string, string>,
): ToolPlan => ({
  requestId,
  incidentId: incident.id,
  incidentRef: incident.ref,
  actionKey: detail.action?.actionKey || '',
  tool: detail.action?.tool || 'generated script',
  mutating: Boolean(detail.action?.mutating),
  target: values['targetHost'] || values['store'] || detail.plan?.target || 'store-0042-pos-01',
  script: detail.script?.script || '',
  language: detail.script?.language || '',
  provenance: detail.script?.provenance || '',
  what: detail.script?.explanation?.what || '',
  how: Array.isArray(detail.script?.explanation?.how) ? detail.script.explanation.how : [],
  scanLevel: detail.script?.scanLevel || '',
  rollback: detail.plan?.rollbackPlan || '',
  findings: Array.isArray(detail.guardrailFindings) ? detail.guardrailFindings : [],
  planHash: detail.plan?.planHash || '',
  risk: Number(detail.plan?.riskScore ?? 0),
  canApprove: Boolean(detail.canApprove),
  sodBlocked: Boolean(detail.separationOfDutiesBlocked),
  riskLevel: (detail.plan?.riskLevel ?? detail.action?.riskLevel ?? 'MEDIUM') as ToolPlan['riskLevel'],
  commandPreview: detail.script?.commandPreview ?? detail.action?.commandPreview ?? '',
  parameters: { ...(detail.plan?.parameters ?? {}), ...values },
});

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
  type?: 'text' | 'number' | 'boolean';
}

// ponytail: MissingInfoCardState is the live type; this duplicate is removed

const STORAGE_KEY = 'iw_chat_history';
const ACTIVE_SESSION_KEY = 'iw_active_session_id';

const ChatPage: React.FC<Props> = ({ user, onLogin }) => {
  // ponytail: navigate removed — routing happens via Link components upstream
  const activeUser = user;
  const [input, setInput] = useState('');
  const ANON_STORAGE_KEY = 'iw_anon_chat_history';

  const [messages, setMessages] = useState<Message[]>(() => {
    try {
      const saved = sessionStorage.getItem(user ? STORAGE_KEY : ANON_STORAGE_KEY);
      return saved ? JSON.parse(saved) : [];
    } catch {
      return [];
    }
  });
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => {
    return user ? (sessionStorage.getItem(ACTIVE_SESSION_KEY) || null) : null;
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

  // Session Delete Confirmation Modal state
  const [sessionToDelete, setSessionToDelete] = useState<{ id: string; title: string } | null>(null);
  const [deletingSession, setDeletingSession] = useState(false);
  const [pendingAction, setPendingAction] = useState<string | null>(null);
  const actionLocks = useRef(new Set<string>());

  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    try {
      sessionStorage.setItem(user ? STORAGE_KEY : ANON_STORAGE_KEY, JSON.stringify(messages));
    } catch {}
  }, [messages, user]);

  /**
   * Mirror the settled conversation into the server session, so History replays it.
   *
   * The turn list is sent whole and the server replaces the session's rows with it
   * (`ChatSessionService.syncMessages` deletes then inserts), which makes this idempotent —
   * no append bookkeeping, and an edited/cancelled card corrects itself on the next sync.
   * Cards travel as `metadata`, which is what `selectSession` spreads back onto the message,
   * so a restored conversation still has its plan, run and resolve state and not just prose.
   *
   * Gated on `!loading` so a run in progress is written once, at rest, rather than once per
   * revealed log line.
   */
  useEffect(() => {
    if (!user || !activeSessionId || loading || messages.length === 0) return;
    const turns = messages.map(({ id, role, text, loading: _l, ...card }) => ({
      role: role === 'bot' ? 'assistant' : 'user',
      content: text ?? '',
      metadata: Object.keys(card).length ? card : undefined,
    }));
    authFetch(`/api/v1/chat/sessions/${activeSessionId}/messages`, {
      method: 'POST',
      body: JSON.stringify({ messages: turns }),
    }).catch(() => null);
  }, [messages, activeSessionId, loading, user]);

  /**
   * Every signed-in question belongs to a session. This used to live inside the plain-chat
   * branch of `handleSend`, so remediation questions — the ones that route to `startSolve`,
   * and the reason most people open the app — created no session and left no history at all.
   */
  const ensureSession = async (question: string): Promise<string | null> => {
    if (activeSessionId) return activeSessionId;
    try {
      const res = await authFetch('/api/v1/chat/sessions', {
        method: 'POST',
        body: JSON.stringify({
          title: question.length > 40 ? `${question.substring(0, 37)}…` : question,
        }),
      });
      if (!res.ok) return null;
      const data = await res.json();
      setActiveSessionId(data.id);
      sessionStorage.setItem(ACTIVE_SESSION_KEY, data.id);
      loadSessions();
      return data.id;
    } catch {
      return null;
    }
  };

  const loadSessions = async () => {
    if (!user) return;
    try {
      const res = await authFetch('/api/v1/chat/sessions');
      if (res.ok) {
        const list = await res.json();
        setSessions(list);
      }
    } catch {}
  };

  useEffect(() => {
    if (!user) {
      setActiveSessionId(null);
      setSessions([]);
      setDrawerOpen(false);
    } else {
      // If user had an anonymous conversation before logging in, preserve and keep it
      const anonSaved = sessionStorage.getItem(ANON_STORAGE_KEY);
      if (anonSaved) {
        try {
          const parsed = JSON.parse(anonSaved);
          if (parsed.length > 0 && messages.length === 0) {
            setMessages(parsed);
          }
          sessionStorage.removeItem(ANON_STORAGE_KEY);
        } catch {}
      }
      loadSessions();
    }
  }, [user]);

  useEffect(() => {
    const handleLogoutEvent = () => {
      setMessages([]);
      setActiveSessionId(null);
      setSessions([]);
      setDrawerOpen(false);
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

  const promptDeleteSession = (e: React.MouseEvent, id: string, title?: string) => {
    e.stopPropagation();
    const sessionItem = sessions.find(s => s.id === id);
    setSessionToDelete({ id, title: title || sessionItem?.title || 'this conversation' });
  };

  const confirmDeleteSession = async () => {
    if (!sessionToDelete) return;
    const { id } = sessionToDelete;
    setDeletingSession(true);
    try {
      const res = await authFetch(`/api/v1/chat/sessions/${id}`, { method: 'DELETE' });
      if (res.ok) {
        if (activeSessionId === id) {
          createNewSession();
        }
        await loadSessions();
        setSessionToDelete(null);
      }
    } catch (err) {
      console.error('Failed to delete session:', err);
    } finally {
      setDeletingSession(false);
    }
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

  const claimAction = (key: string): boolean => {
    if (actionLocks.current.has(key)) return false;
    actionLocks.current.add(key);
    setPendingAction(key);
    return true;
  };

  const releaseAction = (key: string) => {
    actionLocks.current.delete(key);
    setPendingAction(current => current === key ? null : current);
  };

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
          text: '**That is more public questions than the preview allows in one minute.**\n'
            + 'The anonymous tier is rate-limited per browser so it cannot be used to scrape '
            + 'the incident data. Wait about a minute, or sign in — signed-in users get a much '
            + 'higher limit and the full, unmasked ticket detail.',
        });
      } else {
        // Not "I only answer incident questions": that is the guardrail's reply and it
        // arrives with 200. Reaching here means the request itself failed, and blaming the
        // question for a server fault sends the user off to rephrase something that was fine.
        updateMessage(botId, {
          loading: false,
          error: true,
          text: friendlyError('answer that', await extractApiError(res), RETRY_HINT),
        });
      }
    } catch {
      updateMessage(botId, {
        loading: false,
        error: true,
        text: friendlyError('answer that', 'the request never completed', OFFLINE_HINT),
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
      id: string; externalId?: string; subject?: string; status?: string; description?: string;
    }>;
    const choice = (i: typeof all[number]): IncidentChoice => ({
      id: i.id, ref: i.externalId || '—', subject: i.subject || '', status: i.status || '',
      description: i.description || '',
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

  /**
   * No tool to run, so the answer is words: what is wrong, where the advice came from, and
   * the steps to take by hand.
   *
   * ponytail: one card for both no-tool cases rather than two scenario renderers.
   * /incidents/analyze already decides the source — an approved SOP when one matches, the
   * model's own reasoning when none does — and returns that decision as a label the operator
   * reads. Re-deciding it here would be a second opinion the UI has no business holding.
   */
  const explainFor = async (incident: IncidentChoice, botId: string) => {
    const patch = (next: NonNullable<Message['analysis']>) =>
      setMessages(prev => prev.map(m => (m.id === botId
        ? { ...m, analysis: { ...m.analysis, ...next } } : m)));
    try {
      const res = await authFetch('/api/v1/incidents/analyze', {
        method: 'POST',
        body: JSON.stringify({ subject: incident.subject, description: incident.description || '' }),
      });
      if (!res.ok) {
        patch({ loading: false, error: await extractApiError(res) });
        return;
      }
      const analysis = await res.json();
      patch({
        loading: false,
        team: analysis.suggestedTeam,
        sourceLabel: analysis.sourceLabel,
        sourceDetail: analysis.sourceDetail,
        steps: analysis.suggestedResolution,
      });
    } catch (e) {
      patch({ loading: false, error: e instanceof Error ? e.message : 'The platform did not answer.' });
    }
  };

  /** Plans against one incident and renders whichever of the two outcomes the server chose. */
  const planFor = async (incident: IncidentChoice, botId: string, suppliedFields: Record<string, string> = {}) => {
    updateMessage(botId, { loading: true, text: undefined, choices: undefined });
    try {
      const res = await authFetch(`/api/v1/hitl/incidents/${incident.id}/plan`, {
        method: 'POST', body: JSON.stringify(suppliedFields),
      });
      let body: any = null;
      let requestId = '';

      if (res.ok) {
        body = await res.json();
        requestId = body.hitlRequest?.id || '';
      }

      if (body?.route === 'NEEDS_INPUT') {
        const fields: MissingParamField[] = (body.fields || []).map((field: any) => ({
          key: String(field.key), label: field.label || field.key,
          placeholder: field.placeholder || '', required: Boolean(field.required), type: field.type,
        }));
        updateMessage(botId, {
          loading: false,
          missingInfo: {
            requestId: '', incidentId: incident.id, incidentRef: incident.ref,
            actionKey: body.resolution?.action_key || 'remediation_script',
            tool: body.resolution?.script_path || 'configured automation',
            detail: null, fields, values: body.values || {},
          },
        });
        return;
      }

      // If already awaiting decision, locate the existing open request instead of erroring
      if (!requestId) {
        try {
          const reqsRes = await authFetch('/api/v1/hitl/requests');
          if (reqsRes.ok) {
            const allReqs = await reqsRes.json();
            const matchingReq = allReqs.find((r: any) =>
              (r.incident?.id === incident.id || r.request?.incidentId === incident.id) &&
              (r.request?.status === 'PENDING' || r.request?.status === 'APPROVED')
            );
            if (matchingReq?.request?.id) {
              requestId = matchingReq.request.id;
            }
          }
        } catch {}
      }

      if (!requestId && body?.route !== 'HITL_REQUIRED') {
        updateMessage(botId, {
          loading: false,
          escalation: {
            reason: body?.reason || 'No plan could be offered for this incident.',
            action: body?.action || 'A person works this one by hand.',
          },
          analysis: { loading: true },
        });
        await explainFor(incident, botId);
        return;
      }

      if (!requestId) {
        updateMessage(botId, {
          loading: false,
          error: true,
          text: `**I could not open a review request for ${incident.ref}.**\n`
            + 'Every remediation runs behind a human approval record, and this ticket has '
            + 'neither an existing open request nor one the platform was willing to create — '
            + 'usually because the ticket is already closed, or another reviewer is holding a '
            + 'request against it.\n'
            + 'Check the Approvals queue for this ticket; if there is nothing there, reopen the '
            + 'ticket or work it by hand from the Incidents page.',
        });
        return;
      }
      const detailRes = await authFetch(`/api/v1/hitl/requests/${requestId}`);
      if (!detailRes.ok) {
        updateMessage(botId, {
          loading: false, error: true,
          text: friendlyError(
            `read the approved plan for ${incident.ref}`,
            await extractApiError(detailRes),
            'The request exists but its details would not load, so there is nothing to review '
              + 'and nothing has been run. It is also visible in the Approvals queue, which '
              + 'reads the same record.'),
        });
        return;
      }
      const detail = await detailRes.json();

      updateMessage(botId, { loading: false, plan: planFrom(detail, incident, requestId, suppliedFields) });
    } catch (e) {
      updateMessage(botId, {
        loading: false, error: true,
        text: friendlyError(
          `build a remediation plan for ${incident.ref}`,
          e instanceof Error ? e.message : 'the request never completed',
          'Nothing was run and the ticket is unchanged. You can still work it by hand from '
            + 'the Incidents page, where the same SOP and history are available.'),
      });
    }
  };

  const startSolve = async (question: string, botId: string) => {
    try {
      const matches = await findIncidents(question);
      if (!matches.length) {
        updateMessage(botId, {
          loading: false,
          text: '**I could not find a ticket matching that.**\n'
            + 'I search open tickets by reference and by words in the subject, so the quickest '
            + 'way through is to name the reference — for example **FS-1001** — or a distinctive '
            + 'word from the subject such as *printer* or *terminal*.\n'
            + 'If the ticket is already closed, or was raised in a system that has not synced '
            + 'yet, it will not be here: check the Incidents page and run a sync if it is missing.',
        });
        return;
      }
      if (matches.length > 1) {
        updateMessage(botId, {
          loading: false,
          text: `**${matches.length} open tickets match that description.**\n`
            + 'I will only plan a remediation against one ticket at a time, because the script '
            + 'and the target host are derived from that ticket. Pick the one you mean:',
          choices: matches,
        });
        return;
      }
      await planFor(matches[0], botId);
    } catch (e) {
      updateMessage(botId, {
        loading: false, error: true,
        text: friendlyError('look that ticket up',
          e instanceof Error ? e.message : 'the request never completed', RETRY_HINT),
      });
    }
  };

  // ── Running ───────────────────────────────────────────────────────────────────

  /**
   * Approve → execute. One stage, because the operator has already read the script in the
   * Review & Run modal and pressed the button; a dry run here would be a second simulation
   * they did not ask for. The dry run lives on the Tools page, where choosing it is the point.
   *
   * The stage is a real request whose spinner runs for exactly as long as the call does.
   * ponytail: the executor's output arrives whole, at the end of its call, and is then
   * revealed a line at a time so a 40-line run reads as a run rather than a paste. Swap in
   * an SSE tail of the ActionExecution rows if a script ever runs long enough that per-call
   * granularity is not enough.
   */
  const runPlan = async (messageId: string, plan: ToolPlan) => {
    const actionKey = `run:${messageId}`;
    if (!claimAction(actionKey)) return;
    const stages: RunStage[] = [
      { label: `Running ${plan.tool} on ${plan.target}`, state: 'pending' },
    ];
    updateMessage(messageId, { answered: 'yes', run: { stages, done: false, failed: false } });
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
        releaseAction(actionKey);
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

    // A failed run gets no prompt: nothing was fixed, so asking whether to close the
    // ticket would be asking the operator to confirm something untrue.
    const finish = (failed: boolean) => {
      setMessages(prev => prev.map(m => (m.id === messageId && m.run
        ? {
            ...m,
            run: { ...m.run, done: true, failed },
            ...(failed ? {} : { resolve: { incidentId: plan.incidentId, incidentRef: plan.incidentRef } }),
          }
        : m)));
      setLoading(false);
    };

    // Auto-approve the human-requested action
    await authFetch(`/api/v1/hitl/requests/${plan.requestId}/decision`, {
      method: 'POST',
      body: JSON.stringify({ decision: 'APPROVE', reason: 'Confirmed by operator in chat' }),
    }).catch(() => null);

    // Stage 1: Direct Execution
    const run = await step(0, `/api/v1/hitl/requests/${plan.requestId}/execute`);
    if (!run) { finish(true); return; }
    const status = String(run.execution?.status || '');
    const failed = !status.toUpperCase().startsWith('SUCCE') && !status.toUpperCase().includes('OK');
    patchStage(messageId, 0, {
      state: failed ? 'fail' : 'ok',
      detail: `${status}${run.execution?.mode ? ` · ${run.execution.mode}` : ''}`,
    });
    await revealLog(0, run.execution?.output);
    finish(failed);
  };

  /**
   * The last rung of the loop: the operator verifies, then the source system is told.
   * Reuses the integration endpoint that already pushes the status to ServiceNow /
   * Freshservice / Jira and saves it locally — chat gets no second way to close a ticket.
   */
  const answerResolve = async (
    messageId: string,
    resolve: NonNullable<Message['resolve']>,
    answer: 'yes' | 'no',
  ) => {
    const patch = (next: Partial<NonNullable<Message['resolve']>>) =>
      setMessages(prev => prev.map(m => (m.id === messageId && m.resolve
        ? { ...m, resolve: { ...m.resolve, ...next } } : m)));

    patch({ answered: answer });
    if (answer === 'no') return;
    try {
      const res = await authFetch(`/api/v1/integrations/incidents/${resolve.incidentId}/status`, {
        method: 'POST',
        body: JSON.stringify({ status: 'Resolved' }),
      });
      if (!res.ok) {
        patch({ failed: true, result: await extractApiError(res) });
        return;
      }
      const body = await res.json();
      // `updated` is the vendor's answer, not ours: the local row is saved either way, so a
      // false here means "closed here, still open there" — which the operator has to know.
      patch({
        failed: !body.updated,
        result: body.updated
          ? `${resolve.incidentRef} is now ${body.status} in the source system.`
          : `${resolve.incidentRef} is now ${body.status} here, but the source system did not `
            + 'confirm the update. Close it there by hand.',
      });
    } catch (e) {
      patch({ failed: true, result: e instanceof Error ? e.message : 'The platform did not answer.' });
    }
  };

  // ── Send ──────────────────────────────────────────────────────────────────────

  const handleSend = async (question?: string) => {
    const q = (question ?? input).trim();
    if (!q || loading) return;
    setInput('');
    addMessage({ role: 'user', text: maskSensitiveText(q) });
    const botId = addMessage({ role: 'bot', loading: true });
    setLoading(true);

    try {
      if (!activeUser) {
        await answerAnonymously(q, botId);
        return;
      }
      // Before the branch, not inside one: both routes are conversations worth keeping.
      const currentSessionId = await ensureSession(q);

      if (includesAny(q.toLowerCase(), SOLVE_TERMS) || q.toLowerCase().includes('fix') || q.toLowerCase().includes('resolve') || q.toLowerCase().includes('remediate')) {
        await startSolve(q, botId);
        return;
      }

      const res = await authFetch('/api/v1/rag/chat', {
        method: 'POST',
        body: JSON.stringify({
          question: q,
          sessionId: currentSessionId || undefined,
        }),
      });
      if (res.ok) {
        const data = await res.json();
        updateMessage(botId, { loading: false, text: data.answer });
      } else if (res.status === 429) {
        updateMessage(botId, {
          loading: false, error: true,
          text: '**That was a lot of questions in one minute.**\n'
            + 'The platform rate-limits chat per user to keep one busy session from starving '
            + 'the others. Give it about a minute and ask again — your history is untouched.',
        });
      } else {
        updateMessage(botId, {
          loading: false, error: true,
          text: friendlyError('answer that', await extractApiError(res), RETRY_HINT),
        });
      }
    } catch {
      updateMessage(botId, {
        loading: false, error: true,
        text: friendlyError('answer that', 'the request never completed', OFFLINE_HINT),
      });
    } finally {
      setLoading(false);
      // Both branches above can have created the session, so the drawer refreshes here
      // rather than inside one of them.
      loadSessions();
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
      <div className="chat-note">Matching “{maskSensitiveText(msg.matched || '')}” · most recent first</div>
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
                  <strong>{maskSensitiveText(row.subject)}</strong>
                  {row.description && <p>{maskSensitiveText(row.description)}</p>}
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

  /** Update one field value inside the missingInfo card for a given message. */
  const handleMissingParamChange = (msgId: string, key: string, value: string) => {
    setMessages(prev => prev.map(m => {
      if (m.id !== msgId || !m.missingInfo) return m;
      return { ...m, missingInfo: { ...m.missingInfo, values: { ...m.missingInfo.values, [key]: value } } };
    }));
  };

  /** Re-submit plan with the filled-in param values. */
  const handleMissingParamsSubmit = (msgId: string) => {
    const msg = messages.find(m => m.id === msgId);
    if (!msg?.missingInfo || msg.missingInfo.submitted) return;
    const missing = msg.missingInfo;
    const requiredMissing = missing.fields.filter(f => f.required && !missing.values[f.key]?.trim());
    if (requiredMissing.length > 0) {
      setMessages(prev => prev.map(m =>
        m.id === msgId && m.missingInfo
          ? { ...m, missingInfo: { ...m.missingInfo, validationError: `Required: ${requiredMissing.map(f => f.label).join(', ')}` } }
          : m
      ));
      return;
    }
    // Re-plan server-side so supplied values are validated, hashed, and included in the script.
    const incident: IncidentChoice = {
      id: missing.incidentId, ref: missing.incidentRef, subject: '', status: '',
    };
    setMessages(prev => prev.map(m => m.id === msgId && m.missingInfo
      ? { ...m, missingInfo: { ...m.missingInfo, submitted: true, validationError: undefined } } : m));
    void planFor(incident, msgId, missing.values);
  };

  const renderMissingInfoCard = (msg: Message, missing: MissingInfoCardState) => {
    const spent = Boolean(missing.submitted);
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
                disabled={spent}
              />
            </div>
          ))}
        </div>

        <div className="chat-missing-actions" style={{ display: 'flex', gap: '10px', marginTop: '12px' }}>
          <button
            className="chat-btn chat-btn-secondary"
            style={{ padding: '8px 16px', fontSize: '12px' }}
            disabled={spent || pendingAction === `cancel:missing:${msg.id}`}
            onClick={() => {
              const actionKey = `cancel:missing:${msg.id}`;
              if (!claimAction(actionKey)) return;
              updateMessage(msg.id, {
                missingInfo: undefined,
                text: `**Cancelled — nothing was run against ${missing.incidentRef}.**\n`
                  + `I did not have every detail the script needs, and without them there was no `
                  + `plan to approve, so no change of any kind reached the ticket or the host.\n`
                  + `Ask me to fix ${missing.incidentRef} again whenever you have the missing `
                  + `values, or work it by hand from the Incidents page.`,
              });
              releaseAction(actionKey);
            }}
          >
            {pendingAction === `cancel:missing:${msg.id}` ? 'Cancelling…' : 'Cancel'}
          </button>
          <button
            className="chat-btn chat-btn-primary"
            disabled={spent}
            onClick={() => handleMissingParamsSubmit(msg.id)}
          >
            <Sparkles size={14} /> {spent ? 'Parameters submitted' : 'Update & Review Remediation Plan'}
          </button>
        </div>
      </div>
    );
  };

  const renderPlanCard = (msg: Message) => {
    const plan = msg.plan!;
    // Once a run exists the plan is spent: re-opening the modal would offer to execute an
    // already-executed request, and Cancel would hide the card the run is reported under.
    const spent = Boolean(msg.run);
    return (
      <div className="chat-plan-card">
        <div className="chat-plan-head">
          <div className="chat-plan-title">
            <Terminal size={15} />
            <span>Proposed Remediation Tool</span>
          </div>
          <span className={`chat-risk-badge risk-${plan.riskLevel.toLowerCase()}`}>
            {plan.riskLevel} RISK
          </span>
        </div>

        <p style={{ margin: '8px 0 12px', fontSize: '13px', color: 'var(--text)', lineHeight: 1.5 }}>
          I matched <strong>{plan.incidentRef}</strong> to the approved remediation{' '}
          <strong><code>{plan.actionKey || plan.tool}</code></strong> and prepared it to run
          against <strong><code>{plan.target}</code></strong>.
          {plan.provenance
            ? <> The steps come from <strong>{plan.provenance}</strong>, not from anything I
              invented for this ticket.</>
            : <> The steps were generated for this ticket and have been through the guardrail
              scan.</>}
        </p>

        {plan.what && (
          <p style={{ margin: '0 0 12px', fontSize: '13px', color: 'var(--text-2)', lineHeight: 1.5 }}>
            <strong>What it does:</strong> {plan.what}
          </p>
        )}

        <p style={{ margin: '0 0 12px', fontSize: '13px', color: 'var(--text-2)', lineHeight: 1.5 }}>
          {plan.mutating
            ? <><strong>This changes the system.</strong>{' '}
              {plan.rollback
                ? 'A rollback procedure is attached, and you can read it in full before anything runs.'
                : 'No rollback procedure is attached, so read the script before approving.'}</>
            : <><strong>This only reads state</strong> — it inspects{' '}
              <code>{plan.target}</code> and reports back without changing anything.</>}
          {' '}It is rated <strong>{plan.riskLevel}</strong> risk
          {plan.language ? <> and runs as {plan.language}</> : null}.
        </p>

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

        <p style={{ margin: '12px 0 0', fontSize: '12.5px', color: 'var(--text-2)', lineHeight: 1.5 }}>
          Nothing has run yet. <strong>Review &amp; Run Tool</strong> opens the full script, the
          ordered steps, the rollback plan and the guardrail findings, and the run starts only
          when you confirm there.
        </p>

        <div className="chat-plan-actions" style={{ display: 'flex', gap: '10px', marginTop: '14px' }}>
          <button
            className="chat-btn chat-btn-secondary"
            style={{ padding: '8px 16px', fontSize: '12.5px' }}
            disabled={spent || pendingAction === `cancel:plan:${msg.id}`}
            onClick={() => {
              const actionKey = `cancel:plan:${msg.id}`;
              if (!claimAction(actionKey)) return;
              updateMessage(msg.id, {
                plan: undefined,
                text: `**Cancelled — nothing was run against ${plan.incidentRef}.**\n`
                  + `The approval request stays open in the Approvals queue, so the plan is not `
                  + `lost: another reviewer can pick it up, or you can ask me to fix `
                  + `${plan.incidentRef} again and I will rebuild it from the same SOP.\n`
                  + `The ticket itself is untouched — still open, still assigned where it was.`,
              });
              releaseAction(actionKey);
            }}
          >
            {pendingAction === `cancel:plan:${msg.id}` ? 'Cancelling…' : 'Cancel'}
          </button>
          <button
            className="chat-btn chat-btn-primary"
            disabled={spent}
            onClick={() => {
              setReview({ messageId: msg.id, plan });
              setShowExplain(false);
            }}
          >
            {spent ? 'Tool run started' : 'Review & Run Tool'}
          </button>
        </div>
      </div>
    );
  };

  const renderRun = (run: NonNullable<Message['run']>) => {
    return (
      <div className="chat-run">
        <div className="chat-run-head">
          <Terminal size={14} />
          <span>Remediation Execution Pipeline</span>
          {run.done && (
            <span style={{
              marginLeft: 'auto',
              fontSize: '11px',
              padding: '2px 8px',
              borderRadius: '4px',
              background: run.failed ? 'rgba(239, 68, 68, 0.15)' : 'rgba(34, 197, 94, 0.15)',
              color: run.failed ? 'var(--red, #ef4444)' : 'var(--green, #22c55e)',
              fontWeight: 600
            }}>
              {run.failed ? 'EXECUTION FAILED' : 'COMPLETED SUCCESSFULLY'}
            </span>
          )}
        </div>
        <ul className="chat-stages">
          {run.stages.map((stage, idx) => {
            const isRunning = stage.state === 'running' || stage.state === 'active';
            const isOk = stage.state === 'ok';
            const isFail = stage.state === 'fail';
            const displayTitle = stage.title || stage.label;

            return (
              <li key={idx} className={`chat-stage ${isRunning ? 'is-running' : ''} ${isOk ? 'is-ok' : ''} ${isFail ? 'is-fail' : ''}`}>
                <div className="chat-stage-mark">
                  {isRunning && <Loader2 size={12} className="is-spin" />}
                  {isOk && <Check size={12} />}
                  {isFail && <X size={12} />}
                  {stage.state === 'pending' && <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'currentColor', opacity: 0.4 }} />}
                </div>
                <div className="chat-stage-body">
                  <span className="chat-stage-label">{displayTitle}</span>
                  {stage.detail && <span className="chat-stage-detail">{stage.detail}</span>}
                  {stage.log && stage.log.length > 0 && (
                    // <details> rather than a useState toggle: the browser already owns this
                    // widget, keyboard access and all. Open while the run is live so the
                    // operator watches it happen; collapsed once the run has settled, so a
                    // finished run reads as a summary instead of 40 lines of scrollback.
                    //
                    // Keyed off run.done, not the stage: revealLog fills stage.log one line at
                    // a time *after* the stage is marked ok, so a stage-scoped `isRunning` is
                    // already false the first time this element exists and the viewer would
                    // never once be open.
                    <details className="chat-log-wrap" open={!run.done}>
                      <summary>
                        Output · {stage.log.length} {stage.log.length === 1 ? 'line' : 'lines'}
                      </summary>
                      <div className="chat-log" role="region" aria-label="Execution output">
                        {stage.log.join('\n')}
                      </div>
                    </details>
                  )}
                </div>
                <span className="chat-stage-index">0{idx + 1}</span>
              </li>
            );
          })}
        </ul>
      </div>
    );
  };

  const renderBot = (msg: Message) => {
    const hasRichCard = !!(msg.plan || msg.run || msg.missingInfo || msg.escalation || msg.resolve || msg.analysis || (msg.choices && msg.choices.length > 0));
    return (
    <div className="chat-msg chat-msg-bot">
      <div className="chat-avatar"><BotMessageSquare size={16} /></div>
      <div className={`chat-bubble chat-bubble-bot${hasRichCard ? ' chat-bubble-bot--rich' : ''}`}>
        {msg.loading && <span className="chat-typing"><span /><span /><span /></span>}
        {msg.text && <Markdown text={maskSensitiveText(msg.text)} />}
        {msg.stats && renderStats(msg.stats)}
        {msg.rows && msg.rows.length > 0 && renderRows(msg)}
        {msg.choices && (
          <div className="chat-choices">
            {msg.choices.map(choice => (
              <button key={choice.id} className="chat-choice" onClick={() => planFor(choice, msg.id)}>
                <code>{choice.ref}</code>
                <span>{maskSensitiveText(choice.subject)}</span>
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
        {msg.analysis && (
          <div className="chat-analysis">
            {msg.analysis.loading ? (
              <div className="chat-analysis-head">
                <Loader2 size={13} className="is-spin" /> Working out what to do by hand…
              </div>
            ) : msg.analysis.error ? (
              <p className="chat-analysis-why">{msg.analysis.error}</p>
            ) : (
              <>
                <div className="chat-analysis-head">
                  <Sparkles size={13} />
                  <span>{msg.analysis.sourceLabel || 'Suggested steps'}</span>
                  {msg.analysis.team && <em>{msg.analysis.team}</em>}
                </div>
                {msg.analysis.sourceDetail && (
                  <p className="chat-analysis-why">{msg.analysis.sourceDetail}</p>
                )}
                {msg.analysis.steps && <div className="chat-analysis-steps">{msg.analysis.steps}</div>}
              </>
            )}
          </div>
        )}
        {msg.missingInfo && renderMissingInfoCard(msg, msg.missingInfo)}
        {msg.plan && renderPlanCard(msg)}
        {msg.run && renderRun(msg.run)}
        {msg.resolve && (
          <div className="chat-resolve">
            <p className="chat-resolve-ask">
              The issue appears resolved. Please verify. Would you like to update the incident status?
            </p>
            {!msg.resolve.answered ? (
              <div className="chat-resolve-actions">
                <button className="chat-btn chat-btn-primary"
                        onClick={() => answerResolve(msg.id, msg.resolve!, 'yes')}>
                  <Check size={14} /> OK
                </button>
                <button className="chat-btn chat-btn-ghost"
                        onClick={() => answerResolve(msg.id, msg.resolve!, 'no')}>
                  <X size={14} /> Cancel
                </button>
              </div>
            ) : (
              <>
                <p className={`chat-resolve-note${msg.resolve.failed ? ' chat-resolve-note--fail' : ''}`}>
                  {msg.resolve.answered === 'no'
                    ? `Left ${msg.resolve.incidentRef} open. Nothing was sent to the source system.`
                    : msg.resolve.result || `Updating ${msg.resolve.incidentRef}…`}
                </p>
                {/* The loop has an end, so say so: without this the conversation just stops
                    mid-flow and the operator has to guess whether anything is still pending. */}
                {(msg.resolve.answered === 'no' || msg.resolve.result) && (
                  <p className="chat-resolve-next">
                    That closes out {msg.resolve.incidentRef}. Do you want to ask anything else —
                    another ticket to fix, or how the estate is looking right now?
                  </p>
                )}
              </>
            )}
          </div>
        )}
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
  };

  const suggestions = activeUser ? SUGGESTIONS_SIGNED_IN : SUGGESTIONS_ANON;
  const currentSession = sessions.find(s => s.id === activeSessionId);
  const activeTitle = currentSession?.title || (messages.length > 0 ? 'Active Conversation' : 'New Chat');

  return (
    <div className="chat-page">
      {/* Sessions Toolbar (Only for signed-in users) */}
      {activeUser && (
        <div className="chat-toolbar">
          <div className="chat-toolbar-left">
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
            <div className="chat-toolbar-title">
              <span>Session:</span> <strong>{activeTitle}</strong>
              {activeSessionId && (
                <button
                  className="chat-session-action-btn danger"
                  onClick={e => promptDeleteSession(e, activeSessionId, activeTitle)}
                  title="Delete current session"
                  style={{ marginLeft: 6 }}
                >
                  <Trash2 size={13} />
                </button>
              )}
            </div>
          </div>

          <button
            className="chat-toolbar-btn"
            onClick={createNewSession}
            title="Start a new conversation"
          >
            <Plus size={14} /> New Chat
          </button>
        </div>
      )}

      {/* Sessions Slide-over Drawer (Only for signed-in users) */}
      {activeUser && drawerOpen && (
        <div className="chat-sessions-backdrop" onClick={() => setDrawerOpen(false)} />
      )}
      {activeUser && (
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
            New Conversation
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
                              onClick={e => promptDeleteSession(e, s.id, s.title)}
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
      )}

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
              {(review.plan.how?.length ?? 0) > 0 && (
                <ol className="chat-modal-how">
                  {review.plan.how!.map((step, i) => <li key={i}>{step}</li>)}
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
              <button className="chat-btn chat-btn-secondary" onClick={() => setReview(null)}>Cancel</button>
              <button
                className="chat-btn chat-btn-danger"
                disabled={pendingAction === `run:${review.messageId}`}
                onClick={() => {
                  const target = review;
                  setReview(null);
                  runPlan(target.messageId, target.plan);
                }}
              >
                {pendingAction === `run:${review.messageId}` ? <Loader2 size={14} className="is-spin" /> : <Play size={14} />}
                {pendingAction === `run:${review.messageId}` ? 'Starting…' : 'Review done — run it'}
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

      {/* Proper Session Delete Confirmation Modal */}
      {sessionToDelete && (
        <div className="chat-modal-backdrop" onClick={() => !deletingSession && setSessionToDelete(null)}>
          <div
            className="chat-modal"
            role="dialog"
            aria-modal="true"
            aria-label="Delete conversation confirmation"
            style={{ maxWidth: 440, padding: 0 }}
            onClick={e => e.stopPropagation()}
          >
            <header className="chat-modal-head" style={{ borderBottom: '1px solid var(--border)', padding: '16px 20px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <div style={{
                  width: 34, height: 34, borderRadius: 8,
                  background: 'rgba(239, 68, 68, 0.15)', color: 'var(--red, #ef4444)',
                  display: 'flex', alignItems: 'center', justifyContent: 'center'
                }}>
                  <Trash2 size={18} />
                </div>
                <div>
                  <h3 style={{ margin: 0, fontSize: 16 }}>Delete Conversation</h3>
                  <p style={{ margin: 0, fontSize: 12, color: 'var(--text-3)' }}>This action cannot be undone.</p>
                </div>
              </div>
              <button
                className="chat-modal-close"
                onClick={() => !deletingSession && setSessionToDelete(null)}
                aria-label="Close"
              >
                <X size={16} />
              </button>
            </header>

            <div style={{ padding: '20px', fontSize: 13, color: 'var(--text-2)', lineHeight: 1.5 }}>
              Are you sure you want to permanently delete <strong>&ldquo;{sessionToDelete.title}&rdquo;</strong> and all of its message history?
            </div>

            <footer className="chat-modal-foot" style={{ borderTop: '1px solid var(--border)', padding: '14px 20px', display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
              <button
                className="chat-btn chat-btn-ghost"
                onClick={() => setSessionToDelete(null)}
                disabled={deletingSession}
              >
                Cancel
              </button>
              <button
                className="chat-btn chat-btn-danger"
                onClick={confirmDeleteSession}
                disabled={deletingSession}
                style={{ display: 'flex', alignItems: 'center', gap: 6 }}
              >
                {deletingSession ? <Loader2 size={14} className="spin" /> : <Trash2 size={14} />}
                <span>{deletingSession ? 'Deleting…' : 'Delete Conversation'}</span>
              </button>
            </footer>
          </div>
        </div>
      )}
    </div>
  );
};

export default ChatPage;
