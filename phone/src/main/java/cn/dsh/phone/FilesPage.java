package cn.dsh.phone;

import android.graphics.BitmapFactory;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件页：浏览当前会话工作目录里的文件并查看内容。
 *
 * 安全边界：中继只允许读会话工作目录内的路径（`withinRoot`），因此这里不需要
 * 再做一次白名单——手机上也不可能拼出能绕过的路径。
 *
 * 页面状态只有「当前会话 + 当前目录」两项，因此用最简单的重建整屏即可，
 * 不需要保留滚动位置（每次进目录都从顶部开始才是符合直觉的）。
 */
final class FilesPage {
    private final MainActivity host;
    private ScrollView scroll;
    private LinearLayout body;

    private String sessionId = "";
    private String sessionTitle = "";
    private String currentPath = "";
    private String root = "";
    private List<Api.FileEntry> entries = new ArrayList<>();
    private boolean visible;
    private boolean loading;

    FilesPage(MainActivity host) {
        this.host = host;
    }

    View build() {
        scroll = Ui.scroll(host);
        scroll.setBackgroundColor(Ui.BG);
        body = Ui.column(host);
        body.setPadding(Ui.dp(16f), Ui.dp(16f), Ui.dp(16f), Ui.dp(24f));

        LinearLayout header = Ui.row(host);
        header.addView(Ui.title(host, "文件", 26f), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pick = Ui.ghostButton(host, "选会话");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        header.addView(pick);
        body.addView(header);

        TextView hint = Ui.muted(host, "浏览会话工作目录里的文件。", 12f);
        hint.setPadding(0, Ui.dp(6f), 0, Ui.dp(12f));
        body.addView(hint);

        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        visible = true;
        sessionId = host.activeSessionId;
        sessionTitle = host.activeSessionTitle;
        if (sessionId.length() == 0) {
            sessionId = host.store.lastSessionId();
            sessionTitle = host.store.lastSessionTitle();
        }
        if (sessionId.length() == 0) {
            renderPrompt();
        } else {
            loadDirectory("");
        }
        return scroll;
    }

    void stop() {
        visible = false;
    }

    private void renderPrompt() {
        body.removeAllViews();
        LinearLayout header = Ui.row(host);
        header.addView(Ui.title(host, "文件", 26f));
        body.addView(header);
        body.addView(Ui.empty(host, "先选一个会话，再浏览它的工作目录"));
        TextView pick = Ui.button(host, "选择会话", false);
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        body.addView(pick);
    }

    /** 会话选择器：列出最近有工作目录的会话。 */
    private void pickSession() {
        Api.sessions(host.store, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast(error);
                    return;
                }
                final List<Api.Session> sessions = new ArrayList<>();
                for (Api.Session session : Api.parseSessions(value)) {
                    if (!session.subagent && session.projectPath.length() > 0) sessions.add(session);
                }
                if (sessions.isEmpty()) {
                    host.toast("没有可用的会话");
                    return;
                }
                String[] labels = new String[sessions.size()];
                for (int i = 0; i < sessions.size(); i += 1) {
                    Api.Session session = sessions.get(i);
                    labels[i] = session.title + "\n" + session.project;
                }
                new android.app.AlertDialog.Builder(host)
                        .setTitle("选择会话")
                        .setItems(labels, new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface dialog, int which) {
                                Api.Session session = sessions.get(which);
                                sessionId = session.id;
                                sessionTitle = session.title;
                                currentPath = "";
                                loadDirectory("");
                            }
                        })
                        .show();
            }
        });
    }

    private void loadDirectory(final String path) {
        if (loading) return;
        loading = true;
        Api.files(host.store, sessionId, path, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                loading = false;
                if (!visible) return;
                if (error != null) {
                    host.toast(error);
                    return;
                }
                currentPath = Api.text(value, "path");
                root = Api.text(value, "root");
                entries = Api.parseFiles(value);
                renderDirectory();
            }
        });
    }

    private void renderDirectory() {
        body.removeAllViews();

        LinearLayout header = Ui.row(host);
        header.addView(Ui.title(host, "文件", 26f), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView upload = Ui.ghostButton(host, "上传");
        upload.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                uploadFile();
            }
        });
        header.addView(upload);
        TextView pick = Ui.ghostButton(host, "换会话");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                pickSession();
            }
        });
        header.addView(pick);
        body.addView(header);

        LinearLayout context = Ui.card(host);
        TextView title = Ui.ellipsis(host, sessionTitle.length() == 0 ? "当前会话" : sessionTitle, 13f, Ui.FG);
        context.addView(title);
        TextView path = Ui.ellipsis(host, currentPath.length() == 0 ? root : currentPath, 11f, Ui.MUTED);
        path.setPadding(0, Ui.dp(3f), 0, 0);
        context.addView(path);
        LinearLayout.LayoutParams contextParams = Ui.matchParams();
        contextParams.topMargin = Ui.dp(10f);
        contextParams.bottomMargin = Ui.dp(12f);
        body.addView(context, contextParams);

        if (currentPath.length() > 0) {
            LinearLayout up = Ui.listRow(host);
            up.addView(Ui.text(host, "↑", 16f, Ui.ACCENT));
            TextView label = Ui.text(host, "返回上一级", 14f, Ui.FG);
            label.setPadding(Ui.dp(10f), 0, 0, 0);
            up.addView(label);
            up.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    int cut = currentPath.lastIndexOf('/');
                    loadDirectory(cut > 0 ? currentPath.substring(0, cut) : "");
                }
            });
            body.addView(up);
            body.addView(Ui.gap(host, 6f));
        }

        if (entries.isEmpty()) {
            body.addView(Ui.empty(host, "这个目录是空的"));
            return;
        }
        for (Api.FileEntry entry : entries) {
            body.addView(fileRow(entry));
            body.addView(Ui.gap(host, 6f));
        }
    }

    private View fileRow(final Api.FileEntry entry) {
        LinearLayout row = Ui.listRow(host);
        TextView glyph = Ui.text(host, entry.isDir() ? "▸" : "·", 15f, entry.isDir() ? Ui.ACCENT : Ui.MUTED);
        glyph.setWidth(Ui.dp(20f));
        row.addView(glyph);
        LinearLayout text = Ui.column(host);
        text.addView(Ui.ellipsis(host, entry.name, 14f, Ui.FG));
        String meta = entry.isDir() ? "目录" : Api.size(entry.size);
        if (entry.language.length() > 0 && !"text".equals(entry.language)) meta += " · " + entry.language;
        TextView sub = Ui.muted(host, meta, 11f);
        sub.setPadding(0, Ui.dp(2f), 0, 0);
        text.addView(sub);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textParams.leftMargin = Ui.dp(10f);
        row.addView(text, textParams);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                if (entry.isDir()) {
                    loadDirectory(currentPath.length() == 0 ? entry.name : currentPath + "/" + entry.name);
                } else {
                    openFile(currentPath.length() == 0 ? entry.name : currentPath + "/" + entry.name);
                }
            }
        });
        return row;
    }

    /** 选一个本地文件上传到当前会话的工作目录。 */
    private void uploadFile() {
        if (sessionId.length() == 0) {
            host.toast("先选一个会话");
            return;
        }
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        try {
            host.startActivityForResult(android.content.Intent.createChooser(intent, "选择要上传的文件"),
                    MainActivity.REQUEST_UPLOAD_FILE);
        } catch (Exception error) {
            host.toast("打不开文件选择器：" + error.getMessage());
        }
    }

    /** 由 MainActivity 在选好文件后回调。 */
    void onFilePicked(android.net.Uri uri) {
        if (uri == null) return;
        String name = queryDisplayName(uri);
        if (name.length() == 0) name = "上传文件";
        final String fileName = name;
        host.toast("正在读取 " + fileName + "…");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    byte[] bytes = readAll(uri);
                    if (bytes == null) {
                        host.toast("读不到这个文件");
                        return;
                    }
                    if (bytes.length > 32 * 1024 * 1024) {
                        host.toast("文件超过 32MB 上限");
                        return;
                    }
                    final String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    host.runOnUiThread(new Runnable() {
                        @Override public void run() {
                            doUpload(fileName, base64);
                        }
                    });
                } catch (Exception error) {
                    host.toast("读取失败：" + error.getMessage());
                }
            }
        }).start();
    }

    private void doUpload(String name, String base64) {
        org.json.JSONObject body = new org.json.JSONObject();
        try {
            body.put("id", sessionId);
            body.put("name", name);
            body.put("base64", base64);
        } catch (Exception ignored) { }
        Api.upload(host.store, body, new Api.Callback() {
            @Override public void onResult(org.json.JSONObject value, String error) {
                if (error != null) {
                    host.toast("上传失败：" + error);
                    return;
                }
                host.toast("已上传：" + (value == null ? name : Api.text(value, "name")));
                loadDirectory(currentPath);
            }
        });
    }

    private String queryDisplayName(android.net.Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = host.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (Exception ignored) {
            // 取不到名字时退回默认名。
        } finally {
            if (cursor != null) cursor.close();
        }
        return "";
    }

    private byte[] readAll(android.net.Uri uri) {
        java.io.InputStream stream = null;
        try {
            stream = host.getContentResolver().openInputStream(uri);
            if (stream == null) return null;
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) buffer.write(chunk, 0, read);
            return buffer.toByteArray();
        } catch (Exception error) {
            return null;
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Exception ignored) { }
            }
        }
    }

    private void openFile(final String path) {
        Api.file(host.store, sessionId, path, new Api.Callback() {
            @Override public void onResult(JSONObject value, String error) {
                if (error != null) {
                    host.toast(error);
                    return;
                }
                showFile(value);
            }
        });
    }

    /** 文件内容页：文本走 CodeView，图片直接解码显示。 */
    private void showFile(JSONObject value) {
        String name = Api.text(value, "name");
        String kind = Api.text(value, "kind");
        String language = Api.text(value, "language");

        LinearLayout page = Ui.column(host);
        page.setBackgroundColor(Ui.BG);
        page.setPadding(Ui.dp(12f), Ui.dp(12f), Ui.dp(12f), Ui.dp(16f));

        LinearLayout bar = Ui.row(host);
        TextView back = Ui.text(host, "‹ 返回", 14f, Ui.ACCENT);
        back.setPadding(0, Ui.dp(8f), Ui.dp(12f), Ui.dp(8f));
        back.setClickable(true);
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                renderDirectory();
            }
        });
        bar.addView(back);
        bar.addView(Ui.ellipsis(host, name, 14f, Ui.FG), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        page.addView(bar);

        String meta = Api.size(value.optLong("size", 0));
        if (value.optBoolean("truncated", false)) meta += " · 只显示前 512KB";
        TextView metaView = Ui.muted(host, meta, 11f);
        metaView.setPadding(0, Ui.dp(4f), 0, Ui.dp(8f));
        page.addView(metaView);

        ScrollView scrollView = Ui.scroll(host);
        if ("image".equals(kind)) {
            byte[] bytes = Base64.decode(Api.text(value, "base64"), Base64.DEFAULT);
            ImageView image = new ImageView(host);
            image.setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.length));
            image.setAdjustViewBounds(true);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            scrollView.addView(image, new ScrollView.LayoutParams(
                    ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        } else if ("binary".equals(kind)) {
            page.addView(Ui.empty(host, "这是二进制文件，手机端不预览"));
            host.setContent(page);
            return;
        } else {
            String text = Api.text(value, "text");
            // Markdown 用渲染器、其余用代码视图：两者不能混用（见 CodeView 的注释）。
            if ("markdown".equals(language)) {
                LinearLayout card = Ui.card(host);
                TextView content = Ui.text(host, "", 14f, Ui.FG);
                Markdown.render(content, text);
                card.addView(content);
                scrollView.addView(card, new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            } else {
                scrollView.addView(CodeView.build(host, text, language), new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            }
        }
        page.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        host.setContent(page);
    }
}
