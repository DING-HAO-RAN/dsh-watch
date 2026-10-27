package cn.dsh.phone;

import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.TypefaceSpan;
import android.widget.HorizontalScrollView;
import android.view.View;
import android.widget.TextView;

/**
 * 代码视图：等宽字体、带行号、横向滚动、每行独立着色。
 *
 * 与 {@link Markdown} 分开的两个理由（都是踩过的坑）：
 * - Markdown 会把 `#`、`*`、`|`、`- ` 当结构语法吃掉，而这些字符在源码里有本义；
 * - **不做跨行状态机**：块注释/多行字符串的状态一旦算错，后面整屏都会被染成注释色，
 *   而逐行独立判断最坏也只是某一行的颜色不对。
 *
 * 必须横向滚动：自动折行会把缩进层级全部打乱，代码就没法看了。
 */
final class CodeView {
    /** 关键字表按语言分组；未列出的语言按纯文本显示。 */
    private static final String[] COMMON = {
            "if", "else", "for", "while", "return", "break", "continue", "class", "interface",
            "new", "try", "catch", "finally", "throw", "import", "package", "public", "private",
            "protected", "static", "final", "void", "int", "long", "double", "float", "boolean",
            "char", "byte", "short", "null", "true", "false", "this", "super", "extends",
            "implements", "switch", "case", "default", "do", "instanceof", "synchronized",
    };

    private CodeView() { }

    /**
     * 生成一个可横向滚动的代码块。
     * @param text 代码原文
     * @param language 语言标识（来自中继的 languageOf）
     */
    static View build(android.content.Context context, String text, String language) {
        // 外层竖向交给页面，内层横向负责长行；两层嵌套是代码视图的标准做法。
        HorizontalScrollView horizontal = new HorizontalScrollView(context);
        horizontal.setHorizontalScrollBarEnabled(true);
        horizontal.setFillViewport(false);

        TextView view = new TextView(context);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        view.setTextColor(Ui.FG);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextIsSelectable(true);
        view.setHorizontallyScrolling(true);
        view.setPadding(Ui.dp(12f), Ui.dp(10f), Ui.dp(12f), Ui.dp(10f));
        view.setBackground(Ui.round(Ui.BG, 10f, Ui.STROKE));
        view.setText(highlight(text, language));

        horizontal.addView(view, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.WRAP_CONTENT));
        return horizontal;
    }

    /** 逐行着色并加行号。 */
    static CharSequence highlight(String text, String language) {
        if (text == null) text = "";
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        SpannableStringBuilder out = new SpannableStringBuilder();
        int width = String.valueOf(lines.length).length();
        for (int index = 0; index < lines.length; index += 1) {
            String line = lines[index];
            // 行号：右对齐、弱化色，和正文之间留两格。
            String number = pad(String.valueOf(index + 1), width);
            int numberStart = out.length();
            out.append(number).append("  ");
            out.setSpan(new ForegroundColorSpan(Ui.MUTED), numberStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            colorLine(out, line, language);
            if (index < lines.length - 1) out.append('\n');
        }
        return out;
    }

    /**
     * 给一行上色。顺序很重要：先识别注释（整行剩余都算注释），
     * 再处理字符串，最后才是关键字与数字——否则关键字会盖掉字符串里的内容。
     */
    private static void colorLine(SpannableStringBuilder out, String line, String language) {
        String trimmed = line.trim();
        int base = out.length();

        // 整行注释。
        if (isLineComment(trimmed, language)) {
            int start = out.length();
            out.append(line);
            out.setSpan(new ForegroundColorSpan(Ui.MUTED), start, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            return;
        }

        out.append(line);
        int end = out.length();

        // 字符串：单引号与双引号，按行内配对，不做跨行。
        int i = base;
        while (i < end) {
            char c = out.charAt(i);
            if (c == '"' || c == '\'') {
                int close = i + 1;
                boolean closed = false;
                while (close < end) {
                    if (out.charAt(close) == '\\') {
                        close += 2;
                        continue;
                    }
                    if (out.charAt(close) == c) {
                        closed = true;
                        break;
                    }
                    close += 1;
                }
                if (closed) {
                    out.setSpan(new ForegroundColorSpan(Ui.OK), i, close + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = close + 1;
                    continue;
                }
            }
            i += 1;
        }

        // 关键字与数字：按词边界匹配，避免把变量名里的片段染掉。
        int cursor = base;
        while (cursor < end) {
            char c = out.charAt(cursor);
            if (Character.isLetter(c) || c == '_') {
                int stop = cursor;
                while (stop < end && (Character.isLetterOrDigit(out.charAt(stop)) || out.charAt(stop) == '_')) stop += 1;
                String word = out.subSequence(cursor, stop).toString();
                if (isKeyword(word, language)) {
                    out.setSpan(new ForegroundColorSpan(Ui.ACCENT), cursor, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                cursor = stop;
                continue;
            }
            if (Character.isDigit(c)) {
                int stop = cursor;
                while (stop < end && (Character.isLetterOrDigit(out.charAt(stop)) || out.charAt(stop) == '.')) stop += 1;
                out.setSpan(new ForegroundColorSpan(Ui.WARN), cursor, stop, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                cursor = stop;
                continue;
            }
            cursor += 1;
        }
    }

    private static boolean isLineComment(String trimmed, String language) {
        if (trimmed.length() == 0) return false;
        if ("python".equals(language) || "yaml".equals(language) || "toml".equals(language)
                || "ini".equals(language) || "shell".equals(language) || "powershell".equals(language)) {
            return trimmed.startsWith("#");
        }
        if ("batch".equals(language)) return trimmed.toLowerCase().startsWith("rem ") || trimmed.startsWith("::");
        if ("sql".equals(language)) return trimmed.startsWith("--");
        return trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*");
    }

    private static boolean isKeyword(String word, String language) {
        for (String keyword : COMMON) {
            if (keyword.equals(word)) return true;
        }
        // 少数语言特有的关键字，避免把 COMMON 撑得太大。
        if ("python".equals(language)) {
            return "def".equals(word) || "elif".equals(word) || "None".equals(word) || "True".equals(word)
                    || "False".equals(word) || "lambda".equals(word) || "with".equals(word) || "as".equals(word)
                    || "pass".equals(word) || "raise".equals(word) || "yield".equals(word) || "from".equals(word);
        }
        if ("javascript".equals(language) || "typescript".equals(language)) {
            return "function".equals(word) || "const".equals(word) || "let".equals(word) || "var".equals(word)
                    || "async".equals(word) || "await".equals(word) || "export".equals(word) || "typeof".equals(word)
                    || "undefined".equals(word) || "arrow".equals(word) || "of".equals(word) || "in".equals(word);
        }
        if ("go".equals(word) && "go".equals(language)) return true;
        if ("rust".equals(language)) {
            return "fn".equals(word) || "let".equals(word) || "mut".equals(word) || "impl".equals(word)
                    || "pub".equals(word) || "use".equals(word) || "match".equals(word) || "struct".equals(word);
        }
        if ("kotlin".equals(language)) {
            return "fun".equals(word) || "val".equals(word) || "var".equals(word) || "when".equals(word);
        }
        return false;
    }

    private static String pad(String value, int width) {
        StringBuilder builder = new StringBuilder();
        for (int i = value.length(); i < width; i += 1) builder.append(' ');
        return builder.append(value).toString();
    }

    /** 单行代码（工具调用的命令、路径等）：不着色，只保证等宽。 */
    static void mono(TextView view, String text) {
        view.setText(text == null ? "" : text);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f);
        view.setTextColor(Ui.FG);
    }

    static int transparent() {
        return Color.TRANSPARENT;
    }
}
