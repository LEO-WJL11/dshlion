#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""打 VS Code 扩展包（.vsix）—— 按 VS Code 真正认的结构打。

【为什么不能"把目录 zip 一下"】第一版就是这么干的，结果 `code --install-extension` 直接报
    Error: extension/package.json not found inside zip.
VS Code 认的 vsix 是**特定结构的 zip**：
    extension/package.json          <- 所有扩展文件必须在 extension/ 目录下
    extension.vsixmanifest          <- 元数据（Id/Publisher/Version/引擎版本）
    [Content_Types].xml             <- 包内容类型表
少了任何一样，装的时候都不认（而且报错信息只说"找不到 package.json"，很误导人）。

【为什么不用 vsce】vsce 是 npm 包、要联网装；这里就是三个固定文件 + 一层目录，自己打更可控，
也不用为了发一个包引入 node_modules。

用法：python tools/release/_build_vsix.py [版本号]     # 默认读 extensions/vscode/package.json
产物：installer/release/LionBox-VSCode-<版本>.vsix
"""

import io
import json
import os
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
EXT = os.path.join(ROOT, 'extensions', 'vscode')
OUT_DIR = os.path.join(ROOT, 'installer', 'release')

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

CONTENT_TYPES = '''<?xml version="1.0" encoding="utf-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension=".json" ContentType="application/json"/>
  <Default Extension=".js" ContentType="application/javascript"/>
  <Default Extension=".svg" ContentType="image/svg+xml"/>
  <Default Extension=".md" ContentType="text/markdown"/>
  <Default Extension=".vsixmanifest" ContentType="text/xml"/>
  <Default Extension=".vscodeignore" ContentType="text/plain"/>
  <Default Extension=".txt" ContentType="text/plain"/>
</Types>
'''

MANIFEST = '''<?xml version="1.0" encoding="utf-8"?>
<PackageManifest Version="2.0.0" xmlns="http://schemas.microsoft.com/developer/vsx-schema/2011">
  <Metadata>
    <Identity Language="en-US" Id="{id}" Version="{version}" Publisher="{publisher}" />
    <DisplayName>{display}</DisplayName>
    <Description xml:space="preserve">{description}</Description>
    <Tags>{keywords}</Tags>
    <Categories>{categories}</Categories>
    <GalleryFlags>Public</GalleryFlags>
    <Properties>
      <Property Id="Microsoft.VisualStudio.Code.Engine" Value="{engine}" />
      <Property Id="Microsoft.VisualStudio.Code.ExtensionKind" Value="ui,workspace" />
    </Properties>
  </Metadata>
  <Installation>
    <InstallationTarget Id="Microsoft.VisualStudio.Code" />
  </Installation>
  <Dependencies />
  <Assets>
    <Asset Type="Microsoft.VisualStudio.Code.Manifest" Path="extension/package.json" Addressable="true" />
  </Assets>
</PackageManifest>
'''


def build(version=None):
    pkg = json.load(io.open(os.path.join(EXT, 'package.json'), encoding='utf-8'))
    ver = version or pkg['version']
    out = os.path.join(OUT_DIR, 'LionBox-VSCode-%s.vsix' % ver)
    os.makedirs(OUT_DIR, exist_ok=True)
    if os.path.isfile(out):
        os.remove(out)

    skip_dirs = {'node_modules', '.vscode', 'out', '.git'}
    n = 0
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        # ① 扩展本体：全部塞进 extension/
        for base, dirs, files in os.walk(EXT):
            dirs[:] = [d for d in dirs if d not in skip_dirs]
            for f in files:
                if f.endswith(('.vsix', '.zip', '.map')):
                    continue
                full = os.path.join(base, f)
                rel = os.path.relpath(full, EXT).replace('\\', '/')
                z.write(full, 'extension/' + rel)
                n += 1
        # ② 元数据
        z.writestr('extension.vsixmanifest', MANIFEST.format(
            id=pkg.get('name', 'lionbox'),
            version=ver,
            publisher=pkg.get('publisher', 'lioncode'),
            display=pkg.get('displayName', 'LionBox'),
            description=(pkg.get('description') or '').replace('&', '&amp;').replace('<', '&lt;'),
            keywords=','.join(pkg.get('keywords') or []),
            categories=','.join(pkg.get('categories') or []),
            engine=(pkg.get('engines') or {}).get('vscode', '^1.90.0'),
        ))
        z.writestr('[Content_Types].xml', CONTENT_TYPES)

    # 打完自己先验一遍结构：少了这三样，装的时候必然失败
    with zipfile.ZipFile(out) as z:
        names = z.namelist()
        problems = []
        if 'extension/package.json' not in names:
            problems.append('缺 extension/package.json')
        if 'extension.vsixmanifest' not in names:
            problems.append('缺 extension.vsixmanifest')
        if '[Content_Types].xml' not in names:
            problems.append('缺 [Content_Types].xml')
        inner = json.loads(z.read('extension/package.json').decode('utf-8'))
        if inner.get('version') != ver:
            problems.append('包里的版本号 %s 与包名 %s 不一致' % (inner.get('version'), ver))
        icon = ((inner.get('contributes') or {}).get('viewsContainers') or {})
        if not icon:
            problems.append('package.json 里没有 viewsContainers（侧栏面板就挂不上）')

    print('%s  %.1f KB  条目 %d 个' % (os.path.basename(out), os.path.getsize(out) / 1024.0, n + 2))
    if problems:
        for p in problems:
            print('  FAIL %s' % p)
        return 1
    print('  结构自检通过：extension/package.json + extension.vsixmanifest + [Content_Types].xml')
    return 0


if __name__ == '__main__':
    sys.exit(build(sys.argv[1] if len(sys.argv) > 1 else None))
