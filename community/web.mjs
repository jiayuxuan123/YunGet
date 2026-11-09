export const html = String.raw`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="color-scheme" content="light dark">
  <meta name="description" content="云取 YunGet 与 TurboDL 的公告、问题反馈、建议和开发者回复。">
  <title>云取社区</title>
  <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 64 64'%3E%3Crect width='64' height='64' rx='16' fill='%232b62ad'/%3E%3Ctext x='32' y='43' text-anchor='middle' font-size='32' font-family='sans-serif' font-weight='700' fill='white'%3E云%3C/text%3E%3C/svg%3E">
  <link rel="stylesheet" href="/app.css">
  <script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit" defer></script>
  <script type="module" src="/app.js"></script>
</head>
<body>
  <header class="site-head">
    <a class="brand" href="/" aria-label="云取社区首页"><span class="brand-mark">云</span><span><strong>云取社区</strong><small>反馈优先 · 公告同步</small></span></a>
    <nav class="primary-nav" aria-label="社区导航">
      <button data-view="feedback" aria-pressed="true">反馈</button>
      <button data-view="announcements">公告</button>
      <button data-view="chat">闲聊</button>
      <a href="https://jiayuxuan123.github.io/YunGet/">官网</a>
    </nav>
    <button id="identity" class="identity">设置昵称</button>
  </header>
  <main id="app" class="app-shell" tabindex="-1"><div class="loading">正在载入社区…</div></main>
  <footer class="site-foot"><span>云取 YunGet · TurboDL</span><a href="/admin">开发者登录</a><a href="https://github.com/jiayuxuan123/YunGet/issues">GitHub Issues</a></footer>
  <div id="modal" class="modal" hidden><div class="modal-panel" role="dialog" aria-modal="true" aria-labelledby="modal-title"></div></div>
  <div id="toast" class="toast" role="status" aria-live="polite" hidden></div>
</body>
</html>`;

export const style = String.raw`:root {
  --paper: #f4f6f8; --surface: #fff; --surface-muted: #edf1f5; --ink: #0a1317;
  --muted: #4e606f; --line: #d8e0e6; --accent: #2b62ad; --accent-strong: #1e4d8e;
  --accent-soft: #e6eefa; --success: #0d8626; --success-soft: #e4f7e9;
  --warning: #8b5e00; --danger: #b51c32; --shadow: 0 18px 50px rgb(15 35 53 / .13);
}
@media (prefers-color-scheme: dark) {
  :root { --paper: #111214; --surface: #1f2125; --surface-muted: #292c31; --ink: #dfe4e9;
    --muted: #aab2ba; --line: #3c4249; --accent: #9dbef0; --accent-strong: #c6dcff;
    --accent-soft: #263c59; --success: #8dd4a7; --success-soft: #203d2b; --warning: #f1c76a;
    --danger: #ff9caa; --shadow: 0 18px 50px rgb(0 0 0 / .38); }
}
* { box-sizing: border-box; }
html { background: var(--paper); color: var(--ink); font-family: "Noto Sans SC", "Microsoft YaHei UI", system-ui, sans-serif; }
body { margin: 0; min-height: 100vh; background: var(--paper); }
button, input, textarea, select { font: inherit; }
button, a { -webkit-tap-highlight-color: transparent; }
button { color: inherit; }
a { color: var(--accent); text-underline-offset: 4px; }
button:focus-visible, a:focus-visible, input:focus-visible, textarea:focus-visible, select:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px; }
.site-head { position: sticky; top: 0; z-index: 20; display: grid; grid-template-columns: 1fr auto 1fr; align-items: center;
  gap: 24px; min-height: 68px; padding: 10px max(18px, calc((100vw - 1120px)/2));
  background: color-mix(in srgb, var(--surface) 94%, transparent); border-bottom: 1px solid var(--line); backdrop-filter: blur(16px); }
.brand { display: flex; align-items: center; gap: 10px; color: var(--ink); text-decoration: none; width: max-content; }
.brand-mark { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 11px; background: var(--accent); color: #fff; font-weight: 800; }
.brand strong, .brand small { display: block; }
.brand strong { font-size: .98rem; }
.brand small { margin-top: 2px; color: var(--muted); font-size: .7rem; }
.primary-nav { display: flex; gap: 4px; padding: 4px; border-radius: 14px; background: var(--surface-muted); }
.primary-nav button, .primary-nav a { min-height: 42px; padding: 10px 16px; border: 0; border-radius: 10px; background: transparent; color: var(--muted); text-decoration: none; cursor: pointer; }
.primary-nav button[aria-pressed="true"] { background: var(--surface); color: var(--accent-strong); font-weight: 700; box-shadow: 0 1px 2px rgb(15 35 53 / .08); }
.identity { justify-self: end; min-height: 42px; padding: 9px 14px; border: 1px solid var(--line); border-radius: 11px; background: var(--surface); cursor: pointer; }
.identity-primary { border-color: var(--accent); background: var(--accent); color: #fff; }
@media (prefers-color-scheme: dark) { .identity-primary { color: #142d4e; } }
.app-shell { width: min(1120px, calc(100% - 32px)); margin: 0 auto; padding: 34px 0 64px; }
.page-head { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 24px; margin: 16px 0 28px; }
.page-head h1 { margin: 0; max-width: 14ch; font-size: clamp(2rem, 4.2vw, 3.9rem); line-height: 1.08; letter-spacing: -.035em; }
.page-head p { max-width: 58ch; margin: 12px 0 0; color: var(--muted); line-height: 1.7; }
.page-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.button { display: inline-flex; align-items: center; justify-content: center; min-height: 46px; padding: 10px 16px; border: 1px solid var(--line);
  border-radius: 11px; background: var(--surface); color: var(--ink); text-decoration: none; cursor: pointer; }
.button-primary { border-color: var(--accent); background: var(--accent); color: #fff; }
@media (prefers-color-scheme: dark) { .button-primary { color: #142d4e; } }
.button-danger { color: var(--danger); }
.button[disabled] { opacity: .48; cursor: not-allowed; }
.summary-strip { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1px; overflow: hidden; margin-bottom: 24px;
  border: 1px solid var(--line); border-radius: 16px; background: var(--line); }
.summary-strip div { padding: 18px 20px; background: var(--surface); }
.summary-strip strong, .summary-strip span { display: block; }
.summary-strip strong { font-size: 1.6rem; }
.summary-strip span { margin-top: 4px; color: var(--muted); font-size: .82rem; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 18px; }
.chip { min-height: 40px; padding: 8px 13px; border: 1px solid var(--line); border-radius: 999px; background: var(--surface); color: var(--muted); cursor: pointer; }
.chip[aria-pressed="true"] { border-color: var(--accent); background: var(--accent-soft); color: var(--accent-strong); }
.feedback-list, .announcement-list { display: grid; gap: 12px; }
.feedback-card, .announcement-card, .chat-message, .admin-panel { border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }
.feedback-card { display: grid; grid-template-columns: 52px minmax(0, 1fr) auto; gap: 14px; padding: 17px; cursor: pointer; }
.feedback-card:hover { border-color: color-mix(in srgb, var(--accent) 45%, var(--line)); }
.feedback-number { color: var(--muted); font-variant-numeric: tabular-nums; font-size: .8rem; }
.feedback-title { margin: 0; font-size: 1.04rem; line-height: 1.4; }
.feedback-excerpt { margin: 7px 0 0; color: var(--muted); line-height: 1.55; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.feedback-meta { display: flex; flex-wrap: wrap; gap: 8px 14px; margin-top: 11px; color: var(--muted); font-size: .78rem; }
.status { display: inline-flex; align-items: center; min-height: 28px; padding: 4px 9px; border-radius: 8px; background: var(--surface-muted); color: var(--muted); font-size: .76rem; white-space: nowrap; }
.status-working, .status-planned { background: var(--accent-soft); color: var(--accent-strong); }
.status-resolved { background: var(--success-soft); color: var(--success); }
.status-closed { opacity: .72; }
.feedback-arrow { align-self: center; color: var(--muted); font-size: 1.2rem; }
.empty { padding: 48px 20px; border: 1px dashed var(--line); border-radius: 16px; color: var(--muted); text-align: center; }
.composer, .admin-panel { padding: 20px; }
.composer { margin: 16px 0 24px; border: 1px solid var(--line); border-radius: 16px; background: var(--surface); }
.composer h2, .admin-panel h2 { margin: 0 0 14px; font-size: 1.12rem; }
.notice { margin: 0 0 16px; padding: 12px 14px; border: 1px solid color-mix(in srgb, var(--accent) 28%, var(--line)); border-radius: 12px; background: var(--accent-soft); color: var(--accent-strong); line-height: 1.55; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.field { display: grid; gap: 6px; }
.field.full { grid-column: 1 / -1; }
.field label { color: var(--muted); font-size: .8rem; }
.field input, .field textarea, .field select { width: 100%; min-height: 46px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--ink); }
.field textarea { min-height: 132px; resize: vertical; line-height: 1.6; }
.check-row { display: flex; align-items: center; gap: 9px; min-height: 44px; color: var(--muted); }
.form-foot { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 14px; }
.form-note { color: var(--muted); font-size: .78rem; line-height: 1.55; }
.announcement-card { padding: 20px; }
.announcement-card[data-pinned="true"] { border-color: var(--accent); }
.announcement-card h2 { margin: 0; font-size: 1.2rem; }
.announcement-card time { color: var(--muted); font-size: .78rem; }
.announcement-body { margin-top: 14px; color: var(--ink); line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.chat-box { border: 1px solid var(--line); border-radius: 16px; background: var(--surface); overflow: hidden; }
.chat-log { height: min(62vh, 560px); overflow: auto; padding: 16px; }
.chat-message { padding: 12px 14px; margin-bottom: 10px; }
.chat-message header { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 6px; color: var(--muted); font-size: .76rem; }
.chat-message p { margin: 0; line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.chat-compose { display: grid; grid-template-columns: 1fr auto; gap: 10px; padding: 12px; border-top: 1px solid var(--line); }
.chat-compose textarea { min-height: 48px; max-height: 140px; resize: vertical; padding: 11px 12px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface); color: var(--ink); }
.modal { position: fixed; inset: 0; z-index: 50; display: grid; place-items: center; padding: 18px; background: rgb(1 18 40 / .55); }
.modal[hidden] { display: none; }
.modal-panel { width: min(680px, 100%); max-height: min(88vh, 840px); overflow: auto; padding: 22px; border-radius: 18px; background: var(--surface); box-shadow: var(--shadow); }
.modal-panel h2 { margin: 0 0 14px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.thread-body { margin: 14px 0; padding: 14px; border-radius: 12px; background: var(--surface-muted); line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }
.reply { padding: 14px 0; border-top: 1px solid var(--line); }
.reply.developer { border-left: 3px solid var(--accent); padding-left: 14px; }
.reply header { display: flex; justify-content: space-between; gap: 16px; margin-bottom: 6px; color: var(--muted); font-size: .78rem; }
.reply p { margin: 0; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.turnstile-wrap { min-height: 68px; margin-top: 12px; }
.toast { position: fixed; left: 50%; bottom: 24px; z-index: 70; max-width: min(520px, calc(100vw - 32px)); padding: 12px 16px; border-radius: 12px; background: var(--ink); color: var(--paper); box-shadow: var(--shadow); transform: translateX(-50%); }
.loading { padding: 80px 20px; color: var(--muted); text-align: center; }
.site-foot { display: flex; flex-wrap: wrap; justify-content: center; gap: 18px 28px; padding: 28px 16px 40px; border-top: 1px solid var(--line); color: var(--muted); font-size: .82rem; }
.admin-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(320px, .85fr); gap: 18px; align-items: start; }
.admin-list { display: grid; gap: 10px; margin-top: 14px; }
.admin-item { display: grid; gap: 8px; padding: 14px; border: 1px solid var(--line); border-radius: 12px; }
.admin-item header { display: flex; justify-content: space-between; gap: 12px; }
.admin-controls { display: flex; flex-wrap: wrap; gap: 8px; }
.admin-controls select { min-height: 40px; padding: 6px 9px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface); color: var(--ink); }
.small { font-size: .78rem; color: var(--muted); }
@media (max-width: 720px) {
  .site-head { grid-template-columns: 1fr auto; gap: 10px; padding: 10px 14px; }
  .primary-nav { grid-column: 1 / -1; grid-row: 2; width: 100%; overflow-x: auto; }
  .primary-nav button, .primary-nav a { flex: 1 0 auto; text-align: center; }
  .identity { max-width: 130px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .page-head { grid-template-columns: 1fr; align-items: start; }
  .page-head h1 { font-size: clamp(2rem, 12vw, 3.2rem); }
  .page-actions .button { width: 100%; }
  .summary-strip { grid-template-columns: 1fr; }
  .summary-strip div { padding: 14px 16px; }
  .feedback-card { grid-template-columns: 1fr auto; }
  .feedback-number { display: none; }
  .form-grid, .admin-grid { grid-template-columns: 1fr; }
  .form-foot { align-items: stretch; flex-direction: column; }
  .chat-compose { grid-template-columns: 1fr; }
  .chat-compose button { width: 100%; }
  .modal { align-items: end; padding: 0; }
  .modal-panel { max-height: 92vh; border-radius: 20px 20px 0 0; padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); }
}
@media (prefers-reduced-motion: no-preference) {
  .feedback-card, .button, .chip, .identity, .primary-nav button { transition: border-color .16s ease, background-color .16s ease, color .16s ease, transform .16s ease; }
  .feedback-card:active, .button:active, .chip:active { transform: scale(.985); }
}
`;

export const script = String.raw`const state = { session: null, config: null, view: 'feedback', feedback: [], announcements: [], chat: [], adminFeedback: [], adminAnnouncements: [], filter: '', challenge: null, editingAnnouncement: null, feedbackDraft: null };
const app = document.getElementById('app');
const modal = document.getElementById('modal');
const toastEl = document.getElementById('toast');
const isAdminPage = location.pathname.startsWith('/admin');
const labels = {new:'待处理', planned:'已计划', working:'处理中', resolved:'已解决', closed:'已关闭', bug:'问题', idea:'建议', question:'提问', other:'其他', yunget:'云取', turbodl:'TurboDL'};
const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const dateText = seconds => new Date(seconds * 1000).toLocaleString('zh-CN', {month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'});
function toast(message) { toastEl.textContent = message; toastEl.hidden = false; clearTimeout(toast.timer); toast.timer = setTimeout(() => toastEl.hidden = true, 2600); }
function openModal(content) { modal.querySelector('.modal-panel').innerHTML = content; modal.hidden = false; modal.querySelector('button,input,textarea,select')?.focus(); }
function closeModal() { modal.hidden = true; if (window.turnstile) modal.querySelectorAll('.turnstile-wrap').forEach(node => {try {window.turnstile.remove(node);} catch {}}); state.challenge = null; }
modal.addEventListener('click', event => { if (event.target === modal) closeModal(); });
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !modal.hidden) closeModal(); });
window.addEventListener('load', mountChallenge);
async function request(path, options = {}) {
  const headers = new Headers(options.headers || {});
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type','application/json');
  headers.set('Accept','application/json');
  const response = await fetch(path, {...options, headers, credentials:'same-origin'});
  let body; try { body = await response.json(); } catch { body = {}; }
  if (!response.ok) { const error = new Error(body.error || '请求失败'); error.status = response.status; throw error; }
  return body;
}
function challengeMarkup() { return state.config?.sitekey ? '<div class="turnstile-wrap"></div>' : '<p class="form-note">验证服务尚未配置。</p>'; }
function scheduleChallengeMount() {
  if (state.challengeTimer) return;
  state.challengeTimer=setTimeout(() => {state.challengeTimer=null; mountChallenge();}, 180);
}
function mountChallenge() {
  const nodes=[...document.querySelectorAll('.turnstile-wrap:not([data-rendered])')];
  if (!nodes.length || !state.config?.sitekey) return;
  if (!window.turnstile) { scheduleChallengeMount(); return; }
  nodes.forEach(node => {
    node.dataset.rendered='1';
    try {
      node.dataset.widgetId=window.turnstile.render(node, {sitekey:state.config.sitekey, action:'community_write', theme:'auto', appearance:'interaction-only',
        callback:token => {state.challenge=token;}, 'expired-callback':() => {state.challenge=null;},
        'error-callback':() => {state.challenge=null; toast('人机验证载入失败，请刷新重试');}});
    } catch {
      node.dataset.rendered='';
      scheduleChallengeMount();
    }
  });
}
function afterWrite() { state.challenge=null; if (window.turnstile) document.querySelectorAll('.turnstile-wrap[data-rendered]').forEach(node => {try {window.turnstile.reset(node.dataset.widgetId);} catch {}}); }
function requireSession() { if (state.session?.nickname) return true; toast('请先设置社区昵称'); openIdentity(); return false; }
function requireChallenge() { if (state.challenge) return true; toast('请先完成人机验证'); mountChallenge(); return false; }
async function init() {
  try {
    state.config = await request('/api/config');
    if (isAdminPage) state.session={nickname:'开发者',developer:true};
    else { const session=await request('/api/session'); state.session=session.nickname?{nickname:session.nickname}:null; }
    updateIdentity();
    state.view=isAdminPage?'admin':'feedback';
    await loadView();
  } catch (error) { app.innerHTML='<div class="empty">'+esc(error.message)+'</div>'; }
}
function updateIdentity() { const button=document.getElementById('identity'); button.textContent=state.session?.nickname||'设置昵称'; button.classList.toggle('identity-primary',!state.session?.nickname); button.setAttribute('aria-label',state.session?.nickname?'修改社区昵称':'设置社区昵称'); }
function openIdentity() {
  openModal('<h2 id="modal-title">设置社区昵称</h2><form id="identity-form"><div class="field"><label for="nickname">昵称</label><input id="nickname" name="nickname" maxlength="24" required value="'+esc(state.session?.nickname||'')+'" placeholder="例如：海边的风"></div><p class="form-note">昵称仅用于社区展示。请勿使用“官方”“管理员”等容易混淆的身份。</p>'+challengeMarkup()+'<div class="modal-actions"><button type="button" class="button" data-close>取消</button><button class="button button-primary" type="submit">保存</button></div></form>');
  mountChallenge();
  document.getElementById('identity-form').onsubmit=async event => {
    event.preventDefault(); if (!requireChallenge()) return;
    try {
      const result=await request('/api/session',{method:'POST',body:JSON.stringify({nickname:new FormData(event.currentTarget).get('nickname'),challenge:state.challenge})});
      state.session={nickname:result.nickname}; updateIdentity(); closeModal(); toast('昵称已保存'); await loadView(); restoreFeedbackDraft();
    } catch(error){toast(error.message);}
  };
}
function pageHead(title, lead, actions) { return '<section class="page-head"><div><h1>'+esc(title)+'</h1><p>'+esc(lead)+'</p></div><div class="page-actions">'+actions+'</div></section>'; }
function feedbackForm() {
  const visitor=state.session?.nickname?'':'<div class="notice"><strong>普通反馈不需要开发者登录。</strong> 填写后可先设置社区昵称并完成人机验证；开发者登录只用于后台管理。</div>';
  return '<form id="feedback-form" class="composer"><h2>提交反馈</h2>'+visitor+'<div class="form-grid"><div class="field"><label for="product">产品</label><select id="product" name="product"><option value="yunget">云取 YunGet</option><option value="turbodl">TurboDL</option></select></div><div class="field"><label for="category">类型</label><select id="category" name="category"><option value="bug">问题</option><option value="idea">建议</option><option value="question">提问</option><option value="other">其他</option></select></div><div class="field full"><label for="title">标题</label><input id="title" name="title" minlength="3" maxlength="100" required placeholder="一句话说明问题或建议"></div><div class="field full"><label for="body">详细说明</label><textarea id="body" name="body" minlength="5" maxlength="6000" required placeholder="复现步骤、预期结果、实际结果，以及能补充的版本和网络环境"></textarea></div><div class="field"><label for="app_version">版本（可选）</label><input id="app_version" name="app_version" maxlength="32" placeholder="例如 2.7.28"></div><label class="check-row"><input type="checkbox" name="private"> 仅我和管理员可见</label></div>'+challengeMarkup()+'<div class="form-foot"><span class="form-note">提交前请移除 Cookie、JWT、签名直链等敏感信息。</span><button class="button button-primary" type="submit">'+(state.session?.nickname?'提交反馈':'设置昵称并提交')+'</button></div></form>';
}
async function loadFeedback() {
  const result=await request('/api/feedback'+(state.filter?'?status='+encodeURIComponent(state.filter):''));
  state.feedback=result.items;
  const open=state.feedback.filter(item=>['new','planned','working'].includes(item.status)).length;
  const resolved=state.feedback.filter(item=>item.status==='resolved').length;
  app.innerHTML=pageHead('把问题说清楚，才能把它修好','这里集中接收云取和 TurboDL 的问题、建议与使用疑问。公开反馈可由开发者标记处理进度；涉及账号或敏感信息时请选择“仅我和管理员可见”。','<button class="button button-primary" id="new-feedback">提交反馈</button>')
    +'<div class="summary-strip"><div><strong>'+state.feedback.length+'</strong><span>当前筛选</span></div><div><strong>'+open+'</strong><span>待处理</span></div><div><strong>'+resolved+'</strong><span>已解决</span></div></div>'
    +feedbackForm()+'<div class="toolbar"><button class="chip" data-status="" aria-pressed="'+(state.filter===''?'true':'false')+'">全部</button><button class="chip" data-status="new" aria-pressed="'+(state.filter==='new'?'true':'false')+'">待处理</button><button class="chip" data-status="working" aria-pressed="'+(state.filter==='working'?'true':'false')+'">处理中</button><button class="chip" data-status="resolved" aria-pressed="'+(state.filter==='resolved'?'true':'false')+'">已解决</button></div><div class="feedback-list">'
    +(state.feedback.length?state.feedback.map((item,index)=>'<article class="feedback-card" data-thread="'+esc(item.id)+'" tabindex="0"><span class="feedback-number">'+String(index+1).padStart(2,'0')+'</span><div><h2 class="feedback-title">'+esc(item.title)+'</h2><p class="feedback-excerpt">'+esc(item.body)+'</p><div class="feedback-meta"><span>'+esc(labels[item.product]||item.product)+' · '+esc(labels[item.category]||item.category)+'</span><span>'+esc(item.nickname)+'</span><span>'+dateText(item.updated_at)+'</span><span class="status status-'+esc(item.status)+'">'+esc(labels[item.status]||item.status)+'</span></div></div><span class="feedback-arrow" aria-hidden="true">›</span></article>').join(''):'<div class="empty">当前筛选下还没有反馈。欢迎提交第一条。</div>')
    +'</div>';
  const form=document.getElementById('feedback-form');
  form.onsubmit=submitFeedback;
  document.querySelectorAll('[data-thread]').forEach(card=>{const open=()=>openThread(card.dataset.thread);card.onclick=open;card.onkeydown=event=>{if(event.key==='Enter')open();};});
  document.querySelectorAll('[data-status]').forEach(button=>button.onclick=()=>{state.filter=button.dataset.status;loadFeedback().catch(showError);});
  document.getElementById('new-feedback').onclick=()=>{if(requireSession()){form.scrollIntoView({behavior:'smooth'});form.querySelector('[name=title]').focus();}};
  restoreFeedbackDraft();
  mountChallenge();
}
async function submitFeedback(event) {
  event.preventDefault(); const data=new FormData(event.currentTarget);
  state.feedbackDraft=Object.fromEntries(data); state.feedbackDraft.private=data.has('private');
  if(!requireSession()||!requireChallenge())return;
  const body={...state.feedbackDraft,challenge:state.challenge}; state.feedbackDraft=null;
  try { await request('/api/feedback',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify(body)}); afterWrite(); toast('反馈已提交'); await loadFeedback(); } catch(error){toast(error.message);}
}
function restoreFeedbackDraft(){
  const draft=state.feedbackDraft; const form=document.getElementById('feedback-form');
  if(!draft||!form)return;
  for(const name of ['product','category','title','body','app_version']){
    const field=form.elements.namedItem(name); if(field&&draft[name]!==undefined)field.value=draft[name];
  }
  const privateField=form.elements.namedItem('private'); if(privateField)privateField.checked=Boolean(draft.private);
}
async function openThread(id) {
  try {
    const payload=await request((isAdminPage?'/admin/api/feedback/':'/api/feedback/')+id);
    const admin=isAdminPage||state.session?.developer; const replies=payload.replies||[];
    openModal('<h2 id="modal-title">'+esc(payload.item.title)+'</h2><div class="feedback-meta"><span class="status status-'+esc(payload.item.status)+'">'+esc(labels[payload.item.status])+'</span><span>'+esc(labels[payload.item.product])+' · '+esc(labels[payload.item.category])+'</span><span>'+esc(payload.item.nickname)+'</span><span>'+dateText(payload.item.created_at)+'</span></div><div class="thread-body">'+esc(payload.item.body)+'</div>'
      +(admin?'<div class="admin-controls"><select id="thread-status">'+['new','planned','working','resolved','closed'].map(key=>'<option value="'+key+'" '+(payload.item.status===key?'selected':'')+'>'+labels[key]+'</option>').join('')+'</select><label class="check-row"><input id="thread-hidden" type="checkbox" '+(payload.item.hidden?'checked':'')+'> 隐藏</label><button class="button" id="save-thread">保存状态</button></div>':'')
      +'<div>'+replies.map(reply=>'<article class="reply '+(reply.developer?'developer':'')+'"><header><strong>'+esc(reply.nickname)+'</strong><time>'+dateText(reply.created_at)+'</time></header><p>'+esc(reply.body)+'</p></article>').join('')+'</div><form id="reply-form"><div class="field"><label for="reply">补充或回复</label><textarea id="reply" name="body" minlength="1" maxlength="3000" required></textarea></div>'+challengeMarkup()+'<div class="modal-actions"><button type="button" class="button" data-close>关闭</button><button class="button button-primary" type="submit">发送</button></div></form>');
    modal.querySelector('[data-close]').onclick=closeModal;
    if(admin)document.getElementById('save-thread').onclick=async()=>{try{await request('/admin/api/feedback/'+payload.item.id,{method:'PATCH',body:JSON.stringify({status:document.getElementById('thread-status').value,hidden:document.getElementById('thread-hidden').checked})});toast('已更新处理状态');closeModal();await loadView();}catch(error){toast(error.message);}};
    document.getElementById('reply-form').onsubmit=async event=>{event.preventDefault();if(!requireSession()||!requireChallenge())return;try{await request((admin?'/admin/api/feedback/':'/api/feedback/')+payload.item.id+'/replies',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({body:new FormData(event.currentTarget).get('body'),challenge:state.challenge})});afterWrite();closeModal();toast('回复已发送');await openThread(payload.item.id);}catch(error){toast(error.message);}};
    mountChallenge();
  } catch(error){toast(error.message);}
}
async function loadAnnouncements() {
  const result=await request('/api/announcements'); state.announcements=result.items;
  app.innerHTML=pageHead('公告与维护状态','产品更新、已知问题和维护安排会在这里发布。影响使用的变更会同时同步到应用内公告。','<button class="button" id="refresh-announcements">刷新</button>')+'<div class="announcement-list">'+(state.announcements.length?state.announcements.map(item=>'<article class="announcement-card" data-pinned="'+Boolean(item.pinned)+'"><div class="feedback-meta"><span class="status '+(item.important?'status-working':'')+'">'+(item.important?'重要':'公告')+'</span><time>'+esc(item.date)+'</time></div><h2>'+esc(item.title)+'</h2><div class="announcement-body">'+esc(item.body)+'</div></article>').join(''):'<div class="empty">暂时没有公告。</div>')+'</div>';
  document.getElementById('refresh-announcements').onclick=()=>loadAnnouncements().catch(showError);
}
async function loadChat() {
  const previous=document.querySelector('.chat-log'); const atBottom=!previous||previous.scrollHeight-previous.scrollTop-previous.clientHeight<80;
  const result=await request('/api/chat'+(state.chat.length?'?after='+state.chat[state.chat.length-1].id:''));
  if(result.items.length)state.chat=state.chat.concat(result.items).slice(-120);
  app.innerHTML=pageHead('社区闲聊','版本、平台和使用体验都可以聊。具体问题请放到“反馈”，这样才能标记状态并持续跟进。','')+'<div class="chat-box"><div class="chat-log">'+(state.chat.length?state.chat.map(item=>'<article class="chat-message"><header><strong>'+esc(item.nickname)+'</strong><time>'+dateText(item.created_at)+'</time></header><p>'+esc(item.body)+'</p></article>').join(''):'<div class="empty">还没有消息，来打个招呼。</div>')+'</div><form id="chat-form" class="chat-compose"><textarea name="body" maxlength="500" required placeholder="写下消息，最多 500 字"></textarea><button class="button button-primary" type="submit">发送</button></form>'+challengeMarkup()+'</div>';
  document.getElementById('chat-form').onsubmit=submitChat; mountChallenge(); const nextLog=document.querySelector('.chat-log'); if(atBottom)nextLog.scrollTop=nextLog.scrollHeight;
}
async function submitChat(event) {
  event.preventDefault();if(!requireSession()||!requireChallenge())return;
  try{await request('/api/chat',{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:JSON.stringify({body:new FormData(event.currentTarget).get('body'),challenge:state.challenge})});event.currentTarget.reset();afterWrite();await loadChat();}catch(error){toast(error.message);}
}
async function loadAdmin() {
  const feedback=await request('/admin/api/feedback');const announcements=await request('/admin/api/announcements');
  state.adminFeedback=feedback.items;state.adminAnnouncements=announcements.items;
  app.innerHTML=pageHead('开发者后台','处理反馈、发布公告并查看社区运行状态。这个页面只对通过 Cloudflare Access 验证的开发者开放。','<a class="button" href="/">查看公开社区</a>')
    +'<div class="summary-strip"><div><strong>'+state.adminFeedback.filter(item=>item.status==='new').length+'</strong><span>待处理反馈</span></div><div><strong>'+state.adminFeedback.filter(item=>item.status==='working').length+'</strong><span>处理中</span></div><div><strong>'+state.adminAnnouncements.length+'</strong><span>已保存公告</span></div></div>'
    +'<div class="admin-grid"><section class="admin-panel"><h2>反馈队列</h2><div class="admin-list">'+state.adminFeedback.map(item=>'<article class="admin-item"><header><strong>'+esc(item.title)+'</strong><span class="status status-'+esc(item.status)+'">'+esc(labels[item.status])+'</span></header><div class="small">'+esc(item.nickname)+' · '+dateText(item.updated_at)+' · '+(item.private?'私密':'公开')+(item.hidden?' · 已隐藏':'')+'</div><div class="admin-controls"><button class="button" data-admin-thread="'+esc(item.id)+'">查看与处理</button></div></article>').join('')+'</div></section>'
    +'<section class="admin-panel"><h2>发布公告</h2><form id="announcement-form"><div class="form-grid"><div class="field full"><label for="announcement-title">标题</label><input id="announcement-title" name="title" maxlength="120" required></div><div class="field full"><label for="announcement-body">正文</label><textarea id="announcement-body" name="body" maxlength="10000" required></textarea></div><div class="field"><label for="announcement-date">日期</label><input id="announcement-date" name="date" type="date" required value="'+new Date().toISOString().slice(0,10)+'"></div><label class="check-row"><input name="pinned" type="checkbox"> 置顶</label><label class="check-row"><input name="important" type="checkbox"> 重要</label><label class="check-row"><input name="published" type="checkbox" checked> 公开</label></div><div class="modal-actions"><button class="button button-primary" type="submit">发布公告</button></div></form><div class="admin-list">'+state.adminAnnouncements.map(item=>'<article class="admin-item"><header><strong>'+esc(item.title)+'</strong><span class="small">'+esc(item.date)+'</span></header><div class="admin-controls"><button class="button" data-edit-announcement="'+esc(item.id)+'">编辑</button></div></article>').join('')+'</div></section></div>';
  document.querySelectorAll('[data-admin-thread]').forEach(button=>button.onclick=()=>openThread(button.dataset.adminThread));
  document.getElementById('announcement-form').onsubmit=submitAnnouncement;
  document.querySelectorAll('[data-edit-announcement]').forEach(button=>button.onclick=()=>{const item=state.adminAnnouncements.find(entry=>entry.id===button.dataset.editAnnouncement);state.editingAnnouncement=item;const form=document.getElementById('announcement-form');form.title.value=item.title;form.body.value=item.body;form.date.value=item.date;form.pinned.checked=Boolean(item.pinned);form.important.checked=Boolean(item.important);form.published.checked=Boolean(item.published);form.scrollIntoView({behavior:'smooth'});});
}
async function submitAnnouncement(event) {
  event.preventDefault();const data=new FormData(event.currentTarget);const body=Object.fromEntries(data);body.pinned=data.has('pinned');body.important=data.has('important');body.published=data.has('published');if(state.editingAnnouncement)body.id=state.editingAnnouncement.id;
  try{await request('/admin/api/announcements',{method:'POST',body:JSON.stringify(body)});state.editingAnnouncement=null;toast('公告已保存');await loadAdmin();}catch(error){toast(error.message);}
}
async function loadView(){if(state.view==='feedback')return loadFeedback();if(state.view==='announcements')return loadAnnouncements();if(state.view==='chat')return loadChat();if(state.view==='admin')return loadAdmin();}
function showError(error){toast(error.message||'载入失败');}
document.querySelectorAll('[data-view]').forEach(button=>button.onclick=()=>{state.view=button.dataset.view;document.querySelectorAll('[data-view]').forEach(item=>item.setAttribute('aria-pressed',String(item===button)));loadView().catch(showError);});
document.getElementById('identity').onclick=openIdentity;
document.addEventListener('click',event=>{if(event.target.matches('[data-close]'))closeModal();});
setInterval(()=>{if(state.view==='chat'&&!document.hidden&&modal.hidden)loadChat().catch(()=>{});},12000);
init();`;
