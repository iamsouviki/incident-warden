import React from 'react';
import { ArrowDown, CheckCircle2, FileSearch, ShieldCheck } from 'lucide-react';
import HitlApprovalQueue from '../components/HitlApprovalQueue';
import './HitlPage.css';

const HitlPage: React.FC = () => {
  return (
    <div className="hitl-page">
      <div className="hitl-explainer">
        <div className="hitl-explainer-title"><ShieldCheck size={16} /> Human decision gate</div>
        <p>Agents can collect evidence and propose a fix. Operators decide what is allowed to run, record the reason, and keep the incident history complete.</p>
        <div className="hitl-explainer-flow"><span><FileSearch size={13} /> Evidence</span><ArrowDown size={13} /><span><ShieldCheck size={13} /> Review</span><ArrowDown size={13} /><span><CheckCircle2 size={13} /> Execute / reject</span></div>
      </div>
      <HitlApprovalQueue />
    </div>
  );
};

export default HitlPage;
