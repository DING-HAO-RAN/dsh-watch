package cn.dsh.watch;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

/**
 * 代码与纯文本的渲染器（不引入第三方库）。
 *
 * 为什么单独一个类而不是复用 {@link Markdown}：Markdown 会把 `#`、`*`、`|`、`- `
 * 当成结构语法吃掉，而这些字符在代码里恰恰有本义（注释、乘法、位运算、表格常量）。
 * 把源码交给它渲染，等于把代码改坏。这里只做三件事：
 * 1. 保留原始行号与缩进（缩进是代码语义的一部分，不能被 trim 掉）；
 * 2. 用一套极轻的规则给注释、字符串、数字、关键字上色，让长文件可以扫读；
 * 3. 每行独立上色，互不影响——避免跨行状态机出错导致整段染色错乱。
 */
final class CodeView {
    private CodeView() {
    }

    /** 关键字表：按语言族给一份够用的集合，不做完整词法分析。 */
    private static final String[] COMMON = {
            "if", "else", "for", "while", "return", "switch", "case", "break", "continue",
            "class", "interface", "extends", "implements", "public", "private", "protected",
            "static", "final", "void", "new", "try", "catch", "finally", "throw", "throws",
            "import", "package", "this", "super", "null", "true", "false", "const", "let",
            "var", "function", "async", "await", "def", "elif", "None", "True", "False",
            "struct", "enum", "namespace", "using", "override", "virtual", "readonly",
    };

    /**
     * 渲染代码。
     *
     * @param source 原始源码
     * @param baseColor 正文色
     * @param commentColor 注释色
     * @param stringColor 字符串色
     * @param keywordColor 关键字色
     * @param numberColor 数字色
     * @param codeBackground 代码底色
     * @param startLine 起始行号（从 1 开始）
     */
    static CharSequence render(String source, int baseColor, int commentColor, int stringColor,
                              int keywordColor, int numberColor, int codeBackground, int startLine) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null) return out;

        String[] lines = source.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        // 行号宽度按总行数定，个位数与三位数的行号才能右对齐。
        int digits = String.valueOf(startLine + lines.length - 1).length();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');

            int gutterStart = out.length();
            String number = String.valueOf(startLine + i);
            for (int pad = number.length(); pad < digits; pad++) out.append(' ');
            out.append(number).append(' ');
            out.setSpan(new ForegroundColorSpan(numberColor), gutterStart, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            int lineStart = out.length();
            out.append(lines[i]);
            int lineEnd = out.length();
            paintLine(out, lineStart, lineEnd, stringColor, commentColor, keywordColor);
        }

        if (out.length() > 0) {
            out.setSpan(new TypefaceSpan("monospace"), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(baseColor), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(codeBackground), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** 纯文本：只做等宽与底色，不加任何高亮。 */
    static CharSequence plain(String source, int baseColor, int codeBackground) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (source == null) return out;
        out.append(source.replace("\r\n", "\n").replace('\r', '\n'));
        if (out.length() > 0) {
            out.setSpan(new TypefaceSpan("monospace"), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new ForegroundColorSpan(baseColor), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new BackgroundColorSpan(codeBackground), 0, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /**
     * 给一行上色。
     *
     * 只在当前行的区间里找注释与字符串，跨行状态一律不带出去：
     * 代码里少见的跨行字符串不值得为此引入状态机，而状态机一旦出错
     * 会让整屏后续行全被染成注释色。
     */
    private static void paintLine(SpannableStringBuilder out, int lineStart, int lineEnd,
                                  int stringColor, int commentColor, int keywordColor) {
        if (lineEnd <= lineStart) return;

        // 1) 行注释：找到就整行后半段染色，后面的字符串/关键字不再处理。
        int commentAt = findComment(out, lineStart, lineEnd);
        int codeEnd = commentAt >= 0 ? commentAt : lineEnd;

        // 2) 字符串（不含已被注释覆盖的部分）。
        int index = lineStart;
        while (index < codeEnd) {
            char c = out.charAt(index);
            if (c == '"' || c == '\'' || c == '`') {
                int close = index + 1;
                while (close < codeEnd) {
                    char inner = out.charAt(close);
                    if (inner == '\\') {
                        close += 2;
                        continue;
                    }
                    if (inner == c) break;
                    close += 1;
                }
                int end = Math.min(close + 1, codeEnd);
                out.setSpan(new ForegroundColorSpan(stringColor), index, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                index = end;
                continue;
            }
            index += 1;
        }

        // 3) 数字与关键字：按词切分，逐词判断。
        int wordStart = -1;
        for (int i = lineStart; i <= codeEnd; i++) {
            char c = i < codeEnd ? out.charAt(i) : ' ';
            boolean wordChar = Character.isLetterOrDigit(c) || c == '_';
            if (wordChar) {
                if (wordStart < 0) wordStart = i;
                continue;
            }
            if (wordStart >= 0) {
                String word = out.subSequence(wordStart, i).toString();
                if (isKeyword(word)) {
                    out.setSpan(new ForegroundColorSpan(keywordColor), wordStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new StyleSpan(Typeface.BOLD), wordStart, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                wordStart = -1;
            }
        }

        // 注释最后上色，确保它盖住同行里可能已经染上的字符串/关键字色。
        if (commentAt >= 0) {
            out.setSpan(new ForegroundColorSpan(commentColor), commentAt, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    /** 行内注释起点（// 或 #）；不在字符串里时才算。 */
    private static int findComment(SpannableStringBuilder out, int lineStart, int lineEnd) {
        boolean inString = false;
        char quote = 0;
        for (int i = lineStart; i < lineEnd; i++) {
            char c = out.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i += 1;
                    continue;
                }
                if (c == quote) inString = false;
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                inString = true;
                quote = c;
                continue;
            }
            if (c == '/' && i + 1 < lineEnd && out.charAt(i + 1) == '/') return i;
            if (c == '#') {
                // 只把「行首或空白后的 #」当注释，避免误伤 C 预处理之外的情况。
                if (i == lineStart || Character.isWhitespace(out.charAt(i - 1))) return i;
            }
        }
        return -1;
    }

    private static boolean isKeyword(String word) {
        for (String keyword : COMMON) {
            if (keyword.equals(word)) return true;
        }
        return false;
    }


}
