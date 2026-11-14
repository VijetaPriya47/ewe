/*
 * Mends common mistakes in AI-written Mermaid code. Plain JS with no DOM, so the same file runs in
 * the app's diagram page and in the server tests (which check the TypeScript copy matches it).
 *
 *   candidates(code) → list of codes to try in order: the original first, then repaired versions.
 */
var MermaidRepair = (function () {
  var HEADER = /^\s*(flowchart|graph)\s+(TB|TD|BT|RL|LR)\b[ \t;]*/i;
  var KEYWORDS = /^(subgraph|style|classDef|class|click|linkStyle|direction)$/;

  // Node shapes; the text inside is either "quoted" or has no closing bracket in it.
  var Q = '"[^"]*"';
  var SHAPES = [
    '\\(\\(\\((?:' + Q + '|[^)"])*\\)\\)\\)', '\\(\\((?:' + Q + '|[^)"])*\\)\\)', '\\(\\[(?:' + Q + '|[^\\]"])*\\]\\)',
    '\\[\\[(?:' + Q + '|[^\\]"])*\\]\\]', '\\[\\((?:' + Q + '|[^)"])*\\)\\]', '\\{\\{(?:' + Q + '|[^}"])*\\}\\}',
    '\\[(?:' + Q + '|[^\\]"])*\\]', '\\((?:' + Q + '|[^)"])*\\)', '\\{(?:' + Q + '|[^}"])*\\}', '>(?:' + Q + '|[^\\]"])*\\]',
  ];
  var NODE = new RegExp('^[A-Za-z0-9_][\\w-]*(?:' + SHAPES.join('|') + ')?(?::::[\\w-]+)?');
  var EDGE_TEXT = /^(?:--|==|-\.)\s+(?:"[^"]*"|[^\s"][^-=|]*?)\s+(?:-->|==>|-\.->|---|===|-\.-)/;
  var EDGE = /^(?:<|o|x)?(?:-{2,}|={2,}|-\.+-|~{3,})(?:>|o|x)?(?:\s*\|(?:"[^"]*"|[^|"])*\|)?/;

  function stripFences(code) {
    return String(code).trim().replace(/^```(?:mermaid)?[ \t]*\n?/i, '').replace(/\n?```\s*$/, '').trim();
  }

  /** A flowchart written on one line: put each statement on its own line. Returns null if unsure. */
  function splitStatements(code) {
    var m = code.match(HEADER);
    if (!m || code.indexOf('\n') >= 0) return null;
    var rest = code.slice(m[0].length);
    var statements = [], cur = '', prev = null, i = 0;
    while (i < rest.length) {
      var s = rest.slice(i);
      var ws = s.match(/^[\s;]+/);
      if (ws) {
        if (ws[0].indexOf(';') >= 0 && cur.trim()) { statements.push(cur.trim()); cur = ''; prev = null; }
        else cur += ' ';
        i += ws[0].length; continue;
      }
      var t;
      if ((t = s.match(EDGE_TEXT)) || (t = s.match(EDGE))) { cur += t[0]; prev = 'edge'; i += t[0].length; continue; }
      if (s[0] === '&') { cur += '&'; prev = 'amp'; i += 1; continue; }
      if ((t = s.match(NODE))) {
        var id = t[0].match(/^[\w-]+/)[0];
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
    return m[1].toLowerCase() + ' ' + m[2].toUpperCase() + '\n' + statements.map(function (x) { return '  ' + x; }).join('\n');
  }

  /** In flowcharts, labels with brackets, colons and similar must be quoted: A[Fetch (parallel)] → A["Fetch (parallel)"]. */
  function quoteLabels(code) {
    if (!HEADER.test(code)) return null;
    var out = code.replace(/([\w-]+)\[(?!["\[(\/\\])([^\]"\n]*[(){}:;,#&<>|=][^\]"\n]*)\]/g, function (_, id, text) {
      return id + '["' + text.replace(/"/g, "'") + '"]';
    }).replace(/([\w-]+)\{(?![{"])([^}"\n]*[()\[\]:;,#&<>|=][^}"\n]*)\}/g, function (_, id, text) {
      return id + '{"' + text.replace(/"/g, "'") + '"}';
    }).replace(/\|(?!")([^|"\n]*[()\[\]{}:;#&<>][^|"\n]*)\|/g, function (_, text) {
      return '|"' + text.replace(/"/g, "'") + '"|';
    });
    return out === code ? null : out;
  }

  function candidates(code) {
    var list = [String(code)];
    var stripped = stripFences(code);
    list.push(stripped);
    var split = splitStatements(stripped);
    if (split) list.push(split);
    var quoted = quoteLabels(split || stripped);
    if (quoted) list.push(quoted);
    return list.filter(function (c, k) { return c && list.indexOf(c) === k; });
  }

  return { candidates: candidates, stripFences: stripFences, splitStatements: splitStatements, quoteLabels: quoteLabels };
})();
if (typeof module !== 'undefined') module.exports = MermaidRepair;
