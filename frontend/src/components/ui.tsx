import { ButtonHTMLAttributes, ReactNode, useEffect } from 'react';
import { X } from 'lucide-react';
import './ui.css';

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';

export function Button({ variant = 'secondary', size = 'md', className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: ButtonVariant; size?: 'sm' | 'md' }) {
  return <button className={`ui-button ui-button-${variant} ui-button-${size} ${className}`} {...props} />;
}

export function Badge({ tone = 'neutral', children, className = '', title }: { tone?: 'neutral' | 'info' | 'success' | 'warning' | 'danger'; children: ReactNode; className?: string; title?: string }) {
  return <span className={`ui-badge ui-badge-${tone} ${className}`} title={title}>{children}</span>;
}

export function Spinner({ size = 'md' }: { size?: 'sm' | 'md' }) {
  return <span className={`ui-spinner ui-spinner-${size}`} role="status" aria-label="Loading" />;
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return <div className="ui-empty-state"><div className="ui-empty-icon">—</div><h3>{title}</h3>{description && <p>{description}</p>}{action}</div>;
}

export function Modal({ open, title, onClose, children, footer }: { open: boolean; title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;
  return <div className="ui-overlay" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target) onClose(); }}><section className="ui-modal" role="dialog" aria-modal="true" aria-label={title}><header className="ui-modal-header"><h2>{title}</h2><button className="ui-icon-button" onClick={onClose} aria-label="Close"><X size={16} /></button></header><div className="ui-modal-body">{children}</div>{footer && <footer className="ui-modal-footer">{footer}</footer>}</section></div>;
}

export function Drawer({ open, title, onClose, children, footer }: { open: boolean; title: string; onClose: () => void; children: ReactNode; footer?: ReactNode }) {
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => event.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open, onClose]);

  if (!open) return null;
  return <div className="ui-overlay" role="presentation" onMouseDown={event => { if (event.currentTarget === event.target) onClose(); }}><aside className="ui-drawer" role="dialog" aria-modal="true" aria-label={title}><header className="ui-modal-header"><h2>{title}</h2><button className="ui-icon-button" onClick={onClose} aria-label="Close"><X size={16} /></button></header><div className="ui-drawer-body">{children}</div>{footer && <footer className="ui-modal-footer">{footer}</footer>}</aside></div>;
}
