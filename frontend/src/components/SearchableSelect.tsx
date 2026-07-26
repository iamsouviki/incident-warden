import React, { useState, useRef, useEffect } from 'react';
import './SearchableSelect.css';

export interface SelectOption {
  value: string;
  label: string;
}

interface Props {
  options: SelectOption[];
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  allLabel?: string;      // label for the empty "all" option
  id?: string;
}

/**
 * Searchable dropdown — no external deps.
 * ponytail: minimum viable, no animations beyond CSS transition.
 */
const SearchableSelect: React.FC<Props> = ({ options, value, onChange, placeholder = 'Search...', allLabel = 'All', id }) => {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const ref = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const filtered = query
    ? options.filter(o => o.label.toLowerCase().includes(query.toLowerCase()))
    : options;

  const selected = options.find(o => o.value === value);
  const displayLabel = selected ? selected.label : allLabel;

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) {
        setOpen(false);
        setQuery('');
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);

  const pick = (v: string) => {
    onChange(v);
    setOpen(false);
    setQuery('');
  };

  return (
    <div className="ss-root" ref={ref} id={id}>
      <button
        type="button"
        className={`ss-trigger ${open ? 'ss-open' : ''} ${value ? 'ss-has-value' : ''}`}
        onClick={() => setOpen(o => !o)}
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span className="ss-label">{displayLabel}</span>
        <svg className={`ss-arrow ${open ? 'ss-arrow-up' : ''}`} width="12" height="12" viewBox="0 0 12 12">
          <path d="M2 4l4 4 4-4" stroke="currentColor" strokeWidth="1.5" fill="none" strokeLinecap="round"/>
        </svg>
      </button>

      {open && (
        <div className="ss-dropdown" role="listbox">
          <div className="ss-search-wrap">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" className="ss-search-icon">
              <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
            </svg>
            <input
              ref={inputRef}
              type="text"
              className="ss-search"
              placeholder={placeholder}
              value={query}
              onChange={e => setQuery(e.target.value)}
              onClick={e => e.stopPropagation()}
            />
          </div>
          <div className="ss-list">
            <div
              className={`ss-option ${!value ? 'ss-selected' : ''}`}
              onClick={() => pick('')}
              role="option"
              aria-selected={!value}
            >
              {allLabel}
            </div>
            {filtered.length === 0 ? (
              <div className="ss-empty">No results</div>
            ) : (
              filtered.map(o => (
                <div
                  key={o.value}
                  className={`ss-option ${o.value === value ? 'ss-selected' : ''}`}
                  onClick={() => pick(o.value)}
                  role="option"
                  aria-selected={o.value === value}
                >
                  {o.label}
                </div>
              ))
            )}
          </div>
        </div>
      )}
    </div>
  );
};

export default SearchableSelect;
