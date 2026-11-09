# 云取社区

替换应用内「公告与社区」的公告数据和反馈入口。

- `/`：反馈列表、提交反馈、公告和闲聊。
- `/admin`：Cloudflare Access 保护的开发者后台。
- `/api/announcements`：应用内公告读取接口。
- D1 `DB` 保存公告、反馈、回复、闲聊、匿名会话和限流记录。

## 手机界面

- `web.mjs` 为文档壳，`style.mjs` 与 `client.mjs` 分别维护样式与交互。
- 反馈首页先展示列表；写反馈、昵称验证与回复使用可滚动原生 dialog，草稿在本次页面会话保留。
- 闲聊使用独立消息滚动区，输入栏在正常布局中占位；适配 Visual Viewport、键盘高度、旋转与安全区。
- 消息增量刷新，不重建输入框；阅读历史时提示新消息，手动点击才跳到底部。
- 浅色、深色和跟随系统三档主题；遵守系统减少动态效果设置。
- 本次是网页更新，云取 2.7.28 内的原社区入口刷新后即可使用。

## 安全边界

- 普通用户没有密码；设置昵称时获得 30 天随机会话，网页端使用 `HttpOnly; Secure; SameSite=Lax` Cookie。
- 提交昵称、反馈、回复和闲聊都经过 Turnstile 服务端 Siteverify。
- 写接口校验 `Origin`，限制请求体，使用参数化 D1 语句和服务端长度白名单。
- `/admin*` 由 Cloudflare Access 拦截，Worker 再独立校验 `Cf-Access-Jwt-Assertion` 的签名、issuer、audience 和过期时间。
- 管理员身份由 Cloudflare Access policy 绑定，不把邮箱或永久管理员密钥写进前端。
- 反馈、回复和闲聊列表页面使用文本转义，不把用户内容当作 HTML 渲染。

## 本地检查

```powershell
cd community
npm run check
npm test
npx --yes wrangler@4.158.0 deploy --dry-run
```

浏览器回归：先运行 `node test/preview-server.mjs`，用 Playwright CLI 的 `run-code` 执行 `test/mobile-browser-check.mjs` 内容。该脚本只访问 `127.0.0.1:8801`，使用示例数据与本地验证码替身，绝不写入正式社区。覆盖窄屏、键盘视口、增量刷新、草稿、重试和开发者表单。

`TURNSTILE_SECRET` 只通过 Worker secret 写入，不提交。`wrangler.jsonc` 中的资源 ID、Access audience 和公开 site key 由部署脚本写入。
