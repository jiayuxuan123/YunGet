import {readFile, writeFile, mkdir, copyFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';

const root = new URL('../', import.meta.url);
const pkg = JSON.parse(await readFile(new URL('node_modules/altcha/package.json', root), 'utf8'));
if (pkg.version !== '3.5.0') throw new Error('Unexpected ALTCHA version');
const destination = new URL('public/verification/', root);
await mkdir(destination, {recursive: true});
const entries = {
  'external/altcha.min.js': 'altcha-3.5.0.js',
  'external/altcha.css': 'altcha-3.5.0.css',
  'workers/pbkdf2.js': 'pbkdf2-3.5.0.js',
  'i18n/zh-cn.js': 'zh-cn-3.5.0.js',
};
const files = [];
for (const [source, target] of Object.entries(entries)) {
  const bytes = await readFile(new URL('node_modules/altcha/dist/' + source, root));
  await writeFile(new URL(target, destination), bytes);
  files.push({file: target, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')});
}
await copyFile(new URL('node_modules/altcha/LICENSE.txt', root), new URL('ALTCHA-LICENSE.txt', destination));
await writeFile(new URL('sdk-3.5.0.mjs', destination), [
  "import './altcha-3.5.0.js';",
  "import './zh-cn-3.5.0.js';",
  "globalThis.$altcha.algorithms.set('PBKDF2/SHA-256', () => new Worker('/verification/pbkdf2-3.5.0.js'));",
  '',
].join('\n'));
await writeFile(new URL('SOURCES.json', destination), JSON.stringify({
  package: 'altcha', version: pkg.version, license: 'MIT', source: 'https://github.com/altcha-org/altcha', files,
}, null, 2) + '\n');
console.log('Prepared self-hosted verification assets:', fileURLToPath(destination));
