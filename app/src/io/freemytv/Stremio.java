package io.freemytv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The small, read-only bridge to the Stremio account datastore. Stremio keeps its resume state in the libraryItem
 * collection, so this works independently of the Stremio TV app's private cache. The password is used only for the
 * TLS login request; this app retains only Stremio's session key in its private files directory.
 */
final class Stremio {
    interface Listener { void onStremio(); }

    private static final String API = "https://api.strem.io/api/";
    private static final String FANART = "https://webservice.fanart.tv/v3/";
    private static final String TMDB = "https://api.themoviedb.org/3";
    private static final String TMDB_IMAGES = "https://image.tmdb.org/t/p/";
    private static final String COLLECTION = "libraryItem";
    private static Stremio instance;

    static synchronized Stremio get(Context c) {
        if (instance == null) instance = new Stremio(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Artwork embellishments must not delay the user's Continue Watching row. */
    private final ExecutorService artIo = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile List<Recs.Pick> continuing = Collections.emptyList();
    private volatile List<Recs.Pick> upcoming = Collections.emptyList();
    private volatile boolean loading;
    private volatile String error = "";

    private Stremio(Context c) { context = c; }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }
    List<Recs.Pick> continuing() { return continuing; }
    List<Recs.Pick> upcoming() { return upcoming; }
    boolean connected() { return !authKey().isEmpty(); }
    String error() { return error; }

    private File tokenFile() { return new File(context.getFilesDir(), "stremio-session.json"); }

    private String authKey() {
        File f = tokenFile();
        if (!f.exists()) return "";
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in)).optString("authKey");
        } catch (Exception ignored) { return ""; }
    }

    private boolean saveKey(String key) {
        File f = tokenFile(), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(new JSONObject().put("authKey", key).toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        return tmp.renameTo(f);
    }

    void disconnect() {
        //noinspection ResultOfMethodCallIgnored
        tokenFile().delete();
        continuing = Collections.emptyList();
        error = "";
        notifyListeners();
    }

    /** Connects directly to Stremio. Password is deliberately not saved or logged. */
    void connect(String email, String password, Listener done) {
        io.execute(() -> {
            String problem = "";
            try {
                JSONObject result = call("login", new JSONObject().put("email", email).put("password", password)).optJSONObject("result");
                String key = result == null ? "" : result.optString("authKey");
                if (key.isEmpty() || !saveKey(key)) problem = "Couldn't save the Stremio session.";
            } catch (Exception e) {
                problem = message(e);
            }
            error = problem;
            if (problem.isEmpty()) refresh();
            main.post(() -> { if (done != null) done.onStremio(); notifyListeners(); });
        });
    }

    void refresh() {
        String key = authKey();
        if (key.isEmpty() || loading) return;
        loading = true;
        io.execute(() -> {
            String problem = "";
            List<Recs.Pick> result = Collections.emptyList();
            try {
                JSONObject body = new JSONObject().put("authKey", key).put("collection", COLLECTION).put("all", true);
                JSONArray items = call("datastoreGet", body).optJSONArray("result");
                if (items == null) throw new IOException("Stremio returned no watch history.");
                result = picks(items);
            } catch (Exception e) {
                problem = message(e);
            }
            continuing = result;
            for (Recs.Pick p : result) queueFanart(p);
            refreshUpcoming(result);
            error = problem;
            loading = false;
            main.post(this::notifyListeners);
        });
    }

    private List<Recs.Pick> picks(JSONArray items) throws Exception {
        List<JSONObject> started = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i), state = item == null ? null : item.optJSONObject("state");
            if (item == null || state == null || state.optLong("timeOffset") <= 0 || watched(state)) continue;
            started.add(item);
        }
        Collections.sort(started, (a, b) -> b.optJSONObject("state").optString("lastWatched")
                .compareTo(a.optJSONObject("state").optString("lastWatched")));
        List<Recs.Pick> out = new ArrayList<>();
        for (JSONObject item : started) {
            if (out.size() == 12) break;
            String id = item.optString("_id"), type = item.optString("type");
            if (id.isEmpty() || !(type.equals("movie") || type.equals("series"))) continue;
            JSONObject state = item.optJSONObject("state");
            Recs.Pick p = new Recs.Pick();
            p.kind = type.equals("movie") ? "movie" : "tv";
            p.artId = id;
            p.title = item.optString("name", id);
            Art art = artwork(id, type, p.title);
            p.backdrop = art.background;
            p.poster = art.poster;
            p.wide = art.wide;
            p.logo = art.logo;
            p.app = "stremio";
            p.pkg = "com.stremio.one";
            String video = state.optString("video_id");
            p.link = "stremio:///detail/" + type + "/" + id + (video.isEmpty() ? "" : "/" + video);
            long duration = state.optLong("duration"), offset = state.optLong("timeOffset");
            p.progress = duration > 0 ? Math.max(0, Math.min(1, (float) offset / duration)) : -1;
            p.meta = progress(state) + " · Stremio";
            out.add(p);
        }
        return out;
    }

    private static final class Art {
        final String background, poster, wide, logo;
        Art(String background, String poster, String wide, String logo) {
            this.background = background; this.poster = poster; this.wide = wide; this.logo = logo;
        }
    }

    /**
     * TMDB is the primary source: it returns a real 16:9 backdrop and a separate
     * portrait poster for the same title. Fanart remains a fallback for titles TMDB
     * does not recognise.
     */
    private Art artwork(String id, String type, String title) {
        String fallback = id.startsWith("tt") ? "https://images.metahub.space/poster/medium/" + id + "/img" : null;
        Art tmdb = tmdbArtwork(id, type, title);
        if (tmdb != null) return new Art(tmdb.background, tmdb.poster, null, null);
        if (Config.FANART_KEY.trim().isEmpty() || !id.startsWith("tt")) return new Art(fallback, fallback, null, null);
        try {
            String group = type.equals("movie") ? "movies/" : "tv/";
            HttpURLConnection c = (HttpURLConnection) new URL(FANART + group + id + "?api_key=" + Config.FANART_KEY).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            if (c.getResponseCode() != 200) return new Art(fallback, fallback, null, null);
            JSONObject data = new JSONObject(read(c.getInputStream()));
            // Prefer the regular background.  The 4K variants are occasionally too
            // large or encoded in a way some Android TV decoders do not handle well.
            String background = firstImage(data, type.equals("movie") ? "moviebackground" : "showbackground");
            if (background.isEmpty()) background = firstImage(data, type.equals("movie") ? "movie4kbackground" : "tvbackground");
            if (background.isEmpty()) background = firstImage(data, type.equals("movie") ? "moviethumb" : "tvthumb");
            String poster = firstImage(data, type.equals("movie") ? "movieposter" : "tvposter");
            // A poster is preferable to an empty/black wide card when Fanart has no
            // compatible backdrop for this particular title.
            if (background.isEmpty()) background = poster;
            String logo = firstImage(data, type.equals("movie") ? "hdmovielogo" : "hdtvlogo");
            if (logo.isEmpty()) logo = firstImage(data, type.equals("movie") ? "movielogo" : "clearlogo");
            return new Art(background.isEmpty() ? fallback : background, poster.isEmpty() ? fallback : poster,
                    background.isEmpty() ? null : background, logo.isEmpty() ? null : logo);
        } catch (Exception ignored) {
            return new Art(fallback, fallback, null, null);
        }
    }

    private static final class Fanart {
        final String background, logo;
        Fanart(String background, String logo) { this.background = background; this.logo = logo; }
    }

    private Fanart fanart(String id, String type) {
        if (Config.FANART_KEY.trim().isEmpty() || !id.startsWith("tt")) return new Fanart(null, null);
        try {
            String group = type.equals("movie") ? "movies/" : "tv/";
            HttpURLConnection c = (HttpURLConnection) new URL(FANART + group + id + "?api_key=" + Config.FANART_KEY).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            try {
                if (c.getResponseCode() != 200) return new Fanart(null, null);
                JSONObject data = new JSONObject(read(c.getInputStream()));
                String background = firstImage(data, type.equals("movie") ? "moviebackground" : "showbackground");
                if (background.isEmpty()) background = firstImage(data, type.equals("movie") ? "movie4kbackground" : "tvbackground");
                if (background.isEmpty()) background = firstImage(data, type.equals("movie") ? "moviethumb" : "tvthumb");
                String logo = firstImage(data, type.equals("movie") ? "hdmovielogo" : "hdtvlogo");
                if (logo.isEmpty()) logo = firstImage(data, type.equals("movie") ? "movielogo" : "clearlogo");
                return new Fanart(background.isEmpty() ? null : background, logo.isEmpty() ? null : logo);
            } finally {
                c.disconnect();
            }
        } catch (Exception ignored) {
            return new Fanart(null, null);
        }
    }

    private void queueFanart(Recs.Pick p) {
        if (p.artId == null || p.artId.isEmpty()) return;
        artIo.execute(() -> {
            Fanart art = fanart(p.artId, p.kind);
            p.logo = art.logo;
            main.post(this::notifyListeners);
        });
    }

    /** Upcoming episodes for the series the viewer already has in Continue Watching. */
    private void refreshUpcoming(List<Recs.Pick> source) {
        artIo.execute(() -> {
            List<Recs.Pick> out = new ArrayList<>();
            for (Recs.Pick watched : source) {
                if (!"tv".equals(watched.kind)) continue;
                try {
                    Recs.Pick next = nextEpisode(watched);
                    if (next != null) out.add(next);
                } catch (Exception ignored) { }
            }
            Collections.sort(out, (a, b) -> a.meta.compareTo(b.meta));
            upcoming = out;
            main.post(this::notifyListeners);
        });
    }

    private Recs.Pick nextEpisode(Recs.Pick watched) throws Exception {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty() || watched.title == null) return null;
        boolean bearer = key.startsWith("eyJ");
        JSONObject search = tmdbGet("/search/tv?language=" + URLEncoder.encode(Ui.language.equals("pt") ? "pt-BR" : "en-US", "UTF-8")
                + "&query=" + URLEncoder.encode(watched.title.replaceFirst("\\s*\\(\\d{4}\\)\\s*$", ""), "UTF-8"), key, bearer);
        JSONArray results = search == null ? null : search.optJSONArray("results");
        JSONObject show = results == null ? null : results.optJSONObject(0);
        if (show == null) return null;
        JSONObject detail = tmdbGet("/tv/" + show.optInt("id") + "?language=" + (Ui.language.equals("pt") ? "pt-BR" : "en-US"), key, bearer);
        JSONObject episode = detail == null ? null : detail.optJSONObject("next_episode_to_air");
        String date = episode == null ? "" : episode.optString("air_date");
        if (date.isEmpty()) return null;
        Recs.Pick p = new Recs.Pick();
        p.kind = "tv";
        p.title = watched.title;
        p.backdrop = watched.backdrop;
        p.poster = watched.poster;
        p.app = "stremio";
        p.pkg = "com.stremio.one";
        p.link = watched.artId == null ? watched.link : "stremio:///detail/series/" + watched.artId;
        int season = episode.optInt("season_number"), number = episode.optInt("episode_number");
        String name = episode.optString("name");
        p.meta = date + " · T" + season + " E" + number + (name.isEmpty() ? "" : " · " + name);
        return p;
    }

    private Art tmdbArtwork(String id, String type, String title) {
        String key = Config.TMDB_KEY.trim();
        if (key.isEmpty()) return null;
        try {
            boolean bearer = key.startsWith("eyJ");
            String group = type.equals("movie") ? "movie" : "tv";
            JSONObject media = null;
            if (id.startsWith("tt")) {
                JSONObject data = tmdbGet("/find/" + URLEncoder.encode(id, "UTF-8") + "?external_source=imdb_id", key, bearer);
                JSONArray results = data == null ? null : data.optJSONArray(group + "_results");
                media = results == null ? null : results.optJSONObject(0);
            }
            // Anime and some community add-ons use ids such as "kitsu:…", which TMDB
            // cannot resolve through /find. Their Stremio title is still a reliable
            // TMDB text search and gives the same backdrop/poster fields.
            if (media == null && title != null && !title.trim().isEmpty()) {
                media = tmdbSearch(group, title, key, bearer);
                // Stremio often appends a release year to a title. TMDB's text
                // endpoint treats that as part of the name, so retry the clean name.
                String cleanTitle = title.replaceFirst("\\s*\\(\\d{4}\\)\\s*$", "").trim();
                if (media == null && !cleanTitle.equals(title)) media = tmdbSearch(group, cleanTitle, key, bearer);
            }
            if (media == null) return null;
            String background = media.optString("backdrop_path");
            String poster = media.optString("poster_path");
            if (background.isEmpty() && poster.isEmpty()) return null;
            // Use a moderate landscape rendition for the large card; posters use a
            // portrait rendition and are never used as its primary artwork.
            if (!background.isEmpty()) background = TMDB_IMAGES + "w1280" + background;
            if (!poster.isEmpty()) poster = TMDB_IMAGES + "w780" + poster;
            if (background.isEmpty()) background = poster;
            if (poster.isEmpty()) poster = background;
            return new Art(background, poster, null, null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JSONObject tmdbGet(String path, String key, boolean bearer) throws Exception {
        String url = TMDB + path + (bearer ? "" : "&api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            return c.getResponseCode() == 200 ? new JSONObject(read(c.getInputStream())) : null;
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject tmdbSearch(String group, String title, String key, boolean bearer) throws Exception {
        JSONObject data = tmdbGet("/search/" + group + "?query=" + URLEncoder.encode(title, "UTF-8"), key, bearer);
        JSONArray results = data == null ? null : data.optJSONArray("results");
        return results == null ? null : results.optJSONObject(0);
    }

    private static String firstImage(JSONObject data, String field) {
        JSONArray images = data.optJSONArray(field);
        JSONObject image = images == null ? null : images.optJSONObject(0);
        return image == null ? "" : image.optString("url");
    }

    private static boolean watched(JSONObject state) {
        if (state.optInt("flaggedWatched") != 0) return true;
        long duration = state.optLong("duration");
        return duration > 0 && state.optLong("timeWatched") > duration * 0.7;
    }

    private static String progress(JSONObject state) {
        long duration = state.optLong("duration"), offset = state.optLong("timeOffset");
        if (duration <= 0 || offset <= 0) return Ui.tr("Continue watching");
        long left = Math.max(1, (duration - offset) / 60000);
        return left < 60 ? left + " min restantes" : (left / 60) + " h restantes";
    }

    private JSONObject call(String method, JSONObject body) throws Exception {
        body.put("type", Character.toUpperCase(method.charAt(0)) + method.substring(1));
        HttpURLConnection c = (HttpURLConnection) new URL(API + method).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        try (java.io.OutputStream out = c.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int code = c.getResponseCode();
        InputStreamHolder input = new InputStreamHolder(code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream());
        JSONObject data = new JSONObject(read(input.in));
        if (code != 200 || data.has("error")) throw new IOException(data.optJSONObject("error") == null ? "Stremio returned HTTP " + code
                : data.optJSONObject("error").optString("message", "Stremio login failed."));
        return data;
    }

    private static String read(java.io.InputStream in) throws IOException {
        if (in == null) return "{}";
        byte[] buffer = new byte[8192];
        StringBuilder s = new StringBuilder();
        for (int n; (n = in.read(buffer)) > 0;) s.append(new String(buffer, 0, n, StandardCharsets.UTF_8));
        in.close();
        return s.toString();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? "Couldn't connect to Stremio." : m;
    }

    private void notifyListeners() { for (Listener l : new ArrayList<>(listeners)) l.onStremio(); }

    private static final class InputStreamHolder { final java.io.InputStream in; InputStreamHolder(java.io.InputStream value) { in = value; } }
}
