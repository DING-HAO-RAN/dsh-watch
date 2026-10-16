package cn.dsh.watch;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 圆屏适配的界面构件。
 *
 * 安全边距按圆形内接矩形推导：屏幕宽高记为 1，页面上/下各留 16% 时，
 * 距圆心 0.34 处的可用半宽为 sqrt(0.25-0.34^2)≈0.3666，即整宽 73.3%；
 * 左右各留 13.5% 恰好等于该宽度，因此首尾内容不会被圆角切掉。
 */
final class WatchUi {
    static final int BG = Color.rgb(9, 12, 18);
    static final int SURFACE = Color.rgb(23, 29, 40);
    static final int SURFACE_HI = Color.rgb(35, 44, 58);
    static final int FG = Color.rgb(238, 242, 248);
    static final int MUTED = Color.rgb(150, 165, 186);
    static final int ACCENT = Color.rgb(122, 190, 255);
    static final int ON_ACCENT = Color.rgb(9, 12, 18);
    static final int WARN = Color.rgb(250, 184, 96);
    static final int OK = Color.rgb(126, 217, 160);
    static final int DANGER = Color.rgb(248, 132, 132);

    private static final float SIDE_RATIO = 0.135f;
    private static final float VERTICAL_RATIO = 0.16f;

    final Activity activity;

    /** 一个可滚动的页面容器。 */
    static final class Page {
        final FrameLayout root;
        final ScrollView scroll;
        final LinearLayout content;

        Page(FrameLayout root, ScrollView scroll, LinearLayout content) {
            this.root = root;
            this.scroll = scroll;
            this.content = content;
        }
    }

    WatchUi(Activity activity) {
        this.activity = activity;
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /** 建立一个带安全边距、支持表冠滚动的页面。 */
    Page page() {
        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFocusable(true);
        scroll.setDescendantFocusability(ScrollView.FOCUS_AFTER_DESCENDANTS);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            int height = b - t;
            boolean round = activity.getResources().getConfiguration().isScreenRound();
            int side = round ? Math.round(width * SIDE_RATIO) : dp(10);
            int vertical = round ? Math.round(height * VERTICAL_RATIO) : dp(10);
            FrameLayout.LayoutParams current = (FrameLayout.LayoutParams) scroll.getLayoutParams();
            if (current.leftMargin != side || current.topMargin != vertical) {
                current.setMargins(side, vertical, side, vertical);
                scroll.setLayoutParams(current);
            }
        });
        scroll.setOnGenericMotionListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL
                    && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                float factor = ViewConfiguration.get(activity).getScaledVerticalScrollFactor();
                scroll.scrollBy(0, Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * factor));
                return true;
            }
            return false;
        });
        return new Page(root, scroll, content);
    }

    TextView text(CharSequence value, float size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(dp(3), 1.12f);
        return view;
    }

    /** 顶部标题区；副标题为空时不占位。 */
    void heading(Page page, String title, String subtitle) {
        TextView name = text(title, 19, FG);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(page, name, 0, 6);
        if (subtitle != null && !subtitle.isEmpty()) {
            add(page, text(subtitle, 11, MUTED), 0, 12);
        }
    }

    void add(Page page, View view, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        page.content.addView(view, params);
    }

    void space(Page page, int height) {
        View view = new View(activity);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        page.content.addView(view, params);
    }

    void divider(Page page) {
        View line = new View(activity);
        line.setBackgroundColor(Color.rgb(44, 54, 70));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, Math.max(1, dp(0.6f)));
        params.setMargins(0, dp(8), 0, dp(8));
        page.content.addView(line, params);
    }

    Button button(String label, boolean primary, Runnable action) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(primary ? ON_ACCENT : ACCENT);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(0);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setStateListAnimator(null);
        button.setBackground(surface(primary ? ACCENT : SURFACE, 16));
        button.setOnClickListener(v -> action.run());
        return button;
    }

    /** 可点击的行：标题 + 可选副标题，整行高度不低于 48dp。 */
    View row(CharSequence title, String subtitle, Runnable action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(11), dp(11), dp(11), dp(11));
        row.setMinimumHeight(dp(52));
        row.setBackground(surface(SURFACE, 16));
        TextView top = text(title, 14, FG);
        top.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(top);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 11, MUTED);
            sub.setPadding(0, dp(5), 0, 0);
            sub.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(sub);
        }
        row.setContentDescription(title + (subtitle == null || subtitle.isEmpty() ? "" : "，" + subtitle));
        row.setFocusable(true);
        row.setClickable(true);
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 小圆角标签，用于「运行中」「待回答」等状态。 */
    TextView chip(String label, int color) {
        TextView chip = text(label, 10, color);
        chip.setPadding(dp(7), dp(3), dp(7), dp(3));
        chip.setBackground(surface(SURFACE_HI, 9));
        return chip;
    }

    /** 对话气泡：用户靠右用强调色，助手靠左用卡片色。 */
    View bubble(String body, boolean fromUser) {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        TextView view = text(body, 13, fromUser ? ON_ACCENT : FG);
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(surface(fromUser ? ACCENT : SURFACE, 14));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.gravity = fromUser ? Gravity.END : Gravity.START;
        wrapper.addView(view, params);
        return wrapper;
    }

    /** 纯展示卡片。 */
    LinearLayout card() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(11), dp(11), dp(11), dp(11));
        card.setBackground(surface(SURFACE, 16));
        return card;
    }

    android.graphics.drawable.Drawable surface(int color, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(46, 200, 224, 255)), shape, null);
    }
}
