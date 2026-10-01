#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""生成 Tauri / 安装包要用的应用图标（LionBox 的狮子头占位图标）。

为什么自己画：Tauri 打 NSIS 包**必须**有 icon.ico，仓库里没有现成的图标文件
（主安装包用的是 Inno 默认图标）。这里用 Pillow 画一个近黑底 + 圆角 + "LB" 的图标，
256×256，多尺寸打包进 .ico。等有正式设计稿直接替换 desktop/tauri/src-tauri/icons/icon.ico 即可。

用法：python tools/dev/_make_icon.py
"""

import io
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, 'desktop', 'tauri', 'src-tauri', 'icons')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass


def draw(size):
    from PIL import Image, ImageDraw, ImageFont
    img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    pad = int(size * 0.06)
    # 近黑底 + 深灰圆角：和界面暗色主题一个调子（#0d0e10 / #2a2d31）
    d.rounded_rectangle([pad, pad, size - pad, size - pad], radius=int(size * 0.22),
                        fill=(13, 14, 16, 255), outline=(42, 45, 49, 255),
                        width=max(2, int(size * 0.02)))
    # 中间一条灰蓝竖线 + LB 字样（字形用系统字体，找不到就只画方块）
    font = None
    for name in ('msyhbd.ttc', 'msyh.ttc', 'segoeuib.ttf', 'arialbd.ttf'):
        for base in (r'C:\Windows\Fonts',):
            p = os.path.join(base, name)
            if os.path.isfile(p):
                try:
                    font = ImageFont.truetype(p, int(size * 0.42))
                    break
                except Exception:
                    font = None
        if font:
            break
    text = 'LB'
    if font:
        box = d.textbbox((0, 0), text, font=font)
        w, h = box[2] - box[0], box[3] - box[1]
        d.text(((size - w) / 2 - box[0], (size - h) / 2 - box[1]), text,
               font=font, fill=(159, 176, 196, 255))
    else:
        d.rectangle([size * 0.32, size * 0.32, size * 0.68, size * 0.68],
                    fill=(159, 176, 196, 255))
    return img


def main():
    from PIL import Image
    os.makedirs(OUT_DIR, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    base = draw(256)
    ico = os.path.join(OUT_DIR, 'icon.ico')
    base.save(ico, format='ICO', sizes=[(s, s) for s in sizes])
    png = os.path.join(OUT_DIR, 'icon.png')
    base.save(png, format='PNG')
    print('已生成 %s（%.1f KB）' % (ico, os.path.getsize(ico) / 1024.0))
    print('已生成 %s（%.1f KB）' % (png, os.path.getsize(png) / 1024.0))


if __name__ == '__main__':
    main()
