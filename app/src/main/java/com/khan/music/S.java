package com.khan.music;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

public class S extends Service {
    static volatile S inst;
    static final String VERSION = "3.3";
    static final String BASE = "http://127.0.0.1:5000";
    static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0";
    static final String UA_ANDROID = "com.google.android.youtube/19.44.38 (Linux; U; Android 14) gzip";
    static final String UA_IOS = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)";
    static final String UA_VR = "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    static final long TTL = 3000000L;
    static boolean npReady = false;

    static class Res {
        String url = "";
        String ua = UA_WEB;
        long t = 0;
        JSONArray related = new JSONArray();
    }

    final Handler h = new Handler(Looper.getMainLooper());
    final ExecutorService io = Executors.newCachedThreadPool();
    final ExecutorService dlx = Executors.newSingleThreadExecutor();
    final Random rnd = new Random();
    final ArrayList<JSONObject> q = new ArrayList<JSONObject>();
    final ConcurrentHashMap<String, Integer> dlp = new ConcurrentHashMap<String, Integer>();
    final ConcurrentHashMap<String, Res> cache = new ConcurrentHashMap<String, Res>();

    MediaSession ms;
    ExoPlayer player;
    PowerManager.WakeLock wl;
    NotificationManager nm;
    Bitmap art, logo;
    boolean radio;
    int pre = -1, planned = -1;
    float speed = 1f;
    final LinkedList<String> recent = new LinkedList<String>();
    JSONArray prevRel = new JSONArray(), curRel = new JSONArray();
    String artId = "";
    String ac = "";
    String vid = "", title = "Khan Music", artist = "Search karke gaana lagao", err = "";
    boolean started, playing, loading, prepared, shuffle, repeat, moreBusy;
    int idx = -1, gen = 0, qv = 0, fails = 0, dlErrN = 0, lv = 0;
    long pos = 0, dur = 0, sleepAt = 0;
    volatile String snap = "{}", qsnap = "[]", dlErr = "";
    volatile int dn = 0;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("m", "Music", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("s", "Search", NotificationManager.IMPORTANCE_LOW));
        ms = new MediaSession(this, "KhanMusic");
        ms.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { resume(); }
            @Override public void onPause() { pause(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
            @Override public void onSeekTo(long p) { seek(p); }
            @Override public void onCustomAction(String action, Bundle extras) {
                if ("LIKE".equals(action)) toggleLike();
                else if ("SHUF".equals(action)) { setShuffle(!shuffle); push(); }
            }
        });
        ms.setActive(true);
        h.post(tick);
        io.execute(() -> { try { initNP(); } catch (Throwable t) { } });
    }

    final Runnable tick = new Runnable() {
        public void run() {
            try {
                if (player != null && prepared) {
                    pos = player.getCurrentPosition();
                    long d = player.getDuration();
                    if (d > 0) dur = d;
                }
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; pause(); }
                snapshot();
            } catch (Exception e) { }
            h.postDelayed(this, 500);
        }
    };

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
        if (!started) {
            started = true;
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "khan:music");
            wl.acquire();
            push();
            nm.notify(2, buildSearch(null));
        }
        if (i != null && i.getAction() != null) {
            String a = i.getAction();
            if (a.equals("PREV")) prev();
            else if (a.equals("TOG")) toggle();
            else if (a.equals("NEXT")) next();
            else if (a.equals("SRCH")) {
                Bundle r = RemoteInput.getResultsFromIntent(i);
                CharSequence cs = r == null ? null : r.getCharSequence("q");
                if (cs != null && cs.toString().trim().length() > 0) searchPlay(cs.toString().trim());
                else nm.notify(2, buildSearch(null));
            }
        }
        return START_STICKY;
    }

    void post(Runnable r) { h.post(r); }

    String pref(String k, String d) { return getSharedPreferences("khan", MODE_PRIVATE).getString("p_" + k, d); }

    void setPref(String k, String v) {
        getSharedPreferences("khan", MODE_PRIVATE).edit().putString("p_" + k, v).apply();
        if (k.equals("q")) cache.clear();
    }

    boolean autoOn() { return !"off".equals(pref("auto", "on")); }

    void noteRecent(String id) {
        if (id == null || id.isEmpty()) return;
        recent.remove(id);
        recent.addFirst(id);
        while (recent.size() > 60) recent.removeLast();
    }

    static String tkey(String t) {
        String s = t == null ? "" : t.toLowerCase();
        s = s.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        String[] w = s.split("[^\\p{L}\\p{N}]+");
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String x : w) {
            if (x.isEmpty()) continue;
            if (n++ > 0) b.append(' ');
            b.append(x);
            if (n >= 3) break;
        }
        return b.toString();
    }

    // ---------------- YouTube (NewPipe, no Termux needed) ----------------

    static class Dl extends Downloader {
        @Override
        public Response execute(Request request) throws IOException, ReCaptchaException {
            String url = request.url();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestMethod(request.httpMethod());
            c.setRequestProperty("User-Agent", UA_WEB);
            Map<String, List<String>> hs = request.headers();
            if (hs != null) {
                for (Map.Entry<String, List<String>> e : hs.entrySet()) {
                    String k = e.getKey();
                    if (k == null || k.equalsIgnoreCase("Accept-Encoding")) continue;
                    boolean first = true;
                    for (String v : e.getValue()) {
                        if (first) c.setRequestProperty(k, v); else c.addRequestProperty(k, v);
                        first = false;
                    }
                }
            }
            byte[] data = request.dataToSend();
            if (data != null && data.length > 0) {
                c.setDoOutput(true);
                OutputHolder.write(c, data);
            }
            int code = c.getResponseCode();
            if (code == 429) throw new ReCaptchaException("reCaptcha Challenge requested", url);
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = is == null ? "" : new String(readAll(is), "UTF-8");
            Map<String, List<String>> out = new HashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) {
                if (e.getKey() != null) out.put(e.getKey(), e.getValue());
            }
            return new Response(code, c.getResponseMessage(), out, body, c.getURL().toString());
        }
    }

    static class OutputHolder {
        static void write(HttpURLConnection c, byte[] d) throws IOException {
            java.io.OutputStream os = c.getOutputStream();
            os.write(d);
            os.flush();
            os.close();
        }
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toByteArray();
    }

    static synchronized void initNP() {
        if (!npReady) {
            NewPipe.init(new Dl());
            npReady = true;
        }
    }

    static String ytId(String url) {
        if (url == null) return null;
        Matcher m = Pattern.compile("[?&]v=([A-Za-z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);
        m = Pattern.compile("youtu\\.be/([A-Za-z0-9_-]{11})").matcher(url);
        if (m.find()) return m.group(1);
        return null;
    }

    static String fmt(long s) {
        if (s <= 0) return "";
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    static JSONArray toJson(List<?> items) {
        JSONArray out = new JSONArray();
        if (items == null) return out;
        for (Object o : items) {
            try {
                if (!(o instanceof StreamInfoItem)) continue;
                StreamInfoItem s = (StreamInfoItem) o;
                String id = ytId(s.getUrl());
                if (id == null) continue;
                long d = s.getDuration();
                if (d < 0) continue;
                if (d > 0 && d < 45) continue;
                JSONObject j = new JSONObject();
                j.put("id", id);
                j.put("title", s.getName());
                j.put("by", s.getUploaderName() == null ? "" : s.getUploaderName());
                j.put("dur", fmt(d));
                out.put(j);
            } catch (Exception e) { }
        }
        return out;
    }

    JSONArray search(String q0) throws Exception {
        return search(q0, "songs".equals(pref("sm", "songs")));
    }

    SearchExtractor songsExtractor(String q0) throws Exception {
        Object svc = ServiceList.YouTube;
        Object f = svc.getClass().getMethod("getSearchQHFactory").invoke(svc);
        Method fq = null;
        for (Method m : f.getClass().getMethods()) {
            if (m.getName().equals("fromQuery") && m.getParameterTypes().length == 3) { fq = m; break; }
        }
        if (fq == null) throw new Exception("filter api nahi");
        Object qh = fq.invoke(f, q0, Collections.singletonList("music_songs"), "");
        for (Method m : svc.getClass().getMethods()) {
            if (m.getName().equals("getSearchExtractor") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].isInstance(qh)) {
                return (SearchExtractor) m.invoke(svc, qh);
            }
        }
        throw new Exception("extractor api nahi");
    }

    JSONArray search(String q0, boolean songs) throws Exception {
        Exception last = null;
        initNP();
        if (songs) {
            try {
                SearchExtractor se = songsExtractor(q0);
                se.fetchPage();
                JSONArray a = toJson(se.getInitialPage().getItems());
                if (a.length() > 0) { warm(a); return a; }
            } catch (Throwable t) { }
        }
        try {
            SearchExtractor se = ServiceList.YouTube.getSearchExtractor(q0);
            se.fetchPage();
            JSONArray a = toJson(se.getInitialPage().getItems());
            if (a.length() > 0) { warm(a); return a; }
        } catch (Exception e) {
            last = e;
        }
        try {
            JSONArray a = new JSONArray(get(BASE + "/search?q=" + URLEncoder.encode(q0, "UTF-8"), 60000));
            if (a.length() > 0) return a;
        } catch (Exception e) {
            if (last == null) last = e;
        }
        if (last != null) throw last;
        return new JSONArray();
    }

    void warm(JSONArray a) {
        for (int k = 0; k < a.length() && k < 3; k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            final String id = o.optString("id");
            io.execute(() -> { try { resolve(id); } catch (Exception e) { } });
        }
    }

    void warmIds(String json) {
        try {
            JSONArray a = new JSONArray(json);
            for (int k = 0; k < a.length() && k < 3; k++) {
                final String id = a.getString(k);
                io.execute(() -> { try { resolve(id); } catch (Exception e) { } });
            }
        } catch (Exception e) { }
    }

    JSONArray suggest(String q0) throws Exception {
        String body = getUa("https://suggestqueries.google.com/complete/search?client=youtube&ds=yt&hl=en&q="
            + URLEncoder.encode(q0, "UTF-8"), 8000, UA_WEB);
        int a = body.indexOf('[');
        int b = body.lastIndexOf(']');
        JSONArray root = new JSONArray(body.substring(a, b + 1));
        JSONArray list = root.getJSONArray(1);
        JSONArray out = new JSONArray();
        for (int k = 0; k < list.length() && k < 10; k++) {
            Object o = list.get(k);
            if (o instanceof JSONArray) out.put(((JSONArray) o).getString(0));
            else out.put(String.valueOf(o));
        }
        return out;
    }

    JSONArray related(String id, String t) throws Exception {
        try {
            Res r = resolve(id);
            if (r.related.length() > 0) return r.related;
        } catch (Exception e) { }
        try {
            JSONArray a = new JSONArray(get(BASE + "/related?id=" + id + "&t=" + URLEncoder.encode(t, "UTF-8"), 60000));
            if (a.length() > 0) return a;
        } catch (Exception e) { }
        return search(t + " songs");
    }

    Res resolve(String id) throws Exception {
        Res c = cache.get(id);
        if (c != null && System.currentTimeMillis() - c.t < TTL) return c;
        Res r = null;
        Exception np = null;
        try {
            r = viaNewPipe(id);
        } catch (Exception e) {
            np = e;
        }
        if (r == null) {
            try {
                r = viaServer(id);
            } catch (Exception e2) {
                throw new Exception(np != null ? String.valueOf(np.getMessage()) : String.valueOf(e2.getMessage()));
            }
        }
        r.t = System.currentTimeMillis();
        cache.put(id, r);
        return r;
    }

    Res viaNewPipe(String id) throws Exception {
        initNP();
        StreamInfo info = StreamInfo.getInfo("https://www.youtube.com/watch?v=" + id);
        AudioStream best = null;
        int bs = -1;
        String ql = pref("q", "auto");
        for (AudioStream a : info.getAudioStreams()) {
            String content = a.getContent();
            if (content == null || !content.startsWith("http")) continue;
            if (!"PROGRESSIVE_HTTP".equals(String.valueOf(a.getDeliveryMethod()))) continue;
            int br = a.getAverageBitrate();
            String fm = a.getFormat() == null ? "" : a.getFormat().name();
            int sc;
            if ("low".equals(ql)) sc = (fm.contains("M4A") ? 100000 : 0) + (br > 0 ? 1000 - Math.min(br, 999) : 0);
            else if ("high".equals(ql)) sc = (fm.contains("M4A") ? 100000 : 0) + Math.min(Math.max(br, 0), 999);
            else sc = (fm.contains("M4A") ? 100000 : 0) + (br > 0 && br <= 200 ? br : 0);
            if (sc > bs) { bs = sc; best = a; }
        }
        if (best == null) throw new Exception("audio stream nahi mila");
        Res r = new Res();
        r.url = best.getContent();
        r.ua = uaFor(r.url);
        List<?> rel = null;
        try {
            rel = (List<?>) info.getClass().getMethod("getRelatedItems").invoke(info);
        } catch (Exception e) {
            try { rel = (List<?>) info.getClass().getMethod("getRelatedStreams").invoke(info); } catch (Exception e2) { }
        }
        r.related = toJson(rel);
        return r;
    }

    Res viaServer(String id) throws Exception {
        JSONObject j = new JSONObject(get(BASE + "/stream?id=" + id, 60000));
        Res r = new Res();
        r.url = j.optString("url");
        if (r.url.isEmpty()) throw new Exception(j.optString("error", "stream nahi mila"));
        r.ua = j.optString("ua", UA_WEB);
        return r;
    }

    static String uaFor(String url) {
        if (url.contains("c=ANDROID_VR")) return UA_VR;
        if (url.contains("c=ANDROID")) return UA_ANDROID;
        if (url.contains("c=IOS")) return UA_IOS;
        return UA_WEB;
    }

    void api(final String kind, final String arg, final int cb) {
        io.execute(() -> {
            String code;
            try {
                String out;
                if (kind.equals("search")) {
                    String q1 = arg;
                    boolean songs = "songs".equals(pref("sm", "songs"));
                    if (arg.startsWith("{")) {
                        JSONObject so = new JSONObject(arg);
                        q1 = so.optString("q");
                        String mm = so.optString("m", "");
                        if (mm.length() > 0) songs = mm.equals("songs");
                    }
                    out = search(q1, songs).toString();
                } else if (kind.equals("related")) {
                    JSONObject a = new JSONObject(arg);
                    out = related(a.optString("id"), a.optString("t")).toString();
                } else if (kind.equals("suggest")) {
                    out = suggest(arg).toString();
                } else if (kind.equals("warm")) {
                    warmIds(arg);
                    out = "[]";
                } else if (kind.equals("update")) {
                    out = updateJson();
                } else {
                    throw new Exception("bad api");
                }
                code = "__cb(" + cb + ",true," + JSONObject.quote(out) + ")";
            } catch (Exception e) {
                code = "__cb(" + cb + ",false," + JSONObject.quote(String.valueOf(e.getMessage())) + ")";
            }
            M.js(code);
        });
    }

    // ---------------- update check (GitHub releases) ----------------

    String updateJson() throws Exception {
        String repo = "";
        try {
            repo = new String(readAll(getAssets().open("repo.txt")), "UTF-8").trim();
        } catch (Exception e) { }
        if (repo.isEmpty()) throw new Exception("GitHub repo set nahi hai");
        JSONObject j = new JSONObject(get("https://api.github.com/repos/" + repo + "/releases/latest", 20000));
        String tag = j.optString("tag_name");
        String url = j.optString("html_url");
        JSONArray as = j.optJSONArray("assets");
        if (as != null) {
            for (int k = 0; k < as.length(); k++) {
                JSONObject o = as.optJSONObject(k);
                if (o != null && o.optString("name").endsWith(".apk")) url = o.optString("browser_download_url");
            }
        }
        JSONObject r = new JSONObject();
        r.put("cur", VERSION);
        r.put("latest", tag);
        r.put("url", url);
        r.put("newer", vcmp(tag, VERSION) > 0);
        return r.toString();
    }

    static int vcmp(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int k = 0; k < Math.max(x.length, y.length); k++) {
            int p = k < x.length && x[k].length() > 0 ? Integer.parseInt(x[k]) : 0;
            int q2 = k < y.length && y[k].length() > 0 ? Integer.parseInt(y[k]) : 0;
            if (p != q2) return p > q2 ? 1 : -1;
        }
        return 0;
    }

    // ---------------- queue + playback ----------------

    void rebuildQ() {
        JSONArray a = new JSONArray();
        for (JSONObject o : q) a.put(o);
        qsnap = a.toString();
        qv++;
    }

    void setQueue(String json, int n) {
        try {
            JSONArray a = new JSONArray(json);
            q.clear();
            for (int k = 0; k < a.length(); k++) q.add(a.getJSONObject(k));
            fails = 0;
            radio = false;
            rebuildQ();
            playAt(n);
        } catch (Exception e) {
            err = "queue: " + e.getMessage();
        }
    }

    void playRadio(String json) {
        try {
            JSONObject o = new JSONObject(json);
            q.clear();
            q.add(o);
            fails = 0;
            radio = true;
            rebuildQ();
            playAt(0);
        } catch (Exception e) {
            err = "radio: " + e.getMessage();
        }
    }

    void playNext(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (q.isEmpty()) { q.add(o); rebuildQ(); playAt(0); return; }
            int at = (idx >= 0 && idx < q.size()) ? idx + 1 : q.size();
            q.add(at, o);
            rebuildQ();
            invalidatePreload();
        } catch (Exception e) { }
    }

    void removeAt(int n) {
        if (n < 0 || n >= q.size() || n == idx) return;
        q.remove(n);
        if (n < idx) idx--;
        rebuildQ();
        invalidatePreload();
    }

    void clearNext() {
        while (q.size() > idx + 1) q.remove(q.size() - 1);
        rebuildQ();
        invalidatePreload();
    }

    void setSpeed(float f) {
        speed = f;
        if (player != null) player.setPlaybackSpeed(f);
        snapshot();
    }

    int audioSession() { return player != null ? player.getAudioSessionId() : 0; }

    long dlSize() {
        long t = 0;
        File[] fs = offDir().listFiles();
        if (fs != null) for (File f : fs) t += f.length();
        return t;
    }

    void deleteAllDl() {
        File[] fs = offDir().listFiles();
        if (fs != null) for (File f : fs) f.delete();
        getSharedPreferences("khan", MODE_PRIVATE).edit().putString("dl", "[]").apply();
        dn++;
        snapshot();
    }

    void addQueue(String json) {
        try {
            JSONObject o = new JSONObject(json);
            boolean was = q.isEmpty();
            q.add(o);
            rebuildQ();
            if (was) playAt(0); else invalidatePreload();
        } catch (Exception e) { }
    }

    void playAt(int n) {
        if (n < 0 || n >= q.size()) return;
        idx = n;
        JSONObject e = q.get(n);
        vid = e.optString("id");
        title = e.optString("title");
        artist = e.optString("by");
        err = "";
        loading = true;
        playing = false;
        prepared = false;
        pos = 0;
        dur = 0;
        final int my = ++gen;
        stopPlayer();
        prevRel = curRel;
        curRel = new JSONArray();
        noteRecent(vid);
        if (!vid.equals(artId)) { art = null; ac = ""; artId = vid; fetchArt(vid); }
        push();
        final String id = vid;
        File lf = localFile(id);
        if (lf != null) {
            startPlay(lf.getAbsolutePath(), UA_WEB, true, my);
            return;
        }
        io.execute(() -> {
            try {
                final Res r = resolve(id);
                h.post(() -> {
                    if (my != gen) return;
                    curRel = r.related;
                    startPlay(r.url, r.ua, false, my);
                    if (q.size() - idx <= 3) appendRel(r.related);
                });
            } catch (final Exception ex) {
                h.post(() -> { if (my == gen) fail(ex.getMessage()); });
            }
        });
    }

    void initPlayer() {
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 400, 1500)
            .build();
        player = new ExoPlayer.Builder(this).setLoadControl(lc).build();
        player.setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_NETWORK);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int st) {
                if (st == Player.STATE_READY) {
                    if (!prepared) {
                        prepared = true;
                        fails = 0;
                        afterStart();
                    }
                    loading = false;
                } else if (st == Player.STATE_ENDED) {
                    done();
                    return;
                }
                syncState();
                push();
            }

            @Override
            public void onMediaItemTransition(MediaItem item, int reason) {
                if (pre >= 0 && player != null && player.getMediaItemCount() > 1
                        && player.getCurrentMediaItemIndex() == 1) {
                    advanceTo(pre);
                }
            }

            @Override
            public void onPlayWhenReadyChanged(boolean pwr, int reason) {
                syncState();
                push();
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                fail("player: " + e.getErrorCodeName());
            }
        });
    }

    void syncState() {
        if (player == null) return;
        int st = player.getPlaybackState();
        playing = player.getPlayWhenReady() && (st == Player.STATE_READY || st == Player.STATE_BUFFERING);
    }

    MediaSource buildSource(String src, String ua, boolean local) {
        Uri uri = local ? Uri.fromFile(new File(src)) : Uri.parse(src);
        DefaultHttpDataSource.Factory hf = new DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000);
        DefaultDataSource.Factory df = new DefaultDataSource.Factory(this, hf);
        return new DefaultMediaSourceFactory(df).createMediaSource(MediaItem.fromUri(uri));
    }

    void startPlay(String src, String ua, boolean local, int my) {
        try {
            if (player == null) initPlayer();
            pre = -1;
            planned = -1;
            player.setMediaSource(buildSource(src, ua, local));
            player.prepare();
            player.setPlaybackSpeed(speed);
            player.setPlayWhenReady(true);
            player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
    }

    // ---- next-track planning, gapless preload and smart autoplay ----

    int planNext() {
        if (q.isEmpty() || idx < 0 || repeat) return -1;
        if (shuffle && q.size() > 1) {
            if (planned < 0 || planned >= q.size() || planned == idx) {
                do { planned = rnd.nextInt(q.size()); } while (planned == idx);
            }
            return planned;
        }
        planned = idx + 1;
        if (planned >= q.size()) { planned = -1; return -1; }
        return planned;
    }

    void afterStart() {
        Res c = cache.get(vid);
        if (c != null && q.size() - idx <= 3) appendRel(c.related);
        else more();
        prefetch();
        preloadNext();
    }

    void preloadNext() {
        if (player == null || !prepared || repeat) return;
        if (player.getMediaItemCount() > 1) return;
        final int n = planNext();
        if (n < 0 || n >= q.size()) return;
        final String id = q.get(n).optString("id");
        final int my = gen;
        File lf = localFile(id);
        if (lf != null) {
            addPreloaded(n, lf.getAbsolutePath(), UA_WEB, true, my);
            return;
        }
        io.execute(() -> {
            try {
                final Res r = resolve(id);
                h.post(() -> addPreloaded(n, r.url, r.ua, false, my));
            } catch (Exception e) { }
        });
    }

    void addPreloaded(int n, String src, String ua, boolean local, int my) {
        if (player == null || my != gen || player.getMediaItemCount() != 1 || planned != n) return;
        try {
            player.addMediaSource(buildSource(src, ua, local));
            pre = n;
        } catch (Exception e) { }
    }

    void invalidatePreload() {
        planned = -1;
        if (player != null && pre >= 0) {
            try {
                if (player.getMediaItemCount() > 1) player.removeMediaItem(1);
            } catch (Exception e) { }
        }
        pre = -1;
        preloadNext();
    }

    void advanceTo(int n) {
        pre = -1;
        planned = -1;
        gen++;
        if (n < 0 || n >= q.size()) return;
        idx = n;
        JSONObject e = q.get(n);
        prevRel = curRel;
        vid = e.optString("id");
        title = e.optString("title");
        artist = e.optString("by");
        Res c = cache.get(vid);
        curRel = c != null ? c.related : new JSONArray();
        err = "";
        loading = false;
        prepared = true;
        fails = 0;
        pos = 0;
        dur = 0;
        try { player.removeMediaItem(0); } catch (Exception ex) { }
        noteRecent(vid);
        if (!vid.equals(artId)) { art = null; ac = ""; artId = vid; fetchArt(vid); }
        syncState();
        push();
        afterStart();
    }

    void stopPlayer() {
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception e) { }
        }
        pre = -1;
        planned = -1;
        prepared = false;
    }

    void fail(String m) {
        loading = false;
        playing = false;
        prepared = false;
        err = (m == null || m.length() == 0) ? "error" : m;
        push();
        if (++fails < 3 && q.size() > 1) {
            final int my = gen;
            h.postDelayed(() -> { if (my == gen) next(); }, 800);
        }
    }

    void done() { next(); }

    void next() {
        if (q.isEmpty()) return;
        int n = planNext();
        if (n < 0) {
            if (shuffle && q.size() > 1) {
                do { n = rnd.nextInt(q.size()); } while (n == idx);
            } else {
                n = idx + 1;
                if (n >= q.size()) n = 0;
            }
        }
        if (pre >= 0 && n == pre && player != null && player.getMediaItemCount() > 1) {
            player.seekToNextMediaItem();
            return;
        }
        playAt(n);
    }

    void prev() {
        if (q.isEmpty()) return;
        if (prepared && pos > 4000) { seek(0); return; }
        playAt(idx - 1 < 0 ? q.size() - 1 : idx - 1);
    }

    void pause() {
        if (player != null) player.setPlayWhenReady(false);
        playing = false;
        push();
    }

    void resume() {
        if (player != null && prepared) {
            player.setPlayWhenReady(true);
            syncState();
            push();
        } else if (idx >= 0 && !loading) {
            playAt(idx);
        }
    }

    void toggle() {
        if (loading) return;
        if (playing) pause(); else resume();
    }

    void seek(long ms2) {
        if (player != null && prepared) {
            player.seekTo(ms2);
            pos = ms2;
            push();
        }
    }

    void setShuffle(boolean b) { shuffle = b; invalidatePreload(); snapshot(); }

    void setRepeat(boolean b) {
        repeat = b;
        if (player != null) player.setRepeatMode(b ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        invalidatePreload();
        snapshot();
    }

    void sleep(int min) { sleepAt = min > 0 ? System.currentTimeMillis() + min * 60000L : 0; snapshot(); }

    void prefetch() {
        if (idx + 1 < q.size()) {
            final String id = q.get(idx + 1).optString("id");
            if (localFile(id) != null) return;
            io.execute(() -> { try { resolve(id); } catch (Exception e) { } });
        }
    }

    static JSONArray interleave(JSONArray a, JSONArray b, boolean mix) {
        if (!mix) return a;
        JSONArray o = new JSONArray();
        int n = Math.max(a.length(), b.length());
        for (int k = 0; k < n; k++) {
            if (k < a.length()) o.put(a.opt(k));
            if (k < b.length()) o.put(b.opt(k));
        }
        return o;
    }

    int addRel(JSONArray a0) {
        if (!autoOn()) return 0;
        JSONArray a = interleave(a0, prevRel, radio);
        HashSet<String> have = new HashSet<String>();
        for (JSONObject o : q) {
            have.add(o.optString("id"));
            have.add("t:" + tkey(o.optString("title")));
        }
        for (String r : recent) have.add(r);
        int added = 0;
        for (int k = 0; k < a.length() && added < 25; k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String id = o.optString("id");
            String tk = "t:" + tkey(o.optString("title"));
            if (have.contains(id) || have.contains(tk)) continue;
            have.add(id);
            have.add(tk);
            q.add(o);
            added++;
        }
        if (added > 0) {
            rebuildQ();
            if (pre < 0) preloadNext();
        }
        return added;
    }

    void appendRel(JSONArray a) {
        if (addRel(a) == 0) more();
    }

    void addLocal() {
        HashSet<String> have = new HashSet<String>();
        for (JSONObject o : q) have.add(o.optString("id"));
        JSONArray a = dlIndex();
        int added = 0;
        for (int k = a.length() - 1; k >= 0 && added < 20; k--) {
            JSONObject o = a.optJSONObject(k);
            if (o == null || !have.add(o.optString("id"))) continue;
            try {
                JSONObject r = new JSONObject();
                r.put("id", o.optString("id"));
                r.put("title", o.optString("title"));
                r.put("by", o.optString("by"));
                r.put("dur", o.optString("dur"));
                q.add(r);
                added++;
            } catch (Exception e) { }
        }
        if (added > 0) rebuildQ();
    }

    void more() {
        if (moreBusy || q.size() - idx > 3) return;
        moreBusy = true;
        final String id = vid, t = title;
        io.execute(() -> {
            try {
                final JSONArray a = related(id, t);
                h.post(() -> {
                    if (addRel(a) == 0) addLocal();
                    moreBusy = false;
                });
            } catch (Exception e) {
                h.post(() -> { addLocal(); moreBusy = false; });
            }
        });
    }

    // ---------------- like (shared with the web UI) ----------------

    JSONArray likedArr() {
        try {
            String s = getSharedPreferences("khan", MODE_PRIVATE).getString("kv_lk", "");
            return s.isEmpty() ? new JSONArray() : new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    boolean isLiked(String id) {
        if (id == null || id.isEmpty()) return false;
        JSONArray a = likedArr();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && id.equals(o.optString("id"))) return true;
        }
        return false;
    }

    void toggleLike() {
        if (vid.isEmpty()) return;
        JSONArray a = likedArr();
        JSONArray r = new JSONArray();
        boolean had = false;
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (vid.equals(o.optString("id"))) had = true; else r.put(o);
        }
        if (!had) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", vid);
                o.put("title", title);
                o.put("by", artist);
                o.put("dur", idx >= 0 && idx < q.size() ? q.get(idx).optString("dur") : "");
                JSONArray n2 = new JSONArray();
                n2.put(o);
                for (int k = 0; k < r.length(); k++) n2.put(r.opt(k));
                r = n2;
            } catch (Exception e) { }
        }
        getSharedPreferences("khan", MODE_PRIVATE).edit().putString("kv_lk", r.toString()).apply();
        lv++;
        push();
    }

    // ---------------- search from notification ----------------

    void searchPlay(final String s) {
        nm.notify(2, buildSearch("Dhoondh raha hu: " + s));
        io.execute(() -> {
            try {
                final JSONArray a = search(s);
                h.post(() -> {
                    if (a.length() == 0) { nm.notify(2, buildSearch("Kuch nahi mila: " + s)); return; }
                    setQueue(a.toString(), 0);
                    nm.notify(2, buildSearch(null));
                });
            } catch (Exception e) {
                h.post(() -> {
                    JSONArray m = matchDl(s);
                    if (m.length() > 0) {
                        setQueue(m.toString(), 0);
                        nm.notify(2, buildSearch("Offline match chala: " + s));
                    } else {
                        nm.notify(2, buildSearch("Internet nahi hai, offline me match nahi mila"));
                    }
                });
            }
        });
    }

    JSONArray matchDl(String s) {
        JSONArray out = new JSONArray();
        String low = s.toLowerCase();
        JSONArray a = dlIndex();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            String hay = (o.optString("title") + " " + o.optString("by")).toLowerCase();
            if (hay.contains(low)) {
                try {
                    JSONObject r = new JSONObject();
                    r.put("id", o.optString("id"));
                    r.put("title", o.optString("title"));
                    r.put("by", o.optString("by"));
                    r.put("dur", o.optString("dur"));
                    out.put(r);
                } catch (Exception e) { }
            }
        }
        return out;
    }

    // ---------------- downloads (offline) ----------------

    File offDir() {
        File d = new File(getFilesDir(), "offline");
        d.mkdirs();
        return d;
    }

    JSONArray dlIndex() {
        try {
            return new JSONArray(getSharedPreferences("khan", MODE_PRIVATE).getString("dl", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    File localFile(String id) {
        JSONArray a = dlIndex();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o != null && id.equals(o.optString("id"))) {
                File f = new File(offDir(), o.optString("file"));
                if (f.exists() && f.length() > 0) return f;
            }
        }
        return null;
    }

    String dlJson() {
        JSONArray a = dlIndex();
        JSONArray r = new JSONArray();
        for (int k = a.length() - 1; k >= 0; k--) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            File jf = new File(offDir(), o.optString("id") + ".jpg");
            try { o.put("img", jf.exists() ? "file://" + jf.getAbsolutePath() : ""); } catch (Exception e) { }
            r.put(o);
        }
        return r.toString();
    }

    void download(final String id, final String t, final String by, final String d) {
        if (dlp.containsKey(id) || localFile(id) != null) return;
        dlp.put(id, -1);
        dlx.execute(() -> {
            try {
                File out = null;
                try {
                    Res r = resolve(id);
                    String ext = r.url.contains("mime=audio%2Fwebm") ? ".webm" : ".m4a";
                    out = new File(offDir(), id + ext);
                    fetchRanged(r.url, r.ua, out, id);
                } catch (Exception direct) {
                    JSONObject j = new JSONObject(get(BASE + "/dl?id=" + id, 900000));
                    String name = j.optString("file");
                    if (name.isEmpty()) throw new Exception(j.optString("error", String.valueOf(direct.getMessage())));
                    int dot = name.lastIndexOf('.');
                    out = new File(offDir(), id + (dot >= 0 ? name.substring(dot) : ".m4a"));
                    fetchPlain(BASE + "/file/" + URLEncoder.encode(name, "UTF-8"), out, id);
                }
                try { saveImg(id); } catch (Exception e) { }
                JSONArray a = dlIndex();
                JSONObject o = new JSONObject();
                o.put("id", id);
                o.put("title", t);
                o.put("by", by);
                o.put("dur", d);
                o.put("file", out.getName());
                a.put(o);
                getSharedPreferences("khan", MODE_PRIVATE).edit().putString("dl", a.toString()).apply();
                dn++;
            } catch (Exception ex) {
                dlErr = "Download fail: " + t + " #" + (++dlErrN);
            }
            dlp.remove(id);
            h.post(() -> snapshot());
        });
    }

    void fetchRanged(String url, String ua, File out, String id) throws Exception {
        File tmp = new File(out.getPath() + ".part");
        FileOutputStream os = new FileOutputStream(tmp);
        long start = 0, total = -1;
        try {
            while (total < 0 || start < total) {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", ua);
                c.setRequestProperty("Range", "bytes=" + start + "-" + (start + 1048575));
                int code = c.getResponseCode();
                if (code == 200) {
                    copy(c.getInputStream(), os);
                    break;
                }
                if (code != 206) throw new Exception("http " + code);
                String cr = c.getHeaderField("Content-Range");
                if (cr != null && cr.indexOf('/') > 0) total = Long.parseLong(cr.substring(cr.indexOf('/') + 1).trim());
                long n = copy(c.getInputStream(), os);
                if (n <= 0) break;
                start += n;
                if (total > 0) dlp.put(id, (int) (start * 100 / total));
            }
        } finally {
            os.close();
        }
        if (tmp.length() == 0 || !tmp.renameTo(out)) throw new Exception("save fail");
    }

    static long copy(InputStream in, FileOutputStream os) throws IOException {
        byte[] buf = new byte[65536];
        long tot = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) { os.write(buf, 0, n); tot += n; }
        } finally {
            in.close();
        }
        return tot;
    }

    void fetchPlain(String u, File out, String id) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(60000);
        if (c.getResponseCode() != 200) throw new Exception("file http " + c.getResponseCode());
        long total = c.getContentLengthLong();
        long got = 0;
        File tmp = new File(out.getPath() + ".part");
        InputStream in = c.getInputStream();
        FileOutputStream os = new FileOutputStream(tmp);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                got += n;
                if (total > 0) dlp.put(id, (int) (got * 100 / total));
            }
        } finally {
            os.close();
            in.close();
        }
        if (!tmp.renameTo(out)) throw new Exception("rename fail");
    }

    byte[] imgBytes(String id) {
        String[] names = {"maxresdefault", "mqdefault"};
        for (String n : names) {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://i.ytimg.com/vi/" + id + "/" + n + ".jpg").openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(10000);
                if (c.getResponseCode() != 200) continue;
                return readAll(c.getInputStream());
            } catch (Exception e) { }
        }
        return null;
    }

    void saveImg(String id) throws Exception {
        byte[] b = imgBytes(id);
        if (b == null) return;
        FileOutputStream os = new FileOutputStream(new File(offDir(), id + ".jpg"));
        try {
            os.write(b);
        } finally {
            os.close();
        }
    }

    void removeDl(String id) {
        JSONArray a = dlIndex();
        JSONArray r = new JSONArray();
        for (int k = 0; k < a.length(); k++) {
            JSONObject o = a.optJSONObject(k);
            if (o == null) continue;
            if (id.equals(o.optString("id"))) {
                new File(offDir(), o.optString("file")).delete();
                new File(offDir(), id + ".jpg").delete();
            } else {
                r.put(o);
            }
        }
        getSharedPreferences("khan", MODE_PRIVATE).edit().putString("dl", r.toString()).apply();
        dn++;
        snapshot();
    }

    // ---------------- http helper + art ----------------

    static String get(String u, int readMs) throws Exception {
        return getUa(u, readMs, "KhanMusic");
    }

    static String getUa(String u, int readMs, String ua) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(readMs);
        c.setRequestProperty("User-Agent", ua);
        c.setRequestProperty("Accept", "application/json");
        int code = c.getResponseCode();
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = is == null ? "" : new String(readAll(is), "UTF-8");
        if (code >= 400) throw new Exception(body.length() > 0 ? body : "HTTP " + code);
        return body;
    }

    void fetchArt(final String id) {
        io.execute(() -> {
            Bitmap b = null;
            String a = "";
            try {
                File lf = new File(offDir(), id + ".jpg");
                byte[] raw = lf.exists() ? readAll(new java.io.FileInputStream(lf)) : imgBytes(id);
                if (raw != null) {
                    Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (src != null) { b = square(src); a = accent(b); }
                }
            } catch (Exception e) { }
            final Bitmap fb = b;
            final String fa = a;
            h.post(() -> { if (id.equals(vid)) { art = fb; ac = fa; push(); } });
        });
    }

    Bitmap logoBmp() {
        if (logo == null) {
            try {
                Bitmap b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888);
                Drawable d = getDrawable(R.drawable.ic);
                d.setBounds(0, 0, 512, 512);
                d.draw(new Canvas(b));
                logo = b;
            } catch (Exception e) { }
        }
        return logo;
    }

    static Bitmap square(Bitmap b) {
        int s = Math.min(b.getWidth(), b.getHeight());
        Bitmap r = Bitmap.createBitmap(b, (b.getWidth() - s) / 2, (b.getHeight() - s) / 2, s, s);
        return Bitmap.createScaledBitmap(r, 512, 512, true);
    }

    static String accent(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
        double r = 0, g = 0, bl = 0, w = 0;
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                int p = s.getPixel(x, y);
                int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
                int mx = Math.max(pr, Math.max(pg, pb)), mn = Math.min(pr, Math.min(pg, pb));
                if (mx < 40) continue;
                double sat = (mx - mn) / (double) mx;
                double wt = sat * sat * sat + 0.02;
                r += pr * wt;
                g += pg * wt;
                bl += pb * wt;
                w += wt;
            }
        }
        if (w == 0) return "";
        r /= w;
        g /= w;
        bl /= w;
        double m = Math.max(r, Math.max(g, bl));
        if (m < 1) m = 1;
        double k = 215 / m;
        return Math.min(255, (int) (r * k)) + "," + Math.min(255, (int) (g * k)) + "," + Math.min(255, (int) (bl * k));
    }

    // ---------------- state, session, notifications ----------------

    void snapshot() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", vid);
            o.put("title", title);
            o.put("by", artist);
            o.put("playing", playing);
            o.put("loading", loading);
            o.put("pos", pos);
            o.put("dur", dur);
            o.put("idx", idx);
            o.put("qv", qv);
            o.put("sh", shuffle);
            o.put("rp", repeat);
            o.put("sl", sleepAt > 0 ? Math.max(0L, (sleepAt - System.currentTimeMillis()) / 1000) : 0L);
            o.put("err", err);
            o.put("dn", dn);
            o.put("de", dlErr);
            o.put("lv", lv);
            o.put("spd", (double) speed);
            o.put("ac", ac);
            JSONObject d = new JSONObject();
            for (Map.Entry<String, Integer> en : dlp.entrySet()) d.put(en.getKey(), en.getValue().intValue());
            o.put("dl", d);
            snap = o.toString();
        } catch (Exception e) { }
    }

    void push() {
        try {
            MediaMetadata.Builder mb = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
            if (dur > 0) mb.putLong(MediaMetadata.METADATA_KEY_DURATION, dur);
            mb.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title);
            mb.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist);
            mb.putString(MediaMetadata.METADATA_KEY_ALBUM, "Khan Music");
            Bitmap shown = art != null ? art : logoBmp();
            if (shown != null) {
                mb.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, shown);
                mb.putBitmap(MediaMetadata.METADATA_KEY_ART, shown);
                mb.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, shown);
            }
            ms.setMetadata(mb.build());
            long acts = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SEEK_TO;
            int st = loading ? PlaybackState.STATE_BUFFERING : (playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED);
            boolean lk = isLiked(vid);
            PlaybackState.Builder pb = new PlaybackState.Builder()
                .setActions(acts)
                .setState(st, pos, playing ? 1f : 0f)
                .addCustomAction(new PlaybackState.CustomAction.Builder("LIKE", lk ? "Unlike" : "Like",
                    lk ? R.drawable.ic_liked : R.drawable.ic_like).build())
                .addCustomAction(new PlaybackState.CustomAction.Builder("SHUF", "Shuffle",
                    shuffle ? R.drawable.ic_shuf_on : R.drawable.ic_shuf).build());
            ms.setPlaybackState(pb.build());
            nm.notify(1, build());
        } catch (Exception e) { }
        snapshot();
    }

    Notification.Action act(int ic, String t, String a, int rc) {
        PendingIntent pi = PendingIntent.getService(this, rc, new Intent(this, S.class).setAction(a),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Action.Builder(Icon.createWithResource(this, ic), t, pi).build();
    }

    Notification build() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, M.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, "m")
            .setSmallIcon(R.drawable.ic_n)
            .setContentTitle(title)
            .setContentText(loading ? "Loading..." : artist)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC);
        Bitmap shown = art != null ? art : logoBmp();
        if (shown != null) b.setLargeIcon(shown);
        b.addAction(act(android.R.drawable.ic_media_previous, "Prev", "PREV", 1));
        b.addAction(act(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
            playing ? "Pause" : "Play", "TOG", 2));
        b.addAction(act(android.R.drawable.ic_media_next, "Next", "NEXT", 3));
        b.setStyle(new Notification.MediaStyle()
            .setMediaSession(ms.getSessionToken())
            .setShowActionsInCompactView(0, 1, 2));
        return b.build();
    }

    Notification buildSearch(String status) {
        RemoteInput ri = new RemoteInput.Builder("q").setLabel("Gaane ka naam likho...").build();
        PendingIntent pi = PendingIntent.getService(this, 9, new Intent(this, S.class).setAction("SRCH"),
            PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action a = new Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_search), "Search & Play", pi)
            .addRemoteInput(ri).build();
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, M.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "s")
            .setSmallIcon(R.drawable.ic_n)
            .setContentTitle("Khan Music Search")
            .setContentText(status == null ? "Yahin se naya gaana search karke play karo" : status)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(a)
            .build();
    }

    @Override
    public void onDestroy() {
        if (inst == this) inst = null;
        if (player != null) { player.release(); player = null; }
        io.shutdownNow();
        dlx.shutdownNow();
        if (wl != null && wl.isHeld()) wl.release();
        if (ms != null) ms.release();
        super.onDestroy();
    }
}
