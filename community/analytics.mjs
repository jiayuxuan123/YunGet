export const analyticsHtml = String.raw`<!doctype html>
<html lang="zh-CN"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark"><meta name="theme-color" content="#f6f8fc">
<title>云取匿名统计</title>
<style>
:root{--bg:#f6f8fc;--surface:#fff;--text:#172033;--muted:#586980;--line:#d7e0ee;--accent:#2b64b5;color-scheme:light dark;font-family:MiSans,"PingFang SC","Microsoft YaHei UI",sans-serif}
@media(prefers-color-scheme:dark){:root{--bg:#101722;--surface:#172231;--text:#edf3fb;--muted:#aabbd1;--line:#2b3a4e;--accent:#97c0fa}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font-size:16px;line-height:1.7}a{color:var(--accent)}
header{border-bottom:1px solid var(--line);background:var(--surface)}.wrap{width:min(760px,calc(100% - 32px));margin:auto}
.head{min-height:64px;display:flex;align-items:center;gap:20px}.head strong{flex:1}main{padding-block:48px 64px}
.panel{padding:28px;background:var(--surface);border:1px solid var(--line);border-radius:16px}h1{font-size:26px;margin:0 0 12px}
p{margin:10px 0;color:var(--muted)}code{padding:2px 6px;border-radius:6px;background:var(--bg);color:var(--text)}
</style></head>
<body><header><div class="wrap head"><a href="/admin">返回后台</a><strong>使用统计</strong></div></header>
<main class="wrap"><section class="panel"><h1>云取匿名统计已迁移</h1>
<p>旧 Cloudflare D1 统计入口已停止接收事件，现有数据仅用于清理，不再显示在开发者后台。</p>
<p>新版本使用 <code>Google Analytics Measurement Protocol</code>，并继续遵守用户同意与关闭开关。统计分析请在 Google Analytics 后台查看。</p>
<p>旧客户端向 <code>/api/analytics</code> 上报时会收到确认并停止重试，不会写入社区数据库。</p>
</section></main></body></html>`;

export const analyticsStyle = '';
export const analyticsScript = '';