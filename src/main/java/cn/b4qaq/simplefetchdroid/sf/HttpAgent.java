package cn.b4qaq.simplefetchdroid.sf;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HttpsURLConnection;

/**
 * HTTP 代理执行器：普通请求 + SSE 流式，基于 HttpURLConnection（Android 内置）。
 */
public final class HttpAgent {

    public static class Response {
        public int statusCode;
        public Map<String, String> headers = new HashMap<>();
        public byte[] body = new byte[0];
    }

    public interface SseListener {
        /** 每个事件回调，返回 false 停止读取。 */
        boolean onEvent(String event, String data);
    }

    private HttpAgent() {}

    /** 执行普通 HTTP 请求。 */
    public static Response execute(String method, String url, Map<String, String> headers,
                                    byte[] body, int timeoutMs) throws IOException {
        HttpURLConnection conn = open(method, url, headers, timeoutMs);
        try {
            if (body != null && body.length > 0 && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }
            return readResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 执行 SSE 流式请求：按行解析 event/data，空行触发回调。
     *
     * @return true = 正常结束；false = 被取消
     */
    public static boolean executeSse(String method, String url, Map<String, String> headers,
                                     byte[] body, int timeoutMs,
                                     AtomicBoolean cancel, SseListener listener) throws IOException {
        HttpURLConnection conn = open(method, url, headers, timeoutMs);
        try {
            if (body != null && body.length > 0 && !"GET".equalsIgnoreCase(method)) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("SSE HTTP " + code);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new BufferedInputStream(conn.getInputStream()), StandardCharsets.UTF_8))) {
                String event = "";
                StringBuilder data = new StringBuilder();
                String line;
                while (!cancel.get() && (line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data.length() > 0) {
                            if (!listener.onEvent(event.isEmpty() ? "message" : event, data.toString())) {
                                return false;
                            }
                        }
                        event = "";
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        if (data.length() > 0) data.append('\n');
                        data.append(line.substring(5).trim());
                    } else if (line.startsWith(":")) {
                        // 注释/心跳行，忽略
                    }
                }
            }
            return !cancel.get();
        } finally {
            conn.disconnect();
        }
    }

    // ==================== 内部 ====================

    private static HttpURLConnection open(String method, String url,
                                          Map<String, String> headers, int timeoutMs) throws IOException {
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(Math.max(5000, timeoutMs));
        conn.setReadTimeout(Math.max(5000, timeoutMs));
        conn.setRequestMethod(method.toUpperCase(Locale.ROOT));
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "SimpleFetchDroid/1.0");
        boolean hasContentType = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                // 禁止 Hop-by-Hop 头透传
                String k = e.getKey().toLowerCase(Locale.ROOT);
                if (k.equals("connection") || k.equals("keep-alive") || k.equals("content-length")
                        || k.equals("transfer-encoding")) {
                    continue;
                }
                if (k.equals("content-type")) hasContentType = true;
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        // 与 SimpleFetch 插件行为对齐：带 body 的请求自动补全 Content-Type
        if (!hasContentType && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        if (conn instanceof HttpsURLConnection) {
            // 允许自签名证书（与 SimpleFetch 插件行为一致：代理不做证书校验策略）
            ((HttpsURLConnection) conn).setHostnameVerifier((hostname, session) -> true);
            try {
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, new javax.net.ssl.TrustManager[]{trustAll()}, new java.security.SecureRandom());
                ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
            } catch (Exception ignored) {
            }
        }
        return conn;
    }

    private static javax.net.ssl.TrustManager trustAll() {
        return new javax.net.ssl.X509TrustManager() {
            @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
            @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        };
    }

    private static Response readResponse(HttpURLConnection conn) throws IOException {
        Response resp = new Response();
        resp.statusCode = conn.getResponseCode();

        Map<String, java.util.List<String>> fields = conn.getHeaderFields();
        for (Map.Entry<String, List<String>> e : fields.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            resp.headers.put(e.getKey(), String.join(", ", e.getValue()));
        }

        java.io.InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException ioe) {
            is = conn.getErrorStream();
        }
        if (is == null) {
            resp.body = new byte[0];
            return resp;
        }
        try (BufferedInputStream bis = new BufferedInputStream(is)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = bis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            resp.body = bos.toByteArray();
        }
        return resp;
    }
}
