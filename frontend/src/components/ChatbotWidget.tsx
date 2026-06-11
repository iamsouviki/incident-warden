import React, { useState, useRef, useEffect } from 'react';
import './ChatbotWidget.css';
import { authFetch } from '../services/api';
import { MessageCircle, X, BotMessageSquare, Send } from 'lucide-react';

interface Message {
  id: string;
  role: 'user' | 'bot';
  text?: string;
  loading?: boolean;
  error?: boolean;
}

interface Props {
  tenantId?: string;
}

const ChatbotWidget: React.FC<Props> = ({ tenantId }) => {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [messages, setMessages] = useState<Message[]>([
    {
      id: 'welcome',
      role: 'bot',
      text: "👋 Hi! I'm the SOP Assistant. Ask me a question about the inserted SOPs. I will reply ONLY if the answer is found in the documents.",
    },
  ]);
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    if (open) setTimeout(() => inputRef.current?.focus(), 150);
  }, [open]);

  const addMessage = (msg: Omit<Message, 'id'>) => {
    const id = `${Date.now()}-${Math.random()}`;
    setMessages(prev => [...prev, { id, ...msg }]);
    return id;
  };

  const updateMessage = (id: string, update: Partial<Message>) => {
    setMessages(prev => prev.map(m => m.id === id ? { ...m, ...update } : m));
  };

  const handleSend = async () => {
    const q = input.trim();
    if (!q || loading) return;
    setInput('');
    addMessage({ role: 'user', text: q });

    const botId = addMessage({ role: 'bot', loading: true });
    setLoading(true);

    try {
      const response = await fetch('/api/v1/rag/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question: q }),
      });

      if (response.ok) {
        const d = await response.json();
        updateMessage(botId, { loading: false, text: d.answer });
      } else {
        updateMessage(botId, { loading: false, error: true, text: 'not found (Server Error)' });
      }
    } catch {
      updateMessage(botId, {
        loading: false,
        error: true,
        text: 'Failed to connect to backend.',
      });
    } finally {
      setLoading(false);
    }
  };

  const renderBotMessage = (msg: Message) => {
    if (msg.loading) {
      return (
        <div className="cb-msg cb-msg-bot">
          <div className="cb-avatar"><BotMessageSquare size={18} /></div>
          <div className="cb-bubble cb-bubble-bot">
            <span className="cb-typing"><span /><span /><span /></span>
          </div>
        </div>
      );
    }

    if (msg.text) {
      return (
        <div className="cb-msg cb-msg-bot">
          <div className="cb-avatar"><BotMessageSquare size={18} /></div>
          <div className={`cb-bubble cb-bubble-bot ${msg.error ? 'cb-bubble-error' : ''}`}>
            {msg.text.split('\n').map((line, i) => (
              <p key={i} dangerouslySetInnerHTML={{ __html: formatMarkdown(line) }} />
            ))}
          </div>
        </div>
      );
    }

    return null;
  };

  return (
    <>
      {/* Floating button */}
      <button
        className={`cb-toggle ${open ? 'cb-toggle-open' : ''}`}
        onClick={() => setOpen(o => !o)}
        title="SOP Assistant"
      >
        {open ? <X size={20} /> : <MessageCircle size={24} />}
        {!open && <span className="cb-toggle-label">SOP Chat</span>}
      </button>

      {/* Chat panel */}
      {open && (
        <div className="cb-panel">
          {/* Header */}
          <div className="cb-header">
            <div className="cb-header-left">
              <span className="cb-header-icon"><BotMessageSquare size={20} /></span>
              <div>
                <div className="cb-header-title">SOP Assistant</div>
                <div className="cb-header-sub">Ask strict queries against your SOPs</div>
              </div>
            </div>
            <button className="cb-close-btn" onClick={() => setOpen(false)}><X size={16} /></button>
          </div>

          {/* Messages */}
          <div className="cb-messages">
            {messages.map(msg => (
              <div key={msg.id}>
                {msg.role === 'user' ? (
                  <div className="cb-msg cb-msg-user">
                    <div className="cb-bubble cb-bubble-user">{msg.text}</div>
                  </div>
                ) : (
                  renderBotMessage(msg)
                )}
              </div>
            ))}
            <div ref={bottomRef} />
          </div>

          {/* Input */}
          <div className="cb-input-area">
            <input
              ref={inputRef}
              className="cb-input"
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleSend()}
              placeholder="Ask a question about the SOPs..."
              disabled={loading}
            />
            <button
              className="cb-send-btn"
              onClick={handleSend}
              disabled={loading || !input.trim()}
            >
              {loading ? '…' : <Send size={16} />}
            </button>
          </div>
        </div>
      )}
    </>
  );
};

function formatMarkdown(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>');
}

export default ChatbotWidget;
