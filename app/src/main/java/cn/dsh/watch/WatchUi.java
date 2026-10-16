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
        private Runnable onSwipeLeft;
        private Runnable onSwipeRight;

        Page(FrameLayout root, ScrollView scroll, LinearLayout content) {
            this.root = root;
            this.scroll = scroll;
            this.content = content;
        }

        /** 左滑（手指向左）时触发：按约定用于打开操作菜单。 */
        void setOnSwipeLeft(Runnable action) {
            this.onSwipeLeft = action;
        }

        /** 右滑（手指向右）时触发：按约定用于返回上一级。 */
        void setOnSwipeRight(Runnable action) {
            this.onSwipeRight = action;
        }

        void fireSwipeLeft() {
            if (onSwipeLeft != null) onSwipeLeft.run();
        }

        void fireSwipeRight() {
            if (onSwipeRight != null) onSwipeRight.run();
        }

        /** 滚动到底部（进入会话时用来直接看到最新内容）。 */
        void scrollToBottom() {
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    WatchUi(Activity activity) {
        this.activity = activity;
    }

    int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    /**
     * 建立一个带安全边距、支持表冠滚动的页面。
     *
     * 页面同时识别左右滑动：由调用方通过 {@link Page#setOnSwipe} 注册回调，
     * 这样各页面可以自行决定「右滑返回、左滑开菜单」这类行为。
     */
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
        Page page = new Page(root, scroll, content);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            int height = b - t;
            boolean round = activity.getResources().getConfiguration().isScreenRound();
            // 圆屏侧边留出内接矩形之外的部分；上下留白略大于左右，
            // 因为顶部状态栏与底部圆角都会压到内容。
            int side = round ? Math.round(width * SIDE_RATIO) : dp(8);
            int vertical = round ? Math.round(height * VERTICAL_RATIO) : dp(8);
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
        // 横向手势不在这里拦：挂在页面的 FrameLayout 上时，触摸会先被子层
        // ScrollView 消费掉，左滑会被当成纵向滚动。改由 MainActivity 的
        // dispatchTouchEvent 统一判定（配合 page.setGestureTarget）。
        return page;
    }

    /**
     * 横向滑动识别器。
     *
     * 由 MainActivity 在 dispatchTouchEvent 里驱动：只有那里能拿到「还没被子视图
     * 消费」的原始事件，挂在页面根容器上会被 ScrollView 抢先吃掉纵向与横向手势。
     *
     * 只认明显偏水平的滑动（横向位移 ≥60dp 且大于纵向的 1.5 倍），
     * 否则手表上最常用的上下翻页会被误判成菜单手势。
     */
    static final class HorizontalGesture {
        private static final int TRIGGER_DP = 60;
        private static final int SLOP_DP = 24;

        private final float triggerPx;
        private final float slopPx;
        private float startX;
        private float startY;
        private boolean triggered;

        HorizontalGesture(float density) {
            this.triggerPx = TRIGGER_DP * density;
            this.slopPx = SLOP_DP * density;
        }

        /**
         * 喂入一个触摸事件。
         * @param page 当前页面（提供左右滑回调）
         * @return true 表示本次手势已被横向滑动消费
         */
        boolean onTouch(WatchUi.Page page, MotionEvent event) {
            if (page == null) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = event.getX();
                    startY = event.getY();
                    triggered = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    if (triggered) return true;
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    // 纵向一旦明显超过阈值就认定是滚动，本次不再判定横滑。
                    if (Math.abs(dy) > slopPx && Math.abs(dy) > Math.abs(dx)) return false;
                    if (Math.abs(dx) >= triggerPx && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        triggered = true;
                        if (dx > 0) page.fireSwipeRight();
                        else page.fireSwipeLeft();
                        return true;
                    }
                    return false;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (triggered) {
                        triggered = false;
                        return true;
                    }
                    // 快速轻扫可能移动步数少，抬手时补一次判定。
                    float dx = event.getX() - startX;
                    float dy = event.getY() - startY;
                    if (Math.abs(dx) >= triggerPx && Math.abs(dx) > Math.abs(dy) * 1.5f) {
                        if (dx > 0) page.fireSwipeRight();
                        else page.fireSwipeLeft();
                        return true;
                    }
                    return false;
                }
                default:
                    return false;
            }
        }
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
        TextView head = headingView(title);
        // 顶部留一点空间，避免第一行贴着圆角。
        add(page, head, 2, 4);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 10, MUTED);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            add(page, sub, 0, 8);
        }
    }

    /**
     * 标题视图。
     *
     * 会话标题常常很长（如「制作手表端DSH客户端应用」）。圆屏上一屏可见高度只有
     * 三百多像素，标题一旦折成两三行，按钮和内容区就被挤出屏幕、整页也滚不动。
     * 因此强制单行 + 省略号；调用方负责先把文本截短。
     */
    TextView headingView(String title) {
        TextView view = text(title, 16, FG);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setSingleLine(true);
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
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
        return bubbleRich(body, fromUser);
    }

    /**
     * 富文本气泡。
     *
     * 正文可能带 Markdown 样式（粗体、等宽、底色），因此用 Spannable 而不是纯字符串。
     * 长文本要能正常折行，所以宽度用 WRAP_CONTENT 加上限，不能固定宽度。
     */
    View bubbleRich(CharSequence body, boolean fromUser) {
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
