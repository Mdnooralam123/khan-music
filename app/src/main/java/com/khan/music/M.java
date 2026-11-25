package com.khan.music;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class M extends Activity {
    static volatile M inst;
    WebView w;

    static void js(final String code) {
        final M m = inst;
        if (m == null || m.w == null) return;
        m.runOnUiThread(new Runnable() {
            public void run() { m.w.evaluateJavascript(code, null); }
        });
    }

    class Br {
        @JavascriptInterface
        public String getState() { S s = S.inst; return s == null ? "{}" : s.snap; }

        @JavascriptInterface
        public String getQueue() { S s = S.inst; return s == null ? "[]" : s.qsnap; }

        @JavascriptInterface
        public String getDownloads() { S s = S.inst; return s == null ? "[]" : s.dlJson(); }

        @JavascriptInterface
        public void api(final String kind, final String arg, final int cb) {
            final S s = S.inst;
            if (s != null) s.api(kind, arg, cb);
            else js("__cb(" + cb + ",false,\"service\")");
        }

        @JavascriptInterface
        public void playQueue(final String j, final int n) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.setQueue(j, n));
        }

        @JavascriptInterface
        public void playRadio(final String j) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.playRadio(j));
        }

        @JavascriptInterface
        public void playNext(final String j) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.playNext(j));
        }

        @JavascriptInterface
        public void addQueue(final String j) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.addQueue(j));
        }

        @JavascriptInterface
        public void share(final String text) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType("text/plain");
                        i.putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(i, "Share"));
                    } catch (Exception e) { }
                }
            });
        }

        @JavascriptInterface
        public void playIndex(final int n) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.playAt(n));
        }

        @JavascriptInterface
        public void toggle() {
            final S s = S.inst;
            if (s != null) s.post(() -> s.toggle());
        }

        @JavascriptInterface
        public void next() {
            final S s = S.inst;
            if (s != null) s.post(() -> s.next());
        }

        @JavascriptInterface
        public void prev() {
            final S s = S.inst;
            if (s != null) s.post(() -> s.prev());
        }

        @JavascriptInterface
        public void seek(final double sec) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.seek((long) (sec * 1000)));
        }

        @JavascriptInterface
        public void shuffle(final boolean b) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.setShuffle(b));
        }

        @JavascriptInterface
        public void repeat(final boolean b) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.setRepeat(b));
        }

        @JavascriptInterface
        public void sleep(final int min) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.sleep(min));
        }

        @JavascriptInterface
        public void refresh() {
            final S s = S.inst;
            if (s != null) s.post(() -> s.push());
        }

        @JavascriptInterface
        public void download(final String id, final String t, final String by, final String d) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.download(id, t, by, d));
        }

        @JavascriptInterface
        public void removeDl(final String id) {
            final S s = S.inst;
            if (s != null) s.post(() -> s.removeDl(id));
        }

        @JavascriptInterface
        public String getKv(String k) {
            return getSharedPreferences("khan", MODE_PRIVATE).getString("kv_" + k, "");
        }

        @JavascriptInterface
        public void setKv(String k, String v) {
            getSharedPreferences("khan", MODE_PRIVATE).edit().putString("kv_" + k, v).apply();
        }

        @JavascriptInterface
        public void openUrl(final String u) {
            if (u == null || !u.startsWith("https://")) return;
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
                    } catch (Exception e) { }
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        inst = this;
        getWindow().setStatusBarColor(0xFF0A0A0F);
        getWindow().setNavigationBarColor(0xFF0A0A0F);
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        startForegroundService(new Intent(this, S.class));
        w = new WebView(this);
        w.setBackgroundColor(0xFF0A0A0F);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        w.addJavascriptInterface(new Br(), "Android");
        w.setWebViewClient(new WebViewClient());
        w.setWebChromeClient(new WebChromeClient());
        setContentView(w);
        w.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (w == null) { moveTaskToBack(true); return; }
        w.evaluateJavascript("(window.bk&&bk())?1:0", new ValueCallback<String>() {
            public void onReceiveValue(String v) {
                if (!"1".equals(v)) moveTaskToBack(true);
            }
        });
    }

    @Override
    protected void onDestroy() {
        if (inst == this) inst = null;
        super.onDestroy();
    }
}
