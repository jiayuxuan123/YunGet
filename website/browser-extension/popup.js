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

function renderSniffed(items) {
  sniffCountNode.textContent = String(items.length);
  sniffListNode.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "暂未嗅探到音视频或下载直链；播放视频或加载资源后会自动出现在这里。";
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
    sub.textContent = `${formatSize(item.size)} · ${item.url}`;
    sub.title = item.url;

    meta.append(top, sub);

    const btns = document.createElement("div");
    btns.className = "sniff-btns";

    const dlBtn = document.createElement("button");
    dlBtn.type = "button";
    dlBtn.className = "primary mini-btn";
    dlBtn.textContent = "下载";
    dlBtn.addEventListener("click", async () => {
      dlBtn.disabled = true;
      try {
        await sendViaBackground({
          url: item.url,
          fileName: item.name,
          headers: item.headers || {},
          mode: "download"
        });
        statusNode.textContent = `已交给云取 PC 下载：${item.name}`;
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

  if (currentTab?.id != null) {
    const resp = await chrome.runtime.sendMessage({ type: "GET_SNIFFED", tabId: currentTab.id });
    renderSniffed(resp?.items || []);
  }

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
