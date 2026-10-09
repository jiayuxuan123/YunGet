# 打包、签名与发布

这篇讲怎么把一个 `.js` 插件变成用户能装的东西：清单怎么写、签名怎么算、怎么提到官方源、怎么自建源、怎么发新版本。

先读 [写一个云取插件](PLUGIN-DEV.md) 把插件本身跑通；这篇只关心"发布"这一半。

---

## 一个插件目录长什么样

```
plugins/<插件 id>/
├─ <插件 id>.js            插件本体（签名的就是它的原始字节）
├─ turbodl-plugin.json     清单（字段与 TurboDL 的 schema 一致）
├─ market.json             只放清单里没有的市场字段（简介、标签、最低宿主版本）
└─ README.md               可选：写给人看的说明
```

**目录名必须等于清单里的 `id`**。工具和 App 都靠它定位，不一致时 `index` 会直接报错。

参考实现：[`plugins/parser.example-cloud/`](https://github.com/jiayuxuan123/YunGet-Plugins/tree/main/plugins/parser.example-cloud)。

---

## `turbodl-plugin.json`

字段定义来自 TurboDL 的 [`turbodl-plugin.schema.json`](https://github.com/jiayuxuan123/TurboDL/blob/main/docs/plugins/turbodl-plugin.schema.json)。这个 schema 是 `additionalProperties: false` —— **多写一个字段就会被拒**，别自己造字段。

```json
{
  "manifestVersion": "1.0",
  "id": "parser.mycloud",
  "name": "MyCloud Parser",
  "description": "把 mycloud:// 分享链接解析成可下载的 https 请求。",
  "version": "1.0.0",
  "author": "你的名字",
  "homepage": "https://github.com/you/mycloud-plugin",
  "license": "MIT",
  "category": "turbodl-parser",
  "capabilities": ["dev.turbodl.cap.parser.mycloud"],
  "protocols": ["mycloud"],
  "turbodl": { "apiMajor": 1, "requiredApiVersion": "1.0.0" },
  "entry": { "language": "js", "pluginClass": "parser.mycloud.js" },
  "extensionPoints": ["turbo.linkParser"],
  "services": []
}
```

### 必填与可选

| 字段 | 必填 | 说明 |
|---|---|---|
| `manifestVersion` | ✅ | 只能是 `"1.0"` |
| `id` | ✅ | `^[a-z0-9]+(\.[a-z0-9][a-z0-9-]*)+$`，全小写，长度 **3..64** |
| `name` | ✅ | 非空字符串 |
| `version` | ✅ | semver，`MAJOR.MINOR.PATCH[-prerelease][+build]` |
| `category` | ✅ | 见下面的取值表 |
| `turbodl` | ✅ | 对象，含 `apiMajor`（≥ 1 的整数）与 `requiredApiVersion`（`X.Y.Z`） |
| `entry` | ✅ | 对象，含 `language`（`kotlin` / `js`）与 `pluginClass` |
| `description` / `author` / `homepage` / `license` | | 字符串 |
| `capabilities` | | 命名空间能力 id 数组，**不能重复** |
| `protocols` | | 协议数组，**不能重复** |
| `artifact` | | 对象：`type` 是 `maven`（需 `coordinates`）或 `jar`（需 `url`） |
| `extensionPoints` / `services` | | 字符串数组，不能重复 |

### `category` 的六个取值

| 值 | 什么时候用 |
|---|---|
| `turbodl-protocol` | 插件声明它处理哪些协议/方案（`protocols` 字段），**也可以**实现一个下载后端 |
| `turbodl-backend` | 插件的身份就是实现一个下载后端 |
| `turbodl-parser` | 链接解析器 |
| `turbodl-hook` | 任务钩子 |
| `turbodl-adapter` | 适配器 |
| `turbodl-loader` | 加载别种语言写的插件（`turbo-plugin-js` 自己就是这个） |

`turbodl-protocol` 是给"我要声明我认哪些协议"的插件准备的落点 —— 单纯写解析器的插件用 `turbodl-parser` 就行。

### `capabilities` 必须是命名空间形式

```
dev.turbodl.cap.<名字>          ✅
dev.turbodl.cap.parser.mycloud  ✅
turbodl-parser                  ❌ 裸串会被拒
hls                             ❌
```

正则：`^[a-z0-9]+(\.[a-z0-9-]+)+\.cap\.[a-z0-9][a-z0-9.-]*$`。命名空间的作用是防止两个厂商同名能力撞车。项目自己的命名空间是 `dev.turbodl.cap.*`（比如 `dev.turbodl.cap.hls`）。

**能力是一个市场/诊断标签：它不授予任何权限、不改变任何行为。** 想让插件能上网，要写的是脚本里的 `plugin.requires({permissions: ['http']})`，跟 `capabilities` 是两回事。别把这两个概念搞混 —— 也别指望在 `capabilities` 里写 `http` 就能拿到 `http` 权限。

### `protocols`：一个插件可以声明多个

```json
"protocols": ["magnet", "bt", "ed2k"]
```

- 格式：`^[a-z][a-z0-9+.-]{0,31}$`（小写、像 URL scheme 的 token）。
- **一个插件可以声明多个协议** —— 生态模型是"一个插件覆盖多个协议"，不是"一个协议一个插件"。宿主按这些声明建立"scheme → 插件"的索引，用于发现、市场筛选、诊断和冲突报告。
- 声明**不授予权限，也不自己路由下载** —— 真正决定谁处理一个请求的仍然是后端/解析器。
- 注意内容类型不是 URL scheme：一个 `.m3u8` 的 URL，scheme 是 `http(s)`。所以这里写的是"市场应该用它来认识这个插件"的名字。

### `entry` 对 JS 插件怎么写

```json
"entry": { "language": "js", "pluginClass": "parser.mycloud.js" }
```

`language` 是 `js` 时，`pluginClass` 就是**插件目录内的脚本文件名**（工具允许写成 `main.js` 或 `./main.js`，但不接受绝对路径或含 `..` 的路径，且必须以 `.js` 结尾）。

---

## `market.json`

清单里没有、但市场需要的字段。**只放这些键**，多一个都会被拒：

| 键 | 必填 | 说明 |
|---|---|---|
| `minHostVersion` | ✅ | 最低宿主版本（semver）。App 低于它就不给装 —— `index` 缺这个字段会报错 |
| `summary` | | 一句话简介（列表页显示） |
| `description` | | 较长描述（详情页显示，可含 Markdown） |
| `tags` | | 字符串数组 |
| `changelog` | | 这次发布的说明 |
| `releasedAt` | | UTC ISO-8601，形如 `2026-10-09T00:00:00Z`。**固定它可以让索引可复现** |
| `permissions` | | 冗余字段：与脚本 `plugin.requires` 不一致时工具会警告，并以**脚本**为准 |

`permissions` 之所以在两边都有：脚本是权威（它受签名保护），索引里的这份是给"不想下载脚本就能看到权限清单"的场景用的。

---

## 签名

工具在官方源仓库里：[`YunGet-Plugins/tools/sign_plugin.py`](https://github.com/jiayuxuan123/YunGet-Plugins/blob/main/tools/sign_plugin.py)。Ed25519 **不在 Python 标准库里**，所以它优先用 `cryptography`（装了就用），没装就回退到内置的纯标准库 RFC 8032 实现 —— 两条路产出的密钥与签名逐字节一致，`selftest` 会交叉验证这一点。所以签名/验签**零依赖可用**。

```bash
python tools/sign_plugin.py selftest    # 先自检：RFC 8032 官方向量、PEM 往返、与 cryptography 交叉验证、semver
```

### 签名载荷：脚本的原始字节

> **签名对象 = 脚本文件的原始字节（raw bytes）。一个字节都不多、一个字节都不少。**

```
signature = Ed25519_sign(privateKey, bytes_of(<script>.js))
```

具体含义：

- **不剥 BOM**、不做行尾归一化、不做 Unicode 归一化、不重新编码、不追加换行；
- **不是**"先算 sha256 再签摘要"—— `sha256` 与 `signature` 覆盖的是**同一串字节**，所以 App 侧两条检查互相印证：

```
sha256(下载到的字节) == versions[i].sha256        # 内容没被改
Ed25519_verify(下载到的字节, signature, pubkey)   # 内容由私钥持有者发布
```

这个约定对仓库和发布流程有硬约束：

- 脚本文件必须 **UTF-8 无 BOM、换行统一 LF**（仓库的 `.gitattributes` 里 `*.js -text` 就是禁止 Git 改行尾）；
- **发布后不得改写已发布版本的字节** —— 改了字节就是改了签名，必须升版本号。`index` 会拦住你；
- 别在签名之后再用编辑器"顺手保存"一次（那可能改掉行尾或补上末尾换行）。

### 四个子命令

```bash
# 1) 生成密钥对。私钥 0600 落盘，公钥可以直接进仓库
python tools/sign_plugin.py keygen --key-id my-id-2026 --out-dir keys

# 2) 签一个脚本，输出能直接粘进 plugins.json 的 JSON 片段
#    （sha256 / sizeBytes / signature / keyId）
python tools/sign_plugin.py sign plugins/parser.mycloud/parser.mycloud.js \
    --key keys/my-id-2026.key --key-id my-id-2026

# 3) 验签（这就是 App 侧验签逻辑的参考实现），退出码 0/1
python tools/sign_plugin.py verify plugins/parser.mycloud/parser.mycloud.js \
    --signature "<plugins.json 里的 signature>" --key-id my-id-2026

# 4) 扫 plugins/*/ 重新生成整个索引（含真实 sha256 与签名，避免手抄出错）
python tools/sign_plugin.py index --key keys/my-id-2026.key --key-id my-id-2026
```

`index` 的常用参数：

| 参数 | 默认 | 说明 |
|---|---|---|
| `--plugins-dir` | `plugins` | 插件目录 |
| `--out` | `plugins.json` | 输出索引 |
| `--key` / `--key-id` | 必填 | 私钥与写入条目的 keyId |
| `--repo` / `--branch` | `jiayuxuan123/YunGet-Plugins` / `main` | 用来推导 raw 下载地址与仓库链接 |
| `--raw-base` | 按上面两个推导 | 覆盖 raw 地址前缀（自建源/镜像用） |
| `--source-id` / `--source-name` / `--trust-level` | `official` / `云取官方插件源` / `official` | 写进索引的 `source` 块 |
| `--generated-at` | 当前 UTC 时间 | 固定它便于复现 |
| `--allow-rebuild` | 关 | 允许改写"同版本但字节已变"的条目（默认拒绝） |
| `--check` | 关 | 只检查索引是否与 `plugins/` 同步，不写文件（CI 用，退出码 0/1） |

`index` 会拦住四类错误：

1. 清单不合规（缺必填、枚举值错、多了未知字段）；
2. 目录名 ≠ `manifest.id`，或 `id` 重复；
3. **脚本自报的 `id`/`version` 与清单不一致**（App 安装时会因为这条比对拒绝，所以工具先拦）；
4. **版本号没变但脚本字节变了**（已发布版本的字节不可改写）—— 真要返工加 `--allow-rebuild` 显式表态。

它还会给出两类警告：脚本声明了引擎不认识的能力；脚本声明了宿主默认不授予的能力（`storage` / `env` / `timer`）。

### keyId 与公钥的对应关系

| 项 | 位置 | 进仓库吗 |
|---|---|---|
| 公钥 | `keys/<keyId>.pub`（PEM，标准 SPKI） | **要**，就是给 App 用的 |
| 私钥 | `keys/<keyId>.key`（PEM，PKCS#8） | **绝不**，已被 `.gitignore` 拦下 |

- `keyId` 形如 `<用途>-<年份>[-<序号>]`，只允许小写字母、数字、`.`、`_`、`-`（要当文件名用）：`official-2026`、`community-2027-2`。
- 索引里的每个版本条目写它自己的 `keyId`；App 拿这个 `keyId` 去找公钥：**先找应用内置的**，找不到再按源的 `publicKeyUrl` 去取同目录下的 `<keyId>.pub`。找不到公钥 → **拒绝安装**（不会"找不到就跳过验签"）。
- 私钥只是 32 字节的种子。**离线保存**；丢了就等于这个 keyId 再也签不出被信任的更新。生成后第一件事是备份。
- 轮换、吊销、私钥泄露的处理见源仓库的 [`keys/README.md`](https://github.com/jiayuxuan123/YunGet-Plugins/blob/main/keys/README.md)。

---

## 发布到官方源

官方源就是 [YunGet-Plugins](https://github.com/jiayuxuan123/YunGet-Plugins) 这个仓库：一份 JSON 索引 + 插件源码 + 公钥，没有服务器、没有后台。

### 流程

1. **Fork 仓库**，新建分支（如 `add-parser-mycloud`）。
2. 建目录 `plugins/<你的插件 id>/`，目录名**必须等于**清单里的 `id`。
3. 放齐 `<id>.js`、`turbodl-plugin.json`、`market.json`（+ 可选的 README）。
4. 在仓库根目录跑一遍，确认工具能过：

   ```bash
   python tools/sign_plugin.py selftest
   python tools/sign_plugin.py index --key keys/<你的私钥>.key --key-id <你的 keyId> --check
   ```

   想本地生成一份带自己签名的索引看看效果也可以，但**PR 里通常不要提交 `plugins.json` 的改动** —— 官方源的索引由**官方密钥**签，合并后由维护者用自己的密钥重新生成，这样 `keyId` 才一致（`index` 会拒绝生成"条目 keyId 与本次签名 keyId 不一致"的索引）。

5. 提 PR，说明：插件做什么、支持哪些链接、声明了哪些权限、**为什么需要这些权限**。
6. 维护者审核合并 → 重新生成 `plugins.json` → 用户在 App 里就能看到。

社区插件应当由**你自己**的 keyId 签名；`keygen` 生成的私钥放在你本机，公钥随 PR 一起进 `keys/`。

### PR 会被检查什么

会被拒的情况：

- 清单或 id 不合规（字段缺失、枚举值错、多了未知字段、目录名 ≠ id）；
- 脚本自报的 `id`/`version` 与清单不一致；
- 声明了权限却看不出用在哪（或反过来：偷偷用了没声明的能力）；
- 文件名/路径处理不洗输入（返回的 `destination` 带路径、文件名没过滤）；
- 带了混淆代码 —— **不合并混淆过的脚本**，这是可读性要求；
- 与已有插件重复且没有改进。

维护者还会核对：`minHostVersion` 是否合理、`protocols` 声明是否与实际支持的一致、README 是否说清了用法。

---

## 发布到自己的源

官方源不是唯一的选择。你可以自建一个源，让用户自己添加。

### 你需要准备什么

一个能通过 https 访问的静态目录（GitHub Pages、对象存储、你自己的服务器都行），里面放：

```
plugins.json        ← 索引（App 要拉的那个文件）
keys/<keyId>.pub    ← 公钥（App 会按 keyId 去同目录取）
<脚本文件>           ← 插件脚本，路径由索引里的 downloadUrl 决定
```

### 怎么做

```bash
# 1) 生成你自己的密钥（私钥绝不外传）
python tools/sign_plugin.py keygen --key-id my-2026 --out-dir keys

# 2) 生成索引，把地址指到你的仓库/站点
python tools/sign_plugin.py index \
    --key keys/my-2026.key --key-id my-2026 \
    --repo yourname/your-plugins --branch main \
    --source-id community-mine --source-name "我的插件源" --trust-level community

# 3) 把 plugins.json、keys/my-2026.pub 和脚本一起传上去
```

如果 raw 地址不是 GitHub 的形态（比如你自己服务器上的静态目录），用 `--raw-base https://你的域名/plugins` 覆盖，`downloadUrl` 与 `publicKeyUrl` 会按它生成。

### 用户怎么加

云取 → **设置 → 插件市场** → **添加插件源** → 填索引地址（`.../plugins.json`）→ 选信任等级 → 「只添加源」。

**加源与装插件是两个独立动作**，这一点在界面上也是这么做的：添加源只登记地址并拉取索引，**绝不顺手把里面的插件装进来**。加完之后用户还得自己点进某个插件、看过权限、再确认一次。这样"我从哪拿到这个脚本的"永远是用户主动做过的决定，而不是加源时的副作用。

### 自建源默认按不受信处理

- 用户添加源时**默认等级是 `community`**，不是"已验证"。新加的源本来就还没被任何人核验过，默认给"已验证"等于替用户做了信任决定。
- 索引里自称的 `trustLevel` **不是**决定性的：App 会做一次覆盖，当索引自称的等级**高于**用户给的等级时取用户的（保守方向）。所以你在索引里写 `"trustLevel": "official"` 没有任何用 —— 那只是自我描述。
- `official` 需要两个条件同时满足：源的身份是官方，**且**签名公钥是随应用内置的那把。第三方源就算用了同一把公钥也不会被当成官方。
- **等级只影响显示与提醒，不影响校验**：无论哪一级，安装时都要求 `sha256` + 签名齐备。低等级不是"可以少验一点"，而是"验完了仍然要提醒你"。

---

## 更新一个已发布的插件

改脚本 → 升版本号 → 重新签名 → 重新生成索引。步骤：

1. 改 `<id>.js`；
2. **同时改两处版本号**：脚本里的 `plugin.defineMeta({ version })` **和** `turbodl-plugin.json` 的 `version`（两处必须逐字相等）；
3. 在 `market.json` 里写这次的 `changelog`（`releasedAt` 可留空 = 用当天时间）；
4. 重新生成索引：

   ```bash
   python tools/sign_plugin.py index --key keys/<keyId>.key --key-id <keyId>
   ```

5. `git diff plugins.json` 看一眼：新版本排在 `versions` 最前面，带上了新的 `sha256` 与 `signature`；
6. 提交、推送。用户拉到新索引就会提示更新。

### 版本号必须真的递增

App 的更新判据是 **semver 比大小**，不是时间戳：

- 索引里最新版本的版本号**严格大于**本地已装版本，才提示更新；
- 相等 → 没有更新；
- 索引更小 → 这是回滚，App 会拒绝而不是静默降级；
- `releasedAt` / `generatedAt` 只用于展示，**绝不能**用来判断谁更新。

所以：**改了脚本却不升版本号，用户永远收不到更新**（而且 `index` 会因为"同版本但字节变了"直接报错）。预发布版本比正式版小（`1.0.0-rc.1 < 1.0.0`），比较规则按 semver 2.0.0 §11。

另外：`versions[0]` 必须**同时**带 `sha256` 与 `signature`，缺任何一个 App 一律拒绝更新（不是"跳过校验继续装"）。这是防止有人用一条残缺条目把已装插件替换成未签名的东西。

---

## 下架

删掉 `plugins/<id>/` 再跑一次 `index`，条目与它的版本历史会一起消失。已经装过的用户不受影响（他们本地还有脚本），但不会再有更新。

如果想保留历史但停止发布，把 `plugins/<id>/` 里的脚本删掉、只留一个 README 说明也可以 —— 但那样 `index` 会报错找不到脚本，所以更干净的做法是直接从 `plugins/` 移走，并把历史留在 git 里。

---

## 索引格式（给实现源的人）

`plugins.json` 的骨架：

```jsonc
{
  "schemaVersion": 1,                     // 索引格式版本；不兼容的改动 +1，App 遇到不认识的版本应拒绝解析
  "generatedAt": "2026-10-09T12:00:00Z",  // 不参与更新判断
  "source": {
    "id": "official",
    "name": "云取官方插件源",
    "trustLevel": "official",             // official / verified / community / untrusted
    "homepage": "https://github.com/…",
    "publicKeyUrl": "…/keys/official-2026.pub",
    "keyIds": ["official-2026"]
  },
  "verification": {                       // 自描述块：说明签名怎么验（App 可忽略）
    "algorithm": "ed25519",
    "payload": "raw-script-bytes",
    "keyIdField": "keyId",
    "signatureField": "signature",
    "publicKeyUrl": "…",
    "doc": "对下载到的脚本原始字节验签；sha256 与 signature 覆盖同一串字节；另需比对脚本自报的 defineMeta({id,version}) 与索引条目相等"
  },
  "plugins": [
    {
      "id": "parser.mycloud",             // 与 manifest.id、脚本自报 id 三者必须一致
      "name": "MyCloud Parser",
      "summary": "…", "description": "…",
      "author": "…", "license": "MIT",
      "category": "turbodl-parser",
      "protocols": ["mycloud"],           // 可用于按协议筛选
      "capabilities": ["dev.turbodl.cap.parser.mycloud"],
      "tags": ["解析"],
      "homepage": "…", "sourceUrl": "…", "manifestUrl": "…",
      "versions": [
        {
          "version": "1.0.0",             // semver —— 更新判断只看它
          "releasedAt": "2026-10-09T00:00:00Z",
          "downloadUrl": "…/parser.mycloud.js",
          "sha256": "…",                  // 脚本原始字节的 SHA-256（64 位小写十六进制）
          "sizeBytes": 8111,
          "minHostVersion": "2.6.22",
          "entryLanguage": "js",
          "permissions": ["http", "crypto"],
          "signature": "…",               // base64 的 Ed25519 签名（64 字节）
          "keyId": "my-2026",
          "changelog": "…",
          "manifest": { /* 该版本的清单快照，App 可忽略并自行拉取 */ }
        }
        // …更旧的版本排在后面（按 semver 从新到旧）
      ]
    }
  ]
}
```

三条硬约定：

1. **`versions` 按版本从新到旧排**。App 取 `versions[0]` 就是最新版；它自己也会重新挑一次最大值（不盲信顺序）。
2. **更新判据只有 semver**，不看时间戳。
3. **`versions[0]` 必须同时带 `sha256` 与 `signature`**。

字段级校验规则（`isVerifiable`）：`downloadUrl` 非空、`sha256` 是 64 位小写十六进制、`signature` 非空、`keyId` 非空。

---

## App 侧到底校验了什么（按代码，不按文档）

这一节是为了让源作者知道"写什么才真的会被检查"。安装与更新**共用同一条链路**，顺序是：

1. 索引条目必须齐备 `downloadUrl` + `sha256`（64 位小写 hex）+ `signature` + `keyId`，缺任一 → 拒绝；
2. `minHostVersion` ≤ 本机应用版本（比较用 semver）；
3. 索引声明的 `sizeBytes` ≤ 512 KiB（下载前先挡一次）；下载后再按**实际字节数**检查一次，并比对是否等于 `sizeBytes`（`sizeBytes` 为 0 时跳过比对）；
4. `sha256(下载到的原始字节)` 必须等于索引里的值（大小写不敏感）；
5. 按 `keyId` 找公钥（**内置 → 源的 `publicKeyUrl` 同目录下的 `<keyId>.pub`**），找不到 → 拒绝；Ed25519 对**原始字节**验签；
6. 解析脚本自报的 `defineMeta({ id, version })`，与索引条目**逐字比对**；不一致 → 拒绝（脚本没自报则不拦）；
7. 全部通过才落盘：先写新文件（文件名含 sha 前 8 位），成功后再切库里的记录，中途失败不覆盖已装版本。

第 6 步是"版本号不可被索引伪造"的落点：脚本字节受签名保护，所以脚本里写的版本号是发布者签过的；索引里的版本号没有签名。两者不一致说明索引在谎报版本。

> **一处需要你知道的实情**：应用目前**不下载、也不校验 `turbodl-plugin.json`**。索引条目里那份 `manifest` 快照、以及 `manifestUrl`，都只是给详情页/第三方工具用的数据，不参与安装校验。也就是说 `turbodl.apiMajor` / `requiredApiVersion` 的兼容性判断目前**没有**在 App 侧执行 —— 真正会拦下你的是脚本加载时引擎的 ABI 握手（`plugin.requires({abiMajor})` 与加载器实现的主版本不匹配 → 加载失败）。写清单时仍然要把 `turbodl` 填对（工具会校验它的形状），但别指望 App 会替你挡下 ABI 不匹配。

---

## 相关的其它文档

- [写一个云取插件](PLUGIN-DEV.md) —— 从零写一个能跑的插件
- [插件 ABI 参考](PLUGIN-API.md) —— 每个函数、每个上限
- [安全模型](PLUGIN-SECURITY.md) —— 信任四级、校验链，以及不做的承诺
