package cn.dsh.phone;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * 我的页：设备管理、连接状态、主题、关于。
 *
 * 这一页是「出问题时唯一能自救的地方」，所以把三样东西放在最上面：
 * 当前连的是哪台电脑、通不通、以及换一台/重新配对的入口。
 */
final class MinePage {
    private final MainActivity host;
    private ScrollView scroll;
    private LinearLayout body;
    private boolean visible;
    /** 复用同一个界面实例，切标签时不重建。 */
    private View cachedView;

    MinePage(MainActivity host) {
        this.host = host;
    }

    View build() {
        // 复用已有界面：这一页没有内部导航状态，重建只会让状态卡先闪一下空白。
        if (cachedView != null) {
            visible = true;
            render();
            return cachedView;
        }
        scroll = Ui.scroll(host);
        scroll.setBackgroundColor(Ui.BG);
        body = Ui.column(host);
        body.setPadding(Ui.dp(16f), Ui.dp(16f), Ui.dp(16f), Ui.dp(24f));
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        visible = true;
        render();
        cachedView = scroll;
        return scroll;
    }

    void stop() {
        visible = false;
    }

    private void render() {
        body.removeAllViews();
        body.addView(Ui.title(host, "我的", 26f));

        // 当前设备状态
        LinearLayout statusCard = Ui.card(host);
        Store.Device device = host.store.current();
        if (device == null) {
            statusCard.addView(Ui.title(host, "尚未配对", 15f));
            statusCard.addView(Ui.muted(host, "去「连接电脑」输入配对码", 12f));
        } else {
            LinearLayout head = Ui.row(host);
            head.addView(Ui.dot(host, Ui.MUTED));
            head.addView(Ui.title(host, device.label(), 15f));
            statusCard.addView(head);
            TextView base = Ui.ellipsis(host, device.base, 11.5f, Ui.MUTED);
            base.setPadding(0, Ui.dp(4f), 0, 0);
            statusCard.addView(base);
            if (device.alternateBase.length() > 0) {
                TextView alt = Ui.ellipsis(host, "备用：" + device.alternateBase, 11f, Ui.MUTED);
                alt.setPadding(0, Ui.dp(2f), 0, 0);
                statusCard.addView(alt);
            }
            final TextView probe = Ui.text(host, "正在检查连接…", 11.5f, Ui.MUTED);
            probe.setPadding(0, Ui.dp(8f), 0, 0);
            statusCard.addView(probe);
            checkStatus(probe);
        }
        LinearLayout.LayoutParams statusParams = Ui.matchParams();
        statusParams.topMargin = Ui.dp(12f);
        statusParams.bottomMargin = Ui.dp(12f);
        body.addView(statusCard, statusParams);

        // 设备列表
        body.addView(sectionTitle("已配对的电脑"));
        for (int i = 0; i < host.store.devices().size(); i += 1) {
            body.addView(deviceRow(i));
            body.addView(Ui.gap(host, 6f));
        }
        TextView addDevice = Ui.ghostButton(host, "连接另一台电脑");
        LinearLayout.LayoutParams addParams = Ui.matchParams();
        addParams.topMargin = Ui.dp(6f);
        addParams.bottomMargin = Ui.dp(16f);
        addDevice.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                host.showConnect();
            }
        });
        body.addView(addDevice, addParams);

        // 远程连接：经 ds-harness-remote 插件，电脑不在同一网络时用
        body.addView(sectionTitle("远程连接"));
        LinearLayout remoteCard = Ui.card(host);
        if (host.store.hasRemoteAccount()) {
            remoteCard.addView(Ui.title(host, "已登录远程账号", 15f));
            remoteCard.addView(Ui.muted(host, "点这里选择要连接的远程主机", 12f));
            remoteCard.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    host.showRemoteHosts();
                }
            });
        } else {
            remoteCard.addView(Ui.title(host, "登录远程账号", 15f));
            remoteCard.addView(Ui.muted(host, "不在同一网络时，经账号服务器连电脑上的 DSH", 12f));
            remoteCard.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    host.showRemoteLogin();
                }
            });
        }
        LinearLayout.LayoutParams remoteParams = Ui.matchParams();
        remoteParams.bottomMargin = Ui.dp(16f);
        body.addView(remoteCard, remoteParams);

        // 外观
        body.addView(sectionTitle("外观"));
        LinearLayout themeRow = Ui.listRow(host);
        themeRow.addView(Ui.text(host, Theme.LIGHT.equals(host.store.themeName()) ? "浅色主题" : "深色主题", 14f, Ui.FG),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        themeRow.addView(Ui.text(host, "切换", 12f, Ui.ACCENT));
        themeRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                boolean light = Theme.LIGHT.equals(host.store.themeName());
                host.store.setThemeName(light ? Theme.DARK : Theme.LIGHT);
                host.applyThemeAndRebuild();
            }
        });
        body.addView(themeRow);
        body.addView(Ui.gap(host, 16f));

        // 关于
        body.addView(sectionTitle("关于"));
        LinearLayout about = Ui.card(host);
        about.addView(Ui.title(host, "DSH 手机端", 15f));
        TextView version = Ui.muted(host, "版本 1.0.0 · 与手表端同一个中继", 11.5f);
        version.setPadding(0, Ui.dp(4f), 0, Ui.dp(8f));
        about.addView(version);
        about.addView(Ui.muted(host, "这个应用不直接连 DSH，而是通过电脑上的「DSH 中继」转发。"
                + "中继负责鉴权与裁剪，所以手机只需要配对一次。", 12f));
        body.addView(about);
    }

    private View sectionTitle(String text) {
        TextView view = Ui.title(host, text, 13f);
        view.setTextColor(Ui.MUTED);
        view.setPadding(0, 0, 0, Ui.dp(8f));
        return view;
    }

    /** 探一次 /api/status，把结果写进那行小字。 */
    private void checkStatus(final TextView probe) {
        Api.status(host.store.base(), new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (!visible) return;
                if (error != null) {
                    probe.setText("连不上：" + error);
                    probe.setTextColor(Ui.DANGER);
                    return;
                }
                String name = Api.text(value, "name");
                int version = value.optInt("version", 0);
                probe.setText("已连接 · " + (name.length() == 0 ? "DSH 中继" : name) + " v" + version);
                probe.setTextColor(Ui.OK);
            }
        });
    }

    private View deviceRow(final int index) {
        final Store.Device device = host.store.devices().get(index);
        boolean current = index == host.store.currentIndex();
        LinearLayout row = Ui.listRow(host);
        LinearLayout text = Ui.column(host);
        LinearLayout titleRow = Ui.row(host);
        if (current) titleRow.addView(Ui.dot(host, Ui.ACCENT));
        titleRow.addView(Ui.ellipsis(host, device.label(), 14f, Ui.FG));
        text.addView(titleRow);
        TextView base = Ui.ellipsis(host, device.base, 11f, Ui.MUTED);
        base.setPadding(0, Ui.dp(2f), 0, 0);
        text.addView(base);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(Ui.text(host, "⋯", 18f, Ui.MUTED));
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                deviceMenu(index, device);
            }
        });
        return row;
    }

    private void deviceMenu(final int index, final Store.Device device) {
        final String[] items = {"切换到这台电脑", "重命名", "删除"};
        new android.app.AlertDialog.Builder(host)
                .setTitle(device.label())
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            host.store.switchTo(index);
                            host.toast("已切换");
                            render();
                        } else if (which == 1) {
                            rename(index, device);
                        } else {
                            host.store.forget(index);
                            if (!host.store.hasDevice()) {
                                host.showConnect();
                                return;
                            }
                            render();
                        }
                    }
                })
                .show();
    }

    private void rename(final int index, final Store.Device device) {
        final EditText input = Ui.input(host, "给这台电脑起个名字");
        input.setText(device.note);
        input.setSingleLine(true);
        new android.app.AlertDialog.Builder(host)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        host.store.rename(index, input.getText().toString().trim());
                        render();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
