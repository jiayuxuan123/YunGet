/* ==========================================================================
   YunGet site interactions
   Content stays visible without JavaScript. This file adds theme selection,
   an accessible mobile route menu, local-only link recognition and small
   reading conveniences. No network request is made by the page itself.
   ========================================================================== */
(function () {
  "use strict";

  var root = document.documentElement;
  var head = document.querySelector("[data-site-head]");
  var navToggle = document.querySelector(".nav-toggle");
  var mainNav = document.getElementById("site-nav");
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  var systemDark = window.matchMedia("(prefers-color-scheme: dark)");
  var themeButtons = Array.prototype.slice.call(document.querySelectorAll("[data-theme-toggle]"));

  /* ---------------------------------------------------------------- theme */

  function effectiveDark() {
    var chosen = root.getAttribute("data-theme");
    if (chosen === "dark") return true;
    if (chosen === "light") return false;
    return systemDark.matches;
  }

  function syncTheme() {
    var dark = effectiveDark();
    themeButtons.forEach(function (button) {
      button.setAttribute("aria-pressed", dark ? "true" : "false");
      button.setAttribute("aria-label", dark ? "切换到浅色主题" : "切换到深色主题");
    });
    var value = dark ? "#131C18" : "#F4F0E6";
    Array.prototype.forEach.call(
      document.querySelectorAll('meta[name="theme-color"]'),
      function (meta) {
        meta.setAttribute("content", value);
      }
    );
  }

  themeButtons.forEach(function (button) {
    button.addEventListener("click", function () {
      var next = effectiveDark() ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try {
        localStorage.setItem("theme", next);
      } catch (error) {
        /* A blocked storage API must not block the control. */
      }
      syncTheme();
    });
  });

  if (systemDark.addEventListener) {
    systemDark.addEventListener("change", function () {
      var chosen = root.getAttribute("data-theme");
      if (chosen !== "light" && chosen !== "dark") syncTheme();
    });
  }
  syncTheme();

  /* ----------------------------------------------------------- mobile nav */

  function setNav(open, restoreFocus) {
    if (!head || !navToggle) return;
    head.toggleAttribute("data-nav-open", open);
    navToggle.setAttribute("aria-expanded", open ? "true" : "false");
    navToggle.setAttribute("aria-label", open ? "关闭菜单" : "打开菜单");
    if (open && mainNav) {
      var firstLink = mainNav.querySelector("a");
      if (firstLink) firstLink.focus();
    } else if (restoreFocus) {
      navToggle.focus();
    }
  }

  if (navToggle && head && mainNav) {
    navToggle.addEventListener("click", function () {
      setNav(!head.hasAttribute("data-nav-open"), false);
    });

    head.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && head.hasAttribute("data-nav-open")) {
        setNav(false, true);
      }
    });

    document.addEventListener("click", function (event) {
      if (head.hasAttribute("data-nav-open") && !head.contains(event.target)) {
        setNav(false, false);
      }
    });

    mainNav.addEventListener("click", function (event) {
      if (event.target.closest("a")) setNav(false, false);
    });

    function settleNav() {
      if (window.innerWidth > 860 && head.hasAttribute("data-nav-open")) {
        setNav(false, false);
      }
    }

    window.addEventListener("resize", settleNav);
    settleNav();
  }

  /* -------------------------------------------------------- route drawing */

  if (!reduceMotion.matches) {
    window.requestAnimationFrame(function () {
      root.classList.add("route-live");
    });
  }

  /* ------------------------------------------------------- local recognizer */

  var platforms = [
    { name: "夸克网盘", hosts: ["pan.quark.cn"] },
    { name: "UC 网盘", hosts: ["drive.uc.cn"] },
    { name: "迅雷网盘", hosts: ["pan.xunlei.com"] },
    { name: "百度网盘", hosts: ["pan.baidu.com"] },
    { name: "139 网盘", hosts: ["caiyun.139.com"] },
    { name: "123 云盘", hosts: ["123pan.com", "123684.com"] },
    { name: "115 网盘", hosts: ["115.com", "115cdn.com"] },
    { name: "光鸭云盘", hosts: ["guangya.com"] },
    { name: "蓝奏云", hosts: ["lanzou.com", "lanzoui.com", "lanzoux.com", "lanzouv.com"] },
    { name: "蓝奏云优享版", hosts: ["ilanzou.com"] }
  ];
  var form = document.querySelector("[data-resolver]");
  var input = document.getElementById("share-input");
  var result = document.querySelector("[data-resolver-result]");

  function ownHost(host, base) {
    return host === base || host.slice(-(base.length + 1)) === "." + base;
  }

  function platformFor(host) {
    for (var i = 0; i < platforms.length; i += 1) {
      for (var j = 0; j < platforms[i].hosts.length; j += 1) {
        if (ownHost(host, platforms[i].hosts[j])) return platforms[i];
      }
    }
    return null;
  }

  function extractLink(text) {
    var match = String(text || "").match(/https?:\/\/[^\s，,、<>"']+/i);
    if (!match) return null;
    return match[0].replace(/[)）\]】]+$/, "");
  }

  function extractCode(text) {
    var match = String(text || "").match(/(?:提取码|密码|code|pwd)\s*[:：=]?\s*([a-zA-Z0-9]{4})/i);
    if (match) return match[1];
    match = String(text || "").match(/[（(]\s*([a-zA-Z0-9]{4})\s*[)）]/);
    return match ? match[1] : "";
  }

  function showError(message) {
    if (!result) return;
    result.replaceChildren();
    var box = document.createElement("div");
    box.className = "resolver-idle";
    var node = document.createElement("span");
    node.className = "route-node";
    node.textContent = "!";
    var copy = document.createElement("p");
    copy.className = "resolver-error";
    copy.textContent = message;
    box.appendChild(node);
    box.appendChild(copy);
    result.appendChild(box);
  }

  function showMatch(info) {
    if (!result) return;
    result.replaceChildren();
    var box = document.createElement("div");
    box.className = "resolver-hit";

    var node = document.createElement("span");
    node.className = "route-node";
    node.textContent = "01";

    var body = document.createElement("div");
    var summary = document.createElement("p");
    summary.appendChild(document.createTextNode("已识别为 "));
    var strong = document.createElement("strong");
    strong.textContent = info.platform.name;
    summary.appendChild(strong);
    summary.appendChild(document.createTextNode("；主机 " + info.host + "。"));
    body.appendChild(summary);

    var route = document.createElement("div");
    route.className = "resolver-route";
    [
      ["当前页面", "只识别链接结构"],
      ["提取码", info.code ? "已识别：" + info.code : "未发现"],
      ["下一步", "在 YunGet 中解析"]
    ].forEach(function (row) {
      var cell = document.createElement("div");
      var label = document.createElement("span");
      label.textContent = row[0];
      var value = document.createElement("strong");
      value.textContent = row[1];
      cell.appendChild(label);
      cell.appendChild(value);
      route.appendChild(cell);
    });
    body.appendChild(route);
    box.appendChild(node);
    box.appendChild(body);
    result.appendChild(box);
  }

  function recognize() {
    if (!input) return;
    var text = input.value.trim();
    var link = extractLink(text);
    if (!link) {
      showError("没有找到 http 或 https 分享链接。请粘贴完整分享文案或链接。");
      return;
    }
    var host = "";
    try {
      host = new URL(link).hostname.toLowerCase();
    } catch (error) {
      showError("链接格式无法解析。请检查是否粘贴完整。");
      return;
    }
    var platform = platformFor(host);
    if (!platform) {
      showError("这个主机不在当前页面的识别表中。仍可在 YunGet 中尝试打开，实际支持情况以应用为准。");
      return;
    }
    showMatch({ platform: platform, host: host, code: extractCode(text) });
  }

  if (form && input) {
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      recognize();
    });
    input.addEventListener("input", function () {
      if (!input.value.trim() && result) {
        result.replaceChildren();
        var idle = document.createElement("div");
        idle.className = "resolver-idle";
        var node = document.createElement("span");
        node.className = "route-node";
        node.textContent = "00";
        var copy = document.createElement("p");
        copy.textContent =
          "等待输入。这里只判断网盘主机与提取码；真正的目录解析、登录和取直链在 YunGet 应用内完成。";
        idle.appendChild(node);
        idle.appendChild(copy);
        result.appendChild(idle);
      }
    });
  }

  /* ------------------------------------------------------------ docs TOC */

  var docDetails = Array.prototype.slice.call(document.querySelectorAll(".doc-aside details"));
  var docsWide = window.matchMedia("(min-width: 861px)");

  function syncDocDetails() {
    docDetails.forEach(function (details) {
      details.open = docsWide.matches;
    });
  }

  if (docDetails.length) {
    syncDocDetails();
    if (docsWide.addEventListener) docsWide.addEventListener("change", syncDocDetails);
  }

  var tocLinks = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
  var tocMap = {};
  tocLinks.forEach(function (link) {
    var id = decodeURIComponent(link.getAttribute("href").slice(1));
    if (document.getElementById(id)) tocMap[id] = link;
  });

  var tocFrame = 0;

  function updateToc() {
    tocFrame = 0;
    if (!tocLinks.length) return;
    var active = "";
    Object.keys(tocMap).forEach(function (id) {
      var heading = document.getElementById(id);
      if (heading && heading.getBoundingClientRect().top <= 170) active = id;
    });
    tocLinks.forEach(function (link) {
      link.removeAttribute("aria-current");
    });
    if (active && tocMap[active]) tocMap[active].setAttribute("aria-current", "location");
  }

  function queueToc() {
    if (tocFrame) return;
    tocFrame = window.requestAnimationFrame(updateToc);
  }

  if (tocLinks.length) {
    window.addEventListener("scroll", queueToc, { passive: true });
    window.addEventListener("resize", queueToc);
    updateToc();
  }

  /* ----------------------------------------------------------- code copy */

  Array.prototype.forEach.call(document.querySelectorAll("pre"), function (pre) {
    var button = document.createElement("button");
    button.type = "button";
    button.className = "copy";
    button.textContent = "复制";
    button.setAttribute("aria-label", "复制代码");

    function done(message) {
      button.textContent = message;
      window.setTimeout(function () {
        button.textContent = "复制";
      }, 1600);
    }

    button.addEventListener("click", function () {
      var code = pre.querySelector("code");
      var text = code ? code.textContent : pre.textContent;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () {
          done("已复制");
        }).catch(function () {
          done("复制失败");
        });
        return;
      }
      var area = document.createElement("textarea");
      area.value = text;
      area.setAttribute("readonly", "");
      area.style.position = "fixed";
      area.style.opacity = "0";
      document.body.appendChild(area);
      area.select();
      try {
        document.execCommand("copy");
        done("已复制");
      } catch (error) {
        done("复制失败");
      }
      area.remove();
    });

    pre.appendChild(button);
  });
})();
