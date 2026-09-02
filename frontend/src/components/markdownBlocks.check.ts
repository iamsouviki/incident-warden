/**
 * The runnable check for the markdown parser: `node markdownBlocks.check.ts` from this directory.
 *
 * These are the properties that matter if the parser is edited — that model output cannot become
 * markup, that a code fence stays verbatim, and that the structures the prompts emit survive.
 */
import { parseBlocks, parseInline, MAX_RENDERED_CHARS, type Block } from './markdownBlocks.ts';

const flat = (b: Block): string => JSON.stringify(b);

// Model output is data, not markup: angle brackets stay text, so no renderer can be tricked.
const injected = parseBlocks('<script>alert(1)</script> and <img onerror=x>');
console.assert(injected.length === 1 && injected[0].kind === 'p', flat(injected[0]));
console.assert(flat(injected[0]).includes('<script>'), 'kept as literal text');
console.assert(injected[0].kind === 'p' && injected[0].lines[0].every(s => s.kind === 'text'), 'no span kinds');

// A javascript: link is not a link.
const bad = parseInline('[click](javascript:alert(1))');
console.assert(bad.every(s => s.kind === 'text'), flat({ kind: 'p', lines: [bad] } as Block));
const good = parseInline('see [the SOP](https://example.test/sop)');
console.assert(good.some(s => s.kind === 'link'), 'https link kept');

// A fence is verbatim: the ** inside it is not emphasis, and the blank line does not split it.
const fenced = parseBlocks('run this:\n```bash\nsystemctl restart **pos**\n\necho done\n```\nafter');
console.assert(fenced.map(b => b.kind).join(',') === 'p,code,p', fenced.map(b => b.kind).join(','));
console.assert(fenced[1].kind === 'code' && fenced[1].lang === 'bash', flat(fenced[1]));
console.assert(fenced[1].kind === 'code' && fenced[1].text === 'systemctl restart **pos**\n\necho done', flat(fenced[1]));

// An unterminated fence still closes, rather than swallowing nothing or throwing.
const open = parseBlocks('```\nhalf a script');
console.assert(open.length === 1 && open[0].kind === 'code', flat(open[0]));

// Lists, tables, headings and hard line breaks.
const list = parseBlocks('- first\n- **second**\n\n1. one\n2. two');
console.assert(list.length === 2, list.length);
console.assert(list[0].kind === 'list' && !list[0].ordered && list[0].items.length === 2, flat(list[0]));
console.assert(list[1].kind === 'list' && list[1].ordered && list[1].items.length === 2, flat(list[1]));

const table = parseBlocks('| Host | Status |\n|---|:--:|\n| pos-01 | up |\n| pos-02 | down |');
console.assert(table.length === 1 && table[0].kind === 'table', flat(table[0]));
console.assert(table[0].kind === 'table' && table[0].head.length === 2 && table[0].rows.length === 2, flat(table[0]));

const heading = parseBlocks('## Root cause\nthe disk filled\nand stayed full');
console.assert(heading[0].kind === 'h' && heading[0].level === 2, flat(heading[0]));
console.assert(heading[1].kind === 'p' && heading[1].lines.length === 2, 'both lines kept');

// Bounded: a runaway completion is truncated rather than rendered whole.
const huge = parseBlocks('x'.repeat(MAX_RENDERED_CHARS * 2));
console.assert(JSON.stringify(huge).length < MAX_RENDERED_CHARS + 200, 'truncated');

console.log('markdown parser: ok');
