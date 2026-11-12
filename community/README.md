# 云取社区

应用内「公告与社区」的公告、反馈和社区入口。

- `/`：社区信息流，可发帖、评论、点赞、投票和上传图片。
- `/notifications`：社区回复、反馈进度和公告通知。
- `/profile`：公开 UID、昵称、找回码和本人帖子。
- `/feedback`、`/chat`：保留旧反馈与闲聊入口。
- `/admin`：Cloudflare Access 保护的开发者后台。
- `/api/announcements`：应用内公告读取接口。
- `/api/analytics`：应用匿名事件批量上报；只接受白名单字段和专用请求头。
- `/admin/analytics`：仅开发者可访问的匿名统计面板。
- D1 `DB` 保存账号、帖子、评论、投票、通知、公告、反馈、闲聊、匿名会话、限流和验证记录。

## 手机界面

- `web.mjs` 为文档壳，`style.mjs`、`client.mjs` 维护通用页面和旧入口，`social-style.mjs`、`social-client.mjs` 维护社区信息流。
- 信息流在手机上使用底部导航，桌面上使用左栏、时间线和上下文侧栏；发帖、评论和资料弹层使用可滚动原生 dialog。
- 编辑器支持最多4张图片、2–6项投票和分类；提交时在浏览器压缩图片，并保留文字草稿。
- 评论按游标分页，长评论流不会一次载入全部内容；通知和未读角标链接回对应帖子或反馈。
- 闲聊使用独立消息滚动区，输入栏在正常布局中占位；适配 Visual Viewport、键盘高度、旋转与安全区。
- 消息增量刷新，不重建输入框；阅读历史时提示新消息，手动点击才跳到底部。
- 浅色、深色和跟随系统三档主题；遵守系统减少动态效果设置。
- 云取 2.7.33 使用同一 Cookie 读取未读数，并通过应用内 WebView 打开通知和社区。

## 安全边界

- 普通用户没有密码；首次设置昵称时分配随机公开 UID 和30天会话，网页端使用 `HttpOnly; Secure; SameSite=Lax` Cookie。
- 找回码可恢复同一 UID；服务端只保存找回码摘要，昵称和 UID 都不能单独用于登录。
- 提交昵称、反馈、回复、聊天、帖子和评论都使用同域自托管 ALTCHA，服务端校验签名与计算结果；不调用第三方验证 API。
- 验证结果有效期5分钟，按操作用途绑定来源。D1 唯一键原子消费，拒绝重复使用。
- 写接口校验 `Origin`，限制请求体，使用参数化 D1 语句和服务端长度白名单。
- 发帖和评论使用幂等键；点赞与投票按 UID 唯一。公开内容查询会排除隐藏帖子和私密反馈。
- 图片只接受 PNG/WebP，限制单张512 KiB和 D1 总占用；上传与发布分离，不能引用他人未发布图片。
- `/admin*` 由 Cloudflare Access 拦截，Worker 再独立校验 `Cf-Access-Jwt-Assertion` 的签名、issuer、audience 和过期时间。
- 管理员身份由 Cloudflare Access policy 绑定，不把邮箱或永久管理员密钥写进前端。
- 帖子、评论、反馈、回复和闲聊使用文本转义，不把用户内容当作 HTML 渲染。
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

浏览器界面回归：先运行 `node test/preview-server.mjs`，用 Playwright CLI 的 `run-code` 执行 `test/mobile-browser-check.mjs`。社交社区完整链路使用本地 Wrangler、全新 D1 和 `_audit/social-browser-check.js`，覆盖注册、找回码、发图、投票、评论、通知、深浅色及390/1440px布局，不向生产 D1 写测试内容。

完整前后端联调：将配置复制到忽略的 `wrangler.local.jsonc`，移除 `routes` 并把 `PUBLIC_ORIGIN` 改为 `http://127.0.0.1:8803`，在 `.dev.vars` 配置测试签名密钥，执行本地 `schema.sql` 后启动 `wrangler dev --local --port 8803 --config wrangler.local.jsonc`。使用 `test/verification-browser-check.mjs` 验证真实 Worker、D1 与控件，绝不向正式社区写测试内容。

`COMMUNITY_VERIFICATION_SECRET` 至少32字符，仅通过 Worker secret 写入，不提交。正式环境已按顺序执行 `migrations/0001_self_hosted_verification.sql` 与 `migrations/0003_social_community.sql`；部署新 Worker 前必须先完成加法迁移。ALTCHA 3.5.0 和 altcha-lib 2.8.0 固定在锁文件中；客户端脚本、中文资源、样式和计算 Worker 都由 `public/verification/` 同域提供，保留 MIT 许可和来源摘要。
