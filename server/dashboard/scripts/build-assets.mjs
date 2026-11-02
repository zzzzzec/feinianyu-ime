import { readFileSync, mkdirSync, writeFileSync } from 'node:fs';
const root = new URL('../', import.meta.url);
const files = {
    '/': ['index.html', 'text/html; charset=utf-8'],
    '/style.css': ['style.css', 'text/css; charset=utf-8'],
    '/app.mjs': ['app.mjs', 'text/javascript; charset=utf-8'],
    '/analysis.mjs': ['analysis.mjs', 'text/javascript; charset=utf-8'],
    '/data.mjs': ['data.mjs', 'text/javascript; charset=utf-8'],
};
const assets = Object.fromEntries(Object.entries(files).map(([path, [file, type]]) => [path, { type, body: readFileSync(new URL('public/' + file, root), 'utf8') }]));
mkdirSync(new URL('.generated/', root), { recursive: true });
writeFileSync(new URL('.generated/assets.mjs', root), 'export default ' + JSON.stringify(assets) + ';\n');
console.log('Dashboard assets built: ' + Object.keys(assets).length);
