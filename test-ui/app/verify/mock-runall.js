// Runs runAllEndpoints() from the mock backend in node (no browser) and fails on any unexpected status.
global.window = global; global.document = { querySelector: () => null, querySelectorAll: () => [] }; global.renderLog = () => {};
global.localStorage = { getItem: () => null, setItem: () => {} };
const fs = require('fs'), path = require('path'), vm = require('vm'); const src = path.join(__dirname, '..', 'src');
const files = ['seed.js', 'calc.js', 'emi.js', 'core.js', 'api.js', 'live.js', 'db.js', 'engines.js', 'handlers.js', 'handlers2.js', 'runall.js'];
let code = files.map((f) => fs.readFileSync(path.join(src, f), 'utf8').replace(/if \(typeof module !== 'undefined'\) module.exports = \w+;/, '')).join('\n;\n')
  .replace(/typeof DexCalc !== 'undefined' \? DexCalc : require\('\.\/calc\.js'\)/, 'DexCalc');
code += `\n;const r = runAllEndpoints(); const expected = new Set(['SSRF_BLOCKED', 'STREAM_TICKET_INVALID']);
const gaps = ['/oauth2/token', '/v1/admin/accounts/', '/v1/budgets/category-potential'];
const bad = r.filter((x) => x.status >= 400 && !expected.has(x.code) && !gaps.some((g) => x.url.startsWith(g)));
const unhit = REGISTRY.filter((x) => !x.hits);
console.log('mock run-all:', r.length, 'calls,', REGISTRY.length - unhit.length + '/' + REGISTRY.length, 'routes exercised,', bad.length, 'unexpected');
for (const b of bad) console.log('  UNEXPECTED', b.status, b.code, b.m, b.url);
if (bad.length || unhit.length) process.exit(1);`;
vm.runInThisContext(code, { filename: 'mock-bundle.js' });
