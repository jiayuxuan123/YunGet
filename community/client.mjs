import {socialScript} from './social-client.mjs';
export const script = String.raw`const state = {
  session: null, admin: false, config: null, view: '', revision: 0, filter: '', mine: false,
  feedback: [], page: 0, more: false, feedbackBusy: false, feedbackDraft: {},
  chat: [], chatBusy: false, chatDraft: '', unread: 0, chatTimer: null, chatFollow: true, resizingChat: false,
  replyDrafts: new Map(), announcements: [], adminAnnouncements: [], announcementDraft: null,
  modalRevision: 0, modalBack: null, widget: null, challenge: null, challengeRevision: 0, theme: 'system',
};
const app = document.getElementById('app');
const modal = document.getElementById('modal');
const toastEl = document.getElementById('toast');
const isAdminPage = location.pathname.startsWith('/admin');
const labels = {new:'待处理', planned:'已计划', working:'处理中', resolved:'已解决', closed:'已关闭', bug:'问题', idea:'建议', question:'提问', other:'其他', yunget:'云取', turbodl:'TurboDL'};
const paths = {
  plus:'M12 5v14M5 12h14', close:'m6 6 12 12M18 6 6 18',
  arrow:'m5 12 7-7 7 7M12 5v14', refresh:'M20 7v5h-5M4 17v-5h5M6.1 7a7 7 0 0 1 11.7-2L20 8M4 16l2.2 3A7 7 0 0 0 18 17',
  chat:'M21 11.5a8.3 8.3 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.3 8.3 0 0 1-3.8-.9L3 21l1.9-5.7a8.3 8.3 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.3 8.3 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5Z',
  idea:'M9 18h6M10 21h4M8.1 13.8a6 6 0 1 1 7.8 0C15 14.5 15 16 15 16H9s0-1.5-.9-2.2Z',
  bug:'M8 10h8v7a4 4 0 0 1-8 0v-7ZM9 10V8a3 3 0 0 1 6 0v2M5 12h3M16 12h3M5 17h3M16 17h3M8 5l2 2M16 5l-2 2',
  help:'M9.1 9a3 3 0 0 1 5.8 1c0 2-3 3-3 3M12 17h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0Z',
  note:'M8 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V5a2 2 0 0 0-2-2h-3M8 3h8v4H8V3ZM8 12h8M8 16h5',
  lock:'M7 11V7a5 5 0 0 1 10 0v4M5 11h14v10H5V11ZM12 15v2',
  sun:'M12 3v2M12 19v2M3 12h2M19 12h2M5.6 5.6 7 7M17 17l1.4 1.4M5.6 18.4 7 17M17 7l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  moon:'M20.8 13a9 9 0 1 1-9.8-9.8A7 7 0 0 0 20.8 13Z',
  system:'M3 4h18v13H3V4ZM8 21h8M12 17v4',
};
const esc = value => String(value == null ? '' : value).replace(/[&<>"']/g, ch => ({'&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;'}[ch]));
const icon = (name, extra='') => '<svg class="icon '+extra+'" viewBox="0 0 24 24" aria-hidden="true"><path d="'+(paths[name] || paths.note)+'"/></svg>';
const dateText = value => new Date(value * 1000).toLocaleString('zh-CN', {month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit', hour12:false});
const status = value => '<span class="status status-'+esc(value)+'">'+esc(labels[value] || value)+'</span>';
function toast(message) {
  toastEl.textContent=message; toastEl.hidden=false;
  clearTimeout(toast.timer); toast.timer=setTimeout(()=>toastEl.hidden=true, 3000);
}
async function request(path, options={}) {
  const controller=new AbortController(); const timer=setTimeout(()=>controller.abort(), 15000);
  try {
    const headers=new Headers(options.headers || {});
    if (options.body) headers.set('Content-Type', 'application/json');
    headers.set('Accept', 'application/json');
    const response=await fetch(path, {...options, headers, credentials:'same-origin', signal:controller.signal});
    const body=await response.json().catch(()=>({}));
    if (!response.ok) { const error=new Error(body.error || '请求失败，请稍后重试'); error.status=response.status; throw error; }
    return body;
  } catch(error) {
    if (error.name==='AbortError') throw new Error('连接超时，请重试。已填写的内容仍然保留。');
    throw error;
  } finally { clearTimeout(timer); }
}
function write(path, body, key=crypto.randomUUID(), method='POST') {
  return request(path, {method, headers:{'Idempotency-Key':key}, body:JSON.stringify(body)});
}
function showFormError(form, error) {
  let target=form.querySelector('.inline-error');
  if (!target) { target=document.createElement('p'); target.className='inline-error'; target.setAttribute('role','alert'); form.append(target); }
  target.textContent=error.message || '提交失败，请重试'; target.hidden=false;
  target.scrollIntoView({block:'nearest'});
}
function clearChallenge() {
  state.challengeRevision++;
  if (state.widget) { try { state.widget.reset(); state.widget.remove(); } catch {} }
  state.widget=null; state.challenge=null;
}
let challengeLibrary;
function loadChallengeLibrary() {
  if (challengeLibrary) return challengeLibrary;
  const script=state.config?.verification?.script;
  if(typeof script!=='string' || !script.startsWith('/verification/'))return Promise.reject(new Error('验证服务暂不可用'));
  let timer;
  challengeLibrary=Promise.race([
    import(script).then(()=>customElements.whenDefined('altcha-widget')),
    new Promise((_resolve,reject)=>{timer=setTimeout(()=>reject(new Error('验证资源加载超时，请点击重新验证')),12000);}),
  ]).catch(error=>{challengeLibrary=null;throw error;}).finally(()=>clearTimeout(timer));
  return challengeLibrary;
}
async function mountChallenge() {
  clearChallenge(); const revision=state.modalRevision;const attempt=state.challengeRevision;
  const target=modal.querySelector('.verification-wrap'); const note=modal.querySelector('.verification-status');
  if (!target) return;
  const current=()=>revision===state.modalRevision && attempt===state.challengeRevision && modal.open && target.isConnected;
  const report=message=>{if(note && current())note.textContent=message;};
  if (state.config?.verification?.provider!=='altcha') { report('验证服务暂不可用，请刷新页面'); return; }
  report('正在加载验证…');
  try {
    await loadChallengeLibrary();
    if (!current()) return;
    const widget=document.createElement('altcha-widget');
    widget.setAttribute('challenge',state.config.verification.challengeUrl+'?scope='+encodeURIComponent(target.dataset.scope));
    widget.setAttribute('language','zh-cn');widget.setAttribute('workers','1');widget.setAttribute('auto','off');
    widget.addEventListener('load',()=>{
      if(!current())return;
      widget.configure({minDuration:0,timeout:20000,workers:1,humanInteractionSignature:false,auto:'off'});
      widget.verify();
    });
    widget.addEventListener('verified',event=>{if(current() && event.detail?.payload){state.challenge=event.detail.payload;report('验证通过，可以提交');}});
    widget.addEventListener('statechange',event=>{
      if(!current())return;
      if(event.detail?.state==='verifying'){state.challenge=null;report('正在验证，请稍候…');}
      if(event.detail?.state==='expired'){state.challenge=null;report('验证已过期，请重新验证');}
      if(event.detail?.state==='error'){state.challenge=null;report('验证未完成，请点击重新验证');}
    });
    state.widget=widget;target.replaceChildren(widget);
  } catch(error) { report(error.message); }
}
function challengeMarkup(scope) {
  return '<div class="verification-wrap" data-scope="'+scope+'"></div><p class="verification-status" role="status"></p><button type="button" class="button button-quiet" data-retry-challenge>重新验证</button>';
}
function hideModal() {
  state.modalRevision++; state.modalBack=null; clearChallenge();
  if(modal.open)modal.close();
}
function dismissModal() {
  const back=state.modalBack; hideModal(); if(back)back();
}
function openModal(title, content, {back=null}={}) {
  clearChallenge(); state.modalRevision++; state.modalBack=back;
  modal.innerHTML='<div class="dialog-layout"><header class="dialog-head"><h2 id="modal-title">'+esc(title)+'</h2><button type="button" class="icon-button" aria-label="关闭" data-dismiss>'+icon('close')+'</button></header><div class="dialog-content">'+content+'</div></div>';
  if(!modal.open)modal.showModal();
  modal.querySelector('.dialog-content').scrollTop=0;
  modal.querySelector('[data-dismiss]').focus({preventScroll:true});
}
modal.addEventListener('cancel', event=>{event.preventDefault(); dismissModal();});
modal.addEventListener('click',event=>{
  if(event.target.closest('[data-dismiss]'))dismissModal();
  if(event.target.closest('[data-retry-challenge]'))mountChallenge();
  if(event.target===modal && !modal.querySelector('form'))dismissModal();
});
function updateIdentity() {
  const button=document.getElementById('identity');
  button.textContent=state.session?.nickname || (state.admin?'开发者':'设置昵称');
  button.dataset.guest=String(!state.session && !state.admin);
  button.setAttribute('aria-label',state.session?'修改社区昵称':'设置社区昵称');
  document.querySelectorAll('.admin-only').forEach(node=>{node.hidden=!state.admin;});
  const hint=document.getElementById('chat-identity');
  if(hint)hint.textContent=state.session?'以 '+state.session.nickname+' 发送':'无需开发者登录，首次发送时设置昵称';
}
function openIdentity(continuation=null, back=null) {
  const nickname=(state.session?.nickname || '').replace(/·[a-f0-9]{4}$/, '');
  openModal('设置社区昵称', '<form id="identity-form"><p class="form-note">普通用户只需昵称。开发者登录仅用于管理后台。</p><div class="field"><label for="nickname">昵称</label><input id="nickname" name="nickname" maxlength="24" required autocomplete="nickname" placeholder="想让大家怎么称呼你" value="'+esc(nickname)+'"></div>'+challengeMarkup('session')+'<div class="form-actions"><button type="submit" class="button button-primary">保存昵称</button></div></form>',{back});
  mountChallenge();
  document.getElementById('identity-form').onsubmit=async event=>{
    event.preventDefault(); const form=event.currentTarget;
    if(!state.challenge){showFormError(form,new Error('请先完成人机验证'));return;}
    const button=form.querySelector('[type=submit]'); button.disabled=true;
    const revision=state.modalRevision;
    try {
      const result=await write('/api/session',{nickname:form.elements.namedItem('nickname').value,challenge:state.challenge});
      const {recoveryCode,...identity}=result;state.session=identity;updateIdentity();
      if(revision!==state.modalRevision)return;
      hideModal();if(recoveryCode){offerRecovery(recoveryCode,continuation);return;}if(continuation)continuation();else toast('昵称已保存');
    } catch(error){if(revision===state.modalRevision){showFormError(form,error);mountChallenge();}}
    finally {button.disabled=false;}
  };
}
function verifyAction(action) {
  if(!state.session && !action.admin){openIdentity(()=>verifyAction(action),action.back);return;}
  const preview=String(action.preview ?? action.body?.body ?? '').slice(0,300);
  openModal(action.title,'<form id="verify-form"><p class="form-note">'+esc(action.admin?'确认后保存。':'验证后即可发送，已填写的内容仍然保留。')+'</p>'+(preview?'<div class="action-preview">'+esc(preview)+'</div>':'')+(action.admin?'':challengeMarkup(action.path.includes('/account/')?'session':(action.path.endsWith('/replies')||action.path.endsWith('/comments'))?'reply':action.path==='/api/chat'?'chat':'feedback'))+'<div class="form-actions"><button type="button" class="button" data-dismiss>返回修改</button><button class="button button-primary" type="submit">'+esc(action.title)+'</button></div></form>',{back:action.back});
  if(!action.admin)mountChallenge();
  document.getElementById('verify-form').onsubmit=async event=>{
    event.preventDefault();const form=event.currentTarget;
    if(!action.admin && !state.challenge){showFormError(form,new Error('请先完成人机验证'));return;}
    const button=form.querySelector('[type=submit]');button.disabled=true;
    const revision=state.modalRevision;
    try {
      const result=await write(action.path,{...action.body,challenge:state.challenge},action.key);
      if(revision===state.modalRevision)hideModal();
      await action.done(result); toast(action.success);
    } catch(error) {
      if(revision!==state.modalRevision)return;
      if(error.status===401 && !action.admin){state.session=null;updateIdentity();openIdentity(()=>verifyAction(action),action.back);return;}
      showFormError(form,error); if(!action.admin)mountChallenge();
    } finally {button.disabled=false;}
  };
  if(action.autoSubmit){const revision=state.modalRevision,started=Date.now();const timer=setInterval(()=>{
    if(!modal.open||revision!==state.modalRevision||Date.now()-started>25000){clearInterval(timer);return;}
    if(action.admin||state.challenge){clearInterval(timer);const form=document.getElementById('verify-form');if(!form||form.querySelector('[type=submit]').disabled)return;if(form.requestSubmit)form.requestSubmit();else form.dispatchEvent(new Event('submit',{cancelable:true}));}
  },120);}
}
function field(name,label,options={}) {
  return '<div class="field '+(options.full?'full':'')+'"><label for="'+name+'">'+label+'</label><input id="'+name+'" name="'+name+'" '+(options.required?'required':'')+' maxlength="'+(options.max || 100)+'" '+(options.min?'minlength="'+options.min+'"':'')+' placeholder="'+esc(options.placeholder || '')+'"></div>';
}
function captureFeedback(form) {
  const data=new FormData(form);state.feedbackDraft=Object.fromEntries(data);state.feedbackDraft.private=data.has('private');
}
function openFeedbackComposer() {
  openModal('写反馈','<form id="feedback-form"><div class="form-grid"><div class="field"><label for="product">产品</label><select id="product" name="product"><option value="yunget">云取 YunGet</option><option value="turbodl">TurboDL</option></select></div><div class="field"><label for="category">类型</label><select id="category" name="category"><option value="bug">问题</option><option value="idea">建议</option><option value="question">提问</option><option value="other">其他</option></select></div>'+field('title','标题',{full:true,required:true,min:3,placeholder:'一句话说明问题或想法'})+'<div class="field full"><label for="body">详细说明</label><textarea id="body" name="body" minlength="5" maxlength="6000" required placeholder="发生了什么？希望如何改进？"></textarea></div>'+field('app_version','版本 <span class="optional">（选填）</span>',{max:32,placeholder:'例如 2.7.28'})+'<label class="check-row"><input type="checkbox" name="private">仅我和开发者可见</label></div><p class="surface-note">'+icon('lock')+'请勿填写密码、Cookie 或带签名的私有链接。</p><div class="form-actions"><span class="draft-hint">关闭后保留本次草稿</span><button class="button button-primary" type="submit">下一步</button></div></form>');
  const form=document.getElementById('feedback-form');
  for(const [name,value] of Object.entries(state.feedbackDraft)){
    const input=form.elements.namedItem(name);if(input){if(input.type==='checkbox')input.checked=Boolean(value);else input.value=value;}
  }
  form.oninput=()=>captureFeedback(form);form.onchange=()=>captureFeedback(form);
  form.onsubmit=event=>{
    event.preventDefault();captureFeedback(form);
    verifyAction({title:'提交反馈',preview:state.feedbackDraft.title,path:'/api/feedback',body:{...state.feedbackDraft},key:crypto.randomUUID(),back:openFeedbackComposer,success:'反馈已提交',done:async()=>{state.feedbackDraft={};state.filter='';state.mine=false;if(state.view==='feedback')await loadFeedback();}});
  };
}
function viewHead(title,copy,actions='') {
  return '<section class="view-head"><div><h1>'+esc(title)+'</h1><p>'+esc(copy)+'</p></div>'+actions+'</section>';
}
function feedbackRow(item) {
  const categoryIcon=item.category==='idea'?'idea':item.category==='question'?'help':item.category==='bug'?'bug':'note';
  return '<button type="button" class="feedback-row" data-thread="'+esc(item.id)+'"><span class="row-heading"><span class="category-label">'+icon(categoryIcon)+esc(labels[item.product] || item.product)+' · '+esc(labels[item.category] || item.category)+'</span>'+status(item.status)+'</span><span class="feedback-title">'+esc(item.title)+'</span><span class="feedback-excerpt">'+esc(item.body)+'</span><span class="feedback-meta"><span>'+esc(item.nickname)+'</span><time>'+dateText(item.updated_at)+'</time>'+(item.private?'<span>仅本人和开发者可见</span>':'')+'</span></button>';
}
async function loadFeedback(append=false) {
  const revision=state.revision;const filter=state.filter;const mine=state.mine;const page=append?state.page+1:0;
  const params=new URLSearchParams({page:String(page)});if(filter)params.set('status',filter);if(mine)params.set('mine','1');
  const result=await request('/api/feedback?'+params);
  if(revision!==state.revision || filter!==state.filter || mine!==state.mine)return;
  state.feedback=append?state.feedback.concat(result.items):result.items;state.page=page;state.more=result.more;
  if(append){app.querySelector('.feedback-list').insertAdjacentHTML('beforeend',result.items.map(feedbackRow).join(''));app.querySelector('.load-more')?.remove();if(state.more)app.insertAdjacentHTML('beforeend',moreButton());return;}
  app.innerHTML=viewHead('反馈与建议','问题有记录，进展随时看。','<button class="button button-primary" type="button" data-action="compose">'+icon('plus')+'写反馈</button>')
    +'<div class="filter-row" aria-label="筛选反馈">'+[['','全部'],['new','待处理'],['working','处理中'],['resolved','已解决']].map(([key,label])=>'<button class="chip" type="button" data-filter="'+key+'" aria-pressed="'+(!state.mine && key===state.filter)+'">'+label+'</button>').join('')+'<button class="chip" type="button" data-action="mine" aria-pressed="'+state.mine+'">我的反馈</button></div>'
    +'<div class="feedback-list">'+(state.feedback.length?state.feedback.map(feedbackRow).join(''):'<div class="empty">'+icon('chat','empty-icon')+'<h2>这里还没有反馈</h2><p>遇到问题或有新想法，欢迎告诉我们。</p><button class="button" data-action="compose" type="button">写一条反馈</button></div>')+'</div>'
    +'<p class="surface-note">无需开发者登录，首次提交时设置昵称即可。</p>'+(state.more?moreButton():'');
}
function moreButton(){return '<div class="load-more"><button type="button" class="button" data-action="more">加载更多</button></div>';}
let threadRequest=0;
async function openThread(id) {
  const current=++threadRequest;
  try {
    const admin=state.admin;const payload=await request((admin?'/admin/api/feedback/':'/api/feedback/')+id);
    if(current!==threadRequest)return;
    const item=payload.item;const replies=payload.replies || [];
    openModal(item.title,'<div class="feedback-meta">'+status(item.status)+'<span>'+esc(labels[item.product])+' · '+esc(labels[item.category])+'</span><span>'+esc(item.nickname)+'</span><time>'+dateText(item.created_at)+'</time>'+(item.private?'<span>仅本人和开发者可见</span>':'')+'</div><div class="thread-body">'+esc(item.body)+'</div>'
      +(item.app_version?'<p class="form-note">版本 '+esc(item.app_version)+'</p>':'')
      +(admin?'<div class="admin-controls"><label for="thread-status">处理状态</label><select id="thread-status">'+['new','planned','working','resolved','closed'].map(key=>'<option value="'+key+'" '+(item.status===key?'selected':'')+'>'+labels[key]+'</option>').join('')+'</select><label class="check-row"><input id="thread-hidden" type="checkbox" '+(item.hidden?'checked':'')+'>隐藏</label><button class="button" type="button" id="save-thread">保存状态</button></div>':'')
      +'<h3>回复'+(replies.length?' · '+replies.length:'')+'</h3>'+replies.map(reply=>'<article class="reply"><header><strong>'+esc(reply.nickname)+'</strong>'+(reply.developer?'<span class="developer-badge">开发者</span>':'')+'<time>'+dateText(reply.created_at)+'</time>'+(admin?'<button type="button" class="button button-quiet" data-hide-reply="'+esc(reply.id)+'">隐藏</button>':'')+'</header><p>'+esc(reply.body)+'</p></article>').join('')
      +'<form id="reply-form" class="reply-form"><div class="field"><label for="reply">补充或回复</label><textarea id="reply" name="body" required maxlength="3000" placeholder="补充信息，或回复这条反馈"></textarea></div><div class="form-actions"><button class="button button-primary" type="submit">发送回复</button></div></form>');
    const form=document.getElementById('reply-form');form.elements.namedItem('body').value=state.replyDrafts.get(id) || '';
    form.oninput=()=>state.replyDrafts.set(id,form.elements.namedItem('body').value);
    form.onsubmit=event=>{
      event.preventDefault();const body=form.elements.namedItem('body').value;state.replyDrafts.set(id,body);
      verifyAction({title:'发送回复',preview:body,path:(admin?'/admin/api/feedback/':'/api/feedback/')+id+'/replies',body:{body},key:crypto.randomUUID(),admin,back:()=>openThread(id),success:'回复已发送',done:async()=>{state.replyDrafts.delete(id);await openThread(id);}});
    };
    if(admin){
      document.getElementById('save-thread').onclick=async event=>{
        const button=event.currentTarget;button.disabled=true;
        try {await write('/admin/api/feedback/'+id,{status:document.getElementById('thread-status').value,hidden:document.getElementById('thread-hidden').checked},undefined,'PATCH');hideModal();await loadView();toast('处理状态已更新');}catch(error){toast(error.message);}finally{button.disabled=false;}
      };
      modal.querySelectorAll('[data-hide-reply]').forEach(button=>button.onclick=async()=>{try{await write('/admin/api/replies/'+button.dataset.hideReply,{hidden:true},undefined,'PATCH');await openThread(id);}catch(error){toast(error.message);}});
    }
  }catch(error){toast(error.message);}
}
function announcementText(body) {
  // Escape before adding the small, deliberately limited link syntax.
  return esc(body).replace(/\[([^\]\n]+)\]\((https:\/\/[^\s<>]+)\)/g,(_all,label,url)=>'<a href="'+url+'" rel="noopener noreferrer">'+label+'</a>');
}
async function loadAnnouncements() {
  const revision=state.revision;const result=await request('/api/announcements');
  if(revision!==state.revision)return;
  state.announcements=result.items;
  app.innerHTML=viewHead('公告','版本更新与维护消息。','<button type="button" class="icon-button" aria-label="刷新公告" data-action="refresh">'+icon('refresh')+'</button>')+'<div class="announcement-list">'+(result.items.length?result.items.map(item=>'<article class="announcement-card"><div class="feedback-meta"><span class="status">'+(item.pinned?'置顶':item.important?'重要':'公告')+'</span><time>'+esc(item.date)+'</time></div><h2>'+esc(item.title)+'</h2><div class="announcement-body">'+announcementText(item.body)+'</div></article>').join(''):'<div class="empty">暂时没有公告。</div>')+'</div>';
}
function atChatEnd() {
  const log=document.getElementById('chat-log');return !log || log.scrollHeight-log.scrollTop-log.clientHeight<64;
}
function scrollChatEnd() {
  const log=document.getElementById('chat-log');if(!log)return;
  log.scrollTop=log.scrollHeight;state.chatFollow=true;state.unread=0;const button=document.getElementById('chat-new');if(button)button.hidden=true;
}
function messageMarkup(item) {
  const mine=item.nickname===state.session?.nickname;
  return '<article class="chat-message '+(mine?'mine':'')+'" data-message="'+esc(item.id)+'"><span class="avatar" aria-hidden="true">'+esc(Array.from(item.nickname || '访')[0])+'</span><div><header class="message-meta"><strong>'+esc(item.nickname)+'</strong><time>'+dateText(item.created_at)+'</time></header><p class="message-bubble">'+esc(item.body)+'</p></div></article>';
}
function renderChat() {
  app.innerHTML='<section class="chat-view"><header class="chat-heading"><div><h1>社区闲聊</h1><p>具体问题请发到反馈，方便持续跟进。</p></div><button class="icon-button" type="button" data-action="refresh-chat" aria-label="刷新消息">'+icon('refresh')+'</button></header><div id="chat-log" class="chat-log" role="log" aria-label="聊天消息" aria-live="off">'+(state.chat.length?state.chat.map(messageMarkup).join(''):'<div class="empty" id="chat-empty">'+icon('chat','empty-icon')+'<h2>聊聊使用体验</h2><p>消息会在这里显示。</p></div>')+'</div><button id="chat-new" class="chat-new" type="button" hidden>查看新消息</button><form id="chat-form" class="chat-compose"><p id="chat-error" class="chat-error" hidden role="status"></p><div class="compose-row"><textarea id="chat-body" name="body" maxlength="500" rows="1" required aria-label="消息" placeholder="说点什么…"></textarea><button class="button button-primary send-button" type="submit" aria-label="发送消息">'+icon('arrow')+'</button></div><div class="compose-note"><span id="chat-identity"></span><span id="chat-count">0 / 500</span></div></form></section>';
  const input=document.getElementById('chat-body');input.value=state.chatDraft;updateChatInput();updateIdentity();
  input.oninput=()=>{state.chatDraft=input.value;updateChatInput();};
  input.onkeydown=event=>{if(event.ctrlKey && event.key==='Enter' && !event.isComposing){event.preventDefault();document.getElementById('chat-form').requestSubmit();}};
  document.getElementById('chat-form').onsubmit=event=>{
    event.preventDefault();state.chatDraft=input.value;
    if(!state.chatDraft.trim())return;
    verifyAction({title:'发送消息',preview:state.chatDraft,path:'/api/chat',body:{body:state.chatDraft},key:crypto.randomUUID(),back:null,success:'消息已发送',done:async()=>{state.chatDraft='';if(state.view==='chat'){input.value='';updateChatInput();await pollChat();scrollChatEnd();}}});
  };
  document.getElementById('chat-new').onclick=scrollChatEnd;
  document.getElementById('chat-log').onscroll=()=>{
    if(!state.resizingChat)state.chatFollow=atChatEnd();
    if(atChatEnd()){state.unread=0;document.getElementById('chat-new').hidden=true;}
  };
  requestAnimationFrame(scrollChatEnd);
}
function updateChatInput() {
  const input=document.getElementById('chat-body');if(!input)return;
  input.style.height='46px';input.style.height=Math.min(116, Math.max(46,input.scrollHeight))+'px';
  document.getElementById('chat-count').textContent=input.value.length+' / 500';
  if(state.chatFollow)requestAnimationFrame(scrollChatEnd);
}
async function pollChat() {
  if(state.chatBusy || state.view!=='chat' || document.hidden)return;
  state.chatBusy=true;const revision=state.revision;
  try {
    const after=state.chat.at(-1)?.id || 0;const result=await request('/api/chat?after='+encodeURIComponent(after));
    if(revision!==state.revision || state.view!=='chat')return;
    const existing=new Set(state.chat.map(item=>item.id));const fresh=result.items.filter(item=>!existing.has(item.id));
    const log=document.getElementById('chat-log');const bottom=atChatEnd();
    if(fresh.length){
      document.getElementById('chat-empty')?.remove();state.chat.push(...fresh);
      log.insertAdjacentHTML('beforeend',fresh.map(messageMarkup).join(''));
      while(state.chat.length>180){
        state.chat.shift();const first=log.firstElementChild;const next=first?.nextElementSibling;
        const removed=next?next.offsetTop-first.offsetTop:first?.offsetHeight || 0;
        first?.remove();if(!bottom)log.scrollTop=Math.max(0,log.scrollTop-removed);
      }
      if(bottom)scrollChatEnd();
      else{state.unread+=fresh.length;const button=document.getElementById('chat-new');button.textContent=state.unread+' 条新消息 · 查看';button.hidden=false;}
    }
    document.getElementById('chat-error').hidden=true;
  } catch(error) {
    if(revision===state.revision && state.view==='chat'){const note=document.getElementById('chat-error');note.textContent='消息暂未同步，点击右上角刷新重试';note.hidden=false;}
  } finally {state.chatBusy=false;}
}
async function loadAdmin() {
  const revision=state.revision;
  const [feedback,announcements,messages]=await Promise.all([request('/admin/api/feedback'),request('/admin/api/announcements'),request('/admin/api/chat')]);
  if(revision!==state.revision)return;
  state.adminAnnouncements=announcements.items;
  app.innerHTML=viewHead('开发者后台','管理社区、反馈与公告。','<div class="admin-tools"><a class="button" href="#feed">社区与帖子</a><a class="button" href="/admin/analytics">匿名统计</a><a class="button" href="#chat">旧闲聊</a><a class="button" href="/">公开社区</a></div>')+'<div class="admin-grid"><section class="admin-panel"><h2>反馈队列</h2>'+(feedback.items.length?feedback.items.map(feedbackRow).join(''):'<p class="form-note">暂无反馈。</p>')+'</section><section class="admin-panel"><h2>公告管理</h2><button class="button button-primary" data-action="new-announcement">'+icon('plus')+'新建公告</button>'+announcements.items.map(item=>'<article class="admin-item"><header><strong>'+esc(item.title)+'</strong><span>'+esc(item.date)+'</span></header><p class="form-note">'+(item.published?'已发布':'草稿')+(item.pinned?' · 置顶':'')+'</p><button class="button" data-edit-announcement="'+esc(item.id)+'">编辑</button></article>').join('')+'</section><section class="admin-panel"><h2>闲聊管理</h2>'+messages.items.map(item=>'<article class="admin-item"><header><strong>'+esc(item.nickname)+'</strong><time>'+dateText(item.created_at)+'</time></header><p class="thread-body">'+esc(item.body)+'</p><button type="button" class="button" data-moderate-message="'+esc(item.id)+'" data-hidden="'+String(Boolean(item.hidden))+'">'+(item.hidden?'恢复显示':'隐藏消息')+'</button></article>').join('')+'</section></div>';
}
function openAnnouncementEditor(item=null) {
  const saved=state.announcementDraft;
  if(item)state.announcementDraft={...item};else if(saved?.id)state.announcementDraft=null;
  const draft=state.announcementDraft || {date:new Date().toISOString().slice(0,10),published:true};
  openModal(item?'编辑公告':'新建公告','<form id="announcement-form"><div class="form-grid">'+field('announcement-title','标题',{full:true,required:true,max:120})+'<div class="field full"><label for="announcement-body">正文</label><textarea id="announcement-body" name="body" maxlength="10000" required></textarea></div><div class="field"><label for="announcement-date">日期</label><input type="date" id="announcement-date" name="date" required></div><label class="check-row"><input name="pinned" type="checkbox">置顶</label><label class="check-row"><input name="important" type="checkbox">重要</label><label class="check-row"><input name="published" type="checkbox">公开</label></div><div class="form-actions"><button class="button button-primary" type="submit">保存公告</button></div></form>');
  const form=document.getElementById('announcement-form');form.elements.namedItem('announcement-title').value=draft.title || '';form.elements.namedItem('body').value=draft.body || '';form.elements.namedItem('date').value=draft.date;
  for(const key of ['pinned','important','published'])form.elements.namedItem(key).checked=Boolean(draft[key]);
  const capture=()=>({...(draft.id?{id:draft.id}:{}),title:form.elements.namedItem('announcement-title').value,body:form.elements.namedItem('body').value,date:form.elements.namedItem('date').value,...Object.fromEntries(['pinned','important','published'].map(key=>[key,form.elements.namedItem(key).checked]))});
  form.oninput=form.onchange=()=>{state.announcementDraft=capture();};
  form.onsubmit=async event=>{
    event.preventDefault();state.announcementDraft=capture();const button=form.querySelector('[type=submit]');button.disabled=true;
    try{await write('/admin/api/announcements',state.announcementDraft);state.announcementDraft=null;hideModal();await loadAdmin();toast('公告已保存');}catch(error){showFormError(form,error);}finally{button.disabled=false;}
  };
}
async function loadView() {
  if(state.view==='feedback')return loadFeedback();
  if(state.view==='announcements')return loadAnnouncements();
  if(state.view==='chat'){renderChat();return pollChat();}
  if(state.view==='admin')return loadAdmin();
}
function routeView() {
  const hash=location.hash.slice(1);if(hash.startsWith('post/'))return 'post';return ['feed','feedback','announcements','chat','notifications','profile','mine'].includes(hash)?hash:isAdminPage?'admin':location.pathname==='/notifications'?'notifications':location.pathname==='/profile'?'profile':location.pathname==='/chat'?'chat':'feed';
}
async function switchView(view) {
  state.revision++;threadRequest++;state.view=view;hideModal();
  clearInterval(state.chatTimer);state.chatTimer=null;
  document.body.classList.toggle('chat-mode',view==='chat');
  document.querySelectorAll('[data-view]').forEach(button=>{if(button.dataset.view===view)button.setAttribute('aria-current','page');else button.removeAttribute('aria-current');});
  app.innerHTML='<div class="loading" role="status">正在载入…</div>';app.scrollTop=0;
  const revision=state.revision;
  try{await loadView();}catch(error){if(revision===state.revision){app.innerHTML='<div class="empty"><h2>暂时无法载入</h2><p>'+esc(error.message)+'</p><button class="button" type="button" data-action="refresh">重试</button></div>';}}
  if(view==='chat' && revision===state.revision)state.chatTimer=setInterval(()=>{if(!modal.open)pollChat();},12000);
}
app.addEventListener('click',async event=>{
  const target=event.target.closest('button');if(!target)return;
  try {
    if(target.hasAttribute('data-thread'))return openThread(target.dataset.thread);
    if(target.hasAttribute('data-filter')){state.mine=false;state.filter=target.dataset.filter;await loadFeedback();return;}
    if(target.hasAttribute('data-edit-announcement'))return openAnnouncementEditor(state.adminAnnouncements.find(item=>item.id===target.dataset.editAnnouncement));
    if(target.hasAttribute('data-moderate-message')){target.disabled=true;try{await write('/admin/api/messages/'+target.dataset.moderateMessage,{hidden:target.dataset.hidden!=='true'},undefined,'PATCH');await loadAdmin();}finally{target.disabled=false;}return;}
    const action=target.dataset.action;
    if(action==='compose')openFeedbackComposer();
    if(action==='refresh')await switchView(state.view);
    if(action==='refresh-chat'){await pollChat();}
    if(action==='new-announcement')openAnnouncementEditor();
    if(action==='mine'){
      if(!state.session){openIdentity(()=>{state.mine=true;state.filter='';loadFeedback().catch(error=>toast(error.message));});return;}
      state.mine=true;state.filter='';await loadFeedback();
    }
    if(action==='more'){if(state.feedbackBusy)return;state.feedbackBusy=true;target.disabled=true;try{await loadFeedback(true);}finally{state.feedbackBusy=false;target.disabled=false;}}
  } catch(error){toast(error.message);}
});
document.querySelectorAll('[data-view]').forEach(button=>button.onclick=()=>{const view=button.dataset.view;if(location.hash==='#'+view)switchView(view);else location.hash=view;});
window.addEventListener('hashchange',()=>switchView(routeView()));
document.getElementById('identity').onclick=()=>{
  if(state.admin){openModal('开发者','<p class="form-note">管理公告、反馈状态与消息。</p><div class="form-actions"><button class="button button-primary" id="open-admin" type="button">进入后台</button></div>');document.getElementById('open-admin').onclick=()=>switchView('admin');}
  else openIdentity();
};
function updateTheme() {
  if(state.theme==='system')document.documentElement.removeAttribute('data-theme');else document.documentElement.dataset.theme=state.theme;
  const button=document.getElementById('theme');const names={system:'跟随系统',light:'浅色',dark:'深色'};
  button.innerHTML=icon(state.theme==='light'?'sun':state.theme==='dark'?'moon':'system');
  button.setAttribute('aria-label','主题：'+names[state.theme]+'，点击切换');button.title='主题：'+names[state.theme];
}
try{const saved=localStorage.getItem('yunget-community-theme');if(['system','light','dark'].includes(saved))state.theme=saved;}catch{}
document.getElementById('theme').onclick=()=>{
  state.theme={system:'light',light:'dark',dark:'system'}[state.theme];updateTheme();
  try{localStorage.setItem('yunget-community-theme',state.theme);}catch{}
  if(modal.querySelector('.verification-wrap'))mountChallenge();
};
updateTheme();
function fitViewport() {
  // Remember intent before line wrapping changes the scroll height on rotation.
  const follow=state.chatFollow;const viewport=window.visualViewport;
  state.resizingChat=true;
  if(!viewport || viewport.scale===1){
    document.documentElement.style.setProperty('--viewport-height',(viewport?.height || innerHeight)+'px');
    document.documentElement.style.setProperty('--viewport-top',(viewport?.offsetTop || 0)+'px');
  }
  requestAnimationFrame(()=>{
    if(state.view==='chat' && follow)scrollChatEnd();
    requestAnimationFrame(()=>{state.resizingChat=false;});
  });
}
window.visualViewport?.addEventListener('resize',fitViewport);
window.visualViewport?.addEventListener('scroll',()=>{if(modal.open)fitViewport();});
window.addEventListener('resize',fitViewport);fitViewport();
document.addEventListener('visibilitychange',()=>{if(!document.hidden && state.view==='chat')pollChat();});
async function init() {
  try {
    const [config,session]=await Promise.all([request('/api/config'),request('/api/session')]);state.config=config;
    state.session=session.nickname?session:null;
    if(isAdminPage){await request('/admin/api/session');state.admin=true;}
    updateIdentity();await switchView(routeView());
  } catch(error){app.innerHTML='<div class="empty"><h1>社区暂未连接</h1><p>'+esc(error.message)+'</p><button type="button" id="retry-init" class="button">重新连接</button></div>';document.getElementById('retry-init').onclick=init;}
}
init();
`+socialScript;
