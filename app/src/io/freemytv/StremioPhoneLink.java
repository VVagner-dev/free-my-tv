package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

/** A one-use, local-network page for entering Stremio credentials from a phone. */
final class StremioPhoneLink implements AutoCloseable {
    interface Listener { void onSubmitted(String email, String password); }

    private static final long LIFE_MS = 5 * 60 * 1000L;
    private final ServerSocket server;
    private final String token;
    private final String path;
    private final long expiresAt;
    private final Listener listener;
    private volatile boolean closed;
    private volatile boolean submitted;

    private StremioPhoneLink(ServerSocket server, String token, Listener listener) {
        this.server = server;
        this.token = token;
        this.path = "/stremio/" + token;
        this.listener = listener;
        this.expiresAt = System.currentTimeMillis() + LIFE_MS;
    }

    static StremioPhoneLink start(Listener listener) throws IOException {
        InetAddress address = localIpv4();
        if (address == null) throw new IOException("Não foi possível encontrar o endereço da rede local.");
        ServerSocket server = new ServerSocket(0, 4, address);
        byte[] random = new byte[18];
        new SecureRandom().nextBytes(random);
        StringBuilder token = new StringBuilder();
        for (byte b : random) token.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        StremioPhoneLink link = new StremioPhoneLink(server, token.toString(), listener);
        new Thread(link::serve, "stremio-phone-link").start();
        return link;
    }

    String url() { return "http://" + server.getInetAddress().getHostAddress() + ":" + server.getLocalPort() + path; }

    Bitmap qr(int size) throws Exception {
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new MultiFormatWriter().encode(url(), BarcodeFormat.QR_CODE, size, size, hints);
        Bitmap image = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) pixels[y * size + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
        image.setPixels(pixels, 0, size, 0, 0, size, size);
        return image;
    }

    private void serve() {
        try {
            while (!closed && !submitted && System.currentTimeMillis() < expiresAt) {
                server.setSoTimeout(1000);
                try { handle(server.accept()); } catch (java.net.SocketTimeoutException ignored) { }
            }
        } catch (IOException ignored) {
        } finally { close(); }
    }

    private void handle(Socket socket) {
        try (Socket s = socket; BufferedInputStream in = new BufferedInputStream(s.getInputStream()); OutputStream out = s.getOutputStream()) {
            String request = line(in);
            if (request == null) return;
            String[] start = request.split(" ");
            int contentLength = 0;
            for (String h; (h = line(in)) != null && !h.isEmpty();) {
                if (h.toLowerCase(java.util.Locale.US).startsWith("content-length:")) contentLength = Integer.parseInt(h.substring(15).trim());
            }
            if (start.length < 2 || !path.equals(start[1].split("\\?", 2)[0]) || System.currentTimeMillis() >= expiresAt) { reply(out, 404, "Página indisponível"); return; }
            if ("GET".equals(start[0])) { page(out); return; }
            if (!"POST".equals(start[0]) || submitted || contentLength < 1 || contentLength > 8192) { reply(out, 400, "Pedido inválido"); return; }
            byte[] data = new byte[contentLength];
            int at = 0, n;
            while (at < data.length && (n = in.read(data, at, data.length - at)) > 0) at += n;
            Map<String, String> form = form(new String(data, 0, at, StandardCharsets.UTF_8));
            String email = form.get("email"), password = form.get("password");
            if (email == null || email.trim().isEmpty() || password == null || password.isEmpty()) { reply(out, 400, "Preencha e-mail e senha."); return; }
            submitted = true;
            reply(out, 200, "<h1>Conectando…</h1><p>Volte para a TV. Esta página será fechada automaticamente.</p>");
            listener.onSubmitted(email.trim(), password);
        } catch (Exception ignored) { }
    }

    private void page(OutputStream out) throws IOException {
        String body = "<!doctype html><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Conectar Stremio</title>"
                + "<style>body{font:18px system-ui;background:#111;color:#fff;max-width:430px;margin:8vh auto;padding:22px}input,button{box-sizing:border-box;width:100%;font-size:18px;padding:14px;margin:9px 0;border-radius:8px;border:0}button{background:#7c4dff;color:white;font-weight:bold}small{color:#bbb}</style>"
                + "<h1>Conectar ao Stremio</h1><p>Digite seus dados para conectar esta TV.</p><form method=post><input required name=email type=email autocomplete=username placeholder=\"E-mail\"><input required name=password type=password autocomplete=current-password placeholder=\"Senha\"><button>Conectar TV</button></form><small>Use somente sua rede doméstica. A senha não é salva pela Free My TV.</small>";
        reply(out, 200, body);
    }

    private static void reply(OutputStream out, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String status = code == 200 ? "OK" : code == 404 ? "Not Found" : "Bad Request";
        out.write(("HTTP/1.1 " + code + " " + status + "\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(bytes);
    }

    private static String line(BufferedInputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        for (int c; (c = in.read()) >= 0;) { if (c == '\n') break; if (c != '\r') s.append((char) c); if (s.length() > 4096) throw new IOException("header too long"); }
        return s.length() == 0 ? "" : s.toString();
    }

    private static Map<String, String> form(String value) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (String pair : value.split("&")) { String[] kv = pair.split("=", 2); if (kv.length == 2) result.put(URLDecoder.decode(kv[0], "UTF-8"), URLDecoder.decode(kv[1], "UTF-8")); }
        return result;
    }

    private static InetAddress localIpv4() {
        try {
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            while (all.hasMoreElements()) { NetworkInterface n = all.nextElement(); if (!n.isUp() || n.isLoopback()) continue;
                for (Enumeration<InetAddress> a = n.getInetAddresses(); a.hasMoreElements();) { InetAddress address = a.nextElement(); if (address instanceof Inet4Address && address.isSiteLocalAddress()) return address; }
            }
        } catch (Exception ignored) { }
        return null;
    }

    @Override public void close() { closed = true; try { server.close(); } catch (IOException ignored) { } }
}
