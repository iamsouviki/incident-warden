/**
 * The one place assistant prose is turned into structure, for every surface that shows it.
 *
 * This parses to data, never to HTML. The renderer that consumes it builds React nodes, so there
 * is no `dangerouslySetInnerHTML` anywhere in the app and model output cannot inject markup — the
 * escape-then-inject version it replaced was one forgotten `.replace` away from being an XSS hole.
 *
 * Deliberately a subset: fenced code, headings, lists, GFM tables, paragraphs with hard line
 * breaks, and inline code/bold/italic/links. That is what the prompts actually emit. Anything
 * unrecognised falls through as text rather than being dropped.
 *
 * ponytail: hand-rolled because the sandbox cannot reach the npm registry to install
 * react-markdown. Swap `parseBlocks` for `<ReactMarkdown remarkPlugins={[remarkGfm]}>` the moment
 * a network is available — same call site, less code to own.
 */

/** Bounded on purpose: a runaway completion must not be able to hang the renderer. */
export const MAX_RENDERED_CHARS = 20000;

export type Inline =
  | { kind: 'text'; text: string }
  | { kind: 'bold'; text: string }
  | { kind: 'italic'; text: string }
  | { kind: 'code'; text: string }
  | { kind: 'link'; text: string; href: string };

export type Block =
  | { kind: 'p'; lines: Inline[][] }
  | { kind: 'h'; level: number; spans: Inline[] }
  | { kind: 'code'; lang: string; text: string }
  | { kind: 'list'; ordered: boolean; items: Inline[][] }
  | { kind: 'table'; head: Inline[][]; rows: Inline[][][] };

const INLINE = /`([^`]+)`|\*\*([\s\S]+?)\*\*|\*([^*\n]+)\*|\[([^\]]+)\]\(([^)\s]+)\)/g;

/** Only schemes a link can safely carry. `javascript:` and `data:` render as plain text. */
const isSafeHref = (href: string) => /^(https?:\/\/|mailto:|\/)/i.test(href);

export function parseInline(text: string): Inline[] {
  const spans: Inline[] = [];
  let last = 0;
  INLINE.lastIndex = 0;
  for (let m = INLINE.exec(text); m; m = INLINE.exec(text)) {
    if (m.index > last) spans.push({ kind: 'text', text: text.slice(last, m.index) });
    if (m[1] !== undefined) spans.push({ kind: 'code', text: m[1] });
    else if (m[2] !== undefined) spans.push({ kind: 'bold', text: m[2] });
    else if (m[3] !== undefined) spans.push({ kind: 'italic', text: m[3] });
    else if (isSafeHref(m[5])) spans.push({ kind: 'link', text: m[4], href: m[5] });
    else spans.push({ kind: 'text', text: m[0] });
    last = m.index + m[0].length;
  }
  if (last < text.length) spans.push({ kind: 'text', text: text.slice(last) });
  return spans.length ? spans : [{ kind: 'text', text: '' }];
}

const cells = (row: string): Inline[][] =>
  row.replace(/^\||\|$/g, '').split('|').map(c => parseInline(c.trim()));

const isDivider = (line = '') => /^\|?[\s:-]*-[\s:|-]*\|?$/.test(line) && line.includes('-');
const listItem = (line: string) => /^\s*(?:([-*+])|(\d+)[.)])\s+(.*)$/.exec(line);

export function parseBlocks(input: string): Block[] {
  const text = input.length > MAX_RENDERED_CHARS
    ? `${input.slice(0, MAX_RENDERED_CHARS)}\n\n… response truncated.`
    : input;
  const lines = text.split('\n');
  const blocks: Block[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) { i++; continue; }

    // Fenced code: contents are verbatim, never inline-parsed. An unterminated fence runs to the end.
    const fence = /^\s*```(\w*)/.exec(line);
    if (fence) {
      const body: string[] = [];
      for (i++; i < lines.length && !/^\s*```/.test(lines[i]); i++) body.push(lines[i]);
      i++;
      blocks.push({ kind: 'code', lang: fence[1] || '', text: body.join('\n') });
      continue;
    }

    const heading = /^(#{1,6})\s+(.*)$/.exec(line);
    if (heading) {
      blocks.push({ kind: 'h', level: heading[1].length, spans: parseInline(heading[2]) });
      i++;
      continue;
    }

    if (line.trim().startsWith('|') && isDivider(lines[i + 1])) {
      const head = cells(line.trim());
      const rows: Inline[][][] = [];
      for (i += 2; i < lines.length && lines[i].trim().startsWith('|'); i++) rows.push(cells(lines[i].trim()));
      blocks.push({ kind: 'table', head, rows });
      continue;
    }

    let item = listItem(line);
    if (item) {
      const ordered = item[2] !== undefined;
      const items: Inline[][] = [];
      while (i < lines.length && item && (item[2] !== undefined) === ordered) {
        items.push(parseInline(item[3]));
        i++;
        item = listItem(lines[i] ?? '');
      }
      blocks.push({ kind: 'list', ordered, items });
      continue;
    }

    // Paragraph: every source line is kept as its own line, because status text and citations
    // rely on the break the model put there.
    const para: Inline[][] = [];
    for (; i < lines.length && lines[i].trim() && !/^\s*```|^#{1,6}\s/.test(lines[i]) && !listItem(lines[i]); i++) {
      para.push(parseInline(lines[i]));
    }
    blocks.push({ kind: 'p', lines: para });
  }

  return blocks;
}
