package cn.dsh.phone;

import android.graphics.Color;

/**
 * 配色方案：与手表端同一套语义色值，保证两端看起来是同一个产品。
 *
 * 两套主题必须逐项对应同一组语义角色（底色、卡片、正文、弱化文字、强调色、状态色）。
 * 浅色不是把深色数值取反，而是重新给每个角色一个合适的明度——否则会出现
 * 「卡片比底色还深」这类反色错误。强调色两套保持同一个蓝，浅色下压深一档。
 */
final class Theme {
    static final String DARK = "dark";
    static final String LIGHT = "light";

    final String name;
    final int bg;
    final int surface;
    final int surfaceHi;
    final int fg;
    final int muted;
    final int accent;
    final int onAccent;
    final int warn;
    final int ok;
    final int danger;
    final int line;
    final int cardTop;
    final int accentTop;
    final int stroke;
    final int ripple;

    private Theme(String name, int bg, int surface, int surfaceHi, int fg, int muted,
                  int accent, int onAccent, int warn, int ok, int danger, int line,
                  int cardTop, int accentTop, int stroke, int ripple) {
        this.name = name;
        this.bg = bg;
        this.surface = surface;
        this.surfaceHi = surfaceHi;
        this.fg = fg;
        this.muted = muted;
        this.accent = accent;
        this.onAccent = onAccent;
        this.warn = warn;
        this.ok = ok;
        this.danger = danger;
        this.line = line;
        this.cardTop = cardTop;
        this.accentTop = accentTop;
        this.stroke = stroke;
        this.ripple = ripple;
    }

    static Theme dark() {
        return new Theme(DARK,
                Color.rgb(9, 12, 18),
                Color.rgb(23, 29, 40),
                Color.rgb(35, 44, 58),
                Color.rgb(238, 242, 248),
                Color.rgb(150, 165, 186),
                Color.rgb(122, 190, 255),
                Color.rgb(9, 12, 18),
                Color.rgb(250, 184, 96),
                Color.rgb(126, 217, 160),
                Color.rgb(248, 132, 132),
                Color.rgb(36, 45, 60),
                Color.rgb(27, 34, 47),
                Color.rgb(150, 207, 255),
                Color.argb(40, 152, 180, 216),
                Color.argb(46, 206, 228, 255));
    }

    static Theme light() {
        return new Theme(LIGHT,
                Color.rgb(246, 248, 251),
                Color.rgb(255, 255, 255),
                Color.rgb(236, 241, 247),
                Color.rgb(24, 31, 42),
                Color.rgb(104, 118, 136),
                Color.rgb(28, 110, 196),
                Color.rgb(255, 255, 255),
                Color.rgb(176, 112, 16),
                Color.rgb(22, 132, 82),
                Color.rgb(196, 48, 48),
                Color.rgb(219, 226, 235),
                Color.rgb(255, 255, 255),
                Color.rgb(90, 160, 232),
                Color.argb(30, 90, 120, 160),
                Color.argb(40, 28, 110, 196));
    }

    static Theme byName(String value) {
        return LIGHT.equals(value) ? light() : dark();
    }
}
