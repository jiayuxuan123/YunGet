# 写一个云取插件

云取的插件是**一段 JavaScript**。你不用编译、不用打包 APK：写一个 `.js` 文件，粘进应用，启用，它就开始工作。

这篇带你从零写一个能跑的解析器插件。API 的完整清单在 [ABI 参考](PLUGIN-API.md)，发到市场上要做的额外事情在 [打包与发布](PLUGIN-PUBLISH.md)，安全边界在 [安全模型](PLUGIN-SECURITY.md)。

---

## 插件能做什么

插件只在**下载任务开始之前**和**结束之后**参与，一共四类事：

| 能做 | 怎么做 |
|---|---|
| 认出一个链接、算出真正的下载地址 | `plugin.registerParser({ parse })` |
| 拼请求头、算签名、改写提交前的任务 | `plugin.registerTaskPreHook({ beforeSubmit })` |
| 任务结束后做点事（记日志、清临时转存） | `plugin.registerTaskPostHook({ afterFinish })` |
| 看引擎事件（进度、完成、失败） | `plugin.onEvent(fn)` |

在这四类事里，你可以发 HTTP 请求、算摘要和 HMAC、编解码 base64/hex、读写自己的小存储、打日志、用定时器。

**插件不经手下载数据流。** 这一条是刻意的边界，不是还没做完：

- 没有流式 API，没有 body 句柄，没有"每收到一块数据调一次"的回调。`host.http.request` 给你的是一份**完整**（且有上限）的响应体；`host.http.downloadToFile` 给你的是一个**已经写完的文件**。
- 插件**不能**注册下载后端（`DownloadBackend`）。试着注册会直接返回 `unsupported`。
- 为什么这么划：分片和字节面留在 Kotlin 里，脚本就永远不会站在数据路径上。否则一个写得不好的插件（或者干脆是恶意的）会变成整条下载链路的带宽瓶颈 —— 而且每个数据块都要跨一次 JS 边界，代价比下载本身还大。

所以插件的定位是"**算 URL / 拼头 / 签名 / 解析分享链接**"，不是"自己实现一个下载器"。

---

## 一个能跑的最小插件

这个插件认 `demo://` 链接，把它变成一条可下载的 https 请求。整段复制到一个 `.js` 文件里就能用：

```js
/*
 * parser.demo - 最小可用示例：demo://<path>[?name=<文件名>]
 *   demo://share/hello.bin           -> https://example.com/share/hello.bin
 *   demo://share/hello.bin?name=a.txt -> 同一个 URL，保存为 a.txt
 * 其它链接返回 null（不是我的，交给下一个解析器）。
 */

// 1) 声明要用到的能力。没声明的能力在运行时不可用，而且"声明了但宿主没授予"会让插件加载失败。
//    这里只声明真正用到的：crypto 用来算 stableKey。加一条网络查询就再补 'http'。
plugin.requires({ permissions: ['crypto'], abiMajor: 1 });

// 2) 自报身份。id 会被云取用来定位插件，version 是市场更新判据，name 是界面上显示的名字。
plugin.defineMeta({ id: 'parser.demo', name: 'Demo 解析器', version: '1.0.0' });

var SCHEME = 'demo://';
var BASE = 'https://example.com';

/** 链接里的文件名是不可信输入：只留一个裸文件名，丢掉路径与危险字符。 */
function safeName(raw) {
  var last = String(raw || '').split('/').pop().split('\\').pop();
  var cleaned = '';
  for (var i = 0; i < last.length; i++) {
    var c = last.charAt(i);
    var code = last.charCodeAt(i);
    if (code < 32 || code === 127) continue;
    if ('<>:"|?*/\\'.indexOf(c) >= 0) continue;
    cleaned += c;
  }
  cleaned = cleaned.replace(/^[.\s]+/, '').replace(/[.\s]+$/, '').replace(/\.\./g, '_');
  return cleaned.slice(0, 80);   // 80 个字符，不是字节：中文名也能留下
}

plugin.registerParser({
  priority: 300,
  parse: function (input) {
    // 宿主传进来的是一个对象：{ input: '<原始链接>' }
    var raw = String((input && input.input !== undefined) ? input.input : input).trim();
    if (raw.slice(0, SCHEME.length).toLowerCase() !== SCHEME) return null;  // 不是我的链接

    var rest = raw.slice(SCHEME.length);
    var query = '';
    var q = rest.indexOf('?');
    if (q >= 0) { query = rest.slice(q + 1); rest = rest.slice(0, q); }

    var path = rest.replace(/^\/+/, '');
    if (path === '') return null;

    var wanted = '';
    var pairs = query.split('&');
    for (var i = 0; i < pairs.length; i++) {
      var eq = pairs[i].indexOf('=');
      if (eq > 0 && pairs[i].slice(0, eq) === 'name') {
        wanted = pairs[i].slice(eq + 1).replace(/\+/g, ' ');
        try { wanted = decodeURIComponent(wanted); } catch (e) { /* 编码坏了就用原文 */ }
      }
    }

    var url = BASE + '/' + path;
    return [{
      url: url,
      fileName: safeName(wanted) || safeName(path) || 'demo.bin',
      stableKey: 'demo-' + host.crypto.digest('SHA-256', url).hex.slice(0, 16),
      connections: 8,
      tags: { scheme: 'demo', plugin: 'parser.demo' }
    }];
  }
});

plugin.onInit(function () {
  host.log.info('demo 解析器就绪，id=' + plugin.id);
});
```

这份代码里的每一点都是必要的，后面几节解释为什么。

> 想看更完整的写法（含可选的服务端直链解析、失败回退），读官方源仓库里的 [`parser.example-cloud`](https://github.com/jiayuxuan123/YunGet-Plugins/tree/main/plugins/parser.example-cloud) —— 那份是市场模板。

---

## 从零到跑起来

### 1. 粘进去

云取 → **设置 → 插件** → 底部 **粘贴脚本** → 把上面的代码贴进输入框。

### 2. 先校验，再安装

点「校验」。校验**只解析、不执行**你的脚本（引擎会把源码包成一个函数字面量求值，函数字面量只编译不运行），所以你不用担心校验阶段就跑飞。它会给你：

- 语法是否通过、多少行；
- 脚本**会注册什么**（`parser` / `preHook` / `postHook` / `event` / `init` / `destroy`）；
- 脚本**声明了哪些能力**（读的是 `plugin.requires({ permissions: [...] })` 里的字面量）。

校验通过后「安装」按钮才可点。文本一改，上一份校验结果就作废 —— 避免"校验过的是 A、装上去的是 B"。

> 想让校验与安装记录都能读到你的权限声明，`plugin.requires({...})` 与 `plugin.defineMeta({...})` 的对象字面量要**保持扁平**：别在里面嵌套对象。读它们的不是完整的 JS 解析器，而是一个小的文本扫描器 —— 它取的是从第一个 `{` 到**第一个** `}` 之间的正文。所以只要在 `permissions` / `version` 前面放了一个嵌套的 `{...}`，那段正文就会在嵌套对象的 `}` 处提前结束，后面的字段一律读不到，权限预览和详情里的「声明能力」就会是空的。（嵌套对象放在后面虽然能侥幸读到，但别依赖这个顺序。）

### 3. 启用

安装完默认就是启用状态。列表里每一项会显示：

```
Demo 解析器                          粘贴  未验证
运行中 · 注册 1 项 · 占用 1.2 MB              [开关]
```

「注册 1 项」= 你注册的**扩展点**条目数（`registerParser` / `registerTaskPreHook` / `registerTaskPostHook` / `onEvent` 各算一项）。`onInit` / `onDestroy` 不算在内 —— 它们是生命周期钩子，不是扩展点。这个插件只注册了一个解析器，所以是 1 项。

### 4. 验证它真的在工作

点进插件详情，能看到引擎侧的现况：

| 字段 | 说明 |
|---|---|
| 运行状态 | `运行中` / `已停用` / `未运行（引擎里没有它）` / `没关干净` |
| ABI | 引擎报的 ABI 版本，正常是 `1.0.0` |
| 已注册 | 逐条列出 `parser#1@300` 这样的注册项（kind、句柄、优先级） |
| 实际授予能力 | **交集**后的结果，不是你在脚本里写的清单 |
| 内存占用 | 这个插件的 JS 堆当前用量 |
| 声明能力 | 声明了什么。**从文件/粘贴导入的**读的是脚本里 `plugin.requires` 的原文；**从市场安装的**读的是索引条目里的 `permissions`（索引那份是给"不下载脚本也能看权限"用的） |
| 最近一次错误 | 加载失败的原因（加载失败时才有） |

最直接的验证：把 `demo://share/hello.bin` 交给下载框。如果任务建出来了、URL 是 `https://example.com/share/hello.bin`，插件就是活的。

**没生效怎么查**：

- 列表显示「未运行（引擎里没有它）」→ 点开关重新加载一次，失败原因会弹出来。
- 详情里「最近一次错误」非空 → 那就是加载失败的原因，最常见的两条是"脚本自报的 id 不合法"和"要求了宿主没授予的能力"。
- 页面顶部还有一张**引擎状态卡**（引擎起不来所有插件都是摆设）。底部的「引擎自检」会真跑一遍：原生库能否加载、求值+JSON 往返、超时能否中断、中断后实例是否仍可用、内存上限是否生效。
- 日志：`host.log.info/warn/error/debug` 的输出带插件 id 前缀，随「设置 → 导出日志」一起回传。

---

## 脚本结构

脚本在**加载时**整体求值一次，所以注册调用写在顶层。能用的入口一共这些：

```js
// 声明需要什么。permissions 可以是数组，也可以是 "http,crypto" 这样的字符串。
// 声明了宿主没授予的能力 -> 加载失败（比下载到一半才被拒好）。
plugin.requires({ permissions: ['http', 'crypto'], abiMajor: 1 });

// 自报身份。name 显示给人看，id/version 是市场校验用的（见「自报版本」一节）。
plugin.defineMeta({ id: 'parser.demo', name: 'Demo 解析器', version: '1.0.0' });

// 解析器：(链接) -> 请求描述符数组 | 单个描述符 | null
plugin.registerParser({ priority: 300, parse: function (input) { /* ... */ } });

// 提交前钩子：(请求描述符) -> 新描述符 | null/undefined（表示不改）
plugin.registerTaskPreHook({ beforeSubmit: function (payload) { /* payload.request */ } });

// 结束后钩子：只观察，返回值被忽略
plugin.registerTaskPostHook({ afterFinish: function (payload) { /* {request, success, detail} */ } });

// 事件监听：只观察，抛错被吞掉
plugin.onEvent(function (event) { /* {type, taskId, ...} */ });

// 生命周期：各只能注册一次
plugin.onInit(function () { /* 加载时跑，抛错会导致加载失败 */ });
plugin.onDestroy(function () { /* 卸载时跑，这是唯一允许在停止之后进入 JS 的调用 */ });
```

几条实际规则：

- **`priority` 可选**，范围 `-10000..10000`，默认 `200`。同一个扩展点里，数字大的先被问。写一个越界的数（比如 20000）会让注册失败 —— 引擎不会悄悄帮你改成默认值。
- **`onEvent` / `onInit` / `onDestroy` 各只能注册一次**，第二次调用直接抛错。
- **`onInit` 抛错 = 加载失败**。所以别在 `onInit` 里做可能失败的网络请求。
- **`onEvent` 是同步观察者**：它会在事件泵的线程上被调用，最慢可以占用一次调用预算（默认 20 秒）。抛错会被隔离并记日志，不会弄死事件泵，但一个慢监听器会让整个事件泵停那么久。

### 自报版本

`plugin.defineMeta({ id, version })` 有两个不同的用途，别混：

- **引擎**只认 `plugin.requires({abiMajor})` 和 `plugin.id`（后者由宿主传入，脚本不能自选 id）。`defineMeta` 里的 `name`/`version` 是纯文本声明，引擎拿来做诊断。
- **云取和市场**会读脚本里的 `defineMeta({ id, version })` 文本，和索引条目逐字比对。脚本字节受签名保护，所以脚本自报的版本号是**发布者签过的**；索引里的版本号没有签名。两者不一致 → 拒绝安装。这条比对是"索引不能把旧脚本标成新版本"的落点。

---

## 解析器的返回值

这是最容易写错的地方，单独说。

`parse(input)` 的入参是**一个对象**，链接原文在 `input.input`：

```js
parse: function (input) {
  var raw = String((input && input.input !== undefined) ? input.input : input);
  ...
}
```

（写成 `function (link) { ... }` 直接用字符串，在测试里也能跑，但别依赖它 —— 入参的形状是 `{input: ...}`。）

返回值只有三种合法形态：

```js
return null;                      // 不是我的链接，交给下一个解析器
return { url: 'https://…' };      // 单个描述符（引擎会当成只含一条的数组）
return [{ url: 'https://…' }, …]; // 描述符数组
```

### 描述符的字段

```js
{
  url:        'https://cdn.example/a.bin',  // 必填，http/https，最长 8192 字符
  fileName:   'a.bin',                      // 建议填：文件名，不是路径
  headers:    { 'Referer': 'https://…' },   // 可选，字符串表
  knownSize:  12345678,                     // 可选，已知的文件大小
  connections: 8,                           // 可选，1..256
  stableKey:  'demo-abc123',                // 可选，去重键，最长 200 字符
  tags:       { scheme: 'demo' }            // 可选，字符串表，给后续钩子/日志看
}
```

`connections` 这类数值字段是**严格**的：写了但越界或不是数字 → 整条结果按 `validation` 失败。引擎刻意不做"越界就回落到默认值"，因为一个自报 99999 线程却被按 8 线程跑的插件，从日志里根本看不出来。

一次 `parse` 最多返回 **256** 条描述符，超了整个结果失败（丢掉几条会改变下载清单，比失败更糟）。

### 关于 `destination`：写文件名，别写路径

描述符里可以带 `destination`，但**默认策略下只接受裸文件名**：

```js
return [{ url: url, fileName: 'a.bin' }];        // 对：宿主把它放进插件的 downloads/ 沙箱
return [{ url: url, destination: 'a.bin' }];     // 也对：等价于 fileName
return [{ url: url, destination: '/sdcard/a.bin' }];  // 被拒：含分隔符/绝对路径
```

返回路径会被**拒绝**（不是"被忽略"），错误码 `permission`，整条解析结果按不匹配处理。这是防脚本往沙箱外写文件：一个解析器返回的路径，引擎是真的会去创建并写满的。

顺带一个反直觉但正确的细节：pre-hook 里**原样回显**宿主给你的那个 `destination`（绝对路径）是允许的 —— 那个路径本来就是宿主选的。你改别的字段、把路径原样带回来，不会因此被拒；但你不能用它"洗白"一个沙箱外的路径。

### 失败会发生什么

| 你在哪失败 | 后果 |
|---|---|
| `parse` 抛错、返回脏数据、返回非法描述符 | 这个解析器被当作**不匹配**（`null`），下一个解析器接手。日志里会有一条带你插件 id 和错误码的记录 |
| `beforeSubmit` 抛错 | 请求**原样不变**地继续 |
| `afterFinish` / `onEvent` 抛错 | 记一条日志然后忽略 |

也就是说：**一个坏掉的插件不会弄坏一次下载**，它最多是"不再命中"。这也是为什么"我的解析器不工作了"必须去日志里看 —— 引擎不会弹窗告诉你。

---

## 常见错误

**1. 没声明权限，调用时才失败**

```js
plugin.defineMeta({ ... });
// 忘了 plugin.requires
var sig = host.crypto.hmac('HmacSHA256', key, data);  // 抛 permission 错
```

声明了没用的能力不会报错，但**用了没声明的能力**会在运行时抛一个带 `code: 'permission'` 的错。养成"用到什么就声明什么"的习惯，用户装之前也会看这份清单。

**2. 声明了宿主没授予的能力 → 整个插件加载失败**

宿主（云取）默认只授予 `http`、`crypto`、`log`、`time`。`storage`、`env`、`timer` 需要宿主显式打开。所以在云取里写：

```js
plugin.requires({ permissions: ['http', 'storage'] });   // 加载会失败
```

加载失败的表现是插件列表里「未运行」+ 详情里有一条错误说明。这不是 bug，是"别让插件下载到一半才因为一个被拒的能力失败"。

**3. `id` 不合法**

`id` 只允许 `[a-z0-9.-]`，长度 3..64，首尾不能是 `.` 或 `-`，不能含 `..`。`Parser.Demo`（大写）、`demo`（太短）、`parser..demo` 都会被拒 —— 声明了不合法的 id 时**直接拒绝安装**，不会悄悄退回按文件名派生（那会让你以为装的是声明的那个插件）。

`id` 会参与脚本落盘路径，所以这条限制是安全边界，不是审美偏好。

**4. 返回了对象而不是数组**

```js
return { url: url, fileName: name };     // 引擎接受：会当成单条
return { files: [{ url: url }] };        // 不行：这不是描述符，缺 url -> validation 失败
```

想返回"多文件分享"就返回数组，一条文件一个元素。别自己包一层 `{files: [...]}`。

**5. 在 `parse` 里做长时间网络请求**

单次 JS 求值的预算是 **5 秒**（只计 JS 时间），一次调用的总预算是 **20 秒**（JS + 宿主调用）。超了会被强制中断，`parse` 变成"不匹配"。

要点：

- 给每个 `host.http.request` 显式写 `timeoutMillis`（示例里给的 3000 就够了），别让它去撞 30 秒的默认值；
- 用 `maxBytes` 限制响应体（比如 64 KiB），别把一整个 HTML 页面拉进来；
- 网络请求**必须**有失败回退：拿不到直链就回退到确定性 URL，或者返回 `null`。示例插件里的 `resolveThroughApi` 就是这个形状 —— 任何异常都吞掉并回退。

**6. 在 `onInit` 里做会失败的事**

`onInit` 抛错等于加载失败。初始化只做"记一条日志"、"读一次自己的配置"这类不会失败的事。

**7. 忘了 `plugin.id` 不是你能设的**

`plugin.id` 由宿主在脚本运行前写入（云取用的是你安装时那个 id）。`plugin.defineMeta({ id })` 里的 id 是给云取和市场读的文本声明，不会改变引擎侧的 id。两者应当一致。

---

## 下一步

- 每个函数、每个参数、每个上限：[ABI 参考](PLUGIN-API.md)
- 打包、签名、提 PR、自建源：[打包与发布](PLUGIN-PUBLISH.md)
- 信任等级、校验链、以及"签名有效"到底证明了什么：[安全模型](PLUGIN-SECURITY.md)
