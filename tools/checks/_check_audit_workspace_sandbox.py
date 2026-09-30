# -*- coding: utf-8 -*-
r"""审计回归：工作区沙箱 + 事件存储路径段清洗（直接对编译产物做断言，不需要起应用）。

【为什么这么写】这条修复是安全性的（沙箱逃逸 / 事件目录穿越），必须有能反复跑的断言。
常规套件要起 jar，但 target 里的 jar 可能被正在跑的实例占住、也可能不是 fat jar；
而这两个类（WorkspaceContext / EventStore）都不依赖 Spring 容器，
直接用 javac 编一个小探针、拿 target/classes 当 classpath 跑，几秒钟就能出结论。

【验的是什么】
  A. WorkspaceContext.resolve()
     A1 相对路径 "..\..\escaped.txt" 必须抛 IllegalStateException（原来会原样拼出去）
     A2 工作区内的相对路径必须归一化成工作区内的绝对路径
     A3 工作区外的绝对路径必须抛（原有行为，防回归）
     A4 工作区内的绝对路径原样返回（原有行为，防误伤）
     A5 strict=false 时不拦（配置开关仍然有效）

  B. EventStore.safePathSegment()
     B1 "..\..\..\evil" 这类穿越串被清洗成不含分隔符的单级名字
     B2 Windows 保留名（CON/NUL/COM1）被加前缀，不会让 createDirectories 失败
     B3 正常的 UUID 会话名原样保留（不能把正常会话的目录名也改了）

跑法：python tools/checks/_check_audit_workspace_sandbox.py
"""
import os
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding='utf-8', line_buffering=True)

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CLASSES = os.path.join(ROOT, 'target', 'classes')
HOME = os.path.expanduser('~')
REPO = os.path.join(HOME, '.m2', 'repository')

# 探针只需要这几个 jar：EventStore 的静态 logger 与 ObjectMapper
NEEDED = [
    r'org\slf4j\slf4j-api\2.0.11\slf4j-api-2.0.11.jar',
    r'com\fasterxml\jackson\core\jackson-databind\2.18.3\jackson-databind-2.18.3.jar',
    r'com\fasterxml\jackson\core\jackson-core\2.18.3\jackson-core-2.18.3.jar',
    r'com\fasterxml\jackson\core\jackson-annotations\2.18.3\jackson-annotations-2.18.3.jar',
    r'com\fasterxml\jackson\datatype\jackson-datatype-jsr310\2.18.3\jackson-datatype-jsr310-2.18.3.jar',
    r'org\springframework\spring-core\6.2.7\spring-core-6.2.7.jar',
    r'org\springframework\spring-beans\6.2.7\spring-beans-6.2.7.jar',
    r'org\springframework\spring-context\6.2.7\spring-context-6.2.7.jar',
]

PROBE = r'''
package com.lioncode.core.event;

import com.lioncode.core.workspace.WorkspaceContext;
import java.nio.file.Files;
import java.nio.file.Path;

public class AuditProbe {
    static int pass = 0, fail = 0;

    static void check(String label, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  [OK]   " + label); }
        else { fail++; System.out.println("  [FAIL] " + label + "  " + detail); }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("lion-audit-probe");
        Path ws = tmp.resolve("ws").resolve("inner").resolve("proj");
        Files.createDirectories(ws);
        Path outside = tmp.resolve("ws").resolve("escaped.txt");

        // ---------- A. WorkspaceContext ----------
        WorkspaceContext.setStrictMode(true);
        WorkspaceContext.set(ws.toString());
        try {
            String got = WorkspaceContext.resolve("..\\..\\escaped.txt");
            check("A1 相对路径 ..\\.. 越界必须被拦", false, "竟然返回了: " + got);
        } catch (IllegalStateException e) {
            check("A1 相对路径 ..\\.. 越界必须被拦", true, "");
        }

        String inside = WorkspaceContext.resolve("sub\\ok.txt");
        check("A2 工作区内的相对路径归一到工作区内",
              Path.of(inside).normalize().startsWith(ws.toAbsolutePath().normalize())
                  && inside.endsWith("ok.txt"),
              inside);

        try {
            String got = WorkspaceContext.resolve(outside.toAbsolutePath().toString());
            check("A3 工作区外的绝对路径必须被拦", false, "竟然返回了: " + got);
        } catch (IllegalStateException e) {
            check("A3 工作区外的绝对路径必须被拦", true, "");
        }

        String absIn = ws.resolve("a.txt").toAbsolutePath().toString();
        String back = WorkspaceContext.resolve(absIn);
        check("A4 工作区内的绝对路径原样返回", absIn.equals(back), back);

        // A5：关掉严格模式后要能放行（配置开关不能被写死）
        WorkspaceContext.setStrictMode(false);
        boolean allowed;
        try {
            WorkspaceContext.resolve("..\\..\\escaped.txt");
            allowed = true;
        } catch (IllegalStateException e) {
            allowed = false;
        }
        check("A5 strict=false 时不拦（开关仍有效）", allowed, "");
        WorkspaceContext.setStrictMode(true);
        WorkspaceContext.clear();

        // ---------- B. EventStore.safePathSegment ----------
        String s1 = EventStore.safePathSegment("..\\..\\..\\evil");
        check("B1 穿越串被清洗成不含分隔符的名字",
              !s1.contains("/") && !s1.contains("\\") && !s1.equals(".."), s1);

        String s2 = EventStore.safePathSegment("CON");
        check("B2 Windows 保留名被规避", !s2.equalsIgnoreCase("CON"), s2);

        String s3 = EventStore.safePathSegment("..");
        check("B2b 纯 .. 不会残留成上级目录", !s3.equals(".."), s3);

        String uuid = "7b1c2f30-1111-2222-3333-444455556666";
        check("B3 正常 UUID 会话名原样保留", uuid.equals(EventStore.safePathSegment(uuid)),
              EventStore.safePathSegment(uuid));

        // ---------- C. WorkspaceManager：空/非法路径必须抛可读异常，且不覆盖已有权限 ----------
        com.lioncode.core.workspace.WorkspaceManager mgr =
            new com.lioncode.core.workspace.WorkspaceManager();
        check("C1 空路径被拒（不再是 NPE/InvalidPathException）",
              throwsIae(() -> mgr.registerWorkspace(null)), "null");
        check("C1b 空白路径被拒", throwsIae(() -> mgr.registerWorkspace("   ")), "空白");
        check("C1c 非法字符路径被拒（不再是 JDK 原始英文报错）",
              throwsIae(() -> mgr.registerWorkspace("C:\\bad<>|path")), "非法字符");

        String wsPath = tmp.resolve("mgr-ws").toString();
        Files.createDirectories(Path.of(wsPath));
        mgr.registerWorkspace(wsPath, com.lioncode.core.workspace.WorkspaceManager
            .WorkspacePermission.READ_ONLY);
        mgr.registerWorkspace(wsPath);          // 模拟前端启动时重新注册默认工作区
        String perm = mgr.getWorkspace(Path.of(wsPath).toAbsolutePath().toString())
            .map(w -> w.permission().name()).orElse("?");
        check("C2 重复注册不会把 READ_ONLY 重置成 WORKSPACE_WRITE",
              "READ_ONLY".equals(perm), perm);

        System.out.println("PROBE_RESULT pass=" + pass + " fail=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }

    /** 断言某个操作抛出 IllegalArgumentException（而不是 NPE 或 JDK 原始异常） */
    static boolean throwsIae(Runnable r) {
        try {
            r.run();
            return false;
        } catch (IllegalArgumentException e) {
            return true;
        } catch (RuntimeException e) {
            System.out.println("       （抛的是 " + e.getClass().getSimpleName() + ": "
                + e.getMessage() + "）");
            return false;
        }
    }
}
'''

print('=' * 72)
print('审计回归：工作区沙箱拦截 + 事件路径段清洗（直接跑编译产物）')

if not os.path.isdir(CLASSES):
    print('  [FAIL] 找不到 %s，请先编译：python tools/dev/_mvn.py -o -q -DskipTests compile' % CLASSES)
    sys.exit(1)

cp_parts = [CLASSES]
missing = []
for rel in NEEDED:
    p = os.path.join(REPO, rel)
    if os.path.isfile(p):
        cp_parts.append(p)
    else:
        missing.append(rel)
if missing:
    print('  [FAIL] 缺少依赖 jar（先跑一次 mvn compile 让本地仓库就绪）: %s' % missing)
    sys.exit(1)

tmpdir = os.path.join(ROOT, 'target', '_audit_probe')
shutil.rmtree(tmpdir, ignore_errors=True)
os.makedirs(tmpdir, exist_ok=True)
src = os.path.join(tmpdir, 'AuditProbe.java')
with open(src, 'w', encoding='utf-8') as f:
    f.write(PROBE)

javac = shutil.which('javac')
if not javac:
    jh = os.environ.get('JAVA_HOME')
    if jh:
        javac = os.path.join(jh, 'bin', 'javac.exe')
if not javac or not os.path.isfile(javac):
    print('  [FAIL] 找不到 javac（设置 JAVA_HOME 或把它放进 PATH）')
    sys.exit(1)

cp = os.pathsep.join(cp_parts)
r = subprocess.run([javac, '-encoding', 'UTF-8', '-cp', cp, '-d', tmpdir, src],
                   capture_output=True, text=True)
if r.returncode != 0:
    print('  [FAIL] 探针编译失败:\n' + (r.stdout or '') + (r.stderr or ''))
    sys.exit(1)

java = os.path.join(os.path.dirname(javac), 'java.exe' if os.name == 'nt' else 'java')
r = subprocess.run([java, '-Dfile.encoding=UTF-8', '-cp', cp + os.pathsep + tmpdir,
                    'com.lioncode.core.event.AuditProbe'],
                   capture_output=True, text=True)
print(r.stdout.rstrip())
if r.stderr.strip():
    print('  stderr: ' + r.stderr.strip()[:400])

shutil.rmtree(tmpdir, ignore_errors=True)
ok = (r.returncode == 0) and ('fail=0' in r.stdout)
print('=' * 72)
print('结果：' + ('全部通过' if ok else '有失败项'))
sys.exit(0 if ok else 1)
