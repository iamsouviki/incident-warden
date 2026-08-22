import React, { useState } from 'react';
import { ArrowDown, CheckCircle2, FileCode2, FileSearch, ShieldCheck } from 'lucide-react';
import HitlApprovalQueue from '../components/HitlApprovalQueue';
import HitlReviewConsole from '../components/HitlReviewConsole';
import './HitlPage.css';

/**
 * Two views, one page: the triage queue and the review console. Selection lives here so
 * a decision in the console can bump reloadKey and the queue re-reads from the server
 * rather than being patched optimistically — an approval queue showing stale state is
 * how someone approves the same plan twice.
 */
const HitlPage: React.FC = () => {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  if (selectedId) {
    return (
      <div className="hitl-page">
        <HitlReviewConsole
          requestId={selectedId}
          onBack={() => setSelectedId(null)}
          onChanged={() => setReloadKey(key => key + 1)}
        />
      </div>
    );
  }

  return (
    <div className="hitl-page">
      <div className="hitl-explainer">
        <div className="hitl-explainer-title"><ShieldCheck size={16} /> Human decision gate</div>
        <p>
          The agent gathers evidence and writes the remediation script — from an approved SOP where one exists, from model
          knowledge where none does. It cannot run either. You read the script, approve or reject it, and the exact text you
          approved is the only thing that can execute.
        </p>
        <div className="hitl-explainer-flow">
          <span><FileSearch size={13} /> Evidence</span><ArrowDown size={13} />
          <span><FileCode2 size={13} /> Script</span><ArrowDown size={13} />
          <span><ShieldCheck size={13} /> Review</span><ArrowDown size={13} />
          <span><CheckCircle2 size={13} /> Dry run → execute</span>
        </div>
      </div>
      <HitlApprovalQueue onSelect={setSelectedId} reloadKey={reloadKey} />
    </div>
  );
};

export default HitlPage;
