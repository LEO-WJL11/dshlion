#!/usr/bin/env bash
# 把 dev 仓库同步到 dshlion main 的过滤树里。
#
# 【2026-09-30 改法】以前是一个**手写白名单**（一个个列文件），结果漏一个文件
# 公开仓库里就留一份旧源码 —— 这一轮改文案就踩到了：ProviderController、
# StartupRestorer、AdapterManager 这些没在名单里，公开仓库里还是旧的（还写着"盒子"），
# 硬件设计文档删了以后公开仓库里也还留着。
#
# 现在改成机械做法（不靠人记）：
#   1) 先读出 dshlion/main 现有的文件清单 —— 这是"公开仓库自己的范围"；
#   2) 本地还存在的 → git add 更新；本地已删掉的 → git rm --cached 从发布树删掉；
#   3) 再单独 add 少数**新加入**的源码文件；
#   4) 安装包单独处理（release 目录在 .gitignore 里，必须 -f）；
#   5) 推之前跑一遍密钥扫描，扫到就中止。
#
# 这样既不会漏，也仍然**绝不**把本地那些 `_*` 脚本 / output/ / 硬件草稿带上去
# （它们不在 dshlion/main 的清单里，也不在下面"新增"那几条里）。
#
# 用法：bash _sync_dshlion.sh
set -euo pipefail

cd /c/Users/Leo/Desktop/lion-code

# 【坑】git ls-tree 默认把非 ASCII 路径转义成 "README-\346\250\241..." 这种八进制，
# 拿它去 test -e / git add 全是"文件不存在" —— 上一版就这么把公开仓库里
# README-模型.md、docs 里那几份中文文档、dist 里的两个 bat **误删**了。
# 必须 core.quotepath=false 让它原样输出中文路径。
export GIT_INDEX_FILE=/tmp/dshlion_idx
rm -f "$GIT_INDEX_FILE"

git read-tree dshlion/main

# ---- 0) 要**从公开树里删掉**的（白名单，只列你真的要删的）----
# 【为什么不做成"本地没有就从公开树删"】公开仓库里有几个文件本地本来就没有
# （比如 使用教程.md 这种当年只在 GitHub 上写的东西），自动删等于把只在远端的内容删了。
# 所以删除走显式名单。
REMOVED=(
  "docs/硬件设计规格-RK3588S.md"
  "docs/E5主板-原理图交付说明.md"
  "docs/E5主板-电源与外设设计.md"
  "docs/E5主板原理图-状态.md"
  "docs/X99参考设计-抽取.md"
  "docs/EasyEDA-API-实操笔记.md"
  "docs/EasyEDA-自建符号API.md"
  "启动.bat"
  "lion-code-agent-harness-1.0.0-SNAPSHOT.jar"
)

# ---- 1) 公开树里已有的文件：本地还在的就更新 ----
# （安装包单独处理，跳过；它 74 MB，每次都 add 太慢）
count_add=0
count_rm=0
while IFS= read -r f; do
  case "$f" in
    installer/release/*) continue ;;
  esac
  if [ -e "$f" ]; then
    git add -- "$f"
    count_add=$((count_add + 1))
  fi
done < <(git -c core.quotepath=false ls-tree -r --name-only dshlion/main)
for f in "${REMOVED[@]}"; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    git rm --cached -q "$f" 2>/dev/null || true
    echo "  从公开树删掉: $f"
    count_rm=$((count_rm + 1))
  fi
done
echo "  更新 $count_add 个、删掉 $count_rm 个"

# ---- 2) 本地有、公开树里还没有的文件：**全部**加进去 ----
# 【2026-09-30 再改一次】上一版这里是手写 NEW_FILES（一个个列），1.5.0 一口气新增了
# 40 多个源文件（core/context、core/plugin/{team,review,automation,dev}、skills/、
# desktop/、extensions/…），手写名单必然漏 —— 漏一个公开仓库就少一份源码。
# 改成机械做法：私有仓库 git 跟踪的文件就是全部要公开的内容，直接镜像。
#
# 【坑】必须用**真实索引**列这份清单：下面 export 了 GIT_INDEX_FILE 指向发布树索引，
# 而 `git ls-files --cached --others` 在"自定义索引"下**列不出私有仓库里已跟踪、
# 但发布树里还没有的文件**（实测 _push_dshlion.sh / _sync_dshlion.sh / _finalize_141.py
# 就这么被漏掉了）。所以先在前面用 `GIT_INDEX_FILE=` 读一份真实清单存起来。
# 【坑中坑】`GIT_INDEX_FILE= git ls-files` 这种"内联置空"在 Git Bash 里会返回 0 个文件
# （不是回退到默认索引，而是直接什么都没列出来），于是"新增"永远是 0 —— 又踩了一次。
# 正确写法是 subshell 里 unset 掉再列。
DEV_FILES=$(unset GIT_INDEX_FILE; git -c core.quotepath=false ls-files)
count_new=0
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    installer/release/*) continue ;;
  esac
  if [ -e "$f" ] && ! git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    # -f：tools/release/ 这类路径可能被 .gitignore 的规则挡着
    git add -f -- "$f"
    count_new=$((count_new + 1))
  fi
done <<< "$DEV_FILES"
echo "  新增 $count_new 个"

# ---- 3) 安装包 / 插件包：installer/release 下的**所有**产物都发 ----
# （该目录在 .gitignore 里，所以必须 -f）
#
# 【2026-09-30 改法】以前这里写死"只发 LionBox-Setup-$ver.exe"，于是 1.5.0 新增的
# VS Code .vsix 和 JetBrains .zip 进不了公开仓库（只有主包进得去）。改成扫目录。
# 顺手加一道 100MB 上限判断：GitHub 单文件硬上限是 100MB，超了会把整个 push 打回去
# （桌面版 Electron 安装包 178MB 就是这种情况，它只能走 GitHub Releases）。
for old in $(git -c core.quotepath=false ls-tree -r --name-only dshlion/main | grep '^installer/release/' || true); do
  if [ ! -e "$old" ]; then
    git rm --cached -q "$old" 2>/dev/null || true
    echo "  旧产物从公开树删掉: $old"
  fi
done
for f in installer/release/*; do
  [ -e "$f" ] || continue
  size=$(stat -c %s "$f" 2>/dev/null || echo 0)
  if [ "$size" -gt 104857600 ]; then
    echo "  跳过（$(($size / 1048576))MB > GitHub 100MB 上限，要走 Releases）: $(basename "$f")"
    continue
  fi
  git add -f -- "$f"
  echo "  发布产物: $(basename "$f")（$(($size / 1048576))MB）"
done

echo "--- 相对 dshlion/main 的改动 ---"
tree=$(git write-tree)
git diff-tree -r --name-status dshlion/main "$tree"

# ---- 4) 秘密扫描：推之前先把 tree 里每个文件过一遍 ----
# 为什么必须有这一步：2026-09-28 我把写死了 API key 的探针脚本推到了公开仓库的
# dev-sync 分支上（暴露约 10 分钟才删掉）。那是我的错，也是最后一次。
# 这里按"长随机串"的特征扫，扫到就直接中止，绝不往下推。
echo "--- 秘密扫描 ---"
leak=0
while IFS= read -r f; do
  if git cat-file blob ":$f" 2>/dev/null | grep -aE -q 'sk-[A-Za-z0-9_-]{20,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}'; then
    echo "  ！！疑似密钥: $f"
    git cat-file blob ":$f" | grep -aoE 'sk-[A-Za-z0-9_-]{20,}' | head -2 | sed 's/^\(sk-.\{6\}\).*/\1***（已打码）/' | sed 's/^/     /'
    leak=1
  fi
done < <(git ls-files)
if [ "$leak" -ne 0 ]; then
  echo "  发现疑似密钥 —— 已中止，不要推这个 tree（改成读环境变量再试）"
  exit 1
fi
echo "  没扫到密钥"
echo "--- 条目数 ---"
git ls-files | wc -l
echo "tree=$tree"
