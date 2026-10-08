/* 站点交互：只有两件事，都不承担信息表达（无 JS 也能读到全部内容）。
 *
 * 1. 窄屏导航折叠开关（按钮由 CSS 在 <768px 才显示）；
 * 2. 代码块「复制」按钮——渐进增强：注入失败就当没这功能。
 */
(function () {
  "use strict";

  // ---- 1. 导航折叠 ----
  var toggle = document.querySelector(".nav-toggle");
  var nav = document.getElementById("site-nav");
  if (toggle && nav) {
    toggle.addEventListener("click", function () {
      var open = nav.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }

  // ---- 2. 代码块复制 ----
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
