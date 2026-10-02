#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""LionBox 图标（第五版）：**魔方一样的 3D 立方体，某个面上 3D 嵌着一个狮子头**。

用户定稿要求（原话）："我想做的是那种盒子，像个魔方一样的，然后里面 3D 效果里面嵌着一个狮子头。"

所以这版：
  1. 画一个**等轴测立方体**（三个可见面：顶面菱形 + 左前壁 + 右前壁），每个面切成 **3×3 的格子**
     —— 这就是魔方的长相；
  2. 右前壁上**按透视嵌入**一个圆形狮子头：用仿射变换把平面画好的狮头**贴到斜面上**
     （不是直接盖一个圆上去 —— 那样永远是"贴纸感"，这是"3D 嵌进去"的关键）；
  3. 嵌进去的那块周围画**内凹的边框**（外深内亮的斜面），看起来像箱体上开了个凹槽把狮头镶了进去；
  4. 颜色用暖金 + 深色格缝（魔方感），不再用牛皮纸（那版被说成像花盆/卖植物）。

实现要点（PIL）：把狮头画在一张独立的方形图层上（带内凹边框），再用
`Image.transform(..., Image.AFFINE, ...)` 做**输出→输入**的仿射映射贴到斜面：
  右前壁 = 原点 C + s·u + t·v，其中 u = R - C，v = C' - C；
  反过来解出 (s,t) 就得到仿射系数。这一步是"真 3D 嵌入"和"贴纸"的分界线。
"""

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, 'assets', 'icon')
os.makedirs(OUT, exist_ok=True)

S = 2048
BG = (23, 24, 27, 255)

FACE_TOP = (240, 198, 96, 255)     # 顶面（最亮）
FACE_L = (214, 162, 56, 255)       # 左前壁
FACE_R = (168, 120, 30, 255)       # 右前壁（最暗，狮头嵌在这一面上）
GROOVE = (120, 84, 18, 255)        # 格缝
MANE = (128, 56, 6, 255)
FACE_LION = (247, 184, 72, 255)
MUZZLE = (252, 238, 206, 255)
DARK = (34, 20, 4, 255)
SLOT = (86, 58, 12, 255)           # 凹槽底色

img = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
k = S / 1024.0


def P(pts):
    return [(x * k, y * k) for (x, y) in pts]


d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(190 * k), fill=BG)

# ---------- 立方体顶点（设计稿坐标）----------
h = 330
T = (512, 150)
L = (172, 330)
R = (852, 330)
C = (512, 510)
L2 = (L[0], L[1] + h)
C2 = (C[0], C[1] + h)
R2 = (R[0], R[1] + h)

# 三个面
d.polygon(P([T, R, C, L]), fill=FACE_TOP)
d.polygon(P([L, C, C2, L2]), fill=FACE_L)
d.polygon(P([C, R, R2, C2]), fill=FACE_R)


def lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def cell(origin, u, v, i, j, n=3):
    p0 = lerp(lerp(origin, u, i / n), lerp(origin, v, j / n), 1) if False else None
    # 双线性：origin + (i/n)u + (j/n)v
    def pt(s, t):
        return (origin[0] + u[0] * s + v[0] * t, origin[1] + u[1] * s + v[1] * t)
    return [pt(i / n, j / n), pt((i + 1) / n, j / n), pt((i + 1) / n, (j + 1) / n), pt(i / n, (j + 1) / n)]


# 顶面 3×3 格缝（两个方向各两条线）
for i in (1, 2):
    d.line(P([lerp(T, L, i / 3), lerp(R, C, i / 3)]), fill=GROOVE, width=int(7 * k))
    d.line(P([lerp(T, R, i / 3), lerp(L, C, i / 3)]), fill=GROOVE, width=int(7 * k))
# 左前壁 3×3
for i in (1, 2):
    d.line(P([lerp(L, C, i / 3), lerp(L2, C2, i / 3)]), fill=GROOVE, width=int(7 * k))
for j in (1, 2):
    d.line(P([lerp(L, L2, j / 3), lerp(C, C2, j / 3)]), fill=GROOVE, width=int(7 * k))
# 右前壁：只在外圈留格缝（中间那块要嵌狮头）
for i in (1, 2):
    d.line(P([lerp(C, R, i / 3), lerp(C2, R2, i / 3)]), fill=GROOVE, width=int(7 * k))
for j in (1, 2):
    d.line(P([lerp(C, C2, j / 3), lerp(R, R2, j / 3)]), fill=GROOVE, width=int(7 * k))

# 轮廓线，让立方体边缘干脆
d.line(P([T, R, R2, C2, L2, L, T]), fill=(96, 66, 12, 255), width=int(9 * k))

# ---------- 狮头图层（平面画好，再仿射贴到右前壁）----------
LW = 768
layer = Image.new('RGBA', (LW, LW), (0, 0, 0, 0))
ld = ImageDraw.Draw(layer)
m = LW // 12
# 凹槽：外框深 → 内圈亮（做出"嵌进去"的内凹斜面）
ld.rounded_rectangle([m, m, LW - m, LW - m], radius=LW // 8, fill=SLOT)
ld.rounded_rectangle([m + 14, m + 14, LW - m - 14, LW - m - 14], radius=LW // 9,
                     outline=(196, 146, 46, 255), width=16)
ld.ellipse([m + 26, m + 26, LW - m - 26, LW - m - 26], outline=(70, 44, 8, 255), width=10)
cx = cy = LW // 2
mane_r, face_r = int(LW * 0.31), int(LW * 0.225)
ld.ellipse([cx - mane_r, cy - mane_r, cx + mane_r, cy + mane_r], fill=MANE)
for sx in (-1, 1):
    ex = cx + sx * int(face_r * 0.82)
    ld.ellipse([ex - 46, cy - face_r - 54, ex + 46, cy - face_r + 38], fill=MANE)
    ld.ellipse([ex - 26, cy - face_r - 36, ex + 26, cy - face_r + 20], fill=FACE_LION)
ld.ellipse([cx - face_r, cy - face_r, cx + face_r, cy + face_r], fill=FACE_LION)
ld.ellipse([cx - int(face_r * 0.55), cy + int(face_r * 0.08),
            cx + int(face_r * 0.55), cy + int(face_r * 0.74)], fill=MUZZLE)
ld.polygon([(cx - 24, cy + int(face_r * 0.16)), (cx + 24, cy + int(face_r * 0.16)),
            (cx, cy + int(face_r * 0.46))], fill=DARK)
for sx in (-1, 1):
    ex, ey = cx + sx * int(face_r * 0.38), cy - int(face_r * 0.30)
    ld.ellipse([ex - 20, ey - 22, ex + 20, ey + 22], fill=DARK)
    ld.ellipse([ex - 8, ey - 14, ex + 3, ey - 3], fill=(255, 255, 255, 230))

# 把狮头图层仿射映射到右前壁（C, R, C2）：u = R-C，v = C2-C
ux, uy = R[0] - C[0], R[1] - C[1]
vx, vy = C2[0] - C[0], C2[1] - C[1]
det = ux * vy - uy * vx
# s = ((x-Cx)*vy - (y-Cy)*vy?) 解线性方程组 [ux vx; uy vy]·(s,t) = (x-Cx, y-Cy)
a11, a12 = ux, vx
a21, a22 = uy, vy
i11, i12 = vy / det, -vx / det
i21, i22 = -uy / det, ux / det
# 输出(x,y) → 输入(lx,ly)：先解 (s,t)，再乘 LW
A = LW * i11 / k
B = LW * i12 / k
Cc = -LW * (i11 * C[0] + i12 * C[1])
D = LW * i21 / k
E = LW * i22 / k
F = -LW * (i21 * C[0] + i22 * C[1])
warped = layer.transform((S, S), Image.AFFINE, (A, B, Cc, D, E, F), resample=Image.BICUBIC)
img.alpha_composite(warped)

img = img.resize((512, 512), Image.LANCZOS)
for s in (16, 32, 48, 64, 128, 256, 512):
    img.resize((s, s), Image.LANCZOS).save(os.path.join(OUT, 'lionbox-%d.png' % s))
img.save(os.path.join(OUT, 'lionbox.ico'),
         sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print('已重画：魔方立方体（3×3 格）+ 仿射嵌入的 3D 狮头')
