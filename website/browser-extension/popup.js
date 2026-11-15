"use strict";

const providerRules = [
  ["夸克网盘", ["pan.quark.cn"]],
  ["UC 网盘", ["drive.uc.cn"]],
  ["迅雷网盘", ["pan.xunlei.com"]],
  ["百度网盘", ["pan.baidu.com"]],
  ["139 网盘", ["caiyun.139.com"]],
  ["123 云盘", ["123pan.com", "123684.com"]],
  ["115 网盘", ["115.com", "115cdn.com"]],
  ["小飞机网盘", ["feijipan.com", "feijix.com"]],
  ["城通网盘", ["ctfile.com"]],
  ["阿里云盘", ["alipan.com", "aliyundrive.com"]],
  ["天翼云盘", ["cloud.189.cn"]],
  ["腾讯微云", ["weiyun.com"]],
  ["联通沃盘", ["pan.wo.cn"]],
  ["PikPak", ["mypikpak.com", "pikpak.com"]],
];

const providerNode = document.getElementById("provider");
const urlNode = document.getElementById("url");
const copyButton = document.getElementById("copy");
const statusNode = document.getElementById("status");
const recentList = document.getElementById("recent-list");

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
    return "当前页面没有可复制的网址";
  }
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

async function saveRecent(url) {
  const { recent = [] } = await chrome.storage.local.get("recent");
  const next = [{ url, at: Date.now() }, ...recent.filter((item) => item.url !== url)].slice(0, 5);
  await chrome.storage.local.set({ recent: next });
  renderRecent(next);
}

function renderRecent(items) {
  recentList.replaceChildren();
  if (!items.length) {
    const empty = document.createElement("li");
    empty.className = "empty";
    empty.textContent = "还没有记录。";
    recentList.appendChild(empty);
    return;
  }
  for (const item of items) {
    const row = document.createElement("li");
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = item.url;
    button.title = item.url;
    button.addEventListener("click", async () => {
      await writeClipboard(item.url);
      statusNode.textContent = "已复制最近链接。";
    });
    row.appendChild(button);
    recentList.appendChild(row);
  }
}

async function init() {
  [currentTab] = await chrome.tabs.query({ active: true, currentWindow: true });
  const url = currentTab?.url || "";
  urlNode.textContent = url || "当前页面没有可复制的网址。";
  providerNode.textContent = providerFor(url);
  copyButton.disabled = !url;

  const { recent = [] } = await chrome.storage.local.get("recent");
  renderRecent(recent);

  copyButton.addEventListener("click", async () => {
    if (!currentTab?.url) return;
    await writeClipboard(currentTab.url);
    await saveRecent(currentTab.url);
    statusNode.textContent = "链接已复制，可以粘贴到云取。";
  });
}

init().catch(() => {
  providerNode.textContent = "无法读取当前页面";
  statusNode.textContent = "请确认扩展拥有读取当前标签页的权限。";
});
