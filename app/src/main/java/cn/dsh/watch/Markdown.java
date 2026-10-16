package cn.dsh.watch;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 极简 Markdown 渲染器（不引入第三方库）。
 *
 * 手表屏幕小，只需要「一眼看出结构」：标题加粗放大、列表带圆点、行内代码等宽加底色、
 * 引用变灰、代码块整体等宽、表格压成一行。不做完整排版，也不追求嵌套支持。
 *
 * 文本文件（如 .md）经工具读出后就是 Markdown 源码，直接按普通文本显示会充满
 * `**`、`#`、`|` 这类符号，因此所有正文都走这里渲染。
 */
final class Markdown {
    private Markdown() {
    }

    /**
     * 渲染一段 Markdown。
     * @param source 原始文本
     * @param baseColor 正文颜色
     * @param accentColor 强调色（行内代码、链接）
     * @param mutedColor 次要色（引用）
     * @param codeBackground 代码底色
     */
    static CharSequence render(String source, int baseColor, int accentColor, int mutedColor, int codeBackground) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null) return out;

        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean inCode = false;
        StringBuilder code = new StringBuilder();

        for (String line : lines) {
            String trimmed = line.trim();

            // 代码围栏：开合之间的内容整体等宽显示，不做行内解析。
            if (trimmed.startsWith("```")) {
                if (inCode) {
                    appendCodeBlock(out, code.toString(), baseColor, codeBackground);
                    code.setLength(0);
                    inCode = false;
                } else {
                    inCode = true;
                }
                continue;
            }
            if (inCode) {
                code.append(line).append('\n');
                continue;
            }

            if (trimmed.isEmpty()) {
                out.append('\n');
                continue;
            }

            // 分隔线
            if (isDivider(trimmed)) {
                int start = out.length();
                out.append("────────────");
                out.setSpan(new ForegroundColorSpan(mutedColor), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            // 标题：# 的数量决定层级
            int hashes = headingLevel(trimmed);
            if (hashes > 0) {
                String body = trimmed.substring(hashes).trim();
                int start = out.length();
                appendInline(out, body, baseColor, accentColor, codeBackground);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // 一级标题更醒目，其余只略放大。
                float scale = hashes == 1 ? 1.3f : hashes == 2 ? 1.18f : 1.08f;
                out.setSpan(new RelativeSizeSpan(scale), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            // 引用
            if (trimmed.startsWith(">")) {
                String body = trimmed.substring(1).trim();
                int start = out.length();
                out.append("▎ ");
                appendInline(out, body, mutedColor, accentColor, codeBackground);
                out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }

            // 表格行：压成「单元格 · 单元格」，分隔行直接丢掉
            if (trimmed.startsWith("|")) {
                if (isTableDivider(trimmed)) continue;
                out.append(joinCells(trimmed));
                out.append('\n');
                continue;
            }

            // 无序列表
            String bullet = bulletMarker(trimmed);
            if (bullet != null) {
                out.append("  · ");
                appendInline(out, trimmed.substring(bullet.length()).trim(), baseColor, accentColor, codeBackground);
                out.append('\n');
                continue;
            }

            // 有序列表：保留编号，缩进对齐
            int numberEnd = numberedMarkerEnd(trimmed);
            if (numberEnd > 0) {
                out.append("  ").append(trimmed, 0, numberEnd).append(' ');
                appendInline(out, trimmed.substring(numberEnd).trim(), baseColor, accentColor, codeBackground);
                out.append('\n');
                continue;
            }

            appendInline(out, line, baseColor, accentColor, codeBackground);
            out.append('\n');
        }

        if (inCode) appendCodeBlock(out, code.toString(), baseColor, codeBackground);
        // 去掉结尾多余空行，避免气泡底部留白过多。
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
        return out;
    }

    /** 代码块：整体等宽 + 底色，逐行加缩进。 */
    private static void appendCodeBlock(SpannableStringBuilder out, String code, int baseColor, int codeBackground) {
        if (code.isEmpty()) return;
        int start = out.length();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append("  ").append(lines[i]);
        }
        out.append('\n');
        out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new BackgroundColorSpan(codeBackground), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(baseColor), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(0.92f), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 行内解析：粗体、斜体、行内代码、删除线、链接。 */
    private static void appendInline(SpannableStringBuilder out, String text, int baseColor, int accentColor, int codeBackground) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);

            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    mark(out, start, new TypefaceSpan("monospace"), new BackgroundColorSpan(codeBackground),
                            new ForegroundColorSpan(accentColor));
                    i = end + 1;
                    continue;
                }
            }

            if ((c == '*' || c == '_') && i + 1 < n && text.charAt(i + 1) == c) {
                String marker = String.valueOf(c) + c;
                int end = text.indexOf(marker, i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(text, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            if (c == '*') {
                int end = text.indexOf('*', i + 1);
                if (end > i && end > i + 1) {
                    int start = out.length();
                    out.append(text, i + 1, end);
                    out.setSpan(new StyleSpan(Typeface.ITALIC), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }

            if (c == '~' && i + 1 < n && text.charAt(i + 1) == '~') {
                int end = text.indexOf("~~", i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(text, i + 2, end);
                    out.setSpan(new StrikethroughSpan(), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }

            // 链接：只显示文字，URL 在手表上没法点开，留着反而占位置。
            if (c == '[') {
                int labelEnd = text.indexOf(']', i + 1);
                if (labelEnd > i && labelEnd + 1 < n && text.charAt(labelEnd + 1) == '(') {
                    int urlEnd = text.indexOf(')', labelEnd + 2);
                    if (urlEnd > labelEnd) {
                        int start = out.length();
                        out.append(text, i + 1, labelEnd);
                        out.setSpan(new ForegroundColorSpan(accentColor), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = urlEnd + 1;
                        continue;
                    }
                }
            }

            out.append(c);
            i++;
        }
    }

    private static void mark(SpannableStringBuilder out, int start, Object... spans) {
        for (Object span : spans) {
            out.setSpan(span, start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 返回标题层级（1-6），不是标题时返回 0。 */
    private static int headingLevel(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == '#') count += 1;
        if (count == 0 || count > 6) return 0;
        if (count >= line.length() || line.charAt(count) != ' ') return 0;
        return count;
    }

    /** 列表项标记，非列表时返回 null。 */
    private static String bulletMarker(String line) {
        if (line.length() < 2) return null;
        char first = line.charAt(0);
        if ((first == '-' || first == '*' || first == '+') && line.charAt(1) == ' ') return line.substring(0, 1);
        return null;
    }

    /** 有序列表「1. 」这类前缀的结束位置（含点与空格），不是则返回 0。 */
    private static int numberedMarkerEnd(String line) {
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i += 1;
        if (i == 0 || i + 1 >= line.length()) return 0;
        if (line.charAt(i) != '.' || line.charAt(i + 1) != ' ') return 0;
        return i + 1;
    }

    private static boolean isDivider(String line) {
        if (line.length() < 3) return false;
        char first = line.charAt(0);
        if (first != '-' && first != '*' && first != '_') return false;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) != first) return false;
        }
        return true;
    }

    /** 表格的 `|---|---|` 分隔行。 */
    private static boolean isTableDivider(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != '|' && c != '-' && c != ':' && c != ' ') return false;
        }
        return line.indexOf('-') >= 0;
    }

    /** 把 `| a | b |` 压成 `a · b`。 */
    private static String joinCells(String line) {
        String[] cells = line.split("\\|");
        StringBuilder builder = new StringBuilder();
        for (String cell : cells) {
            String value = cell.trim();
            if (value.isEmpty()) continue;
            if (builder.length() > 0) builder.append("  ·  ");
            builder.append(value);
        }
        return builder.length() == 0 ? line : builder.toString();
    }
}
