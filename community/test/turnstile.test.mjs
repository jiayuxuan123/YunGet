import assert from 'node:assert/strict';
import vm from 'node:vm';
import test from 'node:test';
import {script} from '../web.mjs';

function browserFixture() {
  const tags = [];
  const renders = [];
  const removed = [];
  const target = {isConnected: true, clientWidth: 350};
  const note = {textContent: ''};
  const state = {widget: null, challenge: null, challengeRevision: 0, modalRevision: 1, theme: 'system', config: {sitekey: 'fixture'}};
  const modal = {open: true, querySelector: selector => selector === '.turnstile-wrap' ? target : note};
  let readyCalls = 0;
  const context = vm.createContext({
    state, modal, window: {}, URL, setTimeout, clearTimeout,
    document: {createElement: () => ({remove() {this.removed = true;}}), head: {append: tag => tags.push(tag)}},
  });
  const code = script.slice(script.indexOf('function clearChallenge()'), script.indexOf('function challengeMarkup()'));
  vm.runInContext(code, context);
  const loaded = () => {
    context.window.turnstile = {
      // Reproduce the vendor's diagnostic rather than letting ready() silently work.
      ready() {readyCalls++; throw new Error('[Cloudflare Turnstile] Remove async/defer from the Turnstile api.js script tag before using turnstile.ready().');},
      render(container, options) {renders.push({container, options}); return 'widget-' + renders.length;},
      remove: id => removed.push(id),
    };
    const tag = tags.at(-1);
    const callback = new URL(tag.src).searchParams.get('onload');
    if (callback) context.window[callback]();
    else tag.onload();
  };
  return {state, modal, note, tags, renders, removed, loaded,
    mount: () => context.mountChallenge(), clear: () => context.clearChallenge(),
    readyCalls: () => readyCalls};
}

test('async SDK uses its onload callback and never calls incompatible ready()', async () => {
  const fixture = browserFixture();
  const pending = fixture.mount();
  fixture.loaded();
  await pending;
  assert.equal(fixture.readyCalls(), 0);
  assert.equal(fixture.renders.length, 1);
  assert.equal(fixture.state.widget, 'widget-1');
});

test('repeated retry clicks during SDK loading render only the latest attempt', async () => {
  const fixture = browserFixture();
  const first = fixture.mount();
  const second = fixture.mount();
  const third = fixture.mount();
  assert.equal(fixture.tags.length, 1);
  fixture.loaded();
  await Promise.all([first, second, third]);
  assert.equal(fixture.renders.length, 1);
});

test('expired callbacks from a removed widget cannot clear a new token', async () => {
  const fixture = browserFixture();
  const first = fixture.mount();fixture.loaded();await first;
  const old = fixture.renders[0].options;
  await fixture.mount();
  fixture.renders[1].options.callback('current-token');
  old['expired-callback']();old['error-callback']();old.callback('obsolete-token');
  assert.equal(fixture.state.challenge, 'current-token');
  assert.deepEqual(fixture.removed, ['widget-1']);
});

test('closing the dialog while the SDK loads never mounts a hidden widget', async () => {
  const fixture = browserFixture();
  const pending = fixture.mount();
  fixture.state.modalRevision++;fixture.modal.open = false;fixture.clear();
  fixture.loaded();await pending;
  assert.equal(fixture.renders.length, 0);
});

test('SDK load error allows a fresh script and a successful retry', async () => {
  const fixture = browserFixture();
  const first = fixture.mount();fixture.tags[0].onerror();await first;
  assert.match(fixture.note.textContent, /未连接/);
  const second = fixture.mount();assert.equal(fixture.tags.length, 2);
  fixture.loaded();await second;
  assert.equal(fixture.renders.length, 1);
});
