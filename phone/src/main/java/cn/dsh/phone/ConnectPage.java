package cn.dsh.phone;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 连接与配对页。
 *
 * 两条入口：局域网自动发现（推荐）与手动填地址。配对码来自电脑端界面，
 * 一次配对后令牌长期有效，不需要重复输入。
 *
 * 为什么把发现结果放在最上面：同一局域网时用户根本不需要知道 IP，
 * 让他先看到「DSH 中继」并点一下，比让他去电脑上抄地址可靠得多。
 */
final class ConnectPage {
    private final MainActivity host;
    private final List<String> discovered = new ArrayList<>();
    private LinearLayout deviceList;
    private EditText addressInput;
    private EditText codeInput;
    private TextView status;
    private boolean busy;

    ConnectPage(MainActivity host) {
        this.host = host;
    }

    View build() {
        ScrollView scroll = Ui.scroll(host);
        scroll.setBackgroundColor(Ui.BG);
        LinearLayout root = Ui.column(host);
        root.setPadding(Ui.dp(18f), Ui.dp(24f), Ui.dp(18f), Ui.dp(24f));

        TextView title = Ui.title(host, "连接电脑", 24f);
        root.addView(title);
        TextView subtitle = Ui.muted(host, "手机与电脑要在同一局域网，电脑上打开 DSH 中继。", 13f);
        subtitle.setPadding(0, Ui.dp(6f), 0, Ui.dp(18f));
        root.addView(subtitle);

        // 自动发现区
        TextView found = Ui.title(host, "发现的中继", 15f);
        root.addView(found);
        deviceList = Ui.column(host);
        deviceList.setPadding(0, Ui.dp(8f), 0, 0);
        root.addView(deviceList);
        TextView searching = Ui.muted(host, "正在搜索…（中继每 2 秒广播一次）", 12f);
        searching.setPadding(0, Ui.dp(6f), 0, Ui.dp(20f));
        root.addView(searching);

        // 手动输入区
        TextView manual = Ui.title(host, "手动填写", 15f);
        root.addView(manual);
        addressInput = Ui.input(host, "地址，例如 192.168.1.5:19390");
        addressInput.setSingleLine(true);
        addressInput.setPadding(Ui.dp(12f), Ui.dp(12f), Ui.dp(12f), Ui.dp(12f));
        LinearLayout.LayoutParams addressParams = Ui.matchParams();
        addressParams.topMargin = Ui.dp(8f);
        root.addView(addressInput, addressParams);

        codeInput = Ui.input(host, "配对码，6 位数字");
        codeInput.setSingleLine(true);
        codeInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        codeInput.setPadding(Ui.dp(12f), Ui.dp(12f), Ui.dp(12f), Ui.dp(12f));
        LinearLayout.LayoutParams codeParams = Ui.matchParams();
        codeParams.topMargin = Ui.dp(8f);
        root.addView(codeInput, codeParams);

        TextView connect = Ui.button(host, "配对并连接", true);
        LinearLayout.LayoutParams connectParams = Ui.matchParams();
        connectParams.topMargin = Ui.dp(14f);
        connect.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pair();
            }
        });
        root.addView(connect, connectParams);

        status = Ui.text(host, "", 13f, Ui.WARN);
        status.setPadding(0, Ui.dp(12f), 0, 0);
        root.addView(status);

        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        startDiscovery();
        return scroll;
    }

    private void startDiscovery() {
        host.discovery.start(new Discovery.Listener() {
            @Override public void onFound(String base, String name) {
                host.post(new Runnable() {
                    @Override public void run() {
                        addDevice(base, name);
                    }
                });
            }
        });
    }

    /** 发现一台中继：去重后插到列表最上面，点一下就把地址填好。 */
    private void addDevice(final String base, String name) {
        if (discovered.contains(base)) return;
        discovered.add(base);
        LinearLayout row = Ui.listRow(host);
        LinearLayout text = Ui.column(host);
        text.addView(Ui.title(host, name, 15f));
        TextView address = Ui.ellipsis(host, base.replace("http://", ""), 12f, Ui.MUTED);
        address.setPadding(0, Ui.dp(2f), 0, 0);
        text.addView(address);
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = Ui.tag(host, "使用", Ui.ON_ACCENT, Ui.ACCENT);
        row.addView(pick);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                addressInput.setText(base.replace("http://", "").replace("https://", ""));
                addressInput.setSelection(addressInput.getText().length());
                codeInput.requestFocus();
                setStatus("已填入地址，请输入电脑上显示的配对码");
            }
        });
        deviceList.addView(row);
        Ui.setMargins(row, 0, 0, 0, 8);
    }

    private void pair() {
        if (busy) return;
        String address = addressInput.getText().toString().trim();
        String code = codeInput.getText().toString().trim();
        if (address.length() == 0) {
            setStatus("请先填地址，或点上面发现的中继");
            return;
        }
        if (code.length() == 0) {
            setStatus("请输入电脑上显示的 6 位配对码");
            return;
        }
        busy = true;
        setStatus("正在配对…");
        String base = address.startsWith("http") ? address : "http://" + address;
        Api.pair(base, code, "", new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                busy = false;
                if (error != null) {
                    setStatus("配对失败：" + error);
                    return;
                }
                String token = Api.text(value, "token");
                if (token.length() == 0) {
                    setStatus("配对失败：服务端没有返回令牌");
                    return;
                }
                // 配对响应带局域网地址与公网地址：两条都存，家里与外面都能连。
                String lanBase = Api.text(value, "lanBase");
                String publicUrl = Api.text(value, "publicUrl");
                String primary = lanBase.length() > 0 ? lanBase : base;
                host.store.remember(primary, publicUrl, token, "");
                host.discovery.stop();
                host.toast("配对成功");
                host.onPaired();
            }
        });
    }

    private void setStatus(String message) {
        if (status != null) status.setText(message == null ? "" : message);
    }
}
