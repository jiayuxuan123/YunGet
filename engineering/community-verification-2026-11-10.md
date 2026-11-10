# 社区同域自托管验证

## 改动

- 用 ALTCHA 3.5.0 / altcha-lib 2.8.0 替换 Turnstile，全部资源和接口从社区自身域名加载。
- 保留现有昵称、HttpOnly 会话、公告、反馈、回复、聊天及 Cloudflare Access 开发者后台。
- 不依赖第三方 CAPTCHA CDN 或远端 Siteverify；不需要新账号或 API Key。
- 真实计算完成后才显示验证通过。失败、取消、过期或重新验证时清除旧结果，保留表单内容。
- 单 Worker 计算、无额外人为延时；手机浅色、深色和 320px 布局继续可用。

## 服务端约束

- 官方 v2 协议：签名 PBKDF2/SHA-256 挑战，工作量、解答范围、随机 nonce、用途、来源和 5 分钟有效期均经校验。
- 只有服务端验证通过后才接受写入。前端的“完成”状态不能单独放行。
- D1 唯一键原子消费；同一验证并发重放只允许一次成功。
- 注册、反馈、回复和聊天的证明不能混用。新挑战接口有单独限流，原写接口限流继续生效。
- 新增 `used_verifications` 表及过期索引，现有数据不变；启用每小时清理任务，清理过期会话、限流桶和验证记录，并执行原有 30 天聊天保留策略。
- 签名密钥仅在忽略的本地配置和 Worker secret 中；未写入源码或静态资源。
- 内容安全策略收紧为同域脚本、同域计算 Worker、同域连接，禁止验证码 iframe。

## 验证

- 社区 Node 测试：18 项通过，0 失败、0 跳过。
- 示例界面回归：34 项通过，使用真实本地验证算法，外部请求 0。
- 真实本地 Worker + D1 + 控件：13 项通过，覆盖注册、反馈、回复、聊天、重复提交、防重放、网络失败与恢复。
- 本机 Chromium 中 6 次验证等待：309、98、212、818、199、821 ms；仅为当前本机观察，不等同于所有设备或网络。
- 320px 浅深色截图确认无横向溢出；验证控件与表单按钮均可到达。
- npm audit 0 项漏洞；3 个包的 registry 签名通过，2 个包的 provenance attestation 通过。
- 第三方来源与逐资源 SHA-256 见 `community/public/verification/SOURCES.json`，锁文件固定版本。
- 正式 Worker 已部署，版本 ID 为 `a98c5ef7-3871-4862-a06e-58a9caa19931`。
- 正式域名 Chromium 检查：9 项通过，真实计算生成 proof，重试正常，320px 浅深色正常，公告可读，开发者入口仍跳转 Access。阻断第三方网络后验证依旧完成；所有验证资源响应为 200。
- 公网验收只获取挑战并在浏览器计算，没有创建测试昵称、反馈、回复或聊天记录。真实写入流程已在本地 D1 联调。
- 线上另有 Cloudflare 边缘注入脚本被严格 CSP 拒绝的日志，社区脚本没有未捕获异常。

原始证据保存在本地忽略目录 `output/playwright/community-mobile/`，不提交测试用户内容、签名密钥或会话 Cookie。

## 范围

这是社区网页和服务端的热更新，云取 APK 仍为 2.7.28，不需要覆盖旧 Release。现有用户刷新软件内社区即可加载新版本。

参考：[ALTCHA 自托管服务端集成](https://altcha.org/docs/integration/server/)、[防重放与过期建议](https://altcha.org/docs/integration/security-recommendations/)、[Worker 静态资源绑定](https://developers.cloudflare.com/workers/static-assets/binding/)。
