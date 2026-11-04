# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Chinese-speaking Android users who receive or collect cloud-drive share links and need to turn them into reliable local files. They often work with folders, large files, constrained mobile networks, and cloud-drive accounts that must stay on their own device. Plugin authors are a secondary audience.

## Product Purpose

YunGet resolves supported cloud-drive share links on the phone, lets the user inspect and select remote files, and hands the selected sources to its built-in TurboDL engine for resumable multi-connection download. The website should explain that workflow, provide a trustworthy APK download, and make privacy, platform support, plugins, and troubleshooting clear.

## Positioning

The parsing and download path runs through the user's device instead of a central YunGet transfer server. Parsers are updateable JavaScript plugins, while the download engine remains a reusable Kotlin/JVM SDK.

## Operating Context

Users paste a share link or share text, complete a platform login when the source requires it, browse a directory or select files, and start one or more downloads. Current releases are Android-only. The official plugin source and release metadata are maintained in separate GitHub repositories.

## Capabilities and Constraints

- Current release facts come from `website/data/releases.json`; the website must not hardcode a newer version.
- Android 7.0/API 24 and newer is supported. Windows, macOS, and Linux are planned, not downloadable.
- The current app declares ten platforms: Quark, UC, Xunlei, Baidu, 123, 139, 115, Guangya, Lanczos, and Lanczos Premium.
- Full directory browsing and direct-link extraction can require a platform login. The existing website contains conflicting wording about which links work without login; future copy must use one approved behavioral statement rather than inventing a broader claim.
- Official plugin support on Android is JavaScript-only; native plugins are disabled. The fetched official plugin index is authoritative for installed marketplace content.
- Download artifacts include ABI-specific APKs and a universal compatibility bridge. The universal APK remains available for older updater compatibility.
- Website build is an existing Python standard-library static generator writing to `docs/`; it must preserve real directory routes, relative links, release-driven downloads, sitemap, robots, and GitHub Pages compatibility.

## Brand Commitments

The product name is “云取 YunGet”. The project is open source under AGPL-3.0. The voice is direct, technical, and Chinese-first. The product boundary explicitly excludes a central transfer proxy and remote collection of account credentials.

## Evidence on Hand

- Release data: `website/data/releases.json`.
- Real app screenshots: `website/assets/parsing.webp`, `website/assets/setting.webp`.
- Brand marks and social image: `website/assets/logo-mark.svg`, `website/assets/favicon.svg`, `website/assets/og.png`.
- Official plugin index: sibling `YunGet-Plugins/plugins.json`.
- Download edge mapping and GitHub fallbacks: `website/content.py`.
- Legal and privacy source documents: `PRIVACY.md`, `TERMS.md`, `NOTICE.md`, `LICENSE`.

No customer testimonials, customer logos, speed claims, or reliability percentages are approved evidence.

## Product Principles

1. Explain the direct-download workflow before listing implementation details.
2. Keep account and download data on the device; never imply a YunGet transfer server exists.
3. State platform support and login requirements precisely, even when the answer is conditional.
4. Make download, checksum, and ABI choices obvious on small screens.
5. Keep parser capabilities updateable without weakening trust or security communication.

## Accessibility & Inclusion

The public site must meet WCAG AA for text contrast, keyboard focus, semantic headings, reduced-motion preferences, 44px touch targets, and readable mobile form controls. FAQ content must remain available without JavaScript.
