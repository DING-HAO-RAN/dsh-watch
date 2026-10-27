package cn.dsh.phone;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.TextView;

/**
 * 轻量 Markdown 渲染：把模型输出的正文变成带样式的文字。
 *
 * 只支持实际会遇到的那部分语法（标题、粗体、斜体、行内代码、代码块、列表、
 * 引用、链接、分隔线、表格压行）。刻意不引入第三方库：手机端产物要求零依赖，
 * 而完整 Markdown 解析器会带进来几万行用不到的规则。
 *
 * **代码块不交给本类处理**——`#`、`*`、`|`、`-` 在源码里有本义，按 Markdown 渲染
 * 等于改坏代码。代码块由 {@link CodeView} 单独渲染，这里只把它的位置留出来。
 */
final class Markdown {
    private Markdown() { }

    /** 渲染成一段带样式的文字并塞进 TextView。 */
    static void render(TextView view, String source) {
        if (source == null) source = "";
        view.setText(build(source));
        view.setLineSpacing(Ui.dp(4f), 1.05f);
    }

    static CharSequence build(String source) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        String[] lines = source.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        for (int i = 0; i < lines.length; i += 1) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                // 代码围栏在正文里只留一条提示，真正的代码由 CodeView 渲染。
                inCode = !inCode;
                continue;
            }
            if (inCode) {
                // 理论上不会走到这里（调用方会把代码块拆出去），保底按原文追加。
                appendStyled(out, line, false);
                out.append('\n');
                continue;
            }
            if (trimmed.length() == 0) {
                out.append('\n');
                continue;
            }
            if (trimmed.startsWith("---") || trimmed.startsWith("***")) {
                appendLine(out, "──────────", 0.8f, Ui.LINE);
                continue;
            }
            // 标题：整行放大加粗，层级越深越小。
            if (trimmed.startsWith("#")) {
                int level = 0;
                while (level < trimmed.length() && trimmed.charAt(level) == '#') level += 1;
                String content = trimmed.substring(Math.min(level, trimmed.length())).trim();
                float scale = level <= 1 ? 1.35f : (level == 2 ? 1.2f : 1.1f);
                int start = out.length();
                out.append(content);
                out.setSpan(new RelativeSizeSpan(scale), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new ForegroundColorSpan(Ui.FG), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.append('\n');
                continue;
            }
            // 引用：整行变灰，前置一条竖线。
            if (trimmed.startsWith(">")) {
                String content = trimmed.substring(1).trim();
                appendLine(out, "│ " + content, 1f, Ui.MUTED);
                continue;
            }
            // 列表：统一换成圆点，缩进保留一层。
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent += 1;
                String content = trimmed.substring(2);
                appendStyled(out, spaces(indent / 2) + "· " + content, false);
                out.append('\n');
                continue;
            }
            // 有序列表：保留原编号。
            if (isOrderedItem(trimmed)) {
                appendStyled(out, trimmed, false);
                out.append('\n');
                continue;
            }
            // 表格：压成一行，单元格之间用 · 分隔（手机上横向表格读不了）。
            if (trimmed.startsWith("|")) {
                String flattened = trimmed.replaceAll("^\\|", "").replaceAll("\\|$", "").replace("|", "  ·  ").trim();
                if (flattened.replace("-", "").replace(":", "").replace(" ", "").length() == 0) continue;
                appendLine(out, flattened, 0.92f, Ui.MUTED);
                continue;
            }
            appendStyled(out, line, false);
            out.append('\n');
        }
        // 去掉尾部多余空行。
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private static boolean isOrderedItem(String line) {
        int index = 0;
        while (index < line.length() && Character.isDigit(line.charAt(index))) index += 1;
        return index > 0 && index < line.length() && (line.charAt(index) == '.' || line.charAt(index) == ')')
                && index + 1 < line.length() && line.charAt(index + 1) == ' ';
    }

    private static String spaces(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i += 1) builder.append("  ");
        return builder.toString();
    }

    private static void appendLine(SpannableStringBuilder out, String text, float scale, int color) {
        int start = out.length();
        out.append(text);
        if (scale != 1f) out.setSpan(new RelativeSizeSpan(scale), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append('\n');
    }

    /**
     * 处理行内语法：`代码`、**粗体**、*斜体*、[文字](链接)。
     *
     * 逐字符扫描而不是正则替换：正则处理嵌套（比如粗体里套行内代码）会互相破坏，
     * 而正文里这类嵌套很常见。
     */
    private static void appendStyled(SpannableStringBuilder out, String line, boolean bold) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '`') {
                int end = line.indexOf('`', i + 1);
                if (end > i) {
                    int start = out.length();
                    out.append(line, i + 1, end);
                    out.setSpan(new TypefaceSpan("monospace"), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(Ui.SURFACE_HI), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new ForegroundColorSpan(Ui.ACCENT), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '*' && i + 1 < line.length() && line.charAt(i + 1) == '*') {
                int end = line.indexOf("**", i + 2);
                if (end > i) {
                    int start = out.length();
                    out.append(line, i + 2, end);
                    out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (c == '[') {
                int close = line.indexOf(']', i + 1);
                if (close > i && close + 1 < line.length() && line.charAt(close + 1) == '(') {
                    int end = line.indexOf(')', close + 2);
                    if (end > close) {
                        // 只显示链接文字，URL 放在括号里用弱化色跟在后面。
                        String label = line.substring(i + 1, close);
                        String url = line.substring(close + 2, end);
                        out.append(label);
                        if (!url.startsWith(label)) {
                            int start = out.length();
                            out.append(" (").append(shortenUrl(url)).append(")");
                            out.setSpan(new ForegroundColorSpan(Ui.MUTED), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i += 1;
        }
    }

    private static String shortenUrl(String url) {
        String value = url.replace("https://", "").replace("http://", "");
        if (value.length() <= 48) return value;
        return value.substring(0, 45) + "…";
    }
}
