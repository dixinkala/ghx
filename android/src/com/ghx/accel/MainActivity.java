package com.ghx.accel;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.KeyEvent;
import android.provider.Settings;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

public class MainActivity extends Activity {

    WebView webView;
    private static final int VPN_REQUEST = 1001;
    private boolean vpnPending = false;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        DnsConfig.load(this);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.addJavascriptInterface(new Bridge(this), "Ghx");
        webView.setBackgroundColor(0xFF0D1117);
        setContentView(webView);
        // 直接读 assets 注入，避免 targetSdk 30+ 的 file:// 限制
        try (java.io.InputStream in = getAssets().open("index.html")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            webView.loadDataWithBaseURL("https://ghx.local/", bo.toString("UTF-8"),
                    "text/html", "UTF-8", null);
        } catch (java.io.IOException e) {
            webView.loadData("<h1>界面资源缺失</h1>", "text/html", "UTF-8");
        }
    }

    /** 请求 VPN 授权后开关 DNS 加速。 */
    void toggleVpn(boolean on) {
        if (!on) {
            startService(new Intent(this, DnsVpn.class).setAction(DnsVpn.ACTION_STOP));
            return;
        }
        // 私人 DNS 指定主机名时，系统会直接走 DoT，绕过本应用的解析
        try {
            String mode = Settings.Global.getString(getContentResolver(), "private_dns_mode");
            if ("hostname".equals(mode)) {
                notice("手机开着「私人 DNS（指定主机名）」，会绕过本应用。"
                        + "请到 设置 → 网络和互联网 → 私人 DNS 改成「自动」后再开。");
                return;
            }
        } catch (Exception ignored) { }
        Intent prepare = VpnService.prepare(this);
        if (prepare == null) {
            startService(new Intent(this, DnsVpn.class).setAction(DnsVpn.ACTION_START));
            selfCheck();
        } else {
            vpnPending = true;
            startActivityForResult(prepare, VPN_REQUEST);
        }
    }

    /** 开启后过几秒用系统解析验证一次，失败就自动关掉，避免断网。 */
    private void selfCheck() {
        new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                boolean ok = false;
                for (String host : new String[]{"example.com", "github.com", "www.baidu.com"}) {
                    try {
                        if (java.net.InetAddress.getByName(host) != null) { ok = true; break; }
                    } catch (Exception ignored) { }
                }
                if (!ok && DnsVpn.isRunning()) {
                    startService(new Intent(MainActivity.this, DnsVpn.class)
                            .setAction(DnsVpn.ACTION_STOP));
                    notice("DNS 自检未通过，已自动关闭加速，系统 DNS 已恢复。");
                } else if (ok) {
                    notice("DNS 加速已生效。");
                }
            }
        }, "ghx-selfcheck").start();
    }

    /** 把提示回传给网页界面。 */
    void notice(String msg) {
        WebView w = webView;
        if (w == null) return;
        final String js = "window.__ghxNotice&&window.__ghxNotice(" + JSONObject.quote(msg) + ");";
        runOnUiThread(new Runnable() {
            public void run() {
                if (webView != null) webView.evaluateJavascript(js, null);
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VPN_REQUEST) {
            if (res == RESULT_OK) {
                startService(new Intent(this, DnsVpn.class).setAction(DnsVpn.ACTION_START));
                selfCheck();
            }
            vpnPending = false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.evaluateJavascript("window.__ghxRefresh&&window.__ghxRefresh();", null);
        }
    }
}
