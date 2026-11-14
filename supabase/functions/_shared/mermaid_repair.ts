// TypeScript copy of app/src/main/assets/mermaid/repair.js (the app's diagram page uses that one).
// shared_test.ts checks both give the same results. Change them together.

const HEADER = /^\s*(flowchart|graph)\s+(TB|TD|BT|RL|LR)\b[ \t;]*/i;
const KEYWORDS = /^(subgraph|style|classDef|class|click|linkStyle|direction)$/;

  // Node shapes; the text inside is either "quoted" or has no closing bracket in it.
const Q = '"[^"]*"';
const SHAPES = [
    '\\(\\(\\((?:' + Q + '|[^)"])*\\)\\)\\)', '\\(\\((?:' + Q + '|[^)"])*\\)\\)', '\\(\\[(?:' + Q + '|[^\\]"])*\\]\\)',
    '\\[\\[(?:' + Q + '|[^\\]"])*\\]\\]', '\\[\\((?:' + Q + '|[^)"])*\\)\\]', '\\{\\{(?:' + Q + '|[^}"])*\\}\\}',
    '\\[(?:' + Q + '|[^\\]"])*\\]', '\\((?:' + Q + '|[^)"])*\\)', '\\{(?:' + Q + '|[^}"])*\\}', '>(?:' + Q + '|[^\\]"])*\\]',
  ];
const NODE = new RegExp('^[A-Za-z0-9_][\\w-]*(?:' + SHAPES.join('|') + ')?(?::::[\\w-]+)?');
const EDGE_TEXT = /^(?:--|==|-\.)\s+(?:"[^"]*"|[^\s"][^-=|]*?)\s+(?:-->|==>|-\.->|---|===|-\.-)/;
const EDGE = /^(?:<|o|x)?(?:-{2,}|={2,}|-\.+-|~{3,})(?:>|o|x)?(?:\s*\|(?:"[^"]*"|[^|"])*\|)?/;

export function stripFences(code: string): string {
    return String(code).trim().replace(/^```(?:mermaid)?[ \t]*\n?/i, '').replace(/\n?```\s*$/, '').trim();
  }

  /** A flowchart written on one line: put each statement on its own line. Returns null if unsure. */
export function splitStatements(code: string): string | null {
    const m = code.match(HEADER);
    if (!m || code.indexOf('\n') >= 0) return null;
    const rest = code.slice(m[0].length);
    const statements: string[] = []; let cur = '', prev: string | null = null, i = 0;
    while (i < rest.length) {
      const s = rest.slice(i);
      const ws = s.match(/^[\s;]+/);
      if (ws) {
        if (ws[0].indexOf(';') >= 0 && cur.trim()) { statements.push(cur.trim()); cur = ''; prev = null; }
        else cur += ' ';
        i += ws[0].length; continue;
      }
      let t: RegExpMatchArray | null;
      if ((t = s.match(EDGE_TEXT)) || (t = s.match(EDGE))) { cur += t[0]; prev = 'edge'; i += t[0].length; continue; }
      if (s[0] === '&') { cur += '&'; prev = 'amp'; i += 1; continue; }
      if ((t = s.match(NODE))) {
        const id = t[0].match(/^[\w-]+/)![0];
        if (KEYWORDS.test(id)) return null; // subgraphs, styles…: too risky to guess
        if (id === 'end') {
          if (cur.trim()) statements.push(cur.trim());
          statements.push('end'); cur = ''; prev = null; i += t[0].length; continue;
        }
        if (prev === 'node' && cur.trim()) { statements.push(cur.trim()); cur = ''; }
        cur += t[0]; prev = 'node'; i += t[0].length; continue;
      }
      return null; // something unexpected: leave it to the next fix
    }
    if (cur.trim()) statements.push(cur.trim());
    if (statements.length < 2) return null;
    return m[1].toLowerCase() + ' ' + m[2].toUpperCase() + '\n' + statements.map(function (x: string) { return '  ' + x; }).join('\n');
  }

  /** In flowcharts, labels with brackets, colons and similar must be quoted: A[Fetch (parallel)] → A["Fetch (parallel)"]. */
export function quoteLabels(code: string): string | null {
    if (!HEADER.test(code)) return null;
    const out = code.replace(/([\w-]+)\[(?!["\[(\/\\])([^\]"\n]*[(){}:;,#&<>|=][^\]"\n]*)\]/g, function (_: string, id: string, text: string) {
      return id + '["' + text.replace(/"/g, "'") + '"]';
    }).replace(/([\w-]+)\{(?![{"])([^}"\n]*[()\[\]:;,#&<>|=][^}"\n]*)\}/g, function (_: string, id: string, text: string) {
      return id + '{"' + text.replace(/"/g, "'") + '"}';
    }).replace(/\|(?!")([^|"\n]*[()\[\]{}:;#&<>][^|"\n]*)\|/g, function (_: string, text: string) {
      return '|"' + text.replace(/"/g, "'") + '"|';
    });
    return out === code ? null : out;
  }

export function candidates(code: string): string[] {
    const list = [String(code)];
    const stripped = stripFences(code);
    list.push(stripped);
    const split = splitStatements(stripped);
    if (split) list.push(split);
    const quoted = quoteLabels(split || stripped);
    if (quoted) list.push(quoted);
    return list.filter(function (c: string, k: number) { return c && list.indexOf(c) === k; });
  }


/** What the server can do without running Mermaid: the last candidate is the most repaired. */
export function repairMermaid(code: string): string {
  const all = candidates(code);
  return all.length ? all[all.length - 1] : "";
}
