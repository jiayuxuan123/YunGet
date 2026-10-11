/* 站点交互：四件事，都不承担信息表达（无 JS 也能读到全部内容）。
 *
 * 1. 窄屏导航折叠开关；
 * 2. 深色 / 浅色主题切换（跟随系统 → 手动覆盖，存 localStorage）；
 * 3. 页头滚动阴影；
 * 4. 文档页目录在窄屏默认收起。
 * 另有代码块「复制」按钮（渐进增强，注入失败就当没这功能）。
 */
(function () {
  "use strict";

  var root = document.documentElement;

  // ---- 1. 导航折叠 ----
  var toggle = document.querySelector(".nav-toggle");
  var nav = document.getElementById("site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  // ---- 2. 主题切换 ----
  // 默认跟随系统（CSS 里的 prefers-color-scheme 负责）；用户点过之后写 localStorage，
  // 由 <head> 里的内联脚本在样式表之前设好 data-theme，避免闪一帧白。
  var themeBtn = document.querySelector(".theme-toggle");
  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var explicit = root.getAttribute("data-theme");
      var systemDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
      // 当前生效的是深色吗？没设过就看系统
      var isDark = explicit ? explicit === "dark" : systemDark;
      var next = isDark ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try { localStorage.setItem("theme", next); } catch (e) { /* 隐私模式下写不了，忽略 */ }
    });
  }

  // ---- 3. 页头滚动阴影 ----
  // 静止时保持平；一旦页面离开顶部就给一层阴影，让吸顶页头与内容分开。
  var head = document.querySelector(".site-head");
  if (head && window.addEventListener) {
    var syncHead = function () {
      head.classList.toggle("is-stuck", window.scrollY > 4);
    };
    syncHead();
    window.addEventListener("scroll", syncHead, { passive: true });
  }

  // ---- 4. 文档页目录：窄屏默认收起 ----
  // 目录在 HTML 里带 open（桌面端侧栏要常驻展开），而 open 属性 CSS 关不掉，
  // 所以由这里在窄屏移除它。
  //
  // 为什么要听 resize，而不是"加载时判断一次"：加载时判断只在页面加载那一刻成立，
  // 之后把窗口拉窄（或在窄视口里打开一个已在宽视口加载过的页面）目录就仍是展开的，
  // 又把正文推出屏幕。这里只在**跨过断点时**动手，并且在用户手动开合过之后就不再干预。
  var tocTouched = false;
  Array.prototype.forEach.call(document.querySelectorAll("details.toc"), function (d) {
    d.addEventListener("toggle", function () { tocTouched = true; });
  });
  var narrow = window.matchMedia("(max-width: 767px)");
  var syncToc = function () {
    if (tocTouched) return;
    Array.prototype.forEach.call(document.querySelectorAll("details.toc"), function (d) {
      if (narrow.matches) d.removeAttribute("open");
      else d.setAttribute("open", "");
    });
  };
  syncToc();
  if (narrow.addEventListener) narrow.addEventListener("change", syncToc);
  else if (narrow.addListener) narrow.addListener(syncToc);  // 老 Safari

  // ---- 5. 代码块复制 ----
  // 为什么加：文档页的价值之一是"把命令抄走"，手选容易漏字符。
  if (!navigator.clipboard) return;
  var blocks = document.querySelectorAll(".code pre");
  Array.prototype.forEach.call(blocks, function (pre) {
    var btn = document.createElement("button");
    btn.type = "button";
    btn.className = "copy-btn";
    btn.textContent = "复制";
    btn.addEventListener("click", function () {
      navigator.clipboard.writeText(pre.innerText).then(
        function () {
          btn.textContent = "已复制";
          setTimeout(function () { btn.textContent = "复制"; }, 1500);
        },
        function () { btn.textContent = "复制失败"; }
      );
    });
    pre.parentNode.insertBefore(btn, pre);
  });
})();
