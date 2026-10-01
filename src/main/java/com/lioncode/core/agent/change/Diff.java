package com.lioncode.core.agent.change;

import java.util.ArrayList;
import java.util.List;

/**
 * 给人看的行级差异。
 *
 * <p>【为什么自己写而不引库】要的就是"人扫一眼能看出改了哪几行"，不值得为此拖进一个 diff 库；
 * 而且这里必须对**超大文件**有防护：几十万行的文件做 LCS 会直接把内存吃光，
 * 所以超过阈值就退化成"整文件替换"的摘要，并明确写出来（不装作是逐行 diff）。</p>
 *
 * <p>输出用常见的前缀：{@code +} 新增、{@code -} 删除、{@code  } 不变（只在不远处展示几行上下文）。</p>
 */
final class Diff {

    /** 超过多少行就放弃逐行比对（LCS 是 O(n*m)，这里 4000×4000 已经是上限） */
    private static final int MAX_LINES_FOR_LCS = 4000;
    /** 差异块前后各留几行上下文 */
    private static final int CONTEXT = 2;
    /** 最多输出多少行（界面不需要看完整 diff，超了截断并注明） */
    private static final int MAX_OUTPUT_LINES = 400;

    private Diff() {}

    static String render(String path, String oldContent, String newContent) {
        StringBuilder sb = new StringBuilder();
        if (oldContent == null) {
            sb.append("新建文件：").append(path).append('\n');
            appendAll(sb, "+", newContent);
            return sb.toString();
        }
        if (newContent == null) {
            sb.append("删除文件：").append(path).append('\n');
            appendAll(sb, "-", oldContent);
            return sb.toString();
        }
        if (oldContent.equals(newContent)) {
            return "内容没有变化（" + path + "）";
        }

        String[] a = oldContent.split("\n", -1);
        String[] b = newContent.split("\n", -1);
        if (a.length > MAX_LINES_FOR_LCS || b.length > MAX_LINES_FOR_LCS) {
            sb.append("整文件替换：").append(path).append('\n');
            sb.append("  （文件太大，不做逐行比对：原 ").append(a.length)
              .append(" 行 → 新 ").append(b.length).append(" 行）\n");
            appendAll(sb, "+", newContent);
            return sb.toString();
        }

        List<int[]> ops = lcsOps(a, b);      // 每项 {type, i, j}：0=保持 1=删 2=增
        List<Integer> changed = new ArrayList<>();
        for (int k = 0; k < ops.size(); k++) {
            if (ops.get(k)[0] != 0) {
                changed.add(k);
            }
        }
        if (changed.isEmpty()) {
            return "内容没有变化（" + path + "）";
        }

        sb.append(path).append("：").append(changed.size()).append(" 处行变化\n");
        int printed = 0;
        int lastPrinted = -99;
        for (int idx : changed) {
            if (printed >= MAX_OUTPUT_LINES) {
                sb.append("…… 还有更多差异，已截断（总共 ").append(changed.size()).append(" 行变化）\n");
                break;
            }
            int from = Math.max(0, idx - CONTEXT);
            if (from > lastPrinted + 1) {
                sb.append("  ...\n");
            }
            for (int k = Math.max(from, lastPrinted + 1); k <= Math.min(ops.size() - 1, idx + CONTEXT); k++) {
                int[] op = ops.get(k);
                if (op[0] == 0) {
                    sb.append("   ").append(a[op[1]]).append('\n');
                } else if (op[0] == 1) {
                    sb.append("  -").append(a[op[1]]).append('\n');
                } else {
                    sb.append("  +").append(b[op[2]]).append('\n');
                }
                printed++;
            }
            lastPrinted = Math.min(ops.size() - 1, idx + CONTEXT);
        }
        return sb.toString();
    }

    private static void appendAll(StringBuilder sb, String prefix, String content) {
        if (content == null) {
            return;
        }
        String[] lines = content.split("\n", -1);
        int n = Math.min(lines.length, MAX_OUTPUT_LINES);
        for (int i = 0; i < n; i++) {
            sb.append(" ").append(prefix).append(lines[i]).append('\n');
        }
        if (lines.length > n) {
            sb.append("  …… 还有 ").append(lines.length - n).append(" 行（已截断）\n");
        }
    }

    /** 标准 LCS + 回溯，返回操作序列 */
    private static List<int[]> lcsOps(String[] a, String[] b) {
        int n = a.length;
        int m = b.length;
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                    ? dp[i + 1][j + 1] + 1
                    : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<int[]> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a[i].equals(b[j])) {
                ops.add(new int[]{0, i++, j++});
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new int[]{1, i++, -1});
            } else {
                ops.add(new int[]{2, -1, j++});
            }
        }
        while (i < n) {
            ops.add(new int[]{1, i++, -1});
        }
        while (j < m) {
            ops.add(new int[]{2, -1, j++});
        }
        return ops;
    }
}
