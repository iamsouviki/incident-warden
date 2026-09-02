import React, { useEffect, useState } from 'react';
import { authFetch } from '../services/api';
import { Plus, Save, Trash2, AlertTriangle, X } from 'lucide-react';

/**
 * Admin editor for the three agent stages' vocabulary.
 *
 * The agent used to know three things only because they were typed into Java: which words mean
 * "printer", how a till is named in this estate, and which tools may run. All three are now rows
 * in tools.skills, so a workspace that calls its tills "lanes" edits a row instead of waiting
 * for a release. Everything the backend guards, it still guards — this page can add a tool but
 * cannot add a way around the segment rules or the guardrail scan.
 */

interface Skill {
  id?: string;
  kind: 'CATEGORIZATION' | 'EXTRACTION' | 'EXECUTION';
  skillKey: string;
  pattern?: string;
  actionKey?: string;
  argCount?: number;
  mutating?: boolean;
  enabled?: boolean;
  description?: string;
  definitionJson?: string;
  updatedBy?: string;
  updatedAt?: string;
}

type Kind = Skill['kind'];

/** The 3 Core Agent Skills with default hardcoded operational rules */
const KINDS: Array<{ kind: Kind; badge: string; title: string; what: string; field: string; sample: string }> = [
  {
    kind: 'CATEGORIZATION',
    badge: 'Skill 1',
    title: 'Categorization Skill Rules',
    what: 'Maps incoming incident symptom keywords, title phrases, and log messages to operational incident categories.',
    field: 'Incident Classification Rules (Markdown / YAML)',
    sample: `# Incident Classification Rules

## Categories

### POG_ISSUE
- **Patterns / Keywords**:
  - \`POG MISSING\`
  - \`NOT ABLE PRINT LEBELS\`
  - \`NOT ABLE TO PRINT LABELS\`
  - \`PLANOGRAM ISSUE\`
  - \`SHELF TAG NOT GENERATING\`
- **Description**: Planogram and shelf label generation/printing errors.`,
  },
  {
    kind: 'EXTRACTION',
    badge: 'Skill 2',
    title: 'Extraction Skill Rules',
    what: 'Defines mandatory and optional parameter extraction rules from ticket text before triggering remediation tools.',
    field: 'Extraction Rules by Category (Markdown / YAML)',
    sample: `# Extraction Rules by Category

Define the mandatory and optional fields to extract from incident descriptions.

## POG_ISSUE
- **StoreNumber**: (Required) Store or branch number/identifier (e.g. \`4022\`, \`105\`).
- **PogLocation**: (Required) Planogram location code in Department-Number-Level format (e.g. \`4-800-U\` or \`4/800/U\`) or POG layout identifier.
- **LabelPrintIssueFlag**: (Required Boolean: \`true\` or \`false\`)
  - Set to \`true\` if the ticket mentions problems with printing labels, "unable to print labels", "label queue stuck", "labels not generating", or printer errors.
  - Defaults to \`false\` if label printing is not explicitly mentioned as failing.
- **OldPogFlag**: (Required Boolean: \`true\` or \`false\`)
  - Set to \`true\` if the ticket mentions "old POG", "previous planogram", "discontinued layout", "reset old version", or outdated POG references.
  - Defaults to \`false\` if it is a standard active planogram issue.
- **Skulist**: (Optional) Specific SKU or comma-separated list of product IDs affected (e.g. \`123456,789012\`).`,
  },
  {
    kind: 'EXECUTION',
    badge: 'Skill 3',
    title: 'Resolution & Automation Rules',
    what: 'Maps incident categories to execution scripts, mandatory parameters, and escalation routing rules.',
    field: 'Resolution & Automation Rules (Markdown / YAML)',
    sample: `# Resolution and Automation Rules

Map categories to resolution scripts, expected outcomes, and reassignment routing.

Only a **script_path** declared here may be executed: the pipeline builds its
automation allowlist from these values, so removing the line disables automation
for that category.

## POG_ISSUE
- **can_automate**: true
- **script_path**: "scripts/POGISSUEINCIDENTS.PS1"
- **mandatory_args**: ["StoreNumber", "PogLocation", "LabelPrintIssueFlag", "OldPogFlag"]
- **optional_args**: ["Skulist"]
- **success_status**: "Resolved"
- **failure_status**: "Escalated"
- **failure_route**: "ESCALATE_L2_STORE_OPS"
- **duplicate_route**: "ESCALATE_L3_MERCHANDISING_DEV"
- **file_missing_route**: "ESCALATE_L2_STORE_OPS"`,
  },
];

const empty = (kind: Kind): Skill => ({
  kind, skillKey: '', pattern: '', actionKey: '', argCount: 2,
  mutating: true, enabled: true, description: '', definitionJson: kind === 'EXTRACTION' ? '{"fields":[]}' : '{}',
});

const SkillsPanel: React.FC = () => {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [draft, setDraft] = useState<Skill | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const load = async () => {
    setError('');
    try {
      const res = await authFetch('/api/v1/skills');
      if (!res.ok) throw new Error(`Could not load skills (${res.status})`);
      setSkills(await res.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load skills.');
    }
  };

  useEffect(() => { void load(); }, []);

  const save = async () => {
    if (!draft) return;
    setBusy(true);
    setError('');
    try {
      const res = await authFetch('/api/v1/skills', { method: 'POST', body: JSON.stringify(draft) });
      if (!res.ok) {
        // The backend explains exactly which rule the row broke (bad regex, no capturing group,
        // non-ADMIN clearing "changes the system"). Showing its words beats inventing ours.
        const body = await res.json().catch(() => null);
        throw new Error(body?.error || `Save failed (${res.status})`);
      }
      setDraft(null);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed.');
    } finally {
      setBusy(false);
    }
  };

  const remove = async (skill: Skill) => {
    if (!skill.id) return;
    if (!window.confirm(`Delete "${skill.skillKey}"? The agent stops recognising it immediately.`)) return;
    setBusy(true);
    setError('');
    try {
      const res = await authFetch(`/api/v1/skills/${skill.id}`, { method: 'DELETE' });
      if (!res.ok) throw new Error(`Delete failed (${res.status})`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Delete failed.');
    } finally {
      setBusy(false);
    }
  };

  const label: React.CSSProperties = {
    display: 'block', fontSize: '11px', fontWeight: 700, textTransform: 'uppercase',
    letterSpacing: '0.4px', color: 'var(--text-muted)', marginBottom: '5px',
  };
  const input: React.CSSProperties = {
    width: '100%', padding: '9px 10px', minHeight: '38px', fontSize: '12.5px',
    background: 'var(--surface2)', color: 'var(--text)',
    border: '1px solid var(--border)', borderRadius: '5px',
  };

  return (
    <div style={{ display: 'grid', gap: '16px', width: '100%' }}>
      {error && (
        <div className="card" style={{ padding: '12px 14px', display: 'flex', gap: '8px', alignItems: 'flex-start', color: 'var(--crit, #dc2626)', fontSize: '12.5px' }}>
          <AlertTriangle size={15} style={{ flexShrink: 0, marginTop: '1px' }} /> <span>{error}</span>
        </div>
      )}

      {draft && (
        <div className="card" style={{ padding: '16px 18px' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '14px' }}>
            <strong style={{ fontSize: '13px' }}>
              {draft.id ? 'Edit skill' : 'New skill'} · {KINDS.find(k => k.kind === draft.kind)?.title}
            </strong>
            <button onClick={() => setDraft(null)} aria-label="Close editor"
                    style={{ background: 'none', border: 'none', color: 'var(--text-dim)', cursor: 'pointer' }}>
              <X size={16} />
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '14px' }}>
            <div>
              <label style={label} htmlFor="skill-kind">Stage</label>
              <select id="skill-kind" style={input} value={draft.kind} disabled={Boolean(draft.id)}
                      onChange={e => setDraft({ ...draft, kind: e.target.value as Kind })}>
                {KINDS.map(k => <option key={k.kind} value={k.kind}>{k.title}</option>)}
              </select>
            </div>
            <div>
              <label style={label} htmlFor="skill-key">
                {draft.kind === 'EXECUTION' ? 'Tool name (CAPS_WITH_UNDERSCORES)' : 'Category'}
              </label>
              <input id="skill-key" style={input} value={draft.skillKey} placeholder={draft.kind === 'EXECUTION' ? 'ROLL_STORE' : 'PRINTING'}
                     onChange={e => setDraft({ ...draft, skillKey: e.target.value })} />
            </div>

            {draft.kind !== 'EXECUTION' && (
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={label} htmlFor="skill-pattern">
                  {KINDS.find(k => k.kind === draft.kind)?.field}
                </label>
                <input id="skill-pattern" style={{ ...input, fontFamily: 'var(--font-mono, monospace)' }} value={draft.pattern || ''}
                       placeholder={draft.kind === 'EXTRACTION'
                         ? '\\b(\\d{2,6}-(?:till|lane|pos)-\\d{1,3})\\b'
                         : 'printer, print queue, print job'}
                       onChange={e => setDraft({ ...draft, pattern: e.target.value })} />
              </div>
            )}

            {draft.kind !== 'EXECUTION' && (
              <div style={{ gridColumn: '1 / -1' }}>
                <label style={label} htmlFor="skill-definition">Structured rule definition (JSON)</label>
                <textarea id="skill-definition" style={{ ...input, minHeight: '130px', fontFamily: 'var(--font-mono, monospace)', resize: 'vertical' }}
                          value={draft.definitionJson || '{}'}
                          placeholder={draft.kind === 'EXTRACTION'
                            ? '{"fields":[{"key":"StoreNumber","required":true,"pattern":"..."}]}'
                            : '{"script_path":"scripts/example.ps1","can_automate":true}' }
                          onChange={e => setDraft({ ...draft, definitionJson: e.target.value })} />
                <div style={{ marginTop: '4px', fontSize: '11px', color: 'var(--text-muted)' }}>
                  Extraction rules define dynamic fields. Categorization rules carry resolution metadata such as script_path and escalation routes.
                </div>
              </div>
            )}

            {draft.kind === 'CATEGORIZATION' && (
              <div>
                <label style={label} htmlFor="skill-action">Action to propose</label>
                <input id="skill-action" style={input} value={draft.actionKey || ''} placeholder="restart-approved-service"
                       onChange={e => setDraft({ ...draft, actionKey: e.target.value })} />
              </div>
            )}

            {draft.kind === 'EXECUTION' && (
              <>
                <div>
                  <label style={label} htmlFor="skill-args">Arguments it takes</label>
                  <input id="skill-args" style={input} type="number" min={0} max={8} value={draft.argCount ?? 2}
                         onChange={e => setDraft({ ...draft, argCount: Number(e.target.value) })} />
                </div>
                <div>
                  <label style={label} htmlFor="skill-mutating">Effect</label>
                  <select id="skill-mutating" style={input} value={draft.mutating === false ? 'read' : 'change'}
                          onChange={e => setDraft({ ...draft, mutating: e.target.value === 'change' })}>
                    <option value="change">Changes the system — needs approval</option>
                    <option value="read">Read only</option>
                  </select>
                </div>
              </>
            )}

            <div style={{ gridColumn: '1 / -1' }}>
              <label style={label} htmlFor="skill-desc">What it does, in plain words</label>
              <input id="skill-desc" style={input} value={draft.description || ''}
                     placeholder="Restarts the till's payment service. Card payments fail for about ten seconds."
                     onChange={e => setDraft({ ...draft, description: e.target.value })} />
            </div>
          </div>

          {draft.kind === 'EXECUTION' && draft.mutating === false && (
            <p style={{ margin: '12px 0 0', fontSize: '12px', color: 'var(--warn, #b45309)', lineHeight: 1.5 }}>
              A read-only tool skips mutation review. Only mark it read-only if it genuinely cannot
              change anything — the backend rejects this from anyone who is not an admin.
            </p>
          )}

          <div style={{ display: 'flex', gap: '10px', marginTop: '16px', flexWrap: 'wrap' }}>
            <button onClick={() => void save()} disabled={busy || !draft.skillKey.trim()} className="btn-primary"
                    style={{ display: 'flex', alignItems: 'center', gap: '6px', minHeight: '38px', padding: '0 16px' }}>
              <Save size={14} /> {busy ? 'Saving…' : 'Save skill'}
            </button>
            <label style={{ display: 'flex', alignItems: 'center', gap: '7px', fontSize: '12.5px', color: 'var(--text-dim)' }}>
              <input type="checkbox" checked={draft.enabled !== false}
                     onChange={e => setDraft({ ...draft, enabled: e.target.checked })} />
              Active
            </label>
          </div>
        </div>
      )}

      {KINDS.map(group => {
        const rows = skills.filter(s => s.kind === group.kind);
        return (
          <div key={group.kind} className="card" style={{ padding: '16px 18px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: '12px', flexWrap: 'wrap' }}>
              <div style={{ maxWidth: '620px' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '4px' }}>
                  <span style={{ fontSize: '10.5px', fontWeight: 800, padding: '2px 7px', borderRadius: '4px', background: 'var(--accent-dim, rgba(59,130,246,0.15))', color: 'var(--accent)' }}>
                    {group.badge}
                  </span>
                  <strong style={{ fontSize: '14px', color: 'var(--text)' }}>{group.title}</strong>
                </div>
                <p style={{ margin: '3px 0 0', fontSize: '12px', lineHeight: 1.55, color: 'var(--text-dim)' }}>{group.what}</p>
              </div>
              <button onClick={() => setDraft(empty(group.kind))}
                      style={{
                        display: 'flex', alignItems: 'center', gap: '5px', minHeight: '34px', padding: '0 12px',
                        background: 'var(--surface2)', color: 'var(--text)', border: '1px solid var(--border)',
                        borderRadius: '5px', fontSize: '12px', fontWeight: 600, cursor: 'pointer', whiteSpace: 'nowrap',
                      }}>
                <Plus size={13} /> Add
              </button>
            </div>

            {rows.length === 0 ? (
              <p style={{ margin: '14px 0 0', fontSize: '12px', color: 'var(--text-muted)' }}>
                {group.kind === 'EXECUTION'
                  ? 'No tools defined, so the four built-in tools are in force.'
                  : 'Nothing here yet. The built-in behaviour applies.'}
              </p>
            ) : (
              <div style={{ marginTop: '14px', display: 'grid', gap: '8px' }}>
                {rows.map(row => (
                  <div key={row.id} style={{
                    display: 'flex', gap: '12px', alignItems: 'flex-start', flexWrap: 'wrap',
                    padding: '10px 12px', background: 'var(--surface2)', border: '1px solid var(--border)',
                    borderRadius: '6px', opacity: row.enabled === false ? 0.55 : 1,
                  }}>
                    <div style={{ flex: '1 1 240px', minWidth: 0 }}>
                      <code style={{ fontSize: '12px', fontWeight: 700 }}>{row.skillKey}</code>
                      {row.enabled === false && (
                        <span style={{ marginLeft: '8px', fontSize: '10.5px', textTransform: 'uppercase', color: 'var(--text-muted)' }}>off</span>
                      )}
                      {row.kind === 'EXECUTION' && (
                        <span style={{ marginLeft: '8px', fontSize: '11px', color: 'var(--text-dim)' }}>
                          {row.argCount ?? 0} args · {row.mutating === false ? 'read only' : 'changes the system'}
                        </span>
                      )}
                      {row.pattern && (
                        <div style={{ fontFamily: 'var(--font-mono, monospace)', fontSize: '11px', color: 'var(--text-dim)', marginTop: '4px', overflowWrap: 'anywhere' }}>
                          {row.pattern}
                        </div>
                      )}
                      {row.description && (
                        <div style={{ fontSize: '11.5px', color: 'var(--text-dim)', marginTop: '4px', lineHeight: 1.5 }}>{row.description}</div>
                      )}
                    </div>
                    <div style={{ display: 'flex', gap: '6px' }}>
                      <button onClick={() => setDraft({ ...row })} title="Edit"
                              style={{ minHeight: '32px', padding: '0 10px', background: 'transparent', color: 'var(--text)', border: '1px solid var(--border)', borderRadius: '5px', fontSize: '11.5px', cursor: 'pointer' }}>
                        Edit
                      </button>
                      <button onClick={() => void remove(row)} title="Delete" aria-label={`Delete ${row.skillKey}`}
                              style={{ minHeight: '32px', padding: '0 10px', background: 'transparent', color: 'var(--crit, #dc2626)', border: '1px solid var(--border)', borderRadius: '5px', cursor: 'pointer' }}>
                        <Trash2 size={13} />
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
};

export default SkillsPanel;
