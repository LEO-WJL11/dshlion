#!/bin/bash
# 把当前 dshlion 临时索引提交成 commit 并推到 dshlion main
#
# 【坑】提交信息从 origin/master 最后一条提交里**自动取**，不要再依赖某个手写的临时文件。
# 之前用的是 /c/.../\_commit_1114.txt，各版本收尾脚本各自往里写；1.2.7 那次忘了重新生成，
# 于是公开仓库的提交信息写成了 1.2.6 —— 内容是对的、信息是错的，最容易误导人。
set -euo pipefail
cd /c/Users/Leo/Desktop/lion-code
export GIT_INDEX_FILE=/tmp/dshlion_idx

ver=$(sed -n 's/^#define AppVersion[[:space:]]*"\([^"]*\)".*/\1/p' installer/LionBox.iss | head -1)
tree=$(git write-tree)
echo "版本=$ver"
echo "tree=$tree"

msgfile=$(mktemp)
{
  echo "release: $ver（同步到公开仓库）"
  echo
  echo "本地 master 上这条提交的原文："
  echo
  git log -1 --pretty=%B master
} > "$msgfile"

commit=$(git commit-tree "$tree" -p dshlion/main -F "$msgfile")
rm -f "$msgfile"
echo "commit=$commit"
git push dshlion "$commit:main" 2>&1 | tail -2
