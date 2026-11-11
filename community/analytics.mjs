export const analyticsHtml = String.raw`<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
  <meta name="color-scheme" content="light dark">
  <meta name="theme-color" content="#f6f8fc">
  <title>云取匿名统计</title>
  <link rel="stylesheet" href="/analytics.css?v=20261111-1">
  <script type="module" src="/analytics.js?v=20261111-1"></script>
</head>
<body>
  <header><div class="wrap head"><a href="/admin">返回开发者后台</a><strong>云取匿名统计</strong><span id="updated"></span></div></header>
  <main class="wrap">
    <section class="hero">
      <div><p class="eyebrow">PRIVACY-FIRST TELEMETRY</p><h1>更新依据，来自真实使用。</h1><p>仅汇总匿名事件，不保存文件名、分享链接、账号、Cookie、IP 或二维码内容。</p></div>
      <div class="periods" role="group" aria-label="统计周期">
        <button type="button" data-days="7">7 天</button>
        <button type="button" data-days="30" aria-pressed="true">30 天</button>
        <button type="button" data-days="90">90 天</button>
      </div>
    </section>
    <section id="metrics" class="metrics" aria-live="polite"><p>正在读取统计…</p></section>
    <section class="panel"><div class="panel-head"><h2>每日活跃安装</h2><span>独立匿名安装数</span></div><div id="daily" class="bars"></div></section>
    <div class="grid">
      <section class="panel"><h2>版本分布</h2><div id="versions" class="table"></div></section>
      <section class="panel"><h2>事件分布</h2><div id="events" class="table"></div></section>
      <section class="panel"><h2>失败分类</h2><div id="errors" class="table"></div></section>
      <section class="panel"><h2>引擎分布</h2><div id="engines" class="table"></div></section>
    </div>
    <p id="status" class="status" role="status"></p>
  </main>
</body>
</html>`;

export const analyticsStyle = String.raw`:root{--bg:#f6f8fc;--surface:#fff;--surface-2:#eef3fb;--text:#172033;--muted:#61708a;--line:#d7e0ee;--accent:#2b64b5;--accent-2:#8bb7ef;--danger:#b33a3a;--radius:20px;font-family:"Noto Sans SC","Microsoft YaHei",system-ui,sans-serif;color-scheme:light dark}
@media(prefers-color-scheme:dark){:root{--bg:#101722;--surface:#172231;--surface-2:#1d2b3e;--text:#edf3fb;--muted:#9fb0c8;--line:#2b3a4e;--accent:#79adf2;--accent-2:#315b90;--danger:#ff9191}}
*{box-sizing:border-box}body{margin:0;background:radial-gradient(circle at 85% 0,#dbeaff 0,transparent 34rem),var(--bg);color:var(--text);font-size:15px;line-height:1.55}.wrap{width:min(1180px,calc(100% - 32px));margin:auto}header{border-bottom:1px solid var(--line);background:color-mix(in srgb,var(--surface) 88%,transparent);backdrop-filter:blur(14px)}.head{min-height:64px;display:flex;align-items:center;gap:20px}.head a{color:var(--accent);text-decoration:none;font-weight:650}.head strong{flex:1}.head span{color:var(--muted);font-size:13px}main{padding-block:28px 64px}.hero{display:flex;align-items:flex-end;justify-content:space-between;gap:24px;margin-block:20px 26px}.eyebrow{margin:0 0 6px;color:var(--accent);font-size:11px;letter-spacing:.16em;font-weight:800}h1{margin:0;font-size:clamp(28px,5vw,48px);line-height:1.08;letter-spacing:-.035em}h2{font-size:17px;margin:0}.hero>div>p:last-child{max-width:720px;margin:12px 0 0;color:var(--muted)}.periods{display:flex;padding:4px;border:1px solid var(--line);border-radius:14px;background:var(--surface)}.periods button{border:0;border-radius:10px;background:transparent;color:var(--muted);padding:9px 14px;font:inherit;font-weight:650;cursor:pointer}.periods button[aria-pressed=true]{background:var(--accent);color:#fff}.metrics{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:12px;margin-block:16px}.metric,.panel{background:var(--surface);border:1px solid var(--line);border-radius:var(--radius);box-shadow:0 8px 30px rgb(28 58 95/6%)}.metric{padding:17px}.metric span{display:block;color:var(--muted);font-size:13px}.metric strong{display:block;margin-top:8px;font-size:29px;line-height:1}.metric small{display:block;margin-top:8px;color:var(--muted)}.panel{padding:20px;margin-top:12px}.panel-head{display:flex;justify-content:space-between;gap:16px;align-items:baseline}.panel-head span{color:var(--muted);font-size:13px}.bars{height:220px;display:flex;align-items:flex-end;gap:clamp(3px,.7vw,9px);border-bottom:1px solid var(--line);padding-top:22px}.bar{flex:1;min-width:3px;height:calc(var(--h) * 1%);background:linear-gradient(180deg,var(--accent),var(--accent-2));border-radius:6px 6px 0 0;position:relative}.bar:hover::after{content:attr(data-label);position:absolute;bottom:100%;left:50%;transform:translate(-50%,-6px);white-space:nowrap;background:var(--text);color:var(--surface);padding:5px 8px;border-radius:7px;font-size:11px}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.table{margin-top:14px}.row{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:12px;padding:10px 0;border-top:1px solid var(--line)}.row:first-child{border-top:0}.row span{overflow-wrap:anywhere}.row b{font-variant-numeric:tabular-nums}.status{min-height:24px;color:var(--muted)}@media(max-width:900px){.metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.grid{grid-template-columns:1fr}.hero{align-items:flex-start;flex-direction:column}}@media(max-width:520px){.wrap{width:min(100% - 20px,1180px)}.head{gap:10px;flex-wrap:wrap;padding-block:10px}.head span{width:100%}.metrics{grid-template-columns:1fr 1fr}.metric{padding:14px}.metric strong{font-size:24px}.panel{padding:16px;border-radius:16px}.bars{height:170px}}`;

export const analyticsScript = String.raw`const metrics=document.getElementById('metrics'),daily=document.getElementById('daily'),versions=document.getElementById('versions'),events=document.getElementById('events'),errors=document.getElementById('errors'),engines=document.getElementById('engines'),statusEl=document.getElementById('status'),updated=document.getElementById('updated');let days=30;
const fmt=n=>Number(n||0).toLocaleString('zh-CN');const pct=(a,b)=>b>0?(a/b*100).toFixed(1)+'%':'—';
function setPressed(value){document.querySelectorAll('[data-days]').forEach(button=>button.setAttribute('aria-pressed',String(Number(button.dataset.days)===value)))}
function metric(label,value,note=''){const node=document.createElement('article');node.className='metric';const l=document.createElement('span');l.textContent=label;const v=document.createElement('strong');v.textContent=value;node.append(l,v);if(note){const s=document.createElement('small');s.textContent=note;node.append(s)}return node}
function table(target,items,labeler=value=>value){target.replaceChildren();if(!items?.length){const p=document.createElement('p');p.className='status';p.textContent='暂无数据';target.append(p);return}for(const item of items){const row=document.createElement('div');row.className='row';const label=document.createElement('span');label.textContent=labeler(item);const count=document.createElement('b');count.textContent=fmt(item.count);row.append(label,count);target.append(row)}}
async function load(){statusEl.textContent='正在读取…';try{const res=await fetch('/admin/api/analytics?days='+days,{headers:{Accept:'application/json'},credentials:'same-origin'});const data=await res.json();if(!res.ok)throw new Error(data.error||'读取失败');const s=data.summary||{};metrics.replaceChildren(metric('活跃安装',fmt(s.installs),'独立匿名安装'),metric('下载开始',fmt(s.download_starts)),metric('下载完成',fmt(s.download_successes),'成功率 '+pct(s.download_successes,s.download_starts)),metric('失败',fmt(s.download_failures),'保存失败 '+fmt(s.save_failures)),metric('事件总量',fmt(s.events)));const max=Math.max(1,...(data.daily||[]).map(x=>x.installs));daily.replaceChildren();for(const point of data.daily||[]){const bar=document.createElement('i');bar.className='bar';bar.style.setProperty('--h',String(Math.max(2,point.installs/max*100)));bar.dataset.label=point.day+' · '+fmt(point.installs)+' 安装';bar.title=bar.dataset.label;daily.append(bar)}table(versions,data.versions,item=>item.app_version||'未知版本');table(events,data.events,item=>item.event);table(errors,data.errors,item=>item.error||'未知');table(engines,data.engines,item=>item.engine||'未知');updated.textContent='更新于 '+new Date().toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit'});statusEl.textContent=''}catch(error){statusEl.textContent=error.message||'读取失败';metrics.replaceChildren()}}
document.querySelectorAll('[data-days]').forEach(button=>button.addEventListener('click',()=>{days=Number(button.dataset.days);setPressed(days);load()}));setPressed(days);load();`;
