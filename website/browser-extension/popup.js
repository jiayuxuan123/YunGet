"use strict";

const DEFAULT_SETTINGS = {
  relayPort: 17890,
  interceptEnabled: true,
  minSizeMb: 1,
  sniffEnabled: true
};

const providerRules = [
  ["夸克网盘", ["pan.quark.cn"]],
  ["UC 网盘", ["drive.uc.cn"]],
  ["迅雷网盘", ["pan.xunlei.com"]],
  ["百度网盘", ["pan.baidu.com"]],
  ["139 网盘", ["caiyun.139.com"]],
  ["123 云盘", ["123pan.com", "123684.com", "123865.com"]],
  ["115 网盘", ["115.com", "115cdn.com"]],
  ["小飞机网盘", ["feijipan.com", "feijix.com"]],
  ["城通网盘", ["ctfile.com"]],
  ["阿里云盘", ["alipan.com", "aliyundrive.com"]],
  ["天翼云盘", ["cloud.189.cn"]],
  ["腾讯微云", ["weiyun.com"]],
  ["联通沃盘", ["pan.wo.cn"]],
  ["PikPak", ["mypikpak.com", "pikpak.com"]],
  ["GitHub", ["github.com"]],
  ["Bilibili", ["bilibili.com"]],
  ["抖音", ["douyin.com"]],
  ["快手", ["kuaishou.com"]],
  ["小红书", ["xiaohongshu.com"]]
];

const pcStateNode = document.getElementById("pc-state");
const interceptToggle = document.getElementById("intercept-toggle");
const minSizeSelect = document.getElementById("min-size");
const sniffToggle = document.getElementById("sniff-toggle");
const portInput = document.getElementById("port");
const sniffCountNode = document.getElementById("sniff-count");
const sniffListNode = document.getElementById("sniff-list");
const rescanSniffBtn = document.getElementById("rescan-sniff");
const clearSniffBtn = document.getElementById("clear-sniff");
const providerNode = document.getElementById("provider");
const urlNode = document.getElementById("url");
const sendPcButton = document.getElementById("send-pc");
const copyButton = document.getElementById("copy");
const statusNode = document.getElementById("status");

let currentTab = null;

function hostMatches(host, base) {
  return host === base || host.endsWith(`.${base}`);
}

function providerFor(rawUrl) {
  try {
    const host = new URL(rawUrl).hostname.toLowerCase();
    for (const [name, hosts] of providerRules) {
      if (hosts.some((base) => hostMatches(host, base))) {
        return `${name} · ${host}`;
      }
    }
    return `普通网页 · ${host}`;
  } catch {
    return "当前页面没有可发送的网址";
  }
}

function formatSize(bytes) {
  if (!bytes || bytes <= 0) return "大小未知";
  if (bytes < 1024) return `${bytes} B`;
  const kb = bytes / 1024;
  if (kb < 1024) return `${kb.toFixed(1)} KB`;
  const mb = kb / 1024;
  if (mb < 1024) return `${mb.toFixed(1)} MB`;
  return `${(mb / 1024).toFixed(2)} GB`;
}

function getPort() {
  const n = Number(portInput.value);
  return Number.isInteger(n) && n >= 1024 && n <= 65535 ? n : DEFAULT_SETTINGS.relayPort;
}

async function writeClipboard(text) {
  try {
    await navigator.clipboard.writeText(text);
    return;
  } catch {
    const area = document.createElement("textarea");
    area.value = text;
    area.setAttribute("readonly", "");
    document.body.appendChild(area);
    area.select();
    const copied = document.execCommand("copy");
    area.remove();
    if (!copied) throw new Error("copy failed");
  }
}

async function checkPcOnline() {
  const port = getPort();
  try {
    const resp = await fetch(`http://127.0.0.1:${port}/ping`, { method: "GET" });
    if (resp.ok) {
      pcStateNode.textContent = "桌面端已在线";
      pcStateNode.className = "badge-status online";
      return true;
    }
  } catch {}
  pcStateNode.textContent = "桌面端未连接";
  pcStateNode.className = "badge-status offline";
  return false;
}

async function sendViaBackground(payload) {
  const resp = await chrome.runtime.sendMessage({ type: "SEND_TO_PC", ...payload });
  if (!resp?.ok) throw new Error(resp?.error || "发送失败");
}

// 在当前标签页 DOM 中主动扫描可下载直链、音视频元素、磁力链接与网盘分享链接
function scanPageDomResources() {
  const DOWNLOAD_EXTS = {
    apk: "安装包", msi: "安装包", exe: "安装包", dmg: "安装包", pkg: "安装包", deb: "安装包", rpm: "安装包", appimage: "安装包",
    zip: "压缩包", rar: "压缩包", "7z": "压缩包", tar: "压缩包", gz: "压缩包", xz: "压缩包", bz2: "压缩包", zst: "压缩包",
    iso: "镜像", img: "镜像",
    m3u8: "HLS", m3u: "HLS", mpd: "DASH",
    mp4: "视频", mkv: "视频", webm: "视频", flv: "视频", mov: "视频", m4v: "视频", avi: "视频",
    mp3: "音频", m4a: "音频", flac: "音频", wav: "音频", aac: "音频", ogg: "音频",
    torrent: "种子", pdf: "文档", epub: "文档"
  };
  const CLOUD_HOSTS = [
    "pan.quark.cn", "drive.uc.cn", "pan.xunlei.com", "pan.baidu.com",
    "caiyun.139.com", "123pan.com", "123684.com", "123865.com",
    "115.com", "115cdn.com", "feijipan.com", "feijix.com", "ctfile.com",
    "alipan.com", "aliyundrive.com", "cloud.189.cn", "weiyun.com", "pan.wo.cn", "mypikpak.com"
  ];
  const seen = new Set();
  const found = [];
  const pageUrl = location.href;

  function extOf(u) {
    try {
      const p = new URL(u, pageUrl).pathname;
      const base = p.split("/").pop() || "";
      const dot = base.lastIndexOf(".");
      return dot > 0 && dot < base.length - 1 ? base.slice(dot + 1).toLowerCase() : "";
    } catch { return ""; }
  }

  function nameOf(u, downloadAttr, labelText) {
    if (downloadAttr && downloadAttr.trim()) return downloadAttr.trim();
    try {
      const seg = decodeURIComponent(new URL(u, pageUrl).pathname.split("/").pop() || "").trim();
      if (seg && seg.includes(".")) return seg;
    } catch {}
    const txt = (labelText || "").replace(/\s+/g, " ").trim();
    return txt ? txt.slice(0, 60) : u;
  }

  function pushItem(url, name, kind, mode) {
    if (!url || seen.has(url)) return;
    if (/^(javascript:|mailto:|tel:|#)/i.test(url)) return;
    seen.add(url);
    found.push({
      url,
      name: name || url,
      kind,
      size: 0,
      mode: mode || "download",
      headers: { Referer: pageUrl },
      fromDom: true
    });
  }

  // 1. <video> / <audio> / <source>
  for (const el of document.querySelectorAll("video, audio")) {
    const src = el.currentSrc || el.getAttribute("src") || "";
    if (src && /^https?:\/\//i.test(src)) {
      const ext = extOf(src);
      const kind = DOWNLOAD_EXTS[ext] || (el.tagName === "AUDIO" ? "音频" : "视频");
      pushItem(src, nameOf(src, "", document.title), kind, "download");
    }
    for (const s of el.querySelectorAll("source[src]")) {
      const sUrl = s.src || "";
      if (sUrl && /^https?:\/\//i.test(sUrl)) {
        const ext = extOf(sUrl);
        const kind = DOWNLOAD_EXTS[ext] || (el.tagName === "AUDIO" ? "音频" : "视频");
        pushItem(sUrl, nameOf(sUrl, "", document.title), kind, "download");
      }
    }
  }

  // 2. <a href="..."> 可下载文件、磁力链、网盘分享链接
  for (const a of document.querySelectorAll("a[href]")) {
    const rawHref = (a.getAttribute("href") || "").trim();
    if (!rawHref || rawHref.startsWith("#") || /^javascript:/i.test(rawHref)) continue;
    const href = a.href || rawHref;
    if (/^magnet:\?/i.test(href)) {
      const dnMatch = /[?&]dn=([^&]+)/i.exec(href);
      let dn = "磁力链接";
      if (dnMatch) { try { dn = decodeURIComponent(dnMatch[1]); } catch {} }
      pushItem(href, dn, "磁力", "download");
      continue;
    }
    if (!/^https?:\/\//i.test(href)) continue;

    let host = "";
    try { host = new URL(href).hostname.toLowerCase(); } catch { continue; }
    const ext = extOf(href);
    const hasDownloadAttr = a.hasAttribute("download");
    const isReleaseAsset = /\/releases\/download\//i.test(href);

    if (ext && DOWNLOAD_EXTS[ext]) {
      pushItem(href, nameOf(href, a.getAttribute("download"), a.textContent), DOWNLOAD_EXTS[ext], "download");
    } else if (hasDownloadAttr || isReleaseAsset) {
      pushItem(href, nameOf(href, a.getAttribute("download"), a.textContent), "直链", "download");
    } else if (CLOUD_HOSTS.some((base) => host === base || host.endsWith(`.${base}`))) {
      // 跳过首页/登录页，保留分享链接或带路径的资源链接
      try {
        const p = new URL(href).pathname;
        if (p && p !== "/" && !/\/(login|signin|sign\/in)/i.test(p)) {
          pushItem(href, nameOf(href, "", a.textContent || host), "网盘", "resolve");
        }
      } catch {}
    }
    if (found.length >= 60) break;
  }

  return found;
}

async function scanAndRenderSniffed() {
  if (currentTab?.id == null) {
    renderSniffed([]);
    return;
  }
  const netResp = await chrome.runtime.sendMessage({ type: "GET_SNIFFED", tabId: currentTab.id }).catch(() => null);
  const netItems = Array.isArray(netResp?.items) ? netResp.items : [];

  let domItems = [];
  if (currentTab.url && /^https?:\/\//i.test(currentTab.url)) {
    try {
      const results = await chrome.scripting.executeScript({
        target: { tabId: currentTab.id },
        func: scanPageDomResources
      });
      if (Array.isArray(results) && Array.isArray(results[0]?.result)) {
        domItems = results[0].result;
      }
    } catch {}
  }

  // 合并去重：网络实时嗅探优先（含真实大小与捕获头），页面扫描补充静态直链/网盘链接
  const seen = new Set();
  const merged = [];
  for (const item of [...netItems, ...domItems]) {
    if (!item?.url || seen.has(item.url)) continue;
    seen.add(item.url);
    merged.push(item);
  }
  renderSniffed(merged);
}

function renderSniffed(items) {
  sniffCountNode.textContent = String(items.length);
  sniffListNode.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "当前页面未发现可下载直链或媒体资源；播放视频后可点右上角「扫描本页」。";
    sniffListNode.appendChild(empty);
    return;
  }
  for (const item of items) {
    const li = document.createElement("li");
    li.className = "sniff-item";

    const meta = document.createElement("div");
    meta.className = "sniff-meta";

    const top = document.createElement("div");
    top.className = "sniff-top";
    const tag = document.createElement("span");
    tag.className = "tag";
    tag.textContent = item.kind || "资源";
    const name = document.createElement("span");
    name.className = "sniff-name";
    name.textContent = item.name || item.url;
    name.title = item.name || item.url;
    top.append(tag, name);

    const sub = document.createElement("div");
    sub.className = "sniff-sub";
    const sizePrefix = item.size > 0 ? `${formatSize(item.size)} · ` : (item.fromDom ? "页面直链 · " : "");
    sub.textContent = `${sizePrefix}${item.url}`;
    sub.title = item.url;

    meta.append(top, sub);

    const btns = document.createElement("div");
    btns.className = "sniff-btns";

    const isResolve = item.mode === "resolve";
    const dlBtn = document.createElement("button");
    dlBtn.type = "button";
    dlBtn.className = "primary mini-btn";
    dlBtn.textContent = isResolve ? "解析" : "下载";
    dlBtn.addEventListener("click", async () => {
      dlBtn.disabled = true;
      try {
        await sendViaBackground({
          url: item.url,
          fileName: item.name,
          headers: item.headers || {},
          mode: isResolve ? "resolve" : "download"
        });
        statusNode.textContent = isResolve
          ? `已发送到云取 PC 解析：${item.name}`
          : `已交给云取 PC 下载：${item.name}`;
      } catch {
        await writeClipboard(item.url);
        statusNode.textContent = "未连接到云取 PC，已自动复制该资源链接。";
      } finally {
        dlBtn.disabled = false;
      }
    });

    const cpBtn = document.createElement("button");
    cpBtn.type = "button";
    cpBtn.className = "mini-btn";
    cpBtn.textContent = "复制";
    cpBtn.addEventListener("click", async () => {
      await writeClipboard(item.url);
      statusNode.textContent = `已复制链接：${item.name}`;
    });

    btns.append(dlBtn, cpBtn);
    li.append(meta, btns);
    sniffListNode.appendChild(li);
  }
}

async function init() {
  [currentTab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = currentTab?.url || "";
  urlNode.textContent = url || "当前页面没有可发送的网址。";
  providerNode.textContent = providerFor(url);
  copyButton.disabled = !url;
  sendPcButton.disabled = !url;

  const stored = await chrome.storage.local.get(Object.keys(DEFAULT_SETTINGS));
  const settings = { ...DEFAULT_SETTINGS, ...stored };
  portInput.value = String(settings.relayPort);
  interceptToggle.checked = Boolean(settings.interceptEnabled);
  minSizeSelect.value = String(settings.minSizeMb);
  sniffToggle.checked = Boolean(settings.sniffEnabled);

  checkPcOnline();
  await scanAndRenderSniffed();

  rescanSniffBtn.addEventListener("click", async () => {
    statusNode.textContent = "正在重新扫描当前页面…";
    await scanAndRenderSniffed();
    statusNode.textContent = "页面扫描完成。";
  });

  interceptToggle.addEventListener("change", async () => {
    await chrome.storage.local.set({ interceptEnabled: interceptToggle.checked });
    statusNode.textContent = interceptToggle.checked ? "已开启自动拦截浏览器下载" : "已暂停拦截，改由浏览器默认下载";
  });

  minSizeSelect.addEventListener("change", async () => {
    await chrome.storage.local.set({ minSizeMb: Number(minSizeSelect.value) });
    statusNode.textContent = `拦截阈值已设为：${minSizeSelect.selectedOptions[0].textContent}`;
  });

  sniffToggle.addEventListener("change", async () => {
    await chrome.storage.local.set({ sniffEnabled: sniffToggle.checked });
    statusNode.textContent = sniffToggle.checked ? "已开启网页资源嗅探" : "已关闭网页资源嗅探";
  });

  portInput.addEventListener("change", async () => {
    const nextPort = getPort();
    portInput.value = String(nextPort);
    await chrome.storage.local.set({ relayPort: nextPort });
    checkPcOnline();
  });

  clearSniffBtn.addEventListener("click", async () => {
    if (currentTab?.id == null) return;
    await chrome.runtime.sendMessage({ type: "CLEAR_SNIFFED", tabId: currentTab.id });
    renderSniffed([]);
    statusNode.textContent = "已清空本页嗅探列表。";
  });

  sendPcButton.addEventListener("click", async () => {
    if (!currentTab?.url) return;
    try {
      await sendViaBackground({ url: currentTab.url, mode: "resolve" });
      statusNode.textContent = "已发送到云取 PC 解析。";
    } catch {
      await writeClipboard(currentTab.url);
      statusNode.textContent = "未连接到云取 PC，已自动复制链接。";
    }
  });

  copyButton.addEventListener("click", async () => {
    if (!currentTab?.url) return;
    await writeClipboard(currentTab.url);
    statusNode.textContent = "链接已复制。";
  });
}

init().catch(() => {
  providerNode.textContent = "无法读取当前页面";
  statusNode.textContent = "请确认扩展已正确加载。";
});
