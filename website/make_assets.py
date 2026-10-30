#!/usr/bin/env python3
"""生成 YunGet 官网视觉资产（纯本地，不联网）。

产出（写进 `website/assets/`，`build.py` 再拷进 `docs/assets/`）：

- `logo-mark.svg`：云取运单标识，手写矢量，无外部依赖；
- `favicon.svg`：站点图标（矢量，现代浏览器直接用）；
- `apple-touch-icon.png`、`favicon-32.png`：栅格兜底（用 PIL 从同一几何画出来）；
- `og.png`：社交分享大图 1200×630（真实版本号 + 真实标语，不是装饰画）。

为什么用代码画而不是找图：品牌标识、站点图标与分享大图没有现成素材，而设计文档要求
"真实、清晰、像一个正在使用的产品"。这一版把“云端包裹运单”做成唯一母题：
纸色、墨色、朱红路由、分片条码。真实截图不走这里：`build_screenshots()` 只把
YunGet `images/` 里允许上站的那两张压缩后放进 `assets/`。

运行：`python website/make_assets.py`
"""

from __future__ import annotations

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
OUT = HERE / "assets"

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    "/System/Library/Fonts/PingFang.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
]


def font(size: int):
    for path in FONT_CANDIDATES:
        try:
            return ImageFont.truetype(path, size)
        except OSError:
            continue
    return ImageFont.load_default()


# --------------------------------------------------------------------------- #
# 品牌标识：一枚被分片路由切开的云端包裹
# --------------------------------------------------------------------------- #

def mark_svg(size: int = 32) -> str:
    """暖纸、墨色与朱红组成的运单标记。"""
    return f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" width="{size}" height="{size}" role="img" aria-label="云取 YunGet">
  <rect width="32" height="32" fill="#c83f2e"/>
  <rect x="3" y="3" width="26" height="26" fill="#f1eadb"/>
  <path d="M8 8h16v7H8z" fill="none" stroke="#181914" stroke-width="1.8"/>
  <path d="M8 13h16M12 8v5M20 8v5" fill="none" stroke="#181914" stroke-width="1.5"/>
  <path d="M10 18h5l1.5 2 1.5-2h4" fill="none" stroke="#c83f2e" stroke-width="1.8" stroke-linecap="square"/>
  <path d="M16 20v5M12.5 22.5 16 26l3.5-3.5" fill="none" stroke="#181914" stroke-width="1.8" stroke-linecap="square" stroke-linejoin="miter"/>
</svg>
'''


def draw_mark(d: ImageDraw.ImageDraw, ox: int, oy: int, s: float, color: str) -> None:
    """把 [mark_svg] 的同一几何用 PIL 画出来。color 保留为墨色参数。"""
    red = "#c83f2e"
    paper = "#f1eadb"
    d.rectangle([ox, oy, ox + 32 * s, oy + 32 * s], fill=red)
    d.rectangle([ox + 3 * s, oy + 3 * s, ox + 29 * s, oy + 29 * s], fill=paper)
    d.rectangle(
        [ox + 8 * s, oy + 8 * s, ox + 24 * s, oy + 15 * s],
        outline=color,
        width=max(1, round(1.8 * s)),
    )
    width = max(1, round(1.5 * s))
    d.line([(ox + 8 * s, oy + 13 * s), (ox + 24 * s, oy + 13 * s)], fill=color, width=width)
    d.line([(ox + 12 * s, oy + 8 * s), (ox + 12 * s, oy + 13 * s)], fill=color, width=width)
    d.line([(ox + 20 * s, oy + 8 * s), (ox + 20 * s, oy + 13 * s)], fill=color, width=width)
    route_width = max(1, round(1.8 * s))
    d.line(
        [(ox + 10 * s, oy + 18 * s), (ox + 15 * s, oy + 18 * s), (ox + 16.5 * s, oy + 20 * s),
         (ox + 18 * s, oy + 18 * s), (ox + 22 * s, oy + 18 * s)],
        fill=red,
        width=route_width,
    )
    d.line([(ox + 16 * s, oy + 20 * s), (ox + 16 * s, oy + 26 * s)], fill=color, width=route_width)
    d.line(
        [(ox + 12.5 * s, oy + 22.5 * s), (ox + 16 * s, oy + 26 * s), (ox + 19.5 * s, oy + 22.5 * s)],
        fill=color,
        width=route_width,
    )


# --------------------------------------------------------------------------- #
# 站点资产
# --------------------------------------------------------------------------- #

def build_yunget() -> list[str]:
    made = []
    red = "#c83f2e"
    paper = "#f1eadb"
    paper_raised = "#fbf7ed"
    ink = "#181914"
    blue = "#214d78"

    (OUT / "logo-mark.svg").write_text(mark_svg(), encoding="utf-8")
    (OUT / "favicon.svg").write_text(mark_svg(), encoding="utf-8")
    made += ["logo-mark.svg", "favicon.svg"]

    for size, name in ((180, "apple-touch-icon.png"), (32, "favicon-32.png")):
        img = Image.new("RGB", (size, size), paper)
        d = ImageDraw.Draw(img)
        s = size / 32.0
        draw_mark(d, 0, 0, s, ink)
        img.save(OUT / name, "PNG", optimize=True)
        made.append(name)

    data = json.loads((HERE / "data" / "releases.json").read_text(encoding="utf-8"))
    version = data["latest"]["tag"].lstrip("v")

    img = Image.new("RGB", (1200, 630), paper)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 1200, 14], fill=ink)
    for index, width in enumerate((110, 36, 72, 24, 92)):
        left = sum((110, 36, 72, 24, 92)[:index])
        d.rectangle([left, 0, left + width - 3, 14], fill=red if index % 2 == 0 else paper_raised)

    d.rectangle([72, 72, 1128, 558], outline=ink, width=3)
    draw_mark(d, 98, 100, 3.2, ink)
    d.text((222, 102), "云取 YunGet", font=font(70), fill=ink)
    d.text((225, 182), "云 端 包 裹 · CLOUD COURIER", font=font(22), fill=red)

    d.line([(98, 236), (1102, 236)], fill=ink, width=2)
    d.text((98, 272), "把分享链接", font=font(62), fill=ink)
    d.text((98, 344), "变成你的文件。", font=font(62), fill=ink)
    d.text((100, 432), "解析 · 分段并发 · 断点续传 · 本地凭证", font=font(28), fill=blue)

    d.rectangle([850, 266, 1102, 482], fill=paper_raised, outline=ink, width=2)
    d.text((872, 286), "WAYBILL", font=font(18), fill=red)
    d.text((872, 324), f"v{version}", font=font(40), fill=ink)
    d.text((872, 376), "ANDROID", font=font(22), fill=ink)
    d.text((872, 410), "AGPL-3.0", font=font(22), fill=ink)
    d.text((872, 444), "LOCAL CREDENTIALS", font=font(15), fill=blue)

    # 固定条码序列：与站点的分段下载记号同源，不随机。
    x = 100
    for width in (5, 2, 8, 3, 4, 2, 9, 3, 6, 2, 5, 3, 8, 2, 4):
        d.rectangle([x, 502, x + width, 538], fill=ink)
        x += width + 7
    d.text((430, 502), "YUNGET / OPEN SOURCE", font=font(20), fill=ink)
    d.text((430, 529), "github.com/jiayuxuan123/YunGet", font=font(18), fill=red)

    d.rectangle([972, 74, 1126, 158], outline=red, width=3)
    d.text((997, 89), "已封装", font=font(30), fill=red)
    d.text((1002, 126), "SEALED / LOCAL", font=font(12), fill=red)
    img.save(OUT / "og.png", "PNG", optimize=True)
    made.append("og.png")
    return made


def build_screenshots() -> list[str]:
    """把截图压到适合网页的尺寸。

    **只处理调用方明确允许的那两张**：源图里 Login.jpg 含真实账号昵称与容量、
    Link.jpg 含明文签名直链、about.jpg 是上游旧界面 —— 都不上站，因此也不处理。
    """
    allowed = ["Parsing.jpg", "Setting.jpg"]
    src_dir = HERE.parent / "images"
    made = []
    for name in allowed:
        src = src_dir / name
        if not src.exists():
            print(f"  [skip] 缺少源图 {src}")
            continue
        img = Image.open(src)
        # 目标宽度 720 → 展示宽约 340 CSS px，2x 屏也够；同时转成体积更小的 WEBP
        if img.width > 720:
            img = img.resize((720, round(img.height * 720 / img.width)), Image.LANCZOS)
        out = OUT / (Path(name).stem.lower() + ".webp")
        img.convert("RGB").save(out, "WEBP", quality=82, method=6)
        made.append(out.name)
        print(f"  {name} {img.width}x{img.height} → {out.name} {out.stat().st_size // 1024} KB")
    return made


def build_support_qr() -> list[str]:
    """赞赏码。

    源图是应用内「支持开发」页用的那张（`app/src/main/res/drawable/weixin.png`）。
    用代码里的同一张图，而不是另做一张 —— 官网上和手机里看到的必须是同一个码，
    否则会有人把钱扫到别处去。二维码不重采样（缩放会糊掉方块边界，扫不出来）。
    """
    src = HERE.parent / "app" / "src" / "main" / "res" / "drawable" / "weixin.png"
    if not src.exists():
        print(f"  [skip] 缺少赞赏码源图 {src}")
        return []
    img = Image.open(src).convert("RGB")
    # 控制体积；二维码本身是两色，转成调色板也不会损失可读性
    if img.width > 720:
        img = img.resize((720, round(img.height * 720 / img.width)), Image.NEAREST)
    out = OUT / "weixin.webp"
    img.save(out, "WEBP", lossless=True, method=6)
    print(f"  weixin.png {img.width}x{img.height} → {out.name} {out.stat().st_size // 1024} KB")
    return [out.name]


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)

    made = build_yunget()
    made += build_screenshots()
    made += build_support_qr()

    print(f"[assets] yunget: {len(made)} 个文件 → {OUT}")
    for name in made:
        p = OUT / name
        print(f"  {name}  {p.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
