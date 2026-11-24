"use strict";

const DEFAULT_SETTINGS = {
  relayPort: 17890,
  interceptEnabled: true,
  minSizeMb: 1,
  sniffEnabled: true
};

const ALWAYS_INTERCEPT_EXTS = new Set([
  "apk", "msi", "exe", "dmg", "pkg", "deb", "rpm", "appimage",
  "zip", "rar", "7z", "tar", "gz", "xz", "bz2", "zst", "iso", "img",
  "mp4", "mkv", "webm", "flv", "mov", "avi", "m4v", "ts",
  "mp3", "flac", "wav", "aac", "m4a", "ogg",
  "torrent", "metalink", "meta4", "pdf", "epub"
]);

const SKIP_INTERCEPT_EXTS = new Set([
  "html", "htm", "mhtml", "crx", "json", "txt", "csv", "svg", "ico", "css", "js"
]);

const MEDIA_EXT_TYPES = {
  m3u8: "HLS",
  m3u: "HLS",
  mpd: "DASH",
  mp4: "视频",
  mkv: "视频",
  webm: "视频",
  flv: "视频",
  mov: "视频",
  m4v: "视频",
  mp3: "音频",
  m4a: "音频",
  flac: "音频",
  wav: "音频",
  aac: "音频",
  ogg: "音频",
  apk: "安装包",
  msi: "安装包",
  exe: "安装包",
  zip: "压缩包",
  "7z": "压缩包",
  rar: "压缩包",
  iso: "镜像",
  torrent: "种子"
};

const MAX_SNIFFED_PER_TAB = 60;
const MIN_DIRECT_MEDIA_BYTES = 128 * 1024; // 过滤 <128KB 的零碎广告/探测请求

// 请求级临时头缓存（生命周期仅一次请求几秒内，不依赖跨休眠状态）
const inflightHeaders = new Map();

async function getSettings() {
  const stored = await chrome.storage.local.get(Object.keys(DEFAULT_SETTINGS));
  return { ...DEFAULT_SETTINGS, ...stored };
}

function getExtFromUrl(rawUrl) {
  try {
    const pathname = new URL(rawUrl).pathname;
    const base = pathname.split("/").pop() || "";
    const dot = base.lastIndexOf(".");
    if (dot <= 0 || dot === base.length - 1) return "";
    return base.slice(dot + 1).toLowerCase();
  } catch {
    return "";
  }
}

function getExtFromName(fileName) {
  if (!fileName) return "";
  const base = fileName.replace(/\\/g, "/").split("/").pop() || "";
  const dot = base.lastIndexOf(".");
  if (dot <= 0 || dot === base.length - 1) return "";
  return base.slice(dot + 1).toLowerCase();
}

function baseFileName(rawName, rawUrl, fallbackExt = "") {
  if (rawName) {
    const clean = rawName.replace(/\\/g, "/").split("/").pop().trim();
    if (clean) return clean;
  }
  try {
    const u = new URL(rawUrl);
    const seg = decodeURIComponent(u.pathname.split("/").pop() || "").trim();
    if (seg && seg.includes(".")) return seg;
    if (seg && fallbackExt) return `${seg}.${fallbackExt}`;
    if (seg) return seg;
  } catch {}
  return fallbackExt ? `media.${fallbackExt}` : "download.bin";
}

function parseContentDispositionName(headerVal) {
  if (!headerVal) return "";
  const star = /filename\*\s*=\s*(?:UTF-8''|utf-8'')?([^;]+)/i.exec(headerVal);
  if (star && star[1]) {
    try {
      return decodeURIComponent(star[1].trim().replace(/^"|"$/g, ""));
    } catch {
      return star[1].trim().replace(/^"|"$/g, "");
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(headerVal);
  return plain && plain[1] ? plain[1].trim() : "";
}

async function buildCookieHeader(url) {
  try {
    const cookies = await chrome.cookies.getAll({ url });
    if (!cookies || !cookies.length) return "";
    return cookies.map((c) => `${c.name}=${c.value}`).join("; ");
  } catch {
    return "";
  }
}

async function pingDesktop(port, timeoutMs = 800) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const resp = await fetch(`http://127.0.0.1:${port}/ping`, {
      method: "GET",
      signal: controller.signal
    });
    return resp.ok;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}

async function enqueueToDesktop({ port, url, fileName = "", headers = {}, mode = "download" }) {
  const safeHeaders = { ...headers };
  if (!safeHeaders["User-Agent"] && !safeHeaders["user-agent"]) {
    safeHeaders["User-Agent"] = navigator.userAgent;
  }
  if (!safeHeaders["Cookie"] && !safeHeaders["cookie"] && /^https?:\/\//i.test(url)) {
    const cookie = await buildCookieHeader(url);
    if (cookie) safeHeaders["Cookie"] = cookie;
  }
  const resp = await fetch(`http://127.0.0.1:${port}/enqueue`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      url,
      fileName,
      headers: safeHeaders,
      mode,
      source: "extension"
    })
  });
  if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
  return true;
}

function notify(title, message) {
  try {
    chrome.notifications.create({
      type: "basic",
      iconUrl: "icons/icon-128.png",
      title,
      message
    });
  } catch {}
}

async function saveRecentHistory(url) {
  const { recent = [] } = await chrome.storage.local.get("recent");
  const next = [{ url, at: Date.now() }, ...recent.filter((item) => item.url !== url)].slice(0, 8);
  await chrome.storage.local.set({ recent: next });
}

// ============================================================================
// 1. 右键菜单：直接下载 & 发送解析
// ============================================================================
function registerContextMenus() {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: "yunget-download-direct",
      title: "用云取 PC 直接下载",
      contexts: ["link", "video", "audio"]
    });
    chrome.contextMenus.create({
      id: "yunget-send-resolve",
      title: "发送到云取 PC 解析",
      contexts: ["link", "page"]
    });
  });
}

chrome.runtime.onInstalled.addListener(registerContextMenus);
chrome.runtime.onStartup.addListener(registerContextMenus);

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const settings = await getSettings();
  if (info.menuItemId === "yunget-download-direct") {
    const targetUrl = info.srcUrl || info.linkUrl || "";
    if (!targetUrl) return;
    const headers = {};
    if (tab?.url && /^https?:\/\//i.test(tab.url)) headers["Referer"] = tab.url;
    try {
      await enqueueToDesktop({
        port: settings.relayPort,
        url: targetUrl,
        fileName: baseFileName("", targetUrl),
        headers,
        mode: "download"
      });
      await saveRecentHistory(targetUrl);
      notify("已发送到云取 PC 下载", baseFileName("", targetUrl));
    } catch {
      notify("未连接到云取 PC", "请先启动云取 Windows 桌面端（默认端口 17890）");
    }
    return;
  }
  if (info.menuItemId === "yunget-send-resolve") {
    const targetUrl = info.linkUrl || info.pageUrl || tab?.url || "";
    if (!targetUrl) return;
    try {
      await enqueueToDesktop({
        port: settings.relayPort,
        url: targetUrl,
        mode: "resolve"
      });
      await saveRecentHistory(targetUrl);
      notify("已发送到云取 PC 解析", targetUrl.slice(0, 80));
    } catch {
      notify("未连接到云取 PC", "请先启动云取 Windows 桌面端（默认端口 17890）");
    }
  }
});

// ============================================================================
// 2. 自动拦截浏览器下载 → 交给云取 PC 满速下载
// ============================================================================
function shouldInterceptDownload(item, settings) {
  if (!settings.interceptEnabled) return false;
  const rawUrl = item.finalUrl || item.url || "";
  if (!/^https?:\/\//i.test(rawUrl)) return false;
  if (/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(rawUrl)) return false;

  const ext = getExtFromName(item.filename) || getExtFromUrl(rawUrl);
  if (ext && SKIP_INTERCEPT_EXTS.has(ext)) return false;

  const minBytes = Math.max(0, Number(settings.minSizeMb) || 0) * 1024 * 1024;
  if (minBytes === 0) return true;

  if (typeof item.fileSize === "number" && item.fileSize > 0) {
    return item.fileSize >= minBytes;
  }
  // 大小未知时，只要是常见安装包/压缩包/视频/镜像扩展名依然拦截
  return ALWAYS_INTERCEPT_EXTS.has(ext);
}

chrome.downloads.onDeterminingFilename.addListener((downloadItem, suggest) => {
  (async () => {
    try {
      const settings = await getSettings();
      if (!shouldInterceptDownload(downloadItem, settings)) {
        suggest();
        return;
      }
      const online = await pingDesktop(settings.relayPort, 800);
      if (!online) {
        suggest();
        return;
      }
      const url = downloadItem.finalUrl || downloadItem.url;
      const fileName = baseFileName(downloadItem.filename, url);
      const headers = {};
      if (downloadItem.referrer && /^https?:\/\//i.test(downloadItem.referrer)) {
        headers["Referer"] = downloadItem.referrer;
      }
      await enqueueToDesktop({
        port: settings.relayPort,
        url,
        fileName,
        headers,
        mode: "download"
      });
      await saveRecentHistory(url);
      try {
        await chrome.downloads.cancel(downloadItem.id);
        await chrome.downloads.erase({ id: downloadItem.id });
      } catch {}
      notify("已拦截并交给云取 PC 下载", fileName);
      suggest();
    } catch {
      suggest();
    }
  })();
  return true;
});

// ============================================================================
// 3. 网页资源嗅探（HLS / DASH / 视频 / 音频 / 大文件直链）
// ============================================================================
function sniffKey(tabId) {
  return `sniff_tab_${tabId}`;
}

async function updateTabBadge(tabId, count) {
  try {
    await chrome.action.setBadgeBackgroundColor({ tabId, color: "#24745a" });
    await chrome.action.setBadgeText({ tabId, text: count > 0 ? String(count > 99 ? "99+" : count) : "" });
  } catch {}
}

function classifySniffedResource(details) {
  const url = details.url || "";
  if (!/^https?:\/\//i.test(url)) return null;
  if (/^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(url)) return null;

  const headers = details.responseHeaders || [];
  let contentType = "";
  let contentLength = 0;
  let contentDisposition = "";
  for (const h of headers) {
    const name = (h.name || "").toLowerCase();
    if (name === "content-type") contentType = (h.value || "").toLowerCase();
    else if (name === "content-length") contentLength = Number(h.value) || 0;
    else if (name === "content-disposition") contentDisposition = h.value || "";
  }

  const dispName = parseContentDispositionName(contentDisposition);
  const ext = getExtFromName(dispName) || getExtFromUrl(url);
  const lowerUrl = url.toLowerCase();

  // 1. HLS / DASH 流媒体清单（最高优先级，不限大小）
  if (
    ext === "m3u8" || ext === "m3u" ||
    contentType.includes("mpegurl") ||
    lowerUrl.includes(".m3u8?")
  ) {
    return { kind: "HLS", ext: "m3u8", size: contentLength, name: baseFileName(dispName, url, "m3u8") };
  }
  if (
    ext === "mpd" ||
    contentType.includes("dash+xml") ||
    lowerUrl.includes(".mpd?")
  ) {
    return { kind: "DASH", ext: "mpd", size: contentLength, name: baseFileName(dispName, url, "mpd") };
  }

  // 忽略零碎的 HLS .ts / .m4s 分片（应下主 m3u8/mpd，而不是刷屏几百个 ts 分片）
  if (ext === "ts" || ext === "m4s") {
    return null;
  }

  // 2. 音视频媒体直链
  if (contentType.startsWith("video/") || MEDIA_EXT_TYPES[ext] === "视频") {
    if (contentLength > 0 && contentLength < MIN_DIRECT_MEDIA_BYTES) return null;
    return { kind: "视频", ext: ext || "mp4", size: contentLength, name: baseFileName(dispName, url, ext || "mp4") };
  }
  if (contentType.startsWith("audio/") || MEDIA_EXT_TYPES[ext] === "音频") {
    if (contentLength > 0 && contentLength < 32 * 1024) return null;
    return { kind: "音频", ext: ext || "mp3", size: contentLength, name: baseFileName(dispName, url, ext || "mp3") };
  }

  // 3. 明确附件或常见下载包直链
  const isAttachment = contentDisposition.toLowerCase().includes("attachment");
  if (isAttachment || (ext && MEDIA_EXT_TYPES[ext])) {
    if (!isAttachment && contentLength > 0 && contentLength < MIN_DIRECT_MEDIA_BYTES) return null;
    const kind = MEDIA_EXT_TYPES[ext] || "文件";
    return { kind, ext, size: contentLength, name: baseFileName(dispName, url, ext) };
  }

  return null;
}

chrome.webRequest.onBeforeSendHeaders.addListener(
  (details) => {
    if (details.tabId < 0) return;
    const captured = {};
    for (const h of details.requestHeaders || []) {
      const k = (h.name || "").toLowerCase();
      if (k === "referer") captured["Referer"] = h.value || "";
      else if (k === "origin") captured["Origin"] = h.value || "";
      else if (k === "user-agent") captured["User-Agent"] = h.value || "";
    }
    if (Object.keys(captured).length > 0) {
      inflightHeaders.set(details.requestId, captured);
      if (inflightHeaders.size > 300) {
        const firstKey = inflightHeaders.keys().next().value;
        inflightHeaders.delete(firstKey);
      }
    }
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object", "sub_frame"] },
  ["requestHeaders", "extraHeaders"]
);

chrome.webRequest.onHeadersReceived.addListener(
  (details) => {
    const reqHeaders = inflightHeaders.get(details.requestId) || {};
    inflightHeaders.delete(details.requestId);
    if (details.tabId < 0) return;
    if (details.statusCode < 200 || details.statusCode >= 400) return;

    const classified = classifySniffedResource(details);
    if (!classified) return;

    (async () => {
      const settings = await getSettings();
      if (!settings.sniffEnabled) return;
      const key = sniffKey(details.tabId);
      const data = await chrome.storage.session.get(key);
      const list = Array.isArray(data[key]) ? data[key] : [];
      if (list.some((item) => item.url === details.url)) return;

      const entry = {
        url: details.url,
        name: classified.name,
        kind: classified.kind,
        size: classified.size,
        headers: reqHeaders,
        at: Date.now()
      };
      const next = [entry, ...list].slice(0, MAX_SNIFFED_PER_TAB);
      await chrome.storage.session.set({ [key]: next });
      await updateTabBadge(details.tabId, next.length);
    })();
  },
  { urls: ["<all_urls>"], types: ["media", "xmlhttprequest", "other", "object", "sub_frame"] },
  ["responseHeaders"]
);

chrome.tabs.onRemoved.addListener(async (tabId) => {
  await chrome.storage.session.remove(sniffKey(tabId));
});

chrome.tabs.onUpdated.addListener(async (tabId, changeInfo) => {
  if (changeInfo.url) {
    await chrome.storage.session.remove(sniffKey(tabId));
    await updateTabBadge(tabId, 0);
  }
});

// ============================================================================
// 4. Popup 消息通信（一键发送嗅探资源 / 清空本页嗅探）
// ============================================================================
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  (async () => {
    try {
      const settings = await getSettings();
      if (message?.type === "SEND_TO_PC") {
        await enqueueToDesktop({
          port: settings.relayPort,
          url: message.url,
          fileName: message.fileName || "",
          headers: message.headers || {},
          mode: message.mode || "download"
        });
        await saveRecentHistory(message.url);
        sendResponse({ ok: true });
        return;
      }
      if (message?.type === "GET_SNIFFED") {
        const key = sniffKey(message.tabId);
        const data = await chrome.storage.session.get(key);
        sendResponse({ ok: true, items: Array.isArray(data[key]) ? data[key] : [] });
        return;
      }
      if (message?.type === "CLEAR_SNIFFED") {
        const key = sniffKey(message.tabId);
        await chrome.storage.session.remove(key);
        await updateTabBadge(message.tabId, 0);
        sendResponse({ ok: true });
        return;
      }
      sendResponse({ ok: false, error: "unknown_message" });
    } catch (err) {
      sendResponse({ ok: false, error: err?.message || "failed" });
    }
  })();
  return true;
});
