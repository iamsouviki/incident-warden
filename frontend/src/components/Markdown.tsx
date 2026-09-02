import React from 'react';
import { parseBlocks, type Block, type Inline } from './markdownBlocks';

/**
 * Renders assistant prose. One component for every surface that shows model output, so a fix to
 * how a table or a code block looks lands everywhere at once.
 *
 * React nodes only — no HTML string is ever constructed, which is what makes injected markup
 * impossible rather than merely escaped. See markdownBlocks.ts and markdownBlocks.check.ts.
 */

const spans = (list: Inline[]): React.ReactNode[] =>
  list.map((s, i) => {
    switch (s.kind) {
      case 'bold': return <strong key={i}>{s.text}</strong>;
      case 'italic': return <em key={i}>{s.text}</em>;
      case 'code': return <code key={i}>{s.text}</code>;
      case 'link': return <a key={i} href={s.href} target="_blank" rel="noreferrer noopener">{s.text}</a>;
      default: return <React.Fragment key={i}>{s.text}</React.Fragment>;
    }
  });

const block = (b: Block, key: number): React.ReactNode => {
  switch (b.kind) {
    case 'code':
      return (
        <pre key={key} className="md-code" data-lang={b.lang || undefined}>
          <code>{b.text}</code>
        </pre>
      );
    case 'h': {
      // Assistant prose sits inside a chat bubble, so its headings start below the page's own.
      const Tag = `h${Math.min(6, b.level + 2)}` as 'h3';
      return <Tag key={key} className="md-heading">{spans(b.spans)}</Tag>;
    }
    case 'list':
      return b.ordered
        ? <ol key={key} className="md-list">{b.items.map((it, i) => <li key={i}>{spans(it)}</li>)}</ol>
        : <ul key={key} className="md-list">{b.items.map((it, i) => <li key={i}>{spans(it)}</li>)}</ul>;
    case 'table':
      return (
        <div key={key} className="md-table-wrap">
          <table className="md-table">
            <thead>
              <tr>{b.head.map((c, i) => <th key={i} scope="col">{spans(c)}</th>)}</tr>
            </thead>
            <tbody>
              {b.rows.map((row, r) => <tr key={r}>{row.map((c, i) => <td key={i}>{spans(c)}</td>)}</tr>)}
            </tbody>
          </table>
        </div>
      );
    default:
      return (
        <p key={key}>
          {b.lines.map((line, i) => (
            <React.Fragment key={i}>{i > 0 && <br />}{spans(line)}</React.Fragment>
          ))}
        </p>
      );
  }
};

const Markdown: React.FC<{ text: string }> = ({ text }) => <>{parseBlocks(text).map(block)}</>;

export default Markdown;
