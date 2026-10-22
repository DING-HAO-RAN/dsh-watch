package cn.dsh.watch;

import android.graphics.Color;

/**
 * 配色方案：深色与浅色两套。
 *
 * 两套必须逐项对应同一组语义色（底色、卡片、正文、弱化文字、强调色、状态色），
 * 否则切换到浅色时会出现「卡片比底色还深」这种反色错误——浅色主题不是把深色的
 * 数值取反，而是重新给每个语义角色一个合适的明度。
 *
 * 强调色两套都保持同一个蓝，只是浅色下压深一档：主题切换不该让用户重新认一遍品牌色。
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
    final int backdropTop;
    final int stroke;
    final int ripple;

    private Theme(String name, int bg, int surface, int surfaceHi, int fg, int muted,
                  int accent, int onAccent, int warn, int ok, int danger, int line,
                  int cardTop, int accentTop, int backdropTop, int stroke, int ripple) {
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
        this.backdropTop = backdropTop;
        this.stroke = stroke;
        this.ripple = ripple;
    }

    /** 深色：与既有界面完全一致。 */
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
                Color.rgb(13, 18, 27),
                Color.argb(40, 152, 180, 216),
                Color.argb(46, 206, 228, 255));
    }

    /** 浅色：白底、浅灰卡片、深色正文；状态色也压深以保证在浅底上可读。 */
    static Theme light() {
        return new Theme(LIGHT,
                Color.rgb(246, 247, 250),
                Color.rgb(255, 255, 255),
                Color.rgb(235, 238, 244),
                Color.rgb(24, 30, 40),
                Color.rgb(104, 117, 136),
                Color.rgb(20, 104, 200),
                Color.rgb(255, 255, 255),
                Color.rgb(176, 106, 16),
                Color.rgb(20, 138, 78),
                Color.rgb(198, 46, 58),
                Color.rgb(222, 227, 235),
                Color.rgb(255, 255, 255),
                Color.rgb(66, 148, 236),
                Color.rgb(252, 253, 255),
                Color.argb(38, 70, 100, 140),
                Color.argb(38, 40, 90, 160));
    }

    /** 按名字取主题；未知名字返回 null（调用方据此保持原样）。 */
    static Theme named(String name) {
        if (LIGHT.equals(name)) return light();
        if (DARK.equals(name)) return dark();
        return null;
    }

    boolean isLight() {
        return LIGHT.equals(name);
    }
}
