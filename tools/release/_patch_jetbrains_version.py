#!/usr/bin/env python
# -*- coding: utf-8 -*-
r"""把 JetBrains 插件包从 1.5.1 提到 1.5.2（只改版本号，不动代码）。

【为什么是"打补丁"而不是重编】1.5.2 对 IDE 插件没有任何功能改动（插件干的事就是开一个窗口
指向本机 8080 的 WebUI），变的只有版本号。而重编一次要下 Gradle 发行版（~150MB）+
IntelliJ Platform 2026.1（~1.2GB）—— 这台机器上的 Gradle 缓存已经被清掉了
（`%USERPROFILE%\.gradle` 不存在），为改一个字符串下 1.3GB 不划算。

【所以必须把改了什么写清楚、并逐个校验】
  · `lib/lionbox-jetbrains-1.5.1.jar` → 重命名成 1.5.2，jar 里 `META-INF/plugin.xml`
    的 `<version>1.5.1</version>` → `1.5.2`
  · 两个 class 文件原样保留（用 CRC 比对，确保一个字节都没动）
  · 改完把 zip 重新解出来验：版本号、id、since-build、两个 class 都在
想真正重编的话：装 Gradle + JDK 21，`gradlew buildPlugin -Dorg.gradle.java.installations.paths=<jdk21>`。

用法：python tools/release/_patch_jetbrains_version.py
"""

import io
import os
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
# 版本号做成参数：以后每版都用同一条命令，不用再复制脚本
FROM = sys.argv[sys.argv.index('--from') + 1] if '--from' in sys.argv else '1.5.1'
TO = sys.argv[sys.argv.index('--to') + 1] if '--to' in sys.argv else '1.5.2'
SRC = os.path.join(ROOT, 'extensions', 'jetbrains', 'build', 'distributions',
                   'lionbox-jetbrains-%s.zip' % FROM)
OUT = os.path.join(ROOT, 'installer', 'release', 'LionBox-JetBrains-%s.zip' % TO)
OLD, NEW = FROM, TO
OLD_JAR = 'lionbox-jetbrains/lib/lionbox-jetbrains-%s.jar' % OLD
NEW_JAR = 'lionbox-jetbrains/lib/lionbox-jetbrains-%s.jar' % NEW

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

ok = True


def check(label, cond, detail=''):
    global ok
    ok = ok and bool(cond)
    print('%s %s%s' % ('[OK]  ' if cond else '[FAIL]', label, ('  ' + str(detail)) if detail else ''))


def main():
    if not os.path.isfile(SRC):
        raise SystemExit('源包不在：%s' % SRC)

    with zipfile.ZipFile(SRC) as z:
        jar_bytes = z.read(OLD_JAR)

    # 1) 拆开内层 jar，改 plugin.xml，其余原样
    src_jar = zipfile.ZipFile(io.BytesIO(jar_bytes))
    plugins = src_jar.read('META-INF/plugin.xml').decode('utf-8')
    if '<version>%s</version>' % OLD not in plugins:
        raise SystemExit('plugin.xml 里没有 <version>%s</version>，先看一眼' % OLD)
    new_plugins = plugins.replace('<version>%s</version>' % OLD, '<version>%s</version>' % NEW)

    inner = io.BytesIO()
    with zipfile.ZipFile(inner, 'w', zipfile.ZIP_DEFLATED) as out_jar:
        for item in src_jar.infolist():
            data = src_jar.read(item.filename)
            if item.filename == 'META-INF/plugin.xml':
                data = new_plugins.encode('utf-8')
            # 保留原时间戳，方便比对时排除"只是时间变了"
            zi = zipfile.ZipInfo(item.filename, date_time=item.date_time)
            zi.compress_type = zipfile.ZIP_DEFLATED
            out_jar.writestr(zi, data)
    inner_bytes = inner.getvalue()

    # 2) 重新打外层 zip
    if os.path.isfile(OUT):
        os.remove(OUT)
    with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr('lionbox-jetbrains/', b'')
        z.writestr('lionbox-jetbrains/lib/', b'')
        z.writestr(NEW_JAR, inner_bytes)
    print('已生成 %s（%s 字节）' % (os.path.basename(OUT), format(os.path.getsize(OUT), ',')))

    # 3) 校验：版本、id、since-build、class 文件原封不动
    with zipfile.ZipFile(OUT) as z:
        names = z.namelist()
        check('新包里只有新版 jar（旧版号不残留）',
              NEW_JAR in names and not any(OLD in n for n in names), names)
        data = z.read(NEW_JAR)
    with zipfile.ZipFile(io.BytesIO(data)) as j:
        px = j.read('META-INF/plugin.xml').decode('utf-8')
        check('plugin.xml 版本号 = %s' % NEW, '<version>%s</version>' % NEW in px)
        check('插件 id 没变', '<id>com.lioncode.lionbox</id>' in px)
        check('兼容区间没变（since-build 261）', 'since-build="261"' in px)
        classes = [n for n in j.namelist() if n.endswith('.class')]
        check('两个 class 都在', len(classes) == 2, classes)
        # 逐字节比对 class（改版本号不该动到代码）
        with zipfile.ZipFile(io.BytesIO(jar_bytes)) as oj:
            same = all(j.read(c) == oj.read(c) for c in classes)
        check('class 与 1.5.1 版逐字节一致（只改了版本号，没动代码）', same)

    print()
    print('结果：%s' % ('全部通过' if ok else '有失败项'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
