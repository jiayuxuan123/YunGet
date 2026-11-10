// Local browser fixtures only. This process never uses D1, Access or production sessions.
import http from 'node:http';
import {readFile} from 'node:fs/promises';
import {html, style, script} from '../web.mjs';
import {consumeVerification, issueVerification, scopeForPath, verificationConfig} from '../verification.mjs';

const now = Math.floor(Date.now() / 1000);
const feedback = Array.from({length: 7}, (_, index) => ({
  id: '10000000-0000-4000-8000-' + String(index + 1).padStart(12, '0'),
  nickname: ['海边的风', '一只小云', '向北'][index % 3] + '（示例）',
  title: ['建议在下载完成后增加文件分类', '小屏幕下希望能完整阅读任务名称', '关于 aria2 内核安装的一个问题', '暂停之后，再继续下载需要重新解析吗？'][index % 4],
  body: '这是本地界面测试的示例反馈。希望在较窄的窗口里，也能看清说明、处理状态和回复。\n复现步骤：打开下载页面，然后查看任务详情。',
  product: index % 3 ? 'yunget' : 'turbodl', category: ['idea', 'bug', 'question'][index % 3],
  app_version: '2.7.28', status: ['planned', 'working', 'resolved', 'new'][index % 4],
  private: index === 6, mine: index === 6, created_at: now - 7200, updated_at: now - index * 180,
}));
const messages = Array.from({length: 36}, (_, index) => ({
  id: index + 1, nickname: ['海边的风（示例）', '一只小云（示例）', '向北（示例）'][index % 3],
  body: ['这次的下载界面用着挺舒服，暂停和继续都容易找到。', '建议在这里聊体验，具体问题可以发到反馈里，方便跟进。', '窄屏测试：长内容也应该完整换行。https://example.com/' + 'long-path-'.repeat(14), '手机键盘弹出来之后，最后一条消息和发送按钮也要看得见。'][index % 4],
  created_at: now - (36 - index) * 90,
}));
let identity = null;
let failNextWrite = false;
let writes = [];
const announcements = [{id: 'local-announcement', title: '2.7.28：经典界面恢复与新版细化（示例）',
  body: '本地预览，非正式公告。\n\n- 经典界面和新版界面均可使用。\n- 字号与间距可以在主题页调整。\n- 反馈可以查看处理状态。\n\n[查看云取官网](https://yunget.yunl.us.ci/)\n\n' + '较长公告的正文应自然换行，所有内容都能滚动到达。\n'.repeat(12),
  date: '2026-11-09', pinned: true, important: true, published: true}];
const usedProofs=new Set();
const verificationEnv={PUBLIC_ORIGIN:'http://127.0.0.1:8801',COMMUNITY_VERIFICATION_SECRET:crypto.randomUUID()+crypto.randomUUID(),DB:{prepare:()=>({bind(value){this.value=value;return this;},async run(){const fresh=!usedProofs.has(this.value);usedProofs.add(this.value);return {meta:{changes:Number(fresh)}};}})}};

const server = http.createServer(async (request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1');
  const path = url.pathname;
  const json = (body, status = 200) => {response.writeHead(status, {'Content-Type': 'application/json', 'Cache-Control': 'no-store'}); response.end(JSON.stringify(body));};
  if (path.startsWith('/verification/') && /^\/verification\/[a-zA-Z0-9_.-]+$/.test(path) && !path.includes('..')) {
    try {const bytes=await readFile(new URL('../public'+path,import.meta.url));response.setHeader('Content-Type',path.endsWith('.css')?'text/css':'text/javascript');response.end(bytes);}catch{response.writeHead(404);response.end();}return;
  }
  if (path === '/api/verification/challenge') {json(await issueVerification(new Request(verificationEnv.PUBLIC_ORIGIN+request.url),verificationEnv,url.searchParams.get('scope')));return;}
  if (request.method === 'POST' || request.method === 'PATCH') {
    let content = '';for await (const chunk of request) content += chunk;
    const body = JSON.parse(content || '{}');
    if (path === '/test/control') {
      if (body.addChat) messages.push({id: messages.at(-1).id + 1, nickname: '新增消息（示例）', body: body.addChat, created_at: now});
      if (body.failNextWrite) failNextWrite = true;
      if (body.resetIdentity) identity = null;
      json({ok: true});return;
    }
    writes.push({path, body, key: request.headers['idempotency-key']});
    if(path.startsWith('/api/')){
      try{await consumeVerification(new Request(verificationEnv.PUBLIC_ORIGIN+path),verificationEnv,body.challenge,scopeForPath(path));}
      catch(error){json({error:error.message},403);return;}
    }
    if (failNextWrite) {failNextWrite = false;json({error: '模拟网络失败，内容保留'}, 503);return;}
    if (path === '/api/session') {identity = body.nickname;json({nickname: identity}, 201);return;}
    if (path === '/api/chat') messages.push({id: messages.at(-1).id + 1, nickname: identity, body: body.body, created_at: now});
    json({ok: true, id: 'local-created'}, 201);return;
  }
  if (path === '/test/writes') {json(writes);return;}
  if (path === '/api/config') {json({version: '2.7.28', verification:verificationConfig});return;}
  if (path === '/api/session') {json({nickname: identity});return;}
  if (path === '/admin/api/session') {json({developer: true});return;}
  if (path.endsWith('/api/announcements')) {json({items: announcements});return;}
  if (path.endsWith('/api/feedback')) {
    const visible = url.searchParams.get('mine') ? feedback.filter(item => item.mine) : feedback;
    const filtered = url.searchParams.get('status') ? visible.filter(item => item.status === url.searchParams.get('status')) : visible;
    json({items: filtered, more: false});return;
  }
  if (path.includes('/api/feedback/')) {json({item: feedback[1], replies: [{id: 'local-reply', nickname: '开发者', developer: true, body: '已确认这个问题，正在处理。', created_at: now}]});return;}
  if (path.endsWith('/api/chat')) {json({items: messages.filter(item => item.id > Number(url.searchParams.get('after') || 0))});return;}
  if (path === '/app.css' || path === '/app.js') {
    response.setHeader('Content-Type', path.endsWith('.css') ? 'text/css' : 'text/javascript');
    response.end(path.endsWith('.css') ? style : script);return;
  }
  response.setHeader('Content-Type', 'text/html; charset=utf-8');response.end(html);
});
server.listen(Number(process.env.COMMUNITY_PREVIEW_PORT || 8801), '127.0.0.1', () => console.log('Local community fixtures: http://127.0.0.1:8801'));
