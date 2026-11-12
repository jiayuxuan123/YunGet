import assert from 'node:assert/strict';
import vm from 'node:vm';
import test from 'node:test';
import {script, style} from '../web.mjs';
import {analyticsScript} from '../analytics.mjs';

test('the served browser module parses independently of its string wrapper', () => {
  assert.doesNotThrow(() => new vm.Script(script));
});

test('untrusted content remains escaped in feedback, chat and announcement links', () => {
  // Exercise the same encoder/link formatter that is shipped to the browser.
  const declarations = script.slice(script.indexOf('const esc ='), script.indexOf('const icon ='))
    + script.slice(script.indexOf('function announcementText('), script.indexOf('async function loadAnnouncements('));
  const context = vm.createContext({});
  vm.runInContext(declarations + ';globalThis.encode=esc;globalThis.format=announcementText;', context);
  assert.equal(context.encode('<img src=x onerror="alert(1)">'), '&lt;img src=x onerror=&quot;alert(1)&quot;&gt;');
  const formatted = context.format('[unsafe](javascript:alert(1))\n[官网](https://example.com/)\n<script>alert(1)</script>');
  assert.ok(!formatted.includes('href="javascript:'));
  assert.ok(!formatted.includes('<script>'));
  assert.match(formatted, /href="https:\/\/example.com\/"/);
});

test('developer and mobile navigation expose every management destination', () => {
  assert.match(script, /class="admin-tools"/);
  assert.match(script, /href="\/admin\/analytics"/);
  assert.match(script, /href="#feed"/);
  assert.match(script, /href="#chat"/);
  assert.match(script, /旧闲聊/);
  assert.match(style, /\.social-community \.primary-nav button\{[^}]*align-items:center/);
});

test('analytics labels legacy cooldown classes without claiming a missing status', () => {
  assert.match(analyticsScript, /429\/503（类别）/);
  assert.match(analyticsScript, /401\/403\/410（类别）/);
  assert.match(analyticsScript, /探测\/首包/);
});
