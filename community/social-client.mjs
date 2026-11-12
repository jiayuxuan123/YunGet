export const socialScript = String.raw`
Object.assign(paths, {
  bell:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4',
  heart:'M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8Z',
  photo:'M4 3h16v18H4zM4 16l5-5 4 4 3-3 4 4M8 7h.01',
  poll:'M5 20V10M12 20V4M19 20v-6',
  back:'m12 19-7-7 7-7M5 12h15',
  more:'M5 12h.01M12 12h.01M19 12h.01',
  user:'M20 21v-2a7 7 0 0 0-14 0v2M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z',
  send:'m22 2-7 20-4-9-9-4 20-7ZM22 2 11 13',
});
const socialState = {posts:new Map(), category:'', feedNext:null, feedBusy:false, observer:null,
  draft:null, replyDrafts:new Map(), commentPages:[], commentNext:null, commentPrevious:null,
  currentPost:null, commentsBusy:false, inbox:null, expanded:new Set(), busy:new Set(), activeDraft:null};
const socialDraftStoreKey='yunget-social-'+'drafts-v1',socialResumeKey='yunget-social-compose-v1';
const socialApi = path => (state.admin?'/admin':'')+'/api/'+path;
const socialTimeFormat = new Intl.DateTimeFormat('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'});
const socialLabels = {discussion:'讨论',question:'提问',idea:'建议',bug:'问题'};
function socialTime(value){const delta=Math.max(0,Date.now()/1000-value);return delta<60?'刚刚':delta<3600?Math.floor(delta/60)+' 分钟前':delta<86400?Math.floor(delta/3600)+' 小时前':socialTimeFormat.format(new Date(value*1000));}
function socialAvatar(author){const tone=(parseInt(author.uid?.slice(-2)||'0',16)||0)%5;return '<span class="social-avatar tone-'+tone+(author.developer?' official-avatar':'')+'" aria-hidden="true">'+(author.developer?icon('chat'):esc(Array.from(author.nickname||'云')[0]))+'</span>';}
function socialAuthor(author){return '<span class="social-name">'+esc(author.nickname)+(author.developer?'<span class="verified-mark" aria-label="开发者">✓</span>':'')+'</span><span class="social-uid">@'+esc(author.uid)+'</span>';}
function socialBody(body,full=false,id=''){const long=!full&&body.length>360&&!socialState.expanded.has(id);return '<div class="social-body'+(long?' truncated':'')+'">'+esc(body).replace(/\n/g,'<br>')+'</div>'+(long?'<button class="text-button" data-social-expand="'+esc(id)+'">展开全文</button>':'');}
function socialImages(images){if(!images?.length)return '';return '<div class="post-images images-'+images.length+'">'+images.map((image,index)=>'<button type="button" class="post-image" data-social-image="'+esc(image.url)+'" aria-label="查看图片 '+(index+1)+'"><img loading="lazy" decoding="async" src="'+esc(image.url)+'" width="'+image.width+'" height="'+image.height+'" alt="帖子图片 '+(index+1)+'"></button>').join('')+'</div>';}
function socialPoll(post){
  const poll=post.poll;if(!poll)return '';const show=Boolean(poll.yourVote)||poll.closed;
  return '<div class="post-poll" role="group" aria-label="投票">'+poll.options.map(option=>{
    const percent=poll.total?Math.round(option.votes/poll.total*100):0;
    return '<button type="button" class="poll-option'+(poll.yourVote===option.id?' selected':'')+'" data-social-vote="'+post.id+'" data-option="'+option.id+'" aria-pressed="'+(poll.yourVote===option.id)+'"'+(poll.closed?' disabled':'')+'><span class="poll-meter" data-percent="'+percent+'"></span><span>'+esc(option.label)+(poll.yourVote===option.id?' ✓':'')+'</span>'+(show?'<strong>'+percent+'%</strong>':'')+'</button>';
  }).join('')+'<p class="poll-meta">'+poll.total+' 票 · '+(poll.closed?'投票已结束':poll.yourVote?'已投票，截止前可更改':'截止 '+socialTimeFormat.format(new Date(poll.expiresAt*1000)))+'</p></div>';
}
function socialPost(post,detail=false){
  socialState.posts.set(post.id,post);
  return '<article class="social-post" data-post-card="'+post.id+'">'+(post.pinned?'<div class="post-pin">置顶</div>':'')+'<div class="post-grid">'+socialAvatar(post.author)+'<div class="post-main"><div class="post-byline"><div>'+socialAuthor(post.author)+'</div><a class="post-time" href="#post/'+post.id+'">'+esc(socialTime(post.createdAt))+'</a>'+(post.mine||state.admin?'<button class="icon-button post-menu" data-social-menu="'+post.id+'" aria-label="管理帖子">'+icon('more')+'</button>':'')+'</div>'+(post.category!=='discussion'?'<span class="post-category">'+socialLabels[post.category]+(post.category==='bug'||post.category==='idea'?' · '+(labels[post.status]||post.status):'')+'</span>':'')+socialBody(post.body,detail,post.id)+socialImages(post.images)+socialPoll(post)+'<div class="post-actions"><button type="button" data-social-open="'+post.id+'" aria-label="'+post.commentCount+' 条评论">'+icon('chat')+'<span>'+post.commentCount+'</span></button><button type="button" data-social-like="'+post.id+'" class="'+(post.liked?'liked':'')+'" aria-pressed="'+post.liked+'" aria-label="点赞">'+icon('heart')+'<span>'+post.likeCount+'</span></button><button type="button" data-social-share="'+post.id+'" aria-label="复制帖子链接">'+icon('send')+'</button>'+(detail?'<button class="reply-link" type="button" data-social-comment="'+post.id+'">写评论</button>':'')+'</div></div></div></article>';
}
function socialMeters(){app.querySelectorAll('.poll-meter').forEach(node=>node.style.setProperty('--percent',Number(node.dataset.percent)+'%'));}
function socialHeading(title,extra=''){return '<div class="timeline-heading"><h1>'+title+'</h1>'+extra+'</div>';}
function socialEmpty(title,body){return '<div class="social-empty">'+icon('chat')+'<h2>'+title+'</h2><p>'+body+'</p></div>';}
function socialObserve(callback){socialState.observer?.disconnect();const sentinel=document.getElementById('stream-sentinel');if(!sentinel)return;socialState.observer=new IntersectionObserver(entries=>{if(entries.some(x=>x.isIntersecting))callback();},{root:app,rootMargin:'240px'});socialState.observer.observe(sentinel);}
async function socialLoadFeed(append=false,mine=false){
  if(socialState.feedBusy)return;socialState.feedBusy=true;const revision=state.revision;
  try{
    const params=new URLSearchParams({limit:'20'});if(append&&socialState.feedNext)params.set('cursor',socialState.feedNext);
    if(socialState.category)params.set('category',socialState.category);if(mine)params.set('mine','1');
    const data=await request(socialApi('posts')+'?'+params);if(revision!==state.revision)return;
    if(!append){
      app.innerHTML=socialHeading(mine?'我的帖子':'社区','<button class="icon-button" data-social-refresh aria-label="刷新信息流">'+icon('refresh')+'</button>')+
        (!mine?'<button class="composer-prompt" data-social-compose>'+socialAvatar(state.session||{nickname:'访客',uid:'0'})+'<span>分享想法，或者提个问题</span><span class="compose-mark">'+icon('plus')+'</span></button><div class="feed-filters" role="group" aria-label="帖子分类">'+[['','全部'],['discussion','讨论'],['question','提问'],['idea','建议'],['bug','问题']].map(([value,label])=>'<button class="filter-chip'+(socialState.category===value?' active':'')+'" data-social-filter="'+value+'" aria-pressed="'+(socialState.category===value)+'">'+label+'</button>').join('')+'</div>':'')+
        '<section id="post-stream" aria-label="帖子列表"></section><div id="stream-sentinel"></div>';
    }
    const stream=document.getElementById('post-stream');if(!stream)return;
    if(!data.items.length&&!append)stream.innerHTML=socialEmpty('等一条新的分享','发帖、提问、建议，都可以从这里开始。');
    else stream.insertAdjacentHTML('beforeend',data.items.map(post=>socialPost(post)).join(''));
    while(stream.children.length>60)stream.firstElementChild.remove();
    socialState.feedNext=data.next;
    const sentinel=document.getElementById('stream-sentinel');sentinel.innerHTML=data.next?'<button class="button" data-social-more>加载更多</button>':'<p class="stream-end">已经看完了</p>';
    socialMeters();socialObserve(()=>{if(socialState.feedNext&&!socialState.feedBusy)socialLoadFeed(true,mine).catch(error=>toast(error.message));});socialResumeComposer();
  }finally{if(revision===state.revision)socialState.feedBusy=false;}
}
function socialComment(row){return '<article class="social-comment" data-comment="'+row.id+'">'+socialAvatar(row.author)+'<div class="comment-main"><div class="post-byline"><div>'+socialAuthor(row.author)+'</div><time>'+esc(socialTime(row.createdAt))+'</time></div>'+(row.parent?'<div class="reply-target">回复 '+esc(row.parent.nickname||'用户')+' · '+esc(row.parent.preview||'原评论已删除')+'</div>':'')+socialBody(row.body,true)+socialImages(row.images)+'<div class="comment-actions"><button class="text-button" data-social-reply="'+row.id+'">回复</button>'+(row.mine||state.admin?'<button class="text-button muted" data-social-delete-comment="'+row.id+'">删除</button>':'')+'</div></div></article>';}
async function socialLoadComments({after=null,before=null,around=null,reset=false}={}){
  if(socialState.commentsBusy)return;socialState.commentsBusy=true;const revision=state.revision;
  try{
    const params=new URLSearchParams({limit:'30'});if(after)params.set('after',after);if(before)params.set('before',before);if(around)params.set('around',around);
    const data=await request(socialApi('posts/'+socialState.currentPost+'/comments')+'?'+params);if(revision!==state.revision)return;
    const list=document.getElementById('comment-stream');if(!list)return;
    const anchor=[...list.querySelectorAll('[data-comment]')].find(node=>node.getBoundingClientRect().bottom>app.getBoundingClientRect().top+60);
    const anchorId=anchor?.dataset.comment,anchorTop=anchor?.getBoundingClientRect().top;
    if(reset)socialState.commentPages=[data];else if(before)socialState.commentPages.unshift(data);else socialState.commentPages.push(data);
    while(socialState.commentPages.length>6){if(before)socialState.commentPages.pop();else socialState.commentPages.shift();}
    const rows=socialState.commentPages.flatMap(page=>page.items);
    socialState.commentPrevious=socialState.commentPages[0]?.previous;socialState.commentNext=socialState.commentPages.at(-1)?.next;
    list.innerHTML=rows.length?rows.map(socialComment).join(''):socialEmpty('还没有评论','说说你的想法。');
    document.getElementById('comments-before').innerHTML=socialState.commentPrevious?'<button class="button" data-social-older>查看更早的评论</button>':'';
    document.getElementById('stream-sentinel').innerHTML=socialState.commentNext?'<button class="button" data-social-next-comments>加载更多评论</button>':'<p class="stream-end">已显示全部评论</p>';
    if(!reset&&anchorId){const next=list.querySelector('[data-comment="'+anchorId+'"]');if(next)app.scrollTop+=next.getBoundingClientRect().top-anchorTop;}
    if(around){const target=list.querySelector('[data-comment="'+around+'"]');if(target){target.classList.add('highlight-comment');target.scrollIntoView({block:'center'});}}
    socialObserve(()=>{if(socialState.commentNext&&!socialState.commentsBusy)socialLoadComments({after:socialState.commentNext}).catch(error=>toast(error.message));});
  }finally{if(revision===state.revision)socialState.commentsBusy=false;}
}
async function socialLoadPost(){
  const route=location.hash.slice(1).split('/'),postId=route[1],around=route[2]||null;
  if(!/^p_[a-f0-9]{32}$/.test(postId||'')||around&&!/^c_[a-f0-9]{32}$/.test(around))throw Error('帖子地址无效');
  const revision=state.revision,data=await request(socialApi('posts/'+postId));if(revision!==state.revision)return;
  socialState.currentPost=postId;socialState.commentPages=[];socialState.commentsBusy=false;
  app.innerHTML=socialHeading('<a class="icon-button" href="#feed" aria-label="返回社区">'+icon('back')+'</a>帖子','<button class="button button-primary compact" data-social-comment="'+postId+'">写评论</button>')+socialPost(data.post,true)+'<div class="comments-heading"><h2>评论 <span>'+data.post.commentCount+'</span></h2></div><div id="comments-before"></div><section id="comment-stream" aria-label="评论流"></section><div id="stream-sentinel"></div>';
  socialMeters();await socialLoadComments({around,reset:true});socialResumeComposer();
}
async function socialReplacePost(postId){
  const data=await request(socialApi('posts/'+postId)),old=app.querySelector('[data-post-card="'+postId+'"]');
  if(old){const box=document.createElement('div');box.innerHTML=socialPost(data.post,state.view==='post');old.replaceWith(box.firstElementChild);socialMeters();}
  return data.post;
}
function socialGoPost(postId,commentId=null){location.hash='post/'+postId+(commentId?'/'+commentId:'');}
async function socialCopy(value){
  try{await navigator.clipboard.writeText(value);toast('已复制');}
  catch{const input=document.createElement('textarea');input.value=value;input.className='copy-buffer';document.body.append(input);input.select();document.execCommand('copy');input.remove();toast('已复制');}
}
function socialConfirm(title,description,action){openModal(title,'<p class="form-note">'+esc(description)+'</p><div class="form-actions"><button class="button" data-dismiss>取消</button><button class="button danger" id="social-confirm">确认</button></div>');document.getElementById('social-confirm').onclick=async event=>{event.target.disabled=true;try{await action();hideModal();}catch(error){toast(error.message);event.target.disabled=false;}};}
function socialEnsure(continuation){if(state.session||state.admin){continuation();return true;}openIdentity(continuation);return false;}

function socialDraft(postId=null,parentId=null){
  if(!postId){if(!socialState.draft)socialState.draft={body:'',category:'discussion',product:'yunget',images:[],poll:false,options:['',''],hours:168,key:crypto.randomUUID()};return socialState.draft;}
  const key=postId+':'+(parentId||'');if(!socialState.replyDrafts.has(key))socialState.replyDrafts.set(key,{body:'',images:[],key:crypto.randomUUID()});
  return socialState.replyDrafts.get(key);
}
function socialStoredDraft(draft){
  const {blob,...rest}=draft;
  return {...rest,images:draft.images.filter(item=>item.id).map(item=>({id:item.id,url:item.url,key:item.key}))};
}
function socialPersistDrafts(){
  try{
    const replies={};for(const [key,draft] of socialState.replyDrafts)replies[key]=socialStoredDraft(draft);
    localStorage.setItem(socialDraftStoreKey,JSON.stringify({draft:socialState.draft?socialStoredDraft(socialState.draft):null,replies}));
  }catch{}
}
function socialRestoreDrafts(){
  try{
    const data=JSON.parse(localStorage.getItem(socialDraftStoreKey)||'null');
    if(data?.draft)socialState.draft=data.draft;
    if(data?.replies)for(const [key,draft] of Object.entries(data.replies))socialState.replyDrafts.set(key,draft);
  }catch{}
}
function socialRememberComposer(postId=null,parentId=null){
  try{localStorage.setItem(socialResumeKey,JSON.stringify({postId:postId||null,parentId:parentId||null}));}catch{}
}
function socialClearComposer(){
  try{localStorage.removeItem(socialResumeKey);}catch{}
}
function socialResumeComposer(){
  let resume=null;try{resume=JSON.parse(localStorage.getItem(socialResumeKey)||'null');}catch{}
  if(!resume)return;socialClearComposer();
  if(resume.postId&&!/^p_[a-f0-9]{32}$/.test(resume.postId))return;
  if(resume.parentId&&!/^c_[a-f0-9]{32}$/.test(resume.parentId))return;
  socialRememberComposer(resume.postId,resume.parentId);
  socialOpenComposer(resume.postId,resume.parentId,false);
}
socialRestoreDrafts();
function socialDraftImages(draft){return '<div class="draft-images" id="draft-images">'+draft.images.map((item,index)=>'<div class="draft-image"><img src="'+esc(item.url)+'" alt="待发布图片 '+(index+1)+'"><button class="icon-button" type="button" data-remove-draft-image="'+index+'" aria-label="移除图片 '+(index+1)+'">'+icon('close')+'</button></div>').join('')+'</div>';}
async function socialCompress(file){
  if(!file.type.startsWith('image/')||file.size>20*1024*1024)throw Error('请选择 20 MB 以内的图片');
  const source=URL.createObjectURL(file),image=new Image();
  try{
    await new Promise((resolve,reject)=>{image.onload=resolve;image.onerror=()=>reject(Error('无法读取这张图片'));image.src=source;});
    let scale=Math.min(1,1440/image.naturalWidth,1440/image.naturalHeight),blob;
    for(let attempt=0;attempt<5;attempt++){
      const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(image.naturalWidth*scale));canvas.height=Math.max(1,Math.round(image.naturalHeight*scale));
      canvas.getContext('2d').drawImage(image,0,0,canvas.width,canvas.height);
      blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/webp',0.8));
      if(blob&&blob.size<=480*1024)return blob;scale*=0.72;
    }
    throw Error('图片仍然过大，请先缩小图片');
  }finally{URL.revokeObjectURL(source);}
}
async function socialUpload(item){
  if(item.id)return item.id;
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),20000);
  try{
    const response=await fetch(socialApi('media'),{method:'POST',credentials:'same-origin',headers:{'Content-Type':item.blob.type,'Idempotency-Key':item.key},body:item.blob,signal:controller.signal});
    const data=await response.json();if(!response.ok)throw Error(data.error||'图片上传失败');item.id=data.id;return data.id;
  }finally{clearTimeout(timer);}
}
function socialOpenComposer(postId=null,parentId=null,remember=true){
  const reply=Boolean(postId),draft=socialDraft(postId,parentId),maxImages=reply?2:4;
  socialState.activeDraft={draft,postId,parentId,maxImages};
  if(remember)socialRememberComposer(postId,parentId);
  const options=reply?'':'<div class="composer-selects"><label>分类<select name="category">'+Object.entries(socialLabels).map(([value,label])=>'<option value="'+value+'"'+(value===draft.category?' selected':'')+'>'+label+'</option>').join('')+'</select></label><label>项目<select name="product"><option value="yunget"'+(draft.product==='yunget'?' selected':'')+'>云取</option><option value="turbodl"'+(draft.product==='turbodl'?' selected':'')+'>TurboDL</option></select></label></div>';
  const poll=reply?'':'<div id="poll-editor" class="poll-editor"'+(!draft.poll?' hidden':'')+'><div class="panel-head"><strong>投票选项</strong><button type="button" class="text-button" id="remove-poll">移除投票</button></div><div id="poll-options">'+draft.options.map((value,index)=>'<label class="poll-input-label">选项 '+(index+1)+'<input class="poll-input" maxlength="60" value="'+esc(value)+'" data-poll-index="'+index+'" placeholder="填写选项"></label>').join('')+'</div><div class="poll-editor-footer"><button type="button" class="text-button" id="add-poll-option"'+(draft.options.length>=6?' disabled':'')+'>添加选项</button><label>时长<select id="poll-hours">'+[[24,'1 天'],[72,'3 天'],[168,'7 天'],[336,'14 天']].map(([value,label])=>'<option value="'+value+'"'+(value===draft.hours?' selected':'')+'>'+label+'</option>').join('')+'</select></label></div></div>';
  openModal(reply?(parentId?'回复评论':'写评论'):'发布帖子','<form id="social-composer"><p class="composer-identity">'+socialAvatar(state.session||{nickname:state.admin?'开发者':'访客',uid:'0',developer:state.admin})+'<span>'+esc(state.admin?'开发者':state.session?.nickname||'尚未设置昵称')+'</span></p>'+options+'<label class="sr-only" for="post-body">'+(reply?'评论内容':'帖子内容')+'</label><textarea id="post-body" name="body" maxlength="'+(reply?2000:3000)+'" rows="5" placeholder="'+(reply?'说说你的想法':'分享想法、提问，或者描述遇到的问题')+'">'+esc(draft.body)+'</textarea>'+socialDraftImages(draft)+poll+'<p class="form-note small">请勿公开账号凭据或私有下载链接。图片会自动压缩。</p><p class="form-error" role="alert"></p><div class="composer-toolbar"><div><label class="icon-button file-button" aria-label="添加图片">'+icon('photo')+'<input id="social-images" type="file" accept="image/*" multiple></label>'+(!reply?'<button class="icon-button" id="toggle-poll" type="button" aria-label="添加投票">'+icon('poll')+'</button>':'')+'</div><span id="composer-count" class="muted small">'+draft.body.length+'/'+(reply?2000:3000)+'</span><button class="button button-primary" type="submit">'+(reply?'发表评论':'发布')+'</button></div></form>');
  const form=document.getElementById('social-composer'),revision=state.modalRevision;
  form.elements.body.oninput=()=>{draft.body=form.elements.body.value;draft.key=crypto.randomUUID();document.getElementById('composer-count').textContent=draft.body.length+'/'+(reply?2000:3000);socialPersistDrafts();};
  if(!reply){
    form.elements.category.onchange=()=>{draft.category=form.elements.category.value;socialPersistDrafts();};form.elements.product.onchange=()=>{draft.product=form.elements.product.value;socialPersistDrafts();};
    document.getElementById('toggle-poll').onclick=()=>{draft.poll=!draft.poll;socialPersistDrafts();socialOpenComposer();};
    document.getElementById('remove-poll').onclick=()=>{draft.poll=false;socialPersistDrafts();socialOpenComposer();};
    document.getElementById('add-poll-option').onclick=()=>{if(draft.options.length<6){draft.options.push('');socialOpenComposer();}};
    form.querySelectorAll('[data-poll-index]').forEach(input=>input.oninput=()=>{draft.options[Number(input.dataset.pollIndex)]=input.value;socialPersistDrafts();});
    document.getElementById('poll-hours').onchange=event=>{draft.hours=Number(event.target.value);socialPersistDrafts();};
  }
  document.getElementById('social-images').onchange=async event=>{
    const files=[...event.target.files];if(draft.images.length+files.length>maxImages){toast('最多添加 '+maxImages+' 张图片');return;}
    const submit=form.querySelector('[type=submit]');submit.disabled=true;
    try{for(const file of files){const blob=await socialCompress(file),item={blob,url:URL.createObjectURL(blob),key:crypto.randomUUID()};draft.images.push(item);if(state.session||state.admin){item.id=await socialUpload(item);item.url=(state.admin?'/admin':'')+'/media/'+item.id;delete item.blob;}}socialPersistDrafts();if(revision===state.modalRevision)socialOpenComposer(postId,parentId);}
    catch(error){showFormError(form,error);}finally{submit.disabled=false;}
  };
  form.querySelectorAll('[data-remove-draft-image]').forEach(button=>button.onclick=()=>{
    const [item]=draft.images.splice(Number(button.dataset.removeDraftImage),1);URL.revokeObjectURL(item.url);
    if(item.id)fetch((state.admin?'/admin':'')+'/media/'+item.id,{method:'DELETE',credentials:'same-origin'}).catch(()=>{});
    socialPersistDrafts();socialOpenComposer(postId,parentId);
  });
  form.onsubmit=async event=>{
    event.preventDefault();draft.body=form.elements.body.value;
    if(!draft.body.trim()&&!draft.images.length){showFormError(form,Error('请填写内容或添加图片'));return;}
    if(!reply&&draft.poll&&(draft.options.some(value=>!value.trim())||new Set(draft.options.map(x=>x.trim())).size!==draft.options.length)){showFormError(form,Error('请填写至少两个不同的投票选项'));return;}
    if(!state.session&&!state.admin){openIdentity(()=>socialOpenComposer(postId,parentId),()=>socialOpenComposer(postId,parentId));return;}
    const button=form.querySelector('[type=submit]');button.disabled=true;button.textContent='正在准备…';
    try{
      const images=[];for(const item of draft.images)images.push(await socialUpload(item));
      if(revision!==state.modalRevision)return;
      const body={body:draft.body,images,...(reply?{parentId}:{category:draft.category,product:draft.product,poll:draft.poll?{options:draft.options,durationHours:draft.hours}:null})};
      verifyAction({title:reply?'发表评论':'发布帖子',path:socialApi(reply?'posts/'+postId+'/comments':'posts'),body,key:draft.key,admin:state.admin,autoSubmit:true,
        back:()=>socialOpenComposer(postId,parentId),success:reply?'评论已发布':'帖子已发布',
      done:async result=>{draft.images.forEach(item=>{if(String(item.url).startsWith('blob:'))URL.revokeObjectURL(item.url)});if(reply)socialState.replyDrafts.delete(postId+':'+(parentId||''));else socialState.draft=null;socialState.activeDraft=null;socialClearComposer();socialPersistDrafts();socialGoPost(reply?postId:result.id,reply?result.id:null);}
      });
    }catch(error){if(revision===state.modalRevision)showFormError(form,error);}
    finally{button.disabled=false;button.textContent=reply?'发表评论':'发布';}
  };
}
function offerRecovery(code,continuation=null){
  openModal('保存社区找回码','<p class="form-note">换手机或清除数据后，可用它恢复同一个 UID 和私密反馈。持有码即可登录，请勿公开。</p><label class="field">找回码<input id="recovery-code" type="password" readonly spellcheck="false" value="'+esc(code)+'"></label><div class="form-actions"><button class="button" id="copy-recovery" type="button">复制找回码</button><button class="button button-primary" id="finish-recovery" type="button">已保存，继续</button></div>',{back:continuation});
  document.getElementById('copy-recovery').onclick=()=>socialCopy(code);document.getElementById('finish-recovery').onclick=()=>{hideModal();continuation?.();};
}
function socialRecover(){
  openModal('恢复社区身份','<form id="recover-form"><p class="form-note">粘贴之前保存的找回码，不需要手机号或邮箱。</p><label class="field">找回码<input name="code" type="password" autocomplete="off" spellcheck="false" required placeholder="粘贴找回码"></label>'+challengeMarkup('session')+'<div class="form-actions"><button class="button button-primary" type="submit">恢复登录</button></div></form>');mountChallenge();
  document.getElementById('recover-form').onsubmit=async event=>{
    event.preventDefault();const form=event.currentTarget;if(!state.challenge){showFormError(form,Error('请等待验证完成'));return;}
    const button=form.querySelector('button[type=submit]');button.disabled=true;
    try{state.session=await write('/api/account/recover',{recoveryCode:form.elements.code.value.trim(),challenge:state.challenge});hideModal();updateIdentity();await socialLoadProfile();await socialRefreshBadge();toast('已恢复原来的社区身份');}
    catch(error){showFormError(form,error);mountChallenge();}finally{button.disabled=false;}
  };
}
async function socialLoadProfile(){
  const revision=state.revision,who=await request('/api/session');if(revision!==state.revision)return;state.session=who.nickname?who:null;updateIdentity();
  if(!state.session&&!state.admin){app.innerHTML=socialHeading('我的')+'<section class="profile-card">'+socialAvatar({nickname:'云',uid:'0'})+'<h2>来社区聊聊</h2><p>选一个昵称，系统会为你分配独立 UID。可重复昵称，无需手机号或邮箱。</p><button class="button button-primary" data-social-login>设置昵称，开始使用</button><button class="text-button" data-social-recover>已有找回码</button><button class="text-button" data-view="chat">进入旧闲聊</button></section>';return;}
  const author=state.admin?{nickname:'开发者',uid:'000000000001',developer:true}:state.session;
  app.innerHTML=socialHeading('我的')+'<section class="profile-card">'+socialAvatar(author)+'<h2>'+esc(author.nickname)+'</h2><p class="profile-uid">UID '+esc(author.uid)+'</p><div class="profile-actions"><button class="button" data-social-nickname>修改昵称</button><button class="button" data-social-recovery>保存 / 更新找回码</button></div><p class="form-note">社区身份独立于网盘账号。找回码可恢复身份，昵称和 UID 本身不能用于登录。</p></section><div class="profile-links"><button data-social-mine>我的帖子'+icon('arrow')+'</button><button data-view="feedback">我的反馈与处理进度'+icon('arrow')+'</button><a href="#chat">旧闲聊'+icon('arrow')+'</a><a href="#announcements">公告'+icon('arrow')+'</a><a href="/admin">开发者后台'+icon('arrow')+'</a><button data-social-logout class="danger">退出当前社区身份</button></div>';
}
async function socialRefreshBadge(){
  try{const data=await request(socialApi('notifications/unread'));document.querySelectorAll('[data-notification-badge]').forEach(node=>{node.textContent=data.unread>99?'99+':String(data.unread||'');node.hidden=!data.unread;});}catch{}
}
async function socialLoadInbox(append=false){
  const revision=state.revision,data=await request(socialApi('notifications')+(append&&socialState.inbox?.next?'?before='+socialState.inbox.next:''));if(revision!==state.revision)return;
  if(append){socialState.inbox.items.push(...data.items);socialState.inbox.next=data.next;}
  else socialState.inbox=data;
  const inbox=socialState.inbox;
  const kinds={comment:'评论了你的帖子',reply:'回复了你的评论',post_status:'更新了帖子状态',feedback_status:'更新了反馈状态',feedback_reply:'回复了你的反馈'};
  app.innerHTML=socialHeading('通知','<button class="text-button" data-social-read-all>标为已读</button>')+'<div class="notification-list">'+inbox.announcements.map(item=>'<button class="notification-item unread" data-social-announcement="'+esc(item.id)+'"><span class="notification-icon">'+icon('chat')+'</span><span><strong>公告 · '+esc(item.title)+'</strong><span class="notification-preview">'+esc(item.body.replace(/[#*]/g,'').slice(0,100))+'</span><time>'+esc(item.date)+'</time></span><i class="unread-dot"></i></button>').join('')+inbox.items.map(item=>'<button class="notification-item'+(!item.read?' unread':'')+'" data-social-notification="'+item.id+'"><span class="notification-icon">'+icon(item.kind.includes('status')?'idea':'chat')+'</span><span><strong>'+esc(item.actor?.nickname||'开发者')+' '+(kinds[item.kind]||'发来通知')+'</strong><span class="notification-preview">'+esc(labels[item.preview]||item.preview||'点击查看')+'</span><time>'+esc(socialTime(item.createdAt))+'</time></span>'+(!item.read?'<i class="unread-dot"></i>':'')+'</button>').join('')+'</div>'+(!inbox.items.length&&!inbox.announcements.length?socialEmpty('暂时没有新通知','社区回复和反馈进度会出现在这里。'):'')+(inbox.next?'<div class="load-more"><button class="button" data-social-more-notifications>加载更多通知</button></div>':'');
  await socialRefreshBadge();
}
async function socialReadNotifications(ids=[],announcements=[],throughId=0){
  if(!state.session&&!state.admin)return;
  await write(socialApi('notifications/read'),{ids,announcements,throughId});await socialRefreshBadge();
}
function socialManage(postId){
  const post=socialState.posts.get(postId);if(!post)return;
  openModal('帖子选项','<div class="post-management">'+(state.admin?'<button class="button" data-post-toggle="pinned">'+(post.pinned?'取消置顶':'置顶帖子')+'</button><button class="button" data-post-toggle="locked">'+(post.locked?'开放评论':'关闭评论')+'</button><label class="field">处理状态<select id="post-status">'+Object.entries(labels).filter(([value])=>['new','planned','working','resolved','closed'].includes(value)).map(([value,label])=>'<option value="'+value+'"'+(post.status===value?' selected':'')+'>'+label+'</option>').join('')+'</select></label>':'')+'<button class="button danger" id="delete-social-post">删除帖子</button></div>');
  modal.querySelectorAll('[data-post-toggle]').forEach(button=>button.onclick=async()=>{try{const name=button.dataset.postToggle;await write(socialApi('posts/'+postId+'/moderate'),{[name]:!post[name]},undefined,'PATCH');hideModal();await socialReplacePost(postId);}catch(error){toast(error.message);}});
  const status=document.getElementById('post-status');if(status)status.onchange=async()=>{try{await write(socialApi('posts/'+postId+'/moderate'),{status:status.value},undefined,'PATCH');hideModal();await socialReplacePost(postId);}catch(error){toast(error.message);}};
  document.getElementById('delete-social-post').onclick=()=>socialConfirm('删除这条帖子？','删除后不再公开显示。',async()=>{await write(socialApi('posts/'+postId),{},undefined,'DELETE');location.hash='feed';});
}
const previousLoadView=loadView;
loadView=async function(){
  socialState.observer?.disconnect();document.body.dataset.communityView=state.view;
  if(state.view==='feed'){socialState.feedBusy=false;socialState.feedNext=null;return socialLoadFeed();}
  if(state.view==='mine'){socialState.feedBusy=false;socialState.feedNext=null;return socialLoadFeed(false,true);}
  if(state.view==='post')return socialLoadPost();
  if(state.view==='notifications')return socialLoadInbox();
  if(state.view==='profile')return socialLoadProfile();
  return previousLoadView();
};
document.addEventListener('click',async event=>{
  const button=event.target.closest('button,a,label');if(!button)return;
  try{
    if(button.hasAttribute('data-social-compose'))return socialOpenComposer();
    if(button.dataset.socialOpen)return socialGoPost(button.dataset.socialOpen);
    if(button.dataset.socialComment)return socialOpenComposer(button.dataset.socialComment);
    if(button.dataset.socialReply)return socialOpenComposer(socialState.currentPost,button.dataset.socialReply);
    if(button.hasAttribute('data-social-filter')){socialState.category=button.dataset.socialFilter;socialState.feedNext=null;return socialLoadFeed();}
    if(button.hasAttribute('data-social-refresh'))return switchView(state.view);
    if(button.hasAttribute('data-social-more'))return socialLoadFeed(true,state.view==='mine');
    if(button.hasAttribute('data-social-older'))return socialLoadComments({before:socialState.commentPrevious});
    if(button.hasAttribute('data-social-next-comments'))return socialLoadComments({after:socialState.commentNext});
    if(button.dataset.socialExpand){socialState.expanded.add(button.dataset.socialExpand);return socialReplacePost(button.dataset.socialExpand);}
    if(button.dataset.socialShare)return socialCopy(location.origin+'/#post/'+button.dataset.socialShare);
    if(button.dataset.socialMenu)return socialManage(button.dataset.socialMenu);
    if(button.dataset.socialImage){const image=button.dataset.socialImage;if(!/^\/(?:admin\/)?media\/m_[a-f0-9]{32}$/.test(image))return;openModal('查看图片','<img class="lightbox-image" src="'+esc(image)+'" alt="帖子图片">');return;}
    if(button.dataset.socialLike||button.dataset.socialVote){
      const postId=button.dataset.socialLike||button.dataset.socialVote,post=socialState.posts.get(postId);if(socialState.busy.has(postId))return;
      return socialEnsure(async()=>{
        socialState.busy.add(postId);button.disabled=true;
        try{await write(socialApi('posts/'+postId+(button.dataset.socialLike?'/like':'/vote')),button.dataset.socialLike?{liked:!post.liked}:{optionId:button.dataset.option});await socialReplacePost(postId);}
        catch(error){toast(error.message);}finally{socialState.busy.delete(postId);button.disabled=false;}
      });
    }
    if(button.dataset.socialDeleteComment)return socialConfirm('删除这条评论？','删除后不再显示在评论流中。',async()=>{await write(socialApi('posts/'+socialState.currentPost+'/comments'),{id:button.dataset.socialDeleteComment},undefined,'DELETE');await socialLoadComments({reset:true});await socialReplacePost(socialState.currentPost);});
    if(button.hasAttribute('data-social-login'))return openIdentity(()=>socialLoadProfile());
    if(button.hasAttribute('data-social-nickname'))return openIdentity(()=>socialLoadProfile());
    if(button.hasAttribute('data-social-recover'))return socialRecover();
    if(button.hasAttribute('data-social-mine')){socialState.category='';location.hash='mine';return;}
    if(button.hasAttribute('data-social-recovery'))return socialEnsure(()=>verifyAction({title:'生成新的找回码',path:'/api/account/recovery',body:{},key:crypto.randomUUID(),admin:false,success:'找回码已更新',done:async result=>offerRecovery(result.recoveryCode)}));
    if(button.hasAttribute('data-social-logout'))return socialConfirm('退出社区身份？','请先保存找回码，退出后可用它恢复。网盘账号不受影响。',async()=>{await write('/api/account/logout',{});state.session=null;updateIdentity();await socialLoadProfile();});
    if(button.hasAttribute('data-social-more-notifications'))return socialLoadInbox(true);
    if(button.hasAttribute('data-social-read-all'))return socialEnsure(async()=>{const data=socialState.inbox;await socialReadNotifications([],data.announcements.map(x=>({id:x.id,revision:x.revision})),data.throughId);await socialLoadInbox();});
    if(button.dataset.socialAnnouncement){const item=socialState.inbox?.announcements.find(x=>x.id===button.dataset.socialAnnouncement);if(item)await socialReadNotifications([],[{id:item.id,revision:item.revision}]);location.hash='announcements';return;}
    if(button.dataset.socialNotification){const item=socialState.inbox?.items.find(x=>x.id===Number(button.dataset.socialNotification));if(!item)return;await socialReadNotifications([item.id]);if(item.postId)socialGoPost(item.postId,item.commentId);else if(item.feedbackId)await openThread(item.feedbackId);return;}
    if(button.dataset.view&&!button.closest('.primary-nav')){location.hash=button.dataset.view;return;}
  }catch(error){toast(error.message);}
});
document.getElementById('identity').onclick=()=>{location.hash='profile';};
if(new URLSearchParams(location.search).get('embed')==='app')document.body.classList.add('embedded-community');
setInterval(()=>{if(!document.hidden)socialRefreshBadge();},60000);
document.addEventListener('visibilitychange',()=>{if(!document.hidden)socialRefreshBadge();});
socialRefreshBadge();
window.yungetReceivePickedFiles=files=>{
  let attempts=0;
  const attach=async()=>{
    const active=socialState.activeDraft;
    if(!active||!document.getElementById('social-composer')){
      if(attempts++<40)setTimeout(attach,250);
      return false;
    }
    try{
      for(const dataUrl of files){
        const match=/^data:(image\/(?:webp|png|jpeg));base64,(.+)$/.exec(String(dataUrl));
        if(!match)continue;
        const bytes=Uint8Array.from(atob(match[2]),character=>character.charCodeAt(0));
        const original=new File([bytes],'picked-image',{type:match[1]});
        const blob=await socialCompress(original),item={blob,url:URL.createObjectURL(blob),key:crypto.randomUUID()};
        active.draft.images.push(item);
        if(state.session||state.admin){
          item.id=await socialUpload(item);item.url=(state.admin?'/admin':'')+'/media/'+item.id;delete item.blob;
        }
      }
      socialPersistDrafts();socialOpenComposer(active.postId,active.parentId);
      return true;
    }catch(error){toast(error.message);return false;}
  };
  attach();return true;
};
modal.addEventListener('click',event=>{if(event.target.closest('[data-dismiss]')||event.target===modal){socialState.activeDraft=null;socialClearComposer();}},true);
modal.addEventListener('cancel',()=>{socialState.activeDraft=null;socialClearComposer();},true);
window.yungetCommunityBack=()=>{if(modal.open){socialState.activeDraft=null;socialClearComposer();dismissModal();return true;}return false;};
`;
