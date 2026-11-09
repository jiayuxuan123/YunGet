# 云取社区

替换应用内「公告与社区」的公告数据和反馈入口。

- `/`：反馈列表、提交反馈、公告和闲聊。
- `/admin`：Cloudflare Access 保护的开发者后台。
- `/api/announcements`：应用内公告读取接口。
- D1 `DB` 保存公告、反馈、回复、闲聊、匿名会话和限流记录。

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
npm test
npx wrangler deploy --dry-run
```

`TURNSTILE_SECRET` 只通过 Worker secret 写入，不提交。`wrangler.jsonc` 中的资源 ID、Access audience 和公开 site key 由部署脚本写入。
