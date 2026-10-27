/* ============================================================================
   云取官网交互（taste-skill v2 重构）

   四件事，都不承担信息表达 —— 关掉 JS 也能读到全部内容：

   1. 解析控件（"活着的东西"）：把粘贴的分享链接**就地切开**，显示识别到的
      平台、提取码与分片计划。全部本地计算、不发网络请求，
      所以既不伪造任何结果，也不会把访客的链接发出去。
   2. 动效层：首屏标题的遮罩升起、各章节元素的错峰入场、控件的按下/悬停反馈。
      只动 transform 与 opacity，reduced-motion 下全部取消、内容照常。
   3. 问答：点选问题，右侧答案跟着换（窄屏则就地展开）。
   4. 窄屏导航折叠 + 代码块复制。
   ========================================================================= */
(function () {
  "use strict";

  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------------------------------------------------------------- 1. 解析控件 */

  // 平台识别规则与 App 的 ShareLinkParser 同源（这里只放站点演示需要的判定）。
  // 顺序有意义：先具体后宽泛，避免 "m.pan.quark.cn" 被更宽泛的规则先吃掉。
  var PLATFORMS = [
    { name: "夸克网盘", host: "pan.quark.cn" },
    { name: "UC 网盘", host: "drive.uc.cn" },
    { name: "迅雷网盘", host: "pan.xunlei.com" },
    { name: "百度网盘", host: "pan.baidu.com" },
    { name: "139 网盘", host: "caiyun.139.com" },
    { name: "123 云盘", host: "123pan.com" },
    { name: "115 网盘", host: "115.com" },
    { name: "光鸭云盘", host: "guangya.com" },
    { name: "蓝奏云优享版", host: "ilanzou.com" },
    { name: "蓝奏云", host: "lanzou.com" }
  ];

  function detect(text) {
    var t = (text || "").trim();
    if (!t) return null;
    var url = t.match(/https?:\/\/[^\s，,、]+/);
    if (!url) return null;
    var link = url[0];
    var host = "";
    try { host = new URL(link).hostname.toLowerCase(); } catch (e) { return null; }
    var hit = null;
    for (var i = 0; i < PLATFORMS.length; i++) {
      var p = PLATFORMS[i];
      // 后缀匹配：m.pan.quark.cn / www.123pan.com 都算；带点的后缀避免 "not115.com" 误判
      if (host === p.host || host.slice(-(p.host.length + 1)) === "." + p.host) { hit = p; break; }
    }
    if (!hit) return null;
    // 提取码：常见的几种写法（App 里的解析器同样宽松）
    var code = "";
    var m = t.match(/(?:提取码|密码|code|pwd)\s*[:：=]?\s*([a-zA-Z0-9]{4})/i);
    if (m) code = m[1];
    else {
      var tail = t.match(/[（(]\s*([a-zA-Z0-9]{4})\s*[)）]/);
      if (tail) code = tail[1];
    }
    return { platform: hit, link: link, code: code };
  }

  // 分片计划：与引擎真实规则同源 —— 段数 = 连接数 × 每连接段数。
  // 确定性推演（同样输入永远同样输出，不做随机），只用来说明"它是怎么切的"。
  function segmentPlan(bytes, connections) {
    var perConnection = 4;
    var segs = Math.max(1, connections * perConnection);
    return { segs: segs, each: Math.floor(bytes / segs), connections: connections };
  }

  function fmtBytes(n) {
    if (n >= 1048576) return (n / 1048576).toFixed(1) + " MB";
    if (n >= 1024) return Math.round(n / 1024) + " KB";
    return n + " B";
  }

  var input = document.getElementById("share-input");
  var out = document.getElementById("resolver-out");
  var segBox = document.getElementById("seg");
  var legend = document.getElementById("seg-legend");

  function renderSegments(doneRatio) {
    if (!segBox) return;
    // 不等宽的分段：固定序列（可复现），看起来像真实的"下到一半"
    var widths = [7, 3, 5, 2, 9, 4, 6, 3, 8, 5, 2, 7];
    var html = "";
    var doneCount = Math.round(widths.length * doneRatio);
    for (var i = 0; i < widths.length; i++) {
      html += '<i class="' + (i < doneCount ? "done" : "") + '" style="flex:' + widths[i] + ' 1 0%"></i>';
    }
    segBox.innerHTML = html;
  }

  function renderPlan(info, bytes, connections) {
    if (!out) return;
    if (!info) {
      out.innerHTML = '<p class="resolver-empty">还没识别到分享链接。把它粘进来，这里会显示' +
        "识别到的网盘、提取码与分片计划 —— 全部在这台设备上完成，不会联网。</p>";
      renderSegments(0);
      if (legend) legend.innerHTML = "";
      return;
    }
    var plan = segmentPlan(bytes, connections);
    out.innerHTML =
      '<p class="resolver-line">识别到 <b>' + info.platform.name + "</b>" +
      (info.code ? " · 提取码 <b>" + info.code + "</b>" : " · 没有提取码") + "</p>" +
      '<p class="resolver-line">解析后按 <b>' + plan.connections + " 路并发</b>切成 <b>" +
      plan.segs + " 段</b>，每段约 <b>" + fmtBytes(plan.each) + "</b></p>";
    renderSegments(0.34);
    if (legend) {
      legend.innerHTML =
        "<span>总大小 <b>" + fmtBytes(bytes) + "</b></span>" +
        "<span>分段 <b>" + plan.segs + "</b></span>" +
        "<span>并发 <b>" + plan.connections + " 路</b></span>" +
        "<span>浅绿的段 = 已完成</span>";
    }
  }

  if (input) {
    // 示例数字（1.8 GB / 4 路）—— 站点演示用的样例，发布前由项目方确认或替换
    var demoBytes = 1875000000;
    var demoConn = 4;

    input.addEventListener("input", function () {
      renderPlan(detect(input.value), demoBytes, demoConn);
    });
    if (!input.value) input.value = "https://pan.quark.cn/s/6f2a1b9c 提取码：a3k9";
    renderPlan(detect(input.value), demoBytes, demoConn);
  }

  /* ---------------------------------------------------------------- 2. 动效层 */

  // 2a. 首屏标题：遮罩式行升起（签名动效）
  var h1 = document.querySelector(".hero h1");
  if (h1) {
    if (reduced) h1.classList.add("ready");
    else requestAnimationFrame(function () { h1.classList.add("ready"); });
  }

  // 2b. 各章节元素错峰入场：同一父元素内的 .rise 依次延迟 70ms。
  // 纳入范围：章节标题与导语，外加**章节里的每一件内容**（表格、面板、碎片、问答…）。
  // 为什么这么写：内页（下载/插件/文档）本身东西不多，只让 h2 淡入的话整页几乎不动 ——
  // 门禁的 still-page 与"逐件入场而不是整块淡入"讲的是同一件事。同一个父元素里的
  // 几件内容会按顺序各差 70ms，读起来像是被依次写上去的。
  window.__motionReady = true;
  var autoRise = document.querySelectorAll(
    "h2, .section-lead, .doc-body h2, .section > *, .doc-body > *"
  );
  for (var a = 0; a < autoRise.length; a++) {
    // 分段线是版式记号：它有自己的入场（几段依次填上），不跟着一起淡出
    if (autoRise[a].classList.contains("seg-rule")) continue;
    autoRise[a].classList.add("rise");
  }
  var risers = document.querySelectorAll(".rise");
  if (risers.length) {
    if (reduced || !("IntersectionObserver" in window)) {
      for (var i = 0; i < risers.length; i++) risers[i].classList.add("in");
    } else {
      var io = new IntersectionObserver(function (entries) {
        entries.forEach(function (en) {
          if (!en.isIntersecting) return;
          var el = en.target;
          var sibs = el.parentElement ? el.parentElement.querySelectorAll(":scope > .rise") : [el];
          var idx = Array.prototype.indexOf.call(sibs, el);
          el.style.transitionDelay = Math.max(0, idx) * 70 + "ms";
          el.classList.add("in");
          io.unobserve(el);
        });
      }, { rootMargin: "0px 0px -12% 0px", threshold: 0.08 });
      for (var j = 0; j < risers.length; j++) io.observe(risers[j]);
    }
  }

  /* ---------------------------------------------------------------- 3. 问答 */

  var faqQs = document.querySelectorAll(".faq-q");
  if (faqQs.length) {
    var answers = document.querySelectorAll(".faq-answer");
    var panel = document.querySelector(".faq-panel");
    var isNarrow = function () { return window.matchMedia("(max-width: 900px)").matches; };

    function select(id) {
      for (var i = 0; i < faqQs.length; i++) {
        var q = faqQs[i];
        q.setAttribute("aria-expanded", q.getAttribute("data-q") === id ? "true" : "false");
      }
      for (var k = 0; k < answers.length; k++) {
        var a = answers[k];
        var on = a.getAttribute("data-a") === id;
        // 先一律收起，再只展开选中的那一条 —— 之前这里写反过：面板里的每个答案
        // 都会被加上 data-open，结果是五条答案同时可见（截图评审抓到的）。
        a.removeAttribute("data-open");
        if (!on) continue;
        if (isNarrow()) {
          // 窄屏：答案移到该问题下方（移动端没有右侧面板可言）
          var q = document.querySelector('.faq-q[data-q="' + id + '"]');
          if (q && a.previousElementSibling !== q) q.insertAdjacentElement("afterend", a);
          a.setAttribute("data-open", "");
        } else if (panel) {
          // 宽屏：答案集中在右侧面板，只留当前一条
          if (a.parentElement !== panel) panel.appendChild(a);
          a.setAttribute("data-open", "");
        }
      }
    }

    for (var q = 0; q < faqQs.length; q++) {
      (function (btn) {
        var id = btn.getAttribute("data-q");
        btn.addEventListener("click", function () { select(id); });
        btn.addEventListener("mouseenter", function () { if (!isNarrow()) select(id); });
        btn.addEventListener("focus", function () { if (!isNarrow()) select(id); });
      })(faqQs[q]);
    }
    if (!isNarrow()) select(faqQs[0].getAttribute("data-q"));
    else for (var r = 0; r < answers.length; r++) answers[r].removeAttribute("data-open");
  }

  /* ---------------------------------------------------------------- 4. 导航与代码块 */

  var navToggle = document.querySelector(".nav-toggle");
  var topNav = document.getElementById("top-nav");
  if (navToggle && topNav) {
    navToggle.addEventListener("click", function () {
      var open = topNav.hasAttribute("data-open");
      if (open) topNav.removeAttribute("data-open");
      else topNav.setAttribute("data-open", "");
      navToggle.setAttribute("aria-expanded", open ? "false" : "true");
    });
  }

  var pres = document.querySelectorAll("pre");
  for (var p = 0; p < pres.length; p++) {
    (function (pre) {
      var btn = document.createElement("button");
      btn.type = "button";
      btn.className = "copy";
      btn.textContent = "复制";
      btn.addEventListener("click", function () {
        var code = pre.querySelector("code");
        var text = code ? code.textContent : pre.textContent;
        if (navigator.clipboard) {
          navigator.clipboard.writeText(text).then(function () {
            btn.textContent = "已复制";
            setTimeout(function () { btn.textContent = "复制"; }, 1600);
          });
        }
      });
      pre.appendChild(btn);
    })(pres[p]);
  }
})();
