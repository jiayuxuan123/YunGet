# 插件 ABI 参考

这一页是插件脚本能用的每一样东西的完整清单。所有函数名、参数、默认值、上限都从引擎代码里核对过（`turbo-plugin-js` 的 `abi.js` 与 `JsPluginConfig.kt`），不是凭印象写的。

想先跑起来一个插件，看 [写一个云取插件](PLUGIN-DEV.md)。发到市场看 [打包与发布](PLUGIN-PUBLISH.md)。

---

## 边界长什么样

脚本世界里只有两个全局对象：

- **`plugin`** —— 注册面：告诉宿主"我是谁、我要什么、我会做什么"。
- **`host`** —— 能力面：要宿主替你做的事。

这两个对象是 ABI 的**全部**。它们建立在恰好三个宿主绑定原语之上：

```
__turbodlCall(path, payloadJson)      -> envelopeJson   // 要宿主做事
__turbodlRegister(kind, cbId, opts)   -> envelopeJson   // 注册扩展点
__turbodlLog(level, text)             -> void           // 日志快路径
```

跨边界的一切都是 **JSON 文本**，回调以整数 id 传递（QuickJS 交给 Kotlin 的 JS 函数是不可调用的不透明句柄，所以函数留在 JS 侧、由 shim 按 id 派发）。

> **别直接调 `__turbodl*` 原语。** 它们**返回信封、绝不抛异常**：忘了检查 `ok` 就会把失败当成成功。请一律用 `plugin.*` / `host.*` —— 它们把失败转成可 `catch` 的 `Error`，并且带上稳定的 `code` 字段。

信封协议：

```js
{ ok: true,  data: <value> }
{ ok: false, error: { code: "permission", message: "…" } }
```

失败时抛出的 `Error` 上有两个额外字段：`error.code`（错误码）和 `error.hostPath`（哪个宿主调用失败）。

值域是封闭的：`null`、`Boolean`、整数/浮点数、`String`、数组、字符串键的对象。函数、`Date`、`RegExp` 一律**拒绝而非强转**。`NaN` / `Infinity` 也是拒绝（不是悄悄变成 `null`）。

---

## ABI 版本

| 常量 | 值 | 在哪 |
|---|---|---|
| `ABI_MAJOR` | `1` | shim 与加载器各持一份 |
| `ABI_VERSION` | `"1.0.0"` | shim 报告，用于诊断 |
| `plugin.apiMajor` | 脚本可读，默认 `1` | — |

脚本用 `plugin.requires({ abiMajor: 1 })` 声明自己要求的 ABI 主版本。加载时握手会检查两件事：

1. shim 报告的 `abiMajor` 等于加载器实现的主版本；
2. 脚本声明的 `abiMajor` 等于同一个数。

**任一不等 → 加载失败**，错误码 `unsupported`，消息里会写清"要哪个版本、实际是哪个版本、这个加载器实现的是哪个版本"。所以主版本不匹配的插件根本不会跑起来 —— 而不是跑起来之后行为未定义。

不写 `abiMajor` 时默认就是 `1`（当前唯一的主版本）。

---

## `plugin`（注册面）

### `plugin.id`

字符串。宿主在脚本运行**之前**写入，是脚本被安装时的 id（云取用的是你在索引/`defineMeta` 里声明的那个）。脚本不能自选 id —— 所以 `loader.` / `core.` 这类前缀没法被伪造。

### `plugin.sourceUri`

字符串。脚本从哪来的（文件路径、`inline:`、`data:`）。

### `plugin.manifest`

对象或 `null`。宿主传给加载器的 source attributes，作为**纯数据**暴露（云取目前传的是 `{pluginId: "…"}`）。它是数据不是宿主句柄，读不到任何 Kotlin 对象。

### `plugin.defineMeta(meta)`

声明展示元信息，返回 `plugin` 本身。

```js
plugin.defineMeta({ id: 'parser.demo', name: 'Demo 解析器', version: '1.0.0' });
```

| 字段 | 类型 | 作用 |
|---|---|---|
| `id` | string | 给云取与市场读的文本声明（引擎不改自己的 id） |
| `name` | string | 界面显示名 |
| `version` | string | 版本号；市场用它比对索引条目，必须逐字相等 |
| `apiMajor` | number | 等价于 `plugin.requires({abiMajor})` |

`meta` 不是对象时抛错。字段都是可选的（没写的就不设置）。

### `plugin.requires(spec)`

声明需要什么，返回当前的 `requirements` 对象。**建议放在脚本最上面**。

```js
plugin.requires({ permissions: ['http', 'crypto'], abiMajor: 1 });
plugin.requires({ permissions: 'http,crypto' });   // 字符串也行，按 , 或空白切分
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `permissions` | string 或 array | 能力名，多次调用会**累加** |
| `abiMajor` | number | 要求的 ABI 主版本 |

声明了宿主没授予的能力 → **加载失败**（`permission`），消息会点名是哪个能力、以及这个加载器到底授予了哪些。这比"下载到一半才发现能力被拒"好。

声明一个引擎不认识的名字 → 也是加载失败，消息是 `unknown capability 'xxx'`。

### `plugin.registerParser(impl)`

注册一个链接解析器。返回 `{handle, kind, priority}`（注册句柄，诊断用）。

```js
plugin.registerParser({
  priority: 300,                       // 可选，-10000..10000，默认 200
  parse: function (input) { /* … */ }  // 必需
});
```

- **入参**：`{ input: '<原始链接>' }`。
- **返回**：`null`（不是我的链接）/ 单个描述符对象 / 描述符数组。空数组等同于 `null`。
- `impl.parse` 不是函数 → 抛错 `registerParser expects an object with a parse() method`。

描述符字段见下面的「请求描述符」。

### `plugin.registerTaskPreHook(impl)`

注册任务提交前钩子。返回注册句柄。

```js
plugin.registerTaskPreHook({
  priority: 150,
  beforeSubmit: function (payload) {
    var request = payload.request;   // 描述符（含宿主给的绝对 destination 字符串）
    request.headers['X-From'] = 'demo';
    return request;                  // 返回新描述符；返回 null/undefined 表示不改
  }
});
```

- **入参**：`{ request: <描述符> }`。
- **返回**：新的描述符，或 `null`/`undefined`（不改）。
- 抛错、返回非法描述符 → 请求**原样不变**地继续。

### `plugin.registerTaskPostHook(impl)`

注册任务结束后钩子。只观察，返回值被忽略。

```js
plugin.registerTaskPostHook({
  afterFinish: function (payload) {
    // payload = { request: <描述符>, success: true/false, detail: '<文本>' }
  }
});
```

抛错只记日志，不影响任务。

### `plugin.onEvent(fn)`

订阅引擎事件。只观察，返回注册句柄（和 `register*` 一样的 `{handle, kind, priority}` 数据对象）。**只能调用一次**（第二次抛错）。

```js
plugin.onEvent(function (event) {
  // event.type 是事件类名，event.taskId 恒有
});
```

`event` 的形状（`type` 是 Kotlin 事件类的名字）：

| `type` | 额外字段 |
|---|---|
| `Created` | `url` |
| `StateChanged` | `state` |
| `Progress` | `state`、`downloadedBytes`、`totalBytes`、`speedBytesPerSec`、`activeConnections`、`etaMillis`、`percent`、`error` |
| `Completed` | `file`（绝对路径）、`totalBytes` |
| `Failed` | `reason` |
| `Metadata` | `suggestedFileName`、`contentType`、`etag`、`lastModified`、`totalBytes`、`supportsRange`、`resolvedUrl`、`probeMs` |

监听器是**有界同步观察者**：调用同步执行，最慢可以占用一次调用预算（默认 20 秒）—— 也就是说一个慢监听器最多让事件泵停这么久。抛错会被隔离，不会杀死事件泵。

### `plugin.onInit(fn)`

加载时执行一次。**只能注册一次**。

**`onInit` 里抛错 = 整个插件加载失败**（错误码 `plugin`，消息以 `failed in onInit:` 开头）。所以初始化只做不会失败的事。

### `plugin.onDestroy(fn)`

卸载时执行一次。**只能注册一次**。

这是唯一允许在实例进入 STOPPING 之后进入 JS 的调用。它在"手上的调用都跑完、定时器已取消"之后执行，所以不会和正常插件工作竞争。抛错只记日志，不会阻塞卸载。

### 注册优先级

| 来源 | 生效顺序 |
|---|---|
| `impl.priority`（脚本自报） | 最高 |
| 加载器的 `defaultExtensionPriority` | 中间（宿主一次性压低全部 JS 插件用） |
| `200` | 兜底（约定里 adapter 的档位） |

范围 `-10000..10000`。**写了但越界 = 注册失败**（`validation`），不会静默回落到默认值。同优先级按注册序稳定排序，所以一个脚本内部的顺序是可复现的。

### 不能注册什么

| 尝试 | 结果 |
|---|---|
| `backend`（`DownloadBackend`） | `unsupported` —— 字节/分片面留在 Kotlin |
| `service`（发布宿主服务） | `unsupported` —— service 是 Kotlin 对象图 |
| 未知 kind | `validation` |

脚本里没有对应的 `plugin.registerBackend` / `plugin.registerService` 函数，只有通过原语才可能碰到这些错误码。

---

## 请求描述符

这是 JS 侧唯一可见的"下载"形状。字段全是字符串/数字/字符串表：

```js
{
  url:         'https://cdn.example/a.bin',   // 必需，http/https，≤ 8192 字符
  destination: 'a.bin',                       // 可选，见下
  fileName:    'a.bin',                       // 可选，文件名建议
  headers:     { 'Referer': 'https://…' },    // 可选，字符串表
  knownSize:   12345678,                      // 可选，数字；缺省 -1
  connections: 8,                             // 可选，1..256
  stableKey:   'demo-abc123',                 // 可选，≤ 200 字符（去重键）
  tags:        { scheme: 'demo' }             // 可选，字符串表
}
```

**`destination` 的规则**（这是整个 JS 面里风险最高的一个字符串，因为引擎会真的去创建并写满它指向的文件）：

| 你返回的 | 结果 |
|---|---|
| 不写，或写空 | 用 `fileName`（或从 URL 推出来的名字）落到插件的 `downloads/` 沙箱 |
| 裸文件名（`a.bin`） | 落到插件的 `downloads/` 沙箱 |
| 含 `/`、`\`，或绝对路径 | **被拒**（`permission`），除非宿主打开了 `allowExternalDestination`（云取没有打开） |
| pre-hook 里原样回显宿主给的那个绝对路径 | 接受 —— 那是宿主自己的选择，不是脚本在指定路径 |

`url` 的 scheme 在这里就检查：`file:///etc/passwd` 是插件错误，不会变成一个引擎任务。

一次 `parse` 最多 **256** 条描述符，超了整批失败（`limit`）。字段校验失败也是整批失败 —— 不会"跳过坏的那条继续装好的"。

**宿主 → 脚本**方向还会多两个字段，你在 `beforeSubmit` / `afterFinish` 的入参里会看到：

```js
{
  destination: '/data/…/downloads/a.bin',  // 绝对路径字符串（不是 File 对象）
  fileName:    'a.bin',                    // 从上面那个路径取的末段
  sandbox:     '/data/…/turbodl-js/parser.demo_1a2b3c4d5e'   // 你这个插件的沙箱根目录
}
```

`sandbox` 只是让你能向用户解释"相对名字会落到哪"，它不是可以写进去的目录（写文件仍然只有 `host.storage` 与 `host.http.downloadToFile` 两条路）。

---

## `host`（能力面）

### `host.http`

#### `host.http.request(options)`

发一次请求，返回**完整**结果。没有流式 API、没有 body 句柄、没有分块回调。

```js
var res = host.http.request({
  url: 'https://api.example/resolve?id=123',
  method: 'GET',              // 默认 GET，会被转成大写
  headers: { 'Accept': 'application/json' },
  as: 'text',                 // 'text'（默认）| 'base64' | 'none'
  timeoutMillis: 3000,        // 只能调低，不能超过宿主的 30s
  maxBytes: 65536,            // 只能调低，不能超过宿主的 8 MiB
  originUrl: 'https://api.example',   // 凭据归属的 origin（见下）
  followRedirects: false      // 只能关、不能开（宿主默认就是关的）
});
```

| 字段 | 类型 | 说明 |
|---|---|---|
| `url` | string | 必需。只允许 `http` / `https` |
| `method` | string | 默认 `GET` |
| `headers` | 字符串表 | 可选 |
| `body` | string | 可选，按 UTF-8 编码；上限 2 MiB |
| `bodyBase64` | string | 可选，二进制 body |
| `as` | string | `text` / `base64` / `none`，其它值 → `validation` |
| `timeoutMillis` | number | 必须 > 0；实际取 `min(你的值, 30s)` |
| `maxBytes` | number | 实际取 `min(你的值, 8 MiB)`；≤ 0 视为不限制（仍受 8 MiB 上限） |
| `originUrl` | string | 凭据归属 origin |
| `followRedirects` | boolean | `false` 可以关掉本次调用的跟随；`true` 打不开宿主关着的跟随 |

`method` 会被转成大写，只允许 `GET` / `POST` / `PUT` / `HEAD` / `DELETE` / `PATCH` / `OPTIONS`；其它值 → `permission`。`GET`/`HEAD`/`DELETE`/`OPTIONS` 带 body 会被拒（`permission`）；`POST`/`PUT`/`PATCH` 不带 body 时会补一个空 body。

返回：

```js
{
  ok: true,               // 是否 2xx
  status: 200,
  statusText: 'OK',
  headers: { 'content-type': 'application/json' },  // 单值表（首值胜出）
  url: 'https://api.example/resolve?id=123',        // 最终 URL
  redirects: 0,           // 实际跟随的跳数（直连为 0）
  body: '…',              // as='text' 时
  bodyBase64: '…',        // as='base64' 时
  bytes: 1234             // 恒有：真实读取的字节数
}
```

**非 2xx 不是错误**：返回 `ok: false` 和一个 `status`，让你像用普通 HTTP 库那样分支。只有传输失败才是 `network` 错误。

凭据剥离：`Authorization`、`Cookie`、`Referer`、`Origin`、`Proxy-Authorization` 在**跨 origin** 时会被剥掉。默认"凭据 origin"就是你请求的那个 URL；如果先登录 A 域、再从 CDN B 域取数据，用 `originUrl` 声明凭据属于 A。

重定向是逐跳处理的策略动作：宿主**无条件关掉** OkHttp 自带的跟随，自己走每一跳。每跳都重新做 scheme 检查（`Location: file:///etc/passwd` 是拒绝）并重算要发哪些头（所以 `Authorization` 不会跟着 302 坐到 CDN 上）。超过 **5** 跳 → `limit`。`307/308` 保留方法与 body，`301/302/303` 一律降为无 body 的 `GET`。不跟随时 3xx 作为数据返回，`Location` 原样保留。

`User-Agent` 没设时填 `TurboDL-js-plugin/1`。

#### `host.http.downloadToFile(options)`

下载整个文件到插件的 `downloads/` 沙箱，返回**已经写完的文件**。

```js
var got = host.http.downloadToFile({
  url: 'https://cdn.example/a.bin',
  fileName: 'a.bin',       // 可选，不写就从 URL 末段推一个
  headers: { 'Referer': 'https://…' },
  timeoutMillis: 60000,    // 只能调低
  maxBytes: 104857600,     // 只能调低，宿主上限 512 MiB
  originUrl: '…',
  followRedirects: false
});
// -> { ok: true, file: '/data/…/downloads/a.bin', bytes: 1048576, status: 200,
//      contentType: 'application/octet-stream', url: '…', redirects: 0 }
```

先写 `.part` 再改名，所以失败不会留下一个看起来完整的文件。非 2xx 是 `network` 错误（和 `request` 不一样）。`fileName` 会过沙箱校验：含分隔符、以 `.` 开头、绝对路径都被拒。

### `host.crypto`

算法白名单：摘要 `MD5` / `SHA-1` / `SHA-256` / `SHA-512`；HMAC `HmacSHA1` / `HmacSHA256` / `HmacSHA512`。名字匹配会忽略大小写和连字符，所以 `'sha256'`、`'SHA-256'`、`'sha-1'` 都能用；返回的 `algorithm` 是**规范化后**的名字。不支持的名字 → `validation`，消息里点名被拒的名字（绝不泄漏 JCA 的 provider 异常）。

| 调用 | 返回 |
|---|---|
| `host.crypto.digest(algorithm, data)` | `{algorithm, hex}`（小写 hex） |
| `host.crypto.hmac(algorithm, key, data)` | `{algorithm, hex}`；`key` 不能为空；算法传空串时默认 `HmacSHA256` |
| `host.crypto.randomBytes(length)` | `{base64, hex}`；`length` 必须在 `1..512`，越界是 `limit` |
| `host.crypto.base64Encode(data)` | `{text}` —— 字节按 base64 编码后的文本 |
| `host.crypto.base64Decode(text)` | `{base64}` —— 解出的**字节**再编码回 base64 |
| `host.crypto.hexEncode(data)` | `{text}` —— 字节按 hex 编码后的文本 |
| `host.crypto.hexDecode(text)` | `{hex}` —— 解出的**字节**再编码回 hex |

`data` / `key` 可以是字符串（按 UTF-8），也可以是 `ArrayBuffer` / `Uint8Array`（shim 在 JS 侧转成 base64 再过界，字节数组本身从不跨边界）。单次输入上限 `min(8 MiB, 32 MiB)` = **8 MiB**（默认配置下）。hex 字符串长度必须是偶数，否则 `validation`。

密钥不会被记录、也不会被返回。

> **`base64Decode` / `hexDecode` 给回的是编码文本，不是解码后的字符串。** 两个函数返回的字段名分别是 `base64` 和 `hex`，里面装的是"解出来的字节"重新编码后的文本：`host.crypto.base64Decode('YWJj').base64 === 'YWJj'`。
>
> 这个形状对"校验 + 规范化一段编码"很顺手（比如把 URL-safe base64 转成标准 base64），但**没有**"把 base64 解成普通字符串"的 API —— 你想拿解码后的文本，只能自己在 JS 里按字符表转（或让宿主侧做这件事）。这是一个真实的缺口，不是文档没写清。

### `host.log`

```js
host.log.info('…'); host.log.warn('…'); host.log.error('…'); host.log.debug('…');
```

输出格式是 `[<pluginId>] [<level>] <text>`，单条上限 **8 Ki 字符**，非字符串参数尽力 `JSON.stringify`。

`log` 是一项**真实的能力**（默认授予）。宿主撤掉它之后，你的日志行会被**丢弃**（不是抛错 —— 一次日志调用不能让下载失败），宿主自己会发**一条**诊断说明这件事。

`log` 永远不抛错。多参数按空格拼接。

### `host.time`

| 调用 | 返回 | 说明 |
|---|---|---|
| `host.time.now()` | `{millis}` | 宿主时钟，不是 `Date.now()`，所以能和引擎日志对齐 |
| `host.time.iso(millis?)` | `{iso, millis}` | 不传则用当前时间；传非数字 → `validation` |
| `host.time.sleep(millis)` | `{sleptMillis}` | 上限 **5000 ms**（超了就按 5000 睡），负数 → `validation` |

`sleep` 会占用一次调用预算，别用它做长等待。

### `host.storage`

插件自己的小 KV，落在插件的 `storage/` 沙箱里。

| 调用 | 返回 | 说明 |
|---|---|---|
| `host.storage.get(key)` | 值本身，或 `null` | 值是 JSON 域数据 |
| `host.storage.set(key, value)` | `{ok, bytes}` | |
| `host.storage.remove(key)` | `{removed}` | |
| `host.storage.keys()` | 字符串数组（已排序） | |

规则：

- key 是**可逆**的百分号编码后当文件名用的：`user:token` 能用，而且 `user:token` / `user/token` / `user token` 是**三个不同的 key**，不会互相覆盖。
- key ≤ **64** 字符，不能为空，不能以 `.` 开头。
- 单值 ≤ **256 Ki 字符**（编码后的 JSON 文本长度）。
- 总量 ≤ **4 MiB**（宿主上限），超了是 `limit`，消息让你先删没用的 key。
- 卸载时 `downloads/` 一定清（那是临时垃圾），`storage/` 默认**保留**（会话 token、续传游标是你的状态）。

### `host.env`

只读 + 白名单。**云取的白名单是空的** —— 除非宿主点名某个变量，否则环境对你不可见。

| 调用 | 返回 |
|---|---|
| `host.env.get(name)` | `{value}`；名字不在白名单里 → `permission` |
| `host.env.has(name)` | `{allowed, present}`；不在白名单时 `present` 恒为 `false`（不抛错） |

### `host.setTimeout` / `host.setInterval` / `host.clearTimeout`

定时器**由宿主持有**（loader 级调度器），所以卸载一定能取消它们。

```js
var handle = host.setTimeout(function () { host.log.info('tick'); }, 1000);
host.clearTimeout(handle);        // handle 是数字，不是对象
var repeating = host.setInterval(function () { /* … */ }, 5000);
host.clearInterval(repeating);    // 和 clearTimeout 是同一个函数
```

- `delayMillis` ≥ 0，上限 **24 小时**（`86400000` ms），越界是 `limit`。
- 返回的句柄是**数字**（宿主定时器 id），可以直接喂给 `clearTimeout`。
- 回调以回调 id 传递；回调里抛错只记 `warn`，既不杀插件也不阻塞卸载。
- 进入 DRAINING 时全部取消；STOPPING 之后触发的定时器被静默丢弃。
- 调度器已停时 `timer.clear` 返回 `gone`。
- 这一族需要 `timer` 能力，**云取默认不授予**。

---

## 能力（permission）清单

一共 **7** 个能力。授予规则：**实际授予 = 脚本声明 ∩ 加载器上限 ∩ 用户已确认**。

| 能力 | 覆盖的 API | 云取默认授予？ | 没授予时会怎样 |
|---|---|---|---|
| `http` | `host.http.request` / `downloadToFile` | ✅ | 调用抛 `permission`，消息点名要申请 `http` |
| `crypto` | `host.crypto.*` | ✅ | 抛 `permission` |
| `log` | `host.log.*` | ✅ | **静默丢弃**（不抛错），宿主发一条诊断 |
| `time` | `host.time.*` | ✅ | 抛 `permission` |
| `storage` | `host.storage.*` | ❌ | 抛 `permission` |
| `env` | `host.env.*` | ❌ | 抛 `permission` |
| `timer` | `host.setTimeout` / `setInterval` / `clearTimeout` | ❌ | 抛 `permission` |

默认授予集合是 `http, crypto, log, time`（与 `JsCapability.defaultGrant()` 一致）。

两个必须分清的区别：

- **"没授予"** → 抛 `permission`，消息里点名"要申请哪个能力"。
- **"授予了但这次调用被拒"** → 各自的码（比如 scheme 不合法是 `permission`、参数错是 `validation`、超限是 `limit`）。

`log` 是唯一刻意不抛错的能力：一次日志调用不能弄坏一次下载。它被拒时你收不到异常，宿主侧会有一条 `host.log is not granted to this plugin (grants: …); the script's log lines are being dropped`。

**脚本不能在运行时自我提权。** 授予是加载时定下的集合，每次宿主调用都复检一遍；没有任何 API 能改变它。

---

## 错误码表

`error.code` 是跨版本的契约，别去匹配消息文本。一共 **11** 个码：

| 码 | 什么情况下出现 | 建议怎么处理 |
|---|---|---|
| `validation` | 参数不对：算法不支持、`as` 取值非法、`connections` 越界、priority 越界、hex 长度是奇数、payload 不是对象 | 这是脚本的 bug。改参数，别重试 |
| `permission` | 能力没授予；URL 不是 http(s)；重定向目标不是 http(s)；`destination` 出沙箱；方法带不该带的 body | 在 `plugin.requires` 里补声明，或改掉这个调用 |
| `limit` | 超过任何一条上限：响应体、下载体、请求体、重定向跳数、随机字节数、storage 配额、堆上限、描述符条数、定时器延迟 | 调小你请求的量，或分批做 |
| `not_found` | 契约里保留的码；当前实现没有路径会抛出它 | 按 `internal` 一样兜底即可 |
| `gone` | 实例正在停止/已释放：回调已注销、runtime 已 dispose、宿主调度器已停 | 这是正常的卸载竞态，通常什么都不用做 |
| `unsupported` | ABI 主版本不匹配；注册 `backend`/`service`；从宿主回调里再入本 runtime；未知宿主路径 | 改脚本（去掉那个调用），或让宿主升级 |
| `network` | 传输失败；`downloadToFile` 遇到非 2xx；响应没有 body | 可重试，但要有次数上限和回退路径 |
| `timeout` | 单次求值超过 5 秒；一次调用总预算超过 20 秒；排队时预算就用光 | 缩短单次工作量，或拆成多次调用 |
| `interrupted` | 调用被中断（卸载时的 `interruptEvaluation`、线程中断） | 什么都不用做，实例正在被卸载 |
| `internal` | 宿主内部故障：QuickJS 起不来、下载文件无法落盘、未归类的异常 | 记日志报给宿主，脚本侧无解 |
| `plugin` | **你自己的脚本抛的错**：`parse`/`beforeSubmit` 里 `throw`、`onInit` 抛错、回调返回了畸形结果 | 这就是你的 bug，去看消息与行号 |

`plugin` 与 `internal` 的分工是刻意的：插件的 bug 永远不会被当成引擎故障，反之亦然。

---

## 上限表

全部来自 `JsPluginConfig` 的默认值（云取用的就是这一档）。

| 项 | 默认值 | 说明 |
|---|---|---|
| `permissions` | `http, crypto, log, time` | 加载器的授予集合（上限） |
| `evaluationTimeoutMillis` | **5000** ms（5 秒） | 单次 JS 求值，**只计 JS 时间** |
| `invocationTimeoutMillis` | **20000** ms（20 秒） | 一次调用的总预算（JS + 宿主），必须 ≥ 前者 |
| `drainTimeoutMillis` | **5000** ms | 卸载时等手上的 JS 跑完的时限；超了**拒绝关闭**并计一次泄漏 |
| `memoryLimitBytes` | **32 MiB** | 每实例的 QuickJS 堆上限，越限在 JS 里抛错 |
| `maxStackSizeBytes` | **512 KiB** | 原生栈上限：把无界递归变成可捕获的 `InternalError`，而不是让 VM 崩 |
| `maxResponseBytes` | **8 MiB** | `host.http.request` 读进内存的响应体上限 |
| `maxDownloadBytes` | **512 MiB** | `host.http.downloadToFile` 的上限（流式落盘，仍然有界） |
| `maxStorageBytes` | **4 MiB** | `host.storage` 的总配额 |
| `httpTimeoutMillis` | **30000** ms（30 秒） | 单次 host HTTP 的 connect/read 上限 |
| `followRedirects` | `false` | 宿主默认不跟随重定向 |
| `sandboxRoot` | 宿主的 `filesDir/turbodl-js` | 沙箱根；`<root>/<pluginId>/` 下有 `storage/`、`downloads/` |
| `allowedEnvVars` | 空集合 | 环境变量白名单 |
| `allowExternalDestination` | `false` | 不允许解析器返回沙箱外的绝对路径 |

其余散落在各处的上限：

| 项 | 值 | 出处 |
|---|---|---|
| 脚本体积 | **512 KiB** | 加载器与校验器一致；超了不是插件而是应用 |
| 单次响应文本 | 4 Mi 字符 | 值域的字符串上限 |
| JSON 文本 | 4 Mi 字符，嵌套深度 64 | 手写 JSON 编解码器 |
| 请求体 | 2 MiB | `host.http` |
| 响应头数量 | 64 | `host.http` |
| 重定向跳数 | 5 | `host.http` |
| crypto 单次输入 | 8 MiB（= `min(maxResponseBytes, 32 MiB)`） | `host.crypto` |
| `randomBytes` 长度 | 1..512 | `host.crypto` |
| 一次 `parse` 的描述符条数 | 256 | 桥接层 |
| 描述符 `url` | 8192 字符 | 桥接层 |
| 描述符 `connections` | 1..256 | 与引擎的 `maxConnectionsPerTask` 同界 |
| `stableKey` | 200 字符 | 桥接层 |
| 注册 priority | -10000..10000 | 桥接层 |
| storage key | 64 字符 | `host.storage` |
| storage 单值 | 256 Ki 字符 | `host.storage` |
| 日志单条 | 8 Ki 字符 | `host.log` |
| `time.sleep` | 5000 ms | `host.time` |
| 定时器 delay | ≤ 24 小时 | `host.setTimeout` |

每一条都是**安全或资源上界**，不是功能开关：默认值按"一个抓页面 + 清单的解析器"定尺，不是按"内嵌应用服务器"。脚本**无法**把任何一条调高 —— 你在调用里写的 `maxBytes` / `timeoutMillis` / `followRedirects` 只能收窄。

---

## 值域与 JSON

跨边界的值只有：`null`、`Boolean`、整数、有限浮点数、`String`、数组、字符串键的对象。

- 函数、`Date`、`RegExp`、`undefined` 之外的宿主对象一律**拒绝**，不做强转。
- `NaN` / `Infinity` 在两个方向都被拒绝（不是编码成 `null`）。
- 字符串上限 4 Mi 字符。
- JSON 文本上限 4 Mi 字符、嵌套深度 64、严格 RFC 8259（拒绝前导零、尾随垃圾、`NaN`）。

回调**返回 `undefined`** 是合法的：它表示"这个 void 钩子什么都没返回"，不是 `null` 数据。

---

## 没有的东西

别去找这些，它们不存在：

- **没有 `require` / npm / `fetch` / Web API。** `host.http` 是唯一出网口。
- **没有流式 API、没有 body 句柄、没有分块回调。** 见 [写一个云取插件](PLUGIN-DEV.md) 里的边界说明。
- **不能注册下载后端，不能发布宿主服务。**
- **不能读写沙箱外的文件。** 你连沙箱根路径都只能通过描述符里的 `sandbox` 字段看到。
- **不能改传输政策**（代理、DNS、TLS、UA 来自宿主配置），只能在单次调用里收窄。
- **不能提权**：没有 API 能改变已授予的能力集合。
- **`jar:` / classpath / URL 源码**不被加载器接受（读不到就返回空列表 + 一行诊断）。

---

## 相关的其它文档

- [写一个云取插件](PLUGIN-DEV.md) —— 从零到跑起来，含常见错误
- [打包与发布](PLUGIN-PUBLISH.md) —— manifest、签名、提 PR、自建源
- [安全模型](PLUGIN-SECURITY.md) —— 信任四级、校验链，以及不做的承诺
