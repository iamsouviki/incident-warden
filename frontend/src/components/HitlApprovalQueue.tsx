import React, { useEffect, useState } from 'react';
import { apiGet, apiPost } from '../services/api';
import './HitlApprovalQueue.css';

interface Incident {
  id: string;
  subject: string;
  description: string;
  priority: string;
  status: string;
  confidenceScore: number;
  category: string;
  externalSource: string;
  externalId: string;
}

const HitlApprovalQueue: React.FC = () => {
  const [incidents, setIncidents] = useState<Incident[]>([]);
  const [loading, setLoading] = useState(true);

  const fetchPendingApprovals = async () => {
    setLoading(true);
    try {
      // Fetch incidents with status PENDING_APPROVAL
      const data = await apiGet<Incident[]>('/api/v1/incidents');
      setIncidents(data.filter(inc => inc.status === 'PENDING_APPROVAL'));
    } catch (error) {
      console.error('Failed to fetch approvals', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchPendingApprovals();
  }, []);

  const handleAction = async (id: string, approve: boolean) => {
    try {
      const newStatus = approve ? 'APPROVED' : 'REJECTED';
      await apiPost(`/api/v1/incidents/${id}/comments`, {
        commentText: `HITL Decision: ${newStatus}`,
        author: 'Human Operator'
      });
      // In a real app, we'd update the status via a dedicated endpoint
      setIncidents(prev => prev.filter(inc => inc.id !== id));
    } catch (error) {
      alert('Action failed');
    }
  };

  if (loading) return <div className="hitl-queue-loading">Loading approval queue...</div>;

  return (
    <div className="hitl-queue-container">
      <div className="hitl-queue-header">
        <h2>HITL Approval Queue</h2>
        <button onClick={fetchPendingApprovals} className="refresh-btn">Refresh</button>
      </div>
      
      {incidents.length === 0 ? (
        <div className="empty-queue">No incidents requiring approval.</div>
      ) : (
        <div className="hitl-grid">
          {incidents.map(inc => (
            <div key={inc.id} className="hitl-card">
              <div className="hitl-card-header">
                <span className={`priority-badge ${inc.priority.toLowerCase()}`}>{inc.priority}</span>
                <span className="confidence-score">Confidence: {inc.confidenceScore}%</span>
              </div>
              <h3>{inc.subject}</h3>
              <p className="incident-meta">{inc.externalSource} | {inc.externalId}</p>
              <div className="hitl-actions">
                <button onClick={() => handleAction(inc.id, true)} className="approve-btn">Approve</button>
                <button onClick={() => handleAction(inc.id, false)} className="reject-btn">Reject</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

export default HitlApprovalQueue;
