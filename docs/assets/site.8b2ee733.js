/* ==========================================================================
   YunGet site interactions

   The page remains readable without JavaScript. This file only adds:
   - theme and mobile-route controls
   - a local-only share-link recognizer
   - transform/opacity reveals with reduced-motion support
   - FAQ, TOC and code-copy conveniences
   ========================================================================== */
(function () {
  "use strict";

  var root = document.documentElement;
  var body = document.body;
  var reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  var systemDark = window.matchMedia("(prefers-color-scheme: dark)");
  var themeButton = document.querySelector("[data-theme-toggle]");
  var top = document.querySelector(".top");
  var navToggle = document.querySelector(".nav-toggle");
  var topNav = document.getElementById("top-nav");
  var progressBar = document.querySelector(".route-progress i");

  function directChildren(selector, parentSelector) {
    var scope = document.querySelector(parentSelector);
    if (!scope) return [];
    return Array.prototype.filter.call(scope.children, function (node) {
      return node.matches(selector);
    });
  }

  /* ---------------------------------------------------------------- theme */

  function effectiveDark() {
    var chosen = root.getAttribute("data-theme");
    if (chosen === "dark") return true;
    if (chosen === "light") return false;
    return systemDark.matches;
  }

  function syncThemeControl() {
    if (!themeButton) return;
    var dark = effectiveDark();
    themeButton.setAttribute("aria-pressed", dark ? "true" : "false");
    themeButton.setAttribute("aria-label", dark ? "切换到浅色主题" : "切换到深色主题");
    var label = themeButton.querySelector(".theme-label");
    if (label) label.textContent = dark ? "日间" : "夜间";
    var lightMeta = document.querySelector('meta[name="theme-color"][media*="light"]');
    var darkMeta = document.querySelector('meta[name="theme-color"][media*="dark"]');
    if (lightMeta) lightMeta.setAttribute("content", dark ? "#171812" : "#f1eadb");
    if (darkMeta) darkMeta.setAttribute("content", dark ? "#171812" : "#f1eadb");
  }

  if (themeButton) {
    themeButton.addEventListener("click", function () {
      var next = effectiveDark() ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try {
        localStorage.setItem("theme", next);
      } catch (error) {
        /* A blocked storage API must not block the control. */
      }
      syncThemeControl();
    });

    if (systemDark.addEventListener) {
      systemDark.addEventListener("change", function () {
        var chosen = root.getAttribute("data-theme");
        if (chosen !== "light" && chosen !== "dark") syncThemeControl();
      });
    }

    syncThemeControl();
  }

  /* ----------------------------------------------------------- mobile nav */

  function setNav(open) {
    if (!top) return;
    top.toggleAttribute("data-nav-open", open);
    if (navToggle) navToggle.setAttribute("aria-expanded", open ? "true" : "false");
    if (navToggle) navToggle.setAttribute("aria-label", open ? "关闭菜单" : "打开菜单");
  }

  if (navToggle) {
    navToggle.addEventListener("click", function () {
      setNav(!top.hasAttribute("data-nav-open"));
    });
  }

  if (top) {
    top.addEventListener("keydown", function (event) {
      if (event.key === "Escape" && top.hasAttribute("data-nav-open")) {
        setNav(false);
        if (navToggle) navToggle.focus();
      }
    });

    document.addEventListener("click", function (event) {
      if (!top.hasAttribute("data-nav-open")) return;
      if (!top.contains(event.target)) setNav(false);
    });

    if (topNav) {
      topNav.addEventListener("click", function (event) {
        if (event.target.closest("a")) setNav(false);
      });
    }
  }

  window.addEventListener("resize", function () {
    if (window.innerWidth > 960 && top && top.hasAttribute("data-nav-open")) setNav(false);
  });

  /* ------------------------------------------------------ reading progress */

  var progressFrame = 0;

  function updateProgress() {
    progressFrame = 0;
    if (!progressBar) return;
    var max = document.documentElement.scrollHeight - window.innerHeight;
    var ratio = max > 0 ? Math.min(1, Math.max(0, window.scrollY / max)) : 0;
    progressBar.style.transform = "scaleX(" + ratio.toFixed(4) + ")";
  }

  function queueProgress() {
    if (progressFrame) return;
    progressFrame = window.requestAnimationFrame(updateProgress);
  }

  window.addEventListener("scroll", queueProgress, { passive: true });
  window.addEventListener("resize", queueProgress);
  updateProgress();

  /* -------------------------------------------------------------- reveals */

  var revealTargets = [];

  function addReveal(node, order) {
    if (!node || node.hasAttribute("data-reveal") || node.closest(".resolver")) return;
    node.setAttribute("data-reveal", "");
    if (typeof order === "number") {
      node.setAttribute("data-reveal-order", String(Math.min(order, 5)));
    }
    revealTargets.push(node);
  }

  directChildren("h1, h2, .section-lead, .lead, .panel, .cards, .table-wrap, .split, .rows, .btn-row, .version-bar, .platforms, .stat-band, .dl-visual, .notfound-code", ".page-canvas").forEach(function (node) {
    addReveal(node);
  });

  Array.prototype.forEach.call(document.querySelectorAll(".section > h2, .section > .section-lead, .section > .lead, .section > .panel, .section > .cards, .section > .table-wrap, .section > .split, .section > .rows, .section > .btn-row"), function (node) {
    addReveal(node);
  });

  Array.prototype.forEach.call(document.querySelectorAll(".doc-head, .doc-meta, .doc-aside, .doc-body"), function (node) {
    addReveal(node);
  });

  Array.prototype.forEach.call(document.querySelectorAll(".cards > .card, .platforms > .platform"), function (node, index) {
    addReveal(node, index % 5);
  });

  Array.prototype.forEach.call(document.querySelectorAll(".rise"), function (node) {
    if (!revealTargets.includes(node)) revealTargets.push(node);
  });

  if (!reduceMotion.matches && "IntersectionObserver" in window) {
    var revealObserver = new IntersectionObserver(
      function (entries) {
        entries.forEach(function (entry) {
          if (!entry.isIntersecting) return;
          entry.target.classList.add("is-visible");
          revealObserver.unobserve(entry.target);
        });
      },
      { rootMargin: "0px 0px -8% 0px", threshold: 0.06 }
    );
    revealTargets.forEach(function (node) {
      revealObserver.observe(node);
    });
  } else {
    revealTargets.forEach(function (node) {
      node.classList.add("is-visible");
    });
  }

  window.requestAnimationFrame(function () {
    root.classList.add("hero-ready");
  });

  /* ------------------------------------------------------------ local demo */

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
  var SEGMENT_WEIGHTS = [7, 3, 5, 2, 9, 4, 6, 3, 8, 5, 2, 7];
  var input = document.getElementById("share-input");
  var out = document.getElementById("resolver-out");
  var segmentBox = document.getElementById("seg");
  var legend = document.getElementById("seg-legend");
  var resolver = document.querySelector(".resolver");
  var INITIAL_MESSAGE =
    "还没识别到分享链接。把它粘进来，这里会显示识别到的网盘、提取码与分片计划 —— 全部在这台设备上完成，不会联网。";

  function detectShare(text) {
    var value = (text || "").trim();
    if (!value) return null;
    var match = value.match(/https?:\/\/[^\s，,、]+/);
    if (!match) return null;
    var link = match[0].replace(/[)）\]]+$/, "");
    var host = "";
    try {
      host = new URL(link).hostname.toLowerCase();
    } catch (error) {
      return null;
    }
    var platform = null;
    for (var i = 0; i < PLATFORMS.length; i += 1) {
      var candidate = PLATFORMS[i];
      if (host === candidate.host || host.slice(-(candidate.host.length + 1)) === "." + candidate.host) {
        platform = candidate;
        break;
      }
    }
    if (!platform) return null;
    var code = "";
    var codeMatch = value.match(/(?:提取码|密码|code|pwd)\s*[:：=]?\s*([a-zA-Z0-9]{4})/i);
    if (codeMatch) code = codeMatch[1];
    if (!code) {
      var tailMatch = value.match(/[（(]\s*([a-zA-Z0-9]{4})\s*[)）]/);
      if (tailMatch) code = tailMatch[1];
    }
    return { platform: platform, link: link, code: code };
  }

  function formatBytes(bytes) {
    if (bytes >= 1048576) return (bytes / 1048576).toFixed(1) + " MB";
    if (bytes >= 1024) return Math.round(bytes / 1024) + " KB";
    return bytes + " B";
  }

  function makeLine(parts) {
    var line = document.createElement("p");
    line.className = "resolver-line";
    parts.forEach(function (part) {
      if (typeof part === "string") {
        line.appendChild(document.createTextNode(part));
        return;
      }
      var strong = document.createElement("b");
      strong.textContent = part.bold;
      line.appendChild(strong);
    });
    return line;
  }

  function renderSegments(doneRatio) {
    if (!segmentBox) return;
    segmentBox.replaceChildren();
    var doneCount = Math.round(SEGMENT_WEIGHTS.length * doneRatio);
    SEGMENT_WEIGHTS.forEach(function (weight, index) {
      var segment = document.createElement("i");
      if (index < doneCount) segment.className = "done";
      segment.style.flex = weight + " 1 0%";
      segmentBox.appendChild(segment);
    });
  }

  function showInitial() {
    if (out) {
      out.replaceChildren();
      var paragraph = document.createElement("p");
      paragraph.className = "resolver-empty";
      paragraph.textContent = INITIAL_MESSAGE;
      out.appendChild(paragraph);
    }
    if (legend) legend.replaceChildren();
    renderSegments(0);
    if (resolver) resolver.classList.remove("has-hit");
  }

  function showShare(info) {
    if (!out || !info) {
      showInitial();
      return;
    }
    var bytes = 1875000000;
    var connections = 16;
    var segments = connections * 4;
    var each = Math.floor(bytes / segments);
    out.replaceChildren(
      makeLine([
        "识别到 ",
        { bold: info.platform.name },
        info.code ? " · 提取码 " : " · 没有提取码",
        info.code ? { bold: info.code } : "",
        "。只在当前页面本地识别。"
      ]),
      makeLine([
        "示例文件 1.8 GB，按 ",
        { bold: String(connections) },
        " 路连接切成 ",
        { bold: String(segments) },
        " 段，每段约 ",
        { bold: formatBytes(each) },
        "。"
      ])
    );
    renderSegments(0.34);
    if (legend) {
      legend.replaceChildren();
      [
        "总大小 " + formatBytes(bytes),
        "分段 " + segments,
        "并发 " + connections + " 路",
        "朱红 = 示例完成段"
      ].forEach(function (text) {
        var item = document.createElement("span");
        item.textContent = text;
        legend.appendChild(item);
      });
    }
    if (resolver) resolver.classList.add("has-hit");
  }

  if (input) {
    input.addEventListener("input", function () {
      showShare(detectShare(input.value));
    });
    showInitial();
  }

  /* ----------------------------------------------------------------- FAQ */

  var questions = Array.prototype.slice.call(document.querySelectorAll(".faq-q"));
  var answers = Array.prototype.slice.call(document.querySelectorAll(".faq-answer"));
  var faqPanel = document.querySelector(".faq-panel");
  var mobileFaq = window.matchMedia("(max-width: 960px)");

  function resetAnswerLocations() {
    if (!faqPanel) return;
    answers.forEach(function (answer) {
      if (answer.parentElement !== faqPanel) faqPanel.appendChild(answer);
      answer.removeAttribute("data-open");
    });
  }

  function selectFaq(id, focusAnswer) {
    var selected = null;
    questions.forEach(function (question) {
      var on = question.getAttribute("data-q") === id;
      question.setAttribute("aria-expanded", on ? "true" : "false");
      if (on) selected = question;
    });
    answers.forEach(function (answer) {
      var on = answer.getAttribute("data-a") === id;
      answer.removeAttribute("data-open");
      if (!on) return;
      if (mobileFaq.matches && selected) {
        selected.insertAdjacentElement("afterend", answer);
      } else if (faqPanel && answer.parentElement !== faqPanel) {
        faqPanel.appendChild(answer);
      }
      answer.setAttribute("data-open", "");
      if (focusAnswer) answer.setAttribute("tabindex", "-1");
    });
  }

  if (questions.length) {
    questions.forEach(function (question) {
      question.addEventListener("click", function () {
        selectFaq(question.getAttribute("data-q"), false);
      });
    });

    var initialFaq = questions[0].getAttribute("data-q");
    selectFaq(initialFaq, false);

    function onFaqBreakpoint() {
      resetAnswerLocations();
      var open = questions.filter(function (question) {
        return question.getAttribute("aria-expanded") === "true";
      })[0];
      selectFaq(open ? open.getAttribute("data-q") : initialFaq, false);
    }

    if (mobileFaq.addEventListener) mobileFaq.addEventListener("change", onFaqBreakpoint);
  }

  /* ------------------------------------------------------------ docs TOC */

  if (mobileFaq.matches) {
    Array.prototype.forEach.call(document.querySelectorAll(".doc-aside details"), function (details) {
      details.open = false;
    });
  }

  var tocLinks = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
  var tocMap = {};
  tocLinks.forEach(function (link) {
    var id = decodeURIComponent(link.getAttribute("href").slice(1));
    var heading = document.getElementById(id);
    if (heading) tocMap[id] = link;
  });

  var tocFrame = 0;

  function updateToc() {
    tocFrame = 0;
    if (!tocLinks.length) return;
    var activeId = "";
    Object.keys(tocMap).forEach(function (id) {
      var heading = document.getElementById(id);
      if (heading && heading.getBoundingClientRect().top <= 170) activeId = id;
    });
    tocLinks.forEach(function (link) {
      link.removeAttribute("aria-current");
    });
    if (activeId && tocMap[activeId]) tocMap[activeId].setAttribute("aria-current", "location");
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

  /* ------------------------------------------------------------ code copy */

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
