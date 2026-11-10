export {style} from './style.mjs';
export {script} from './client.mjs';

export const html = String.raw`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,interactive-widget=resizes-content">
  <meta name="color-scheme" content="light dark">
  <meta name="theme-color" content="#f7f9fc">
  <meta name="description" content="云取和 TurboDL 的公告、问题反馈、建议与交流。">
  <title>云取社区</title>
  <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%23275fba'/%3E%3Cpath d='M9 13h14M8 17h16M12 21h8' stroke='white' stroke-width='2.5' stroke-linecap='round'/%3E%3C/svg%3E">
  <link rel="stylesheet" href="/app.css?v=20261109-mobile2">
  <link rel="stylesheet" href="/verification/altcha-3.5.0.css">
  <script type="module" src="/app.js?v=20261110-local-verification"></script>
</head>
<body>
  <a class="skip-link" href="#app">跳到内容</a>
  <header class="site-head"><div class="head-inner">
    <a class="brand" href="/" aria-label="云取社区首页"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M9 12h14M7 17h18M12 22h8" stroke="currentColor" stroke-width="2.8" stroke-linecap="round"/></svg></span><strong>云取<span>社区</span></strong></a>
    <nav class="primary-nav" aria-label="社区导航"><button type="button" data-view="feedback" aria-current="page">反馈</button><button type="button" data-view="announcements">公告</button><button type="button" data-view="chat">闲聊</button></nav>
    <div class="head-actions"><button id="theme" class="icon-button" type="button" aria-label="主题：跟随系统"></button><button id="identity" class="identity" type="button">设置昵称</button></div>
  </div></header>
  <main id="app" class="app-shell" tabindex="-1" aria-label="社区内容"><div class="loading" role="status">正在载入社区…</div></main>
  <footer class="site-foot"><a href="https://yunget.yunl.us.ci/">云取官网</a><span aria-hidden="true">·</span><a href="/admin">开发者后台</a><span aria-hidden="true">·</span><a href="https://github.com/jiayuxuan123/YunGet/issues">GitHub Issues</a></footer>
  <dialog id="modal" class="modal" aria-labelledby="modal-title"></dialog>
  <div id="toast" class="toast" role="status" aria-live="polite" hidden></div>
  <noscript>社区需要启用 JavaScript，也可以前往 GitHub Issues 反馈问题。</noscript>
</body>
</html>`;
