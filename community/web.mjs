export {style} from './style.mjs';
export {script} from './client.mjs';
export const html = String.raw`<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover,interactive-widget=resizes-content">
<meta name="color-scheme" content="light dark"><meta name="theme-color" content="#f7f9fc">
<meta name="description" content="云取社区：讨论、提问、图片、投票与产品反馈。">
<title>云取社区</title>
<link rel="icon" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='9' fill='%23275fba'/%3E%3Cpath d='M9 13h14M8 17h16M12 21h8' stroke='white' stroke-width='2.5' stroke-linecap='round'/%3E%3C/svg%3E">
<link rel="stylesheet" href="/app.css?v=20261112-social9"><link rel="stylesheet" href="/verification/altcha-3.5.0.css">
<script type="module" src="/app.js?v=20261112-social9"></script>
</head><body class="social-community">
<a class="skip-link" href="#app">跳到内容</a>
<header class="site-head"><div class="head-inner">
<a class="brand" href="/#feed" aria-label="云取社区首页"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M9 12h14M7 17h18M12 22h8" stroke="currentColor" stroke-width="2.8" stroke-linecap="round"/></svg></span><strong>云取<span>社区</span></strong></a>
<span class="community-tagline">交流想法，也把问题解决。</span>
<div class="head-actions"><button id="theme" class="icon-button" type="button" aria-label="主题：跟随系统"></button><button id="identity" class="identity" type="button">我的</button></div>
</div></header>
<div class="community-layout">
<aside class="community-rail"><nav class="primary-nav" aria-label="社区导航">
<button type="button" data-view="feed"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 10 9-7 9 7v11h-6v-7H9v7H3z"/></svg><span>社区</span></button>
<button type="button" data-view="feedback"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 4h16v13H9l-5 4zM8 8h8M8 12h5"/></svg><span>反馈</span></button>
<button type="button" class="compose-nav" data-social-compose><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 4v16M4 12h16"/></svg><span>发帖</span></button>
<button type="button" data-view="notifications"><span class="nav-icon"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/></svg><i class="notification-badge" data-notification-badge hidden></i></span><span>通知</span></button>
<button type="button" data-view="profile"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 21v-2a7 7 0 0 0-14 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z"/></svg><span>我的</span></button>
</nav><div class="rail-links"><a href="#announcements">公告</a><a href="#chat">旧闲聊</a><a class="admin-only" href="/admin#feed" hidden>开发者后台</a><a class="admin-only" href="/admin/analytics" hidden>匿名统计</a></div><p class="rail-footnote">云取 · TurboDL<br>一起，把下载做好。</p></aside>
<main id="app" class="app-shell" tabindex="-1" aria-label="社区内容"><div class="loading" role="status">正在载入社区…</div></main>
<aside class="community-context"><section class="community-note"><span class="context-eyebrow">YUNGET COMMUNITY</span><h2>想法有回应，<br>问题有进展。</h2><p>分享使用体验，参与投票，或把遇到的问题交给开发者。</p><button class="button button-primary" type="button" data-social-compose>发布第一条想法</button></section><section class="community-help"><h2>反馈小贴士</h2><p>附上版本号和复现步骤，更容易定位。</p><p>请勿公开账号凭据或私有链接。</p><a href="#announcements">查看更新公告 →</a></section><footer class="site-foot"><a href="https://yunget.yunl.us.ci/">云取官网</a><a href="https://github.com/jiayuxuan123/YunGet/issues">GitHub Issues</a></footer></aside>
</div>
<dialog id="modal" class="modal" aria-labelledby="modal-title"></dialog>
<div id="toast" class="toast" role="status" aria-live="polite" hidden></div>
<noscript>社区需要启用 JavaScript，也可以前往 GitHub Issues 反馈问题。</noscript>
</body></html>`;
