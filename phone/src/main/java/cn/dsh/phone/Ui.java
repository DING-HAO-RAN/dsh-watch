package cn.dsh.phone;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 手机端界面构件。
 *
 * 与手表端的 {@code WatchUi} 是同一套设计语言（深色底、浅一档的卡片、蓝色强调、
 * 低对比描边），但布局规则完全不同：手表按圆屏内接矩形留边，手机是矩形屏，
 * 只留常规 dp 边距 + 状态栏/导航栏避让。
 *
 * 配色做成**可变静态字段**（与手表端同样的理由）：全项目有大量 `Ui.FG` 这类引用，
 * 切主题时一次性换掉整组值、重绘整屏即可，调用点一行都不用改。
 */
final class Ui {
    static int BG = Theme.dark().bg;
    static int SURFACE = Theme.dark().surface;
    static int SURFACE_HI = Theme.dark().surfaceHi;
    static int FG = Theme.dark().fg;
    static int MUTED = Theme.dark().muted;
    static int ACCENT = Theme.dark().accent;
    static int ON_ACCENT = Theme.dark().onAccent;
    static int WARN = Theme.dark().warn;
    static int OK = Theme.dark().ok;
    static int DANGER = Theme.dark().danger;
    static int LINE = Theme.dark().line;
    static int CARD_TOP = Theme.dark().cardTop;
    static int ACCENT_TOP = Theme.dark().accentTop;
    static int STROKE = Theme.dark().stroke;
    static int RIPPLE = Theme.dark().ripple;

    static float density = 1f;

    private Ui() { }

    /** 应用一套主题（只换静态色值，界面重建后生效）。 */
    static void apply(Theme theme) {
        BG = theme.bg;
        SURFACE = theme.surface;
        SURFACE_HI = theme.surfaceHi;
        FG = theme.fg;
        MUTED = theme.muted;
        ACCENT = theme.accent;
        ON_ACCENT = theme.onAccent;
        WARN = theme.warn;
        OK = theme.ok;
        DANGER = theme.danger;
        LINE = theme.line;
        CARD_TOP = theme.cardTop;
        ACCENT_TOP = theme.accentTop;
        STROKE = theme.stroke;
        RIPPLE = theme.ripple;
    }

    static int dp(float value) {
        return Math.round(value * density);
    }

    static float sp() {
        return density;
    }

    /** 圆角矩形填充，可选描边。 */
    static GradientDrawable round(int color, float radiusDp, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) drawable.setStroke(dp(1f), strokeColor);
        return drawable;
    }

    /** 带按压反馈的可点背景。 */
    static RippleDrawable pressable(int color, float radiusDp, int strokeColor) {
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), round(color, radiusDp, strokeColor), null);
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static TextView text(Context context, String value, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(value == null ? "" : value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(3f), 1f);
        return view;
    }

    static TextView title(Context context, String value, float sizeSp) {
        TextView view = text(context, value, sizeSp, FG);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setSingleLine(false);
        return view;
    }

    static TextView muted(Context context, String value, float sizeSp) {
        return text(context, value, sizeSp, MUTED);
    }

    /** 单行省略的弱化文字，用于副标题与元信息。 */
    static TextView ellipsis(Context context, String value, float sizeSp, int color) {
        TextView view = text(context, value, sizeSp, color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    /** 圆角标签（状态、模型名、工具名等）。 */
    static TextView tag(Context context, String value, int textColor, int fillColor) {
        TextView view = text(context, value, 11f, textColor);
        view.setSingleLine(true);
        view.setPadding(dp(8f), dp(3f), dp(8f), dp(3f));
        view.setBackground(round(fillColor, 6f, 0));
        return view;
    }

    /** 主按钮：整行宽、48dp 高、圆角渐变。 */
    static TextView button(Context context, String label, boolean primary) {
        TextView view = text(context, label, 15f, primary ? ON_ACCENT : FG);
        view.setGravity(Gravity.CENTER);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        view.setMinHeight(dp(48f));
        view.setPadding(dp(16f), dp(12f), dp(16f), dp(12f));
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{primary ? ACCENT_TOP : SURFACE_HI, primary ? ACCENT : SURFACE_HI});
        background.setCornerRadius(dp(12f));
        if (!primary) background.setStroke(dp(1f), STROKE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), background, null));
        view.setClickable(true);
        return view;
    }

    /** 次要按钮：描边、透明底。 */
    static TextView ghostButton(Context context, String label) {
        TextView view = text(context, label, 14f, FG);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setMinHeight(dp(44f));
        view.setPadding(dp(14f), dp(10f), dp(14f), dp(10f));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.TRANSPARENT);
        background.setCornerRadius(dp(10f));
        background.setStroke(dp(1f), STROKE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(RIPPLE), background, null));
        view.setClickable(true);
        return view;
    }

    /** 卡片容器：圆角、浅一档底色、细描边。 */
    static LinearLayout card(Context context) {
        LinearLayout layout = column(context);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{CARD_TOP, SURFACE});
        background.setCornerRadius(dp(16f));
        background.setStroke(dp(1f), STROKE);
        layout.setBackground(background);
        layout.setPadding(dp(14f), dp(12f), dp(14f), dp(12f));
        return layout;
    }

    /** 列表行：左侧内容 + 右侧箭头，整行可点，高度不低于 64dp。 */
    static LinearLayout listRow(Context context) {
        LinearLayout layout = row(context);
        layout.setPadding(dp(14f), dp(12f), dp(14f), dp(12f));
        layout.setMinimumHeight(dp(64f));
        layout.setBackground(pressable(SURFACE, 14f, STROKE));
        layout.setClickable(true);
        return layout;
    }

    static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(LINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.6f)));
        view.setLayoutParams(params);
        return view;
    }

    static View gap(Context context, float heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return view;
    }

    static LinearLayout.LayoutParams wrapParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams matchParams() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 可滚动容器：内容多时纵向滚动，滚动位置由调用方保存。 */
    static ScrollView scroll(Context context) {
        ScrollView view = new ScrollView(context);
        view.setFillViewport(true);
        view.setClipToPadding(false);
        return view;
    }

    /**
     * 输入框：多行、圆角、深底。
     * 用 EditText 而不是自绘，是因为手机输入法（中文、候选词、粘贴）必须靠系统控件。
     */
    static EditText input(Context context, String hint) {
        EditText view = new EditText(context);
        view.setHint(hint == null ? "" : hint);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        view.setTextColor(FG);
        view.setHintTextColor(MUTED);
        view.setBackground(round(SURFACE_HI, 12f, STROKE));
        view.setPadding(dp(12f), dp(10f), dp(12f), dp(10f));
        view.setMinLines(1);
        view.setMaxLines(6);
        view.setGravity(Gravity.TOP | Gravity.START);
        return view;
    }

    /** 空状态：居中的图标位置 + 一句说明。 */
    static LinearLayout empty(Context context, String message) {
        LinearLayout layout = column(context);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(24f), dp(48f), dp(24f), dp(48f));
        TextView view = text(context, message, 14f, MUTED);
        view.setGravity(Gravity.CENTER);
        layout.addView(view);
        return layout;
    }

    /** 一个图标用的小方块（用文字符号代替图片资源，避免额外 drawable）。 */
    static TextView glyph(Context context, String symbol, int color) {
        TextView view = text(context, symbol, 20f, color);
        view.setGravity(Gravity.CENTER);
        view.setWidth(dp(28f));
        return view;
    }

    /** 状态点：运行中绿色、等待审批橙色、出错红色。 */
    static View dot(Context context, int color) {
        View view = new View(context);
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        view.setBackground(drawable);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(8f), dp(8f));
        params.rightMargin = dp(6f);
        view.setLayoutParams(params);
        return view;
    }

    /** 横向可滚动的标签条（用于筛选、模型选择）。 */
    static HorizontalScrollView hscroll(Context context) {
        HorizontalScrollView view = new HorizontalScrollView(context);
        view.setHorizontalScrollBarEnabled(false);
        view.setClipToPadding(false);
        return view;
    }

    /**
     * 安全区处理：把状态栏与导航栏的高度变成根容器的内边距。
     *
     * 这里直接量系统栏高度，而不是等 `setOnApplyWindowInsetsListener` 回调：
     * 实测在 `Theme.DeviceDefault.NoActionBar` + 手写根布局的组合下，insets 监听
     * 不会被触发（内容仍然顶到状态栏、底部标签被导航栏压住）。量高度不依赖任何
     * 分发时机，是最稳的做法。
     */
    static void applyWindowInsets(Activity activity, View root) {
        root.setBackgroundColor(BG);
        if (activity.getWindow() != null) {
            activity.getWindow().setStatusBarColor(BG);
            activity.getWindow().setNavigationBarColor(BG);
        }
        root.setPadding(0, systemBarHeight(activity, "status_bar_height"),
                0, systemBarHeight(activity, "navigation_bar_height"));
    }

    private static int systemBarHeight(Activity activity, String name) {
        int id = activity.getResources().getIdentifier(name, "dimen", "android");
        if (id <= 0) return 0;
        return activity.getResources().getDimensionPixelSize(id);
    }

    static void setMargins(View view, float left, float top, float right, float bottom) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
            margins.setMargins(dp(left), dp(top), dp(right), dp(bottom));
            view.setLayoutParams(margins);
        }
    }

    static void weight(View view, float value) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).weight = value;
            params.width = 0;
            view.setLayoutParams(params);
        }
    }

    /** 让一个 ImageView 显示圆角（头像、缩略图）。 */
    static void clipRound(ImageView view, float radiusDp) {
        view.setClipToOutline(true);
        view.setBackground(round(SURFACE_HI, radiusDp, 0));
    }

    static FrameLayout frame(Context context) {
        return new FrameLayout(context);
    }
}
