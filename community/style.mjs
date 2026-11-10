export const style = String.raw`:root {
  color-scheme: light;
  --canvas: #f7f9fc; --surface: #ffffff; --wash: #edf2f8; --ink: #1c2738;
  --muted: #576477; --line: #dce3ed; --accent: #275fba; --accent-hover: #1c4c9a;
  --accent-soft: #e8f0fd; --on-accent: #ffffff; --success: #226547; --success-soft: #e7f4ec;
  --danger: #a5283e; --danger-soft: #fbeaf0; --shadow: 0 12px 48px rgb(14 30 53 / .18);
  --scrim: rgb(13 25 43 / .4); --radius: 16px; --control-radius: 12px;
  --space-1: .25rem; --space-2: .5rem; --space-3: .75rem; --space-4: 1rem;
  --space-5: 1.25rem; --space-6: 1.5rem; --space-8: 2rem;
  --viewport-height: 100vh;
  font-family: "MiSans", "PingFang SC", "Microsoft YaHei UI", "Noto Sans CJK SC", sans-serif;
  font-size: 16px; line-height: 1.6; -webkit-text-size-adjust: 100%;
}
@supports (height: 100dvh) { :root { --viewport-height: 100dvh; } }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    color-scheme: dark;
    --canvas: #11161d; --surface: #1b232e; --wash: #242e3c; --ink: #e6edf6;
    --muted: #a4b2c4; --line: #354153; --accent: #a5c7ff; --accent-hover: #c4d9ff;
    --accent-soft: #253c60; --on-accent: #152c4c; --success: #92d4b0; --success-soft: #223b30;
    --danger: #ffabbc; --danger-soft: #442833; --shadow: 0 12px 48px rgb(0 0 0 / .45);
    --scrim: rgb(0 0 0 / .6);
  }
}
:root[data-theme="dark"] {
  color-scheme: dark;
  --canvas: #11161d; --surface: #1b232e; --wash: #242e3c; --ink: #e6edf6;
  --muted: #a4b2c4; --line: #354153; --accent: #a5c7ff; --accent-hover: #c4d9ff;
  --accent-soft: #253c60; --on-accent: #152c4c; --success: #92d4b0; --success-soft: #223b30;
  --danger: #ffabbc; --danger-soft: #442833; --shadow: 0 12px 48px rgb(0 0 0 / .45);
  --scrim: rgb(0 0 0 / .6);
}
* { box-sizing: border-box; scrollbar-width: thin; scrollbar-color: var(--line) transparent; }
[hidden] { display: none !important; }
html, body { margin: 0; height: 100%; background: var(--canvas); color: var(--ink); }
body { height: var(--viewport-height); display: grid; grid-template-rows: auto minmax(0, 1fr) auto; overflow: hidden; }
body, input, textarea, select { caret-color: var(--accent); }
::selection { background: var(--accent-soft); color: var(--ink); }
button, input, textarea, select { font: inherit; color: inherit; }
button, a { -webkit-tap-highlight-color: transparent; touch-action: manipulation; }
button { cursor: pointer; }
button:disabled { opacity: .55; cursor: wait; }
button, input, textarea, select, a { outline-offset: 3px; }
:focus-visible { outline: 3px solid var(--accent); }
a { color: var(--accent); text-underline-offset: .2em; }
button svg, .icon { width: 20px; height: 20px; flex: 0 0 auto; }
button svg, .icon, .brand-mark svg { fill: none; stroke: currentColor; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
.skip-link { position: fixed; left: var(--space-4); top: -5rem; z-index: 100; padding: var(--space-3); background: var(--surface); }
.skip-link:focus { top: var(--space-3); }
.site-head { background: var(--surface); border-bottom: 1px solid var(--line); padding-top: env(safe-area-inset-top); }
.head-inner { max-width: 1064px; margin: auto; padding: var(--space-2) var(--space-6); display: grid; align-items: center; grid-template-columns: 1fr auto 1fr; gap: var(--space-5); }
.brand { display: flex; align-items: center; gap: var(--space-3); color: var(--ink); text-decoration: none; white-space: nowrap; }
.brand-mark { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 11px; background: var(--accent); color: var(--on-accent); }
.brand-mark svg { width: 30px; height: 30px; }
.brand strong { font-size: 18px; font-weight: 750; letter-spacing: .02em; }
.brand strong span { margin-left: .35em; font-size: 15px; font-weight: 500; color: var(--muted); }
.primary-nav { display: flex; gap: var(--space-1); align-self: stretch; }
.primary-nav button { min-width: 72px; min-height: 44px; border: 0; border-radius: 10px; background: transparent; color: var(--muted); padding: var(--space-2) var(--space-4); font-size: 15px; font-weight: 600; }
.primary-nav button[aria-current="page"] { color: var(--accent); background: var(--accent-soft); }
.head-actions { display: flex; justify-content: flex-end; gap: var(--space-2); min-width: 0; }
.identity { min-height: 44px; max-width: 144px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; border: 0; border-radius: var(--control-radius); background: var(--wash); color: var(--ink); padding: var(--space-2) var(--space-3); font-size: 14px; font-weight: 600; }
.identity[data-guest="true"] { background: var(--accent-soft); color: var(--accent); }
.icon-button { min-width: 44px; width: 44px; height: 44px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: var(--control-radius); background: transparent; color: var(--muted); padding: 0; }
.app-shell { width: 100%; min-width: 0; min-height: 0; overflow: auto; overscroll-behavior-y: contain; padding: var(--space-8) max(var(--space-6), calc((100% - 900px) / 2)); scroll-padding-block: var(--space-4); }
.view-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-6); }
h1, h2, h3, p { margin: 0; }
h1 { font-size: 26px; line-height: 1.35; font-weight: 750; }
h2 { font-size: 18px; line-height: 1.45; font-weight: 700; }
h3 { font-size: 16px; line-height: 1.5; }
.view-head p { margin-top: var(--space-1); color: var(--muted); font-size: 14px; }
.button { display: inline-flex; align-items: center; justify-content: center; gap: var(--space-2); min-height: 44px; padding: var(--space-2) var(--space-4); border: 1px solid var(--line); border-radius: var(--control-radius); background: var(--surface); color: var(--ink); text-decoration: none; font-size: 14px; font-weight: 600; flex-shrink: 0; }
.button-primary { background: var(--accent); color: var(--on-accent); border-color: transparent; }
.button-quiet { background: transparent; border-color: transparent; color: var(--accent); }
.button-danger { color: var(--danger); }
.filter-row { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-bottom: var(--space-4); }
.chip { min-height: 44px; padding: var(--space-2) var(--space-3); border-radius: 10px; border: 1px solid transparent; background: transparent; color: var(--muted); font-size: 14px; }
.chip[aria-pressed="true"] { background: var(--surface); color: var(--accent); border-color: var(--line); font-weight: 650; }
.feedback-list { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); overflow: hidden; }
.feedback-row { display: block; width: 100%; text-align: left; border: 0; background: transparent; padding: var(--space-5); }
.feedback-row + .feedback-row { border-top: 1px solid var(--line); }
.feedback-row:focus-visible { outline-offset: -4px; }
.row-heading { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); margin-bottom: var(--space-2); }
.category-label { display: flex; align-items: center; gap: var(--space-2); font-size: 12px; color: var(--muted); }
.category-label svg { width: 16px; height: 16px; color: var(--accent); }
.feedback-title { display: block; font-size: 17px; font-weight: 650; line-height: 1.5; overflow-wrap: anywhere; }
.feedback-excerpt { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-top: var(--space-1); color: var(--muted); font-size: 14px; line-height: 1.65; overflow-wrap: anywhere; }
.feedback-meta { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-1) var(--space-3); color: var(--muted); font-size: 12px; margin-top: var(--space-3); }
.feedback-meta span, .message-meta strong { overflow-wrap: anywhere; }
.status { display: inline-flex; align-items: center; gap: 6px; border-radius: 7px; padding: 3px var(--space-2); font-size: 12px; white-space: nowrap; color: var(--muted); background: var(--wash); }
.status::before { content: ''; width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.status-working, .status-planned { color: var(--accent); background: var(--accent-soft); }
.status-resolved { color: var(--success); background: var(--success-soft); }
.load-more { display: flex; justify-content: center; margin-top: var(--space-4); }
.surface-note { display: flex; align-items: flex-start; gap: var(--space-2); color: var(--muted); font-size: 13px; line-height: 1.65; margin-top: var(--space-4); }
.surface-note svg { width: 17px; height: 17px; margin-top: 2px; }
.empty { padding: var(--space-8) var(--space-5); text-align: center; color: var(--muted); font-size: 14px; }
.empty .empty-icon { color: var(--accent); display: block; margin: 0 auto var(--space-3); width: 32px; height: 32px; }
.empty h2 { margin-bottom: var(--space-2); color: var(--ink); font-size: 17px; }
.empty .button { margin-top: var(--space-4); }
.loading { padding: var(--space-8); color: var(--muted); text-align: center; font-size: 14px; }
.inline-error { color: var(--danger); background: var(--danger-soft); border-radius: 10px; padding: var(--space-3); margin-block: var(--space-3); font-size: 14px; overflow-wrap: anywhere; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); }
.field { display: grid; gap: var(--space-2); min-width: 0; }
.field.full { grid-column: 1 / -1; }
.field > label { font-size: 14px; font-weight: 600; }
.field label .optional { color: var(--muted); font-weight: 400; }
input:not([type=checkbox]), textarea, select { display: block; min-width: 0; width: 100%; min-height: 46px; padding: 10px 12px; border: 1px solid var(--line); border-radius: var(--control-radius); background: var(--canvas); font-size: 16px; line-height: 1.55; }
input::placeholder, textarea::placeholder { color: var(--muted); opacity: 1; }
textarea { resize: vertical; min-height: 112px; }
.check-row { display: flex; align-items: center; gap: var(--space-3); min-height: 44px; font-size: 14px; cursor: pointer; }
input[type=checkbox] { width: 19px; height: 19px; flex: 0 0 auto; accent-color: var(--accent); }
.form-note { color: var(--muted); font-size: 13px; line-height: 1.6; }
.form-note + .field, .field + .form-note { margin-top: var(--space-3); }
.form-actions { display: flex; justify-content: flex-end; flex-wrap: wrap; gap: var(--space-2); margin-top: var(--space-5); }
.draft-hint { color: var(--muted); font-size: 12px; flex: 1; align-self: center; }
.announcement-list { display: grid; gap: var(--space-4); }
.announcement-card { background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius); padding: var(--space-6); }
.announcement-card .feedback-meta { margin: 0 0 var(--space-3); }
.announcement-body, .thread-body, .reply p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.8; font-size: 15px; }
.announcement-body { margin-top: var(--space-4); }
.announcement-body a { overflow-wrap: anywhere; }
.chat-mode .site-foot { display: none; }
.chat-mode .app-shell { padding-block: 0; overflow: hidden; }
.chat-view { display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto; height: 100%; min-height: 0; }
.chat-heading { padding-block: var(--space-4); display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); border-bottom: 1px solid var(--line); }
.chat-heading h1 { font-size: 18px; }
.chat-heading p { color: var(--muted); font-size: 12px; margin-top: 2px; }
.chat-heading .icon-button { width: 40px; }
.chat-log { overflow-y: auto; overscroll-behavior: contain; overflow-anchor: none; min-height: 0; padding: var(--space-5) 0; scroll-padding-block: var(--space-4); }
.chat-message { display: grid; grid-template-columns: 30px minmax(0, 1fr); gap: var(--space-3); margin-bottom: var(--space-5); }
.avatar { display: grid; place-items: center; width: 30px; height: 30px; font-size: 12px; font-weight: 650; border-radius: 10px; background: var(--wash); color: var(--muted); }
.message-meta { display: flex; flex-wrap: wrap; align-items: baseline; gap: var(--space-2); margin-bottom: 5px; color: var(--muted); font-size: 12px; }
.message-meta strong { font-size: 13px; color: var(--muted); font-weight: 550; }
.message-bubble { display: inline-block; max-width: 100%; background: var(--surface); border: 1px solid var(--line); padding: 10px 14px; border-radius: 2px 14px 14px 14px; text-align: left; font-size: 15px; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; }
.chat-message.mine .avatar { background: var(--accent-soft); color: var(--accent); }
.chat-message.mine .message-bubble { background: var(--accent-soft); border-color: transparent; color: var(--ink); }
.chat-new { justify-self: center; margin: var(--space-2) 0; min-height: 36px; padding: 4px 12px; background: var(--accent-soft); color: var(--accent); border: 0; border-radius: 18px; font-size: 13px; }
.chat-compose { padding: var(--space-3) 0 max(var(--space-3), env(safe-area-inset-bottom)); border-top: 1px solid var(--line); background: var(--canvas); }
.compose-row { display: grid; grid-template-columns: minmax(0, 1fr) 46px; align-items: end; gap: var(--space-2); }
.chat-compose textarea { height: 46px; min-height: 46px; max-height: 116px; resize: none; background: var(--surface); overflow-y: auto; }
.send-button { width: 46px; padding: 0; min-height: 46px; border-radius: 13px; }
.compose-note { display: flex; justify-content: space-between; gap: var(--space-3); font-size: 12px; color: var(--muted); margin-top: var(--space-2); }
.compose-note button { border: 0; background: none; color: var(--accent); padding: 0; font-size: 12px; text-align: left; }
.chat-error { margin: 0 0 var(--space-2); color: var(--danger); font-size: 12px; }
.modal { position: fixed; inset-inline: 0; top: calc(var(--viewport-top, 0px) + var(--viewport-height) * .5); bottom: auto; margin: 0 auto; transform: translateY(-50%); padding: 0; color: var(--ink); border: 0; border-radius: 20px; background: var(--surface); box-shadow: var(--shadow); width: min(620px, calc(100% - 32px)); max-width: 100%; max-height: calc(var(--viewport-height) - 32px); overflow: hidden; }
.modal::backdrop { background: var(--scrim); }
.dialog-layout { display: flex; flex-direction: column; max-height: calc(var(--viewport-height) - 32px); }
.dialog-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-3) var(--space-5); border-bottom: 1px solid var(--line); flex: 0 0 auto; }
.dialog-head h2 { overflow-wrap: anywhere; }
.dialog-content { padding: var(--space-5); overflow-y: auto; overscroll-behavior-y: contain; min-height: 0; }
.dialog-content > .feedback-meta { margin-top: 0; margin-bottom: var(--space-4); }
.dialog-content .thread-body { margin-bottom: var(--space-5); }
.dialog-content h3 { margin-block: var(--space-5) var(--space-3); }
.reply { padding: var(--space-4) 0; border-top: 1px solid var(--line); }
.reply header { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2); font-size: 12px; color: var(--muted); margin-bottom: var(--space-2); }
.developer-badge { color: var(--accent); background: var(--accent-soft); padding: 2px 6px; border-radius: 5px; font-size: 11px; }
.reply-form { padding-top: var(--space-4); border-top: 1px solid var(--line); }
.action-preview { background: var(--wash); border-radius: var(--control-radius); margin-block: var(--space-3); padding: var(--space-3); font-size: 14px; white-space: pre-wrap; overflow-wrap: anywhere; }
altcha-widget { --altcha-color-base: var(--surface); --altcha-color-text: var(--ink); --altcha-color-neutral: var(--muted); --altcha-color-primary: var(--accent); --altcha-border-color: var(--line); --altcha-border-radius: 12px; width: 100%; }
.verification-wrap { margin-top: var(--space-4); min-height: 65px; max-width: 100%; }
.verification-status { margin-top: var(--space-2); color: var(--muted); font-size: 13px; }
.toast { position: fixed; top: calc(env(safe-area-inset-top) + 12px); left: 50%; transform: translateX(-50%); max-width: min(480px, calc(100% - 24px)); z-index: 100; border-radius: var(--control-radius); padding: 10px 16px; color: var(--surface); background: var(--ink); box-shadow: var(--shadow); font-size: 14px; pointer-events: none; }
.site-foot { display: flex; align-items: center; justify-content: center; flex-wrap: wrap; gap: var(--space-3); color: var(--muted); padding: var(--space-2) var(--space-4) max(var(--space-2), env(safe-area-inset-bottom)); font-size: 12px; }
.site-foot a { min-height: 32px; display: inline-flex; align-items: center; color: var(--muted); text-decoration: none; }
.admin-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; gap: var(--space-5); }
.admin-panel { padding: var(--space-5); border: 1px solid var(--line); border-radius: var(--radius); background: var(--surface); min-width: 0; }
.admin-panel h2 { margin-bottom: var(--space-4); }
.admin-panel .feedback-row { padding-inline: 0; }
.admin-panel .form-grid { grid-template-columns: 1fr; }
.admin-controls { display: flex; flex-wrap: wrap; gap: var(--space-2); align-items: center; margin-block: var(--space-4); }
.admin-controls select { flex: 1; width: auto; min-width: 120px; }
.admin-item { padding-block: var(--space-4); border-top: 1px solid var(--line); overflow-wrap: anywhere; }
.admin-item header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: var(--space-2); font-size: 14px; }
.admin-item .form-note { margin-block: var(--space-2); }
@media (hover: hover) {
  .button-primary:hover { background: var(--accent-hover); }
  .feedback-row:hover, .icon-button:hover, .chip:hover { background: var(--wash); }
  .primary-nav button:hover { color: var(--accent); }
}
@media (max-width: 760px) {
  .head-inner { padding: 6px var(--space-4) var(--space-2); grid-template-columns: 1fr auto; gap: 4px var(--space-2); }
  .primary-nav { grid-row: 2; grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .primary-nav button { min-width: 0; padding-block: 7px; }
  .brand { gap: var(--space-2); }
  .brand-mark { width: 32px; height: 32px; border-radius: 10px; }
  .brand strong { font-size: 17px; }
  .brand strong span { font-size: 14px; }
  .head-actions { gap: 0; }
  .identity { max-width: 112px; padding-inline: 10px; }
  .app-shell { padding: var(--space-5) var(--space-4); }
  h1 { font-size: 23px; }
  .view-head { margin-bottom: var(--space-4); }
  .view-head p { font-size: 13px; }
  .view-head .button { padding-inline: 12px; }
  .feedback-row { padding: var(--space-4); }
  .filter-row { gap: 2px; }
  .chip { padding-inline: 10px; font-size: 13px; }
  .announcement-card { padding: var(--space-4); }
  .chat-heading { padding-block: var(--space-3); }
  .chat-log { padding-block: var(--space-4); }
  .chat-message { gap: 10px; margin-bottom: var(--space-4); }
  .modal { width: 100%; top: auto; bottom: max(0px, calc(100% - var(--viewport-height) - var(--viewport-top, 0px))); margin: 0; transform: none; border-radius: 20px 20px 0 0; max-height: calc(var(--viewport-height) - 12px); }
  .dialog-layout { max-height: calc(var(--viewport-height) - 12px); }
  .dialog-head { padding: var(--space-2) var(--space-4); }
  .dialog-content { padding: var(--space-4) var(--space-4) max(var(--space-5), env(safe-area-inset-bottom)); }
  .dialog-head h2 { font-size: 17px; }
  .admin-grid { grid-template-columns: 1fr; }
  .site-foot { gap: var(--space-2); font-size: 11px; }
}
@media (max-width: 350px) {
  .app-shell { padding-inline: var(--space-3); }
  .head-inner { padding-inline: var(--space-3); }
  .brand strong span { margin-left: .15em; }
  .form-grid { grid-template-columns: 1fr; }
  .dialog-content { padding-inline: var(--space-3); }
}
@media (max-height: 480px) {
  .chat-heading { padding-block: 4px; }
  .chat-heading p, .compose-note { display: none; }
  .chat-compose { padding-block: 6px; }
}
@media (prefers-reduced-motion: no-preference) {
  .button, .chip, .icon-button, .primary-nav button, .feedback-row { transition: background-color 160ms ease-out, color 160ms ease-out; }
  .modal[open] .dialog-layout { animation: sheet-in 220ms cubic-bezier(.16, 1, .3, 1); }
  .modal[open]::backdrop { animation: scrim-in 180ms ease-out; }
  @keyframes sheet-in { from { transform: translateY(24px); opacity: .6; } to { transform: translateY(0); opacity: 1; } }
  @keyframes scrim-in { from { background: transparent; } to { background: var(--scrim); } }
}
`;
