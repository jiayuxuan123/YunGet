# 云取社区

替换应用内「公告与社区」的公告数据和反馈入口。

- `/`：反馈列表、提交反馈、公告和闲聊。
- `/admin`：Cloudflare Access 保护的开发者后台。
- `/api/announcements`：应用内公告读取接口。
- `/api/analytics`：应用匿名事件批量上报；只接受白名单字段和专用请求头。
- `/admin/analytics`：仅开发者可访问的匿名统计面板。
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
- 提交昵称、反馈、回复和闲聊都使用同域自托管 ALTCHA，服务端校验签名与计算结果；不调用第三方验证 API。
- 验证结果有效期 5 分钟，按注册、反馈、回复与聊天分别绑定用途，并绑定当次来源。D1 唯一键原子消费，拒绝重复使用。
- 写接口校验 `Origin`，限制请求体，使用参数化 D1 语句和服务端长度白名单。
- `/admin*` 由 Cloudflare Access 拦截，Worker 再独立校验 `Cf-Access-Jwt-Assertion` 的签名、issuer、audience 和过期时间。
- 管理员身份由 Cloudflare Access policy 绑定，不把邮箱或永久管理员密钥写进前端。
- 反馈、回复和闲聊列表页面使用文本转义，不把用户内容当作 HTML 渲染。
- 匿名统计不保存原始安装 UUID：服务端使用 Worker 密钥计算 HMAC 摘要；事件不包含链接、文件名、账号、Cookie、二维码内容或 IP，默认保留 180 天。

## 本地检查

```powershell
cd community
npm ci --ignore-scripts
npm run prepare:verification
npm run check
npm test
npx --yes wrangler@4.158.0 deploy --dry-run
```

浏览器界面回归：先运行 `node test/preview-server.mjs`，用 Playwright CLI 的 `run-code` 执行 `test/mobile-browser-check.mjs`。该脚本只访问 `127.0.0.1:8801`，使用示例数据和真实 ALTCHA 计算，覆盖窄屏、键盘视口、增量刷新、草稿、重试和开发者表单。

完整前后端联调：将配置复制到忽略的 `wrangler.local.jsonc`，移除 `routes` 并把 `PUBLIC_ORIGIN` 改为 `http://127.0.0.1:8803`，在 `.dev.vars` 配置测试签名密钥，执行本地 `schema.sql` 后启动 `wrangler dev --local --port 8803 --config wrangler.local.jsonc`。使用 `test/verification-browser-check.mjs` 验证真实 Worker、D1 与控件，绝不向正式社区写测试内容。

`COMMUNITY_VERIFICATION_SECRET` 至少 32 字符，仅通过 Worker secret 写入，不提交。正式部署前先执行 `migrations/0001_self_hosted_verification.sql`。ALTCHA 3.5.0 和 altcha-lib 2.8.0 固定在锁文件中；客户端脚本、中文资源、样式和计算 Worker 都由 `public/verification/` 同域提供，保留 MIT 许可和来源摘要。
