package cn.b4qaq.simplefetchdroid.sf;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.b4qaq.simplefetchdroid.device.DeviceSession;

/**
 * SF（SimpleFetch）协议桥接：完整移植自 SimpleFetch AstroBox 插件的 event_handler.rs。
 * <p>
 * 消息集：SF_HANDSHAKE / SF_HANDSHAKE_ACK / SF_PING / SF_PONG / SF_REQUEST / SF_RESPONSE /
 * SF_SSE_EVENT / SF_SSE_END / SF_SSE_ERROR / SF_DOWNLOAD / SF_DL_RESPONSE / SF_DL_FAIL /
 * SF_CLOSE / SF_CLOSE_BRIDGE / SF_CLOSE_BRIDGE_ACK
 */
public class SfBridge {

    public enum Status { DISCONNECTED, HANDSHAKING, CONNECTED, FAILED }

    public interface Listener {
        void onStatusChanged(String pkg, Status status);
        void onStatsChanged();
        void onLog(String line);
    }

    /** 无受检异常的 JSON 构建器。 */
    private static final class J {
        private final JSONObject o = new JSONObject();

        static J obj() {
            return new J();
        }

        J put(String k, Object v) {
            try {
                o.put(k, v);
            } catch (Exception ignored) {
            }
            return this;
        }

        JSONObject done() {
            return o;
        }
    }

    private static final long HANDSHAKE_TIMEOUT_MS = 5000;
    private static final long LAUNCH_READY_WAIT_MS = 2000;
    private static final long HEARTBEAT_CHECK_MS = 3000;
    private static final long HEARTBEAT_TIMEOUT_MS = 30_000;
    private static final int TEXT_LIMIT = 16 * 1024;    // 直接文本返回上限
    private static final int TEXT_CHUNK = 12 * 1024;    // base64 文本分片（编码后）
    private static final int DL_CHUNK_BYTES = 8 * 1024; // 下载分片（编码前）

    private final DeviceSession session;
    private volatile Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** UI 重建后重新绑定监听（桥接对象跨 UI 生命周期复用）。 */
    public void setListener(Listener l) {
        this.listener = l;
    }

    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPing = new ConcurrentHashMap<>();
    private final Map<String, Runnable> handshakeTimers = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> sseCancels = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> sseByApp = new ConcurrentHashMap<>();

    private long totalRequests = 0;
    private long okRequests = 0;
    private long failedRequests = 0;

    public SfBridge(DeviceSession session, Listener listener) {
        this.session = session;
        this.listener = listener;
        main.postDelayed(this::checkHeartbeats, HEARTBEAT_CHECK_MS);
    }

    // ==================== 入口：设备侧消息 ====================

    /** 收到快应用互联消息（JSON 文本）。 */
    public void onMessage(String pkg, String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception e) {
            log(pkg, "非 JSON 消息: " + truncate(text));
            return;
        }
        String type = msg.optString("type", "");
        String status = msg.optString("status", "");
        JSONObject data = msg.optJSONObject("data");

        if ("SF_HANDSHAKE".equals(type)) {
            handleIncomingHandshake(pkg);
            return;
        }

        Status st = statuses.getOrDefault(pkg, Status.DISCONNECTED);
        if (st == Status.DISCONNECTED || st == Status.FAILED) {
            log(pkg, "丢弃未连接应用的消息: " + type);
            return;
        }

        switch (type) {
            case "SF_HANDSHAKE_ACK":
                if ("OK".equals(status)) handleHandshakeAck(pkg);
                break;
            case "SF_PING": {
                lastPing.put(pkg, System.currentTimeMillis());
                long ts = data != null ? data.optLong("ts", 0) : 0;
                sendJson(pkg, J.obj().put("type", "SF_PONG").put("status", "OK")
                        .put("data", J.obj().put("ts", ts).done()).done());
                break;
            }
            case "SF_REQUEST":
                if (data != null) handleRequest(pkg, data);
                break;
            case "SF_DOWNLOAD":
                if (data != null) handleDownload(pkg, data);
                break;
            case "SF_CLOSE_BRIDGE_ACK":
                setStatus(pkg, Status.DISCONNECTED);
                log(pkg, "快应用确认关闭桥接");
                break;
            case "SF_CLOSE":
                if (data != null) {
                    String id = data.optString("id", "");
                    cancelSse(id);
                    log(pkg, "SF_CLOSE，已取消请求: " + id);
                }
                break;
            default:
                log(pkg, "未处理消息类型: " + type);
                break;
        }
    }

    // ==================== 握手 ====================

    /** 连接指定快应用：启动 → 等待 → SF_HANDSHAKE → 超时定时。 */
    public void connectApp(String pkg) {
        setStatus(pkg, Status.HANDSHAKING);
        pool.execute(() -> {
            session.launchApp(pkg, "/index");
            try {
                Thread.sleep(LAUNCH_READY_WAIT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (statuses.get(pkg) != Status.HANDSHAKING) return; // 已被快应用主动握手满足
            sendJson(pkg, J.obj().put("type", "SF_HANDSHAKE")
                    .put("data", J.obj().done()).done());
            scheduleHandshakeTimeout(pkg);
        });
    }

    /** 断开指定快应用。 */
    public void disconnectApp(String pkg) {
        cancelHandshakeTimer(pkg);
        cancelAllSse(pkg);
        if (statuses.get(pkg) == Status.CONNECTED) {
            sendJson(pkg, J.obj().put("type", "SF_CLOSE_BRIDGE").put("status", "OK")
                    .put("data", J.obj().done()).done());
        }
        setStatus(pkg, Status.DISCONNECTED);
    }

    private void handleIncomingHandshake(String pkg) {
        log(pkg, "收到快应用主动握手");
        cancelHandshakeTimer(pkg);
        lastPing.put(pkg, System.currentTimeMillis());
        setStatus(pkg, Status.CONNECTED);
        sendJson(pkg, J.obj().put("type", "SF_HANDSHAKE_ACK").put("status", "OK")
                .put("data", J.obj().done()).done());
    }

    private void handleHandshakeAck(String pkg) {
        cancelHandshakeTimer(pkg);
        setStatus(pkg, Status.CONNECTED);
        lastPing.put(pkg, System.currentTimeMillis());
        log(pkg, "握手成功 ✓");
    }

    private void scheduleHandshakeTimeout(String pkg) {
        Runnable timer = () -> {
            handshakeTimers.remove(pkg);
            if (statuses.get(pkg) == Status.HANDSHAKING) {
                setStatus(pkg, Status.FAILED);
                log(pkg, "握手超时，未收到响应");
            }
        };
        handshakeTimers.put(pkg, timer);
        main.postDelayed(timer, HANDSHAKE_TIMEOUT_MS);
    }

    private void cancelHandshakeTimer(String pkg) {
        Runnable t = handshakeTimers.remove(pkg);
        if (t != null) main.removeCallbacks(t);
    }

    // ==================== 心跳 ====================

    private void checkHeartbeats() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : lastPing.entrySet()) {
            String pkg = e.getKey();
            if (statuses.get(pkg) == Status.CONNECTED && now - e.getValue() > HEARTBEAT_TIMEOUT_MS) {
                log(pkg, "心跳超时，断开");
                sendJson(pkg, J.obj().put("type", "SF_CLOSE_BRIDGE").put("status", "OK")
                        .put("data", J.obj().done()).done());
                setStatus(pkg, Status.DISCONNECTED);
            }
        }
        main.postDelayed(this::checkHeartbeats, HEARTBEAT_CHECK_MS);
    }

    // ==================== SF_REQUEST ====================

    private void handleRequest(String pkg, JSONObject data) {
        String id = data.optString("id", "");
        String url = data.optString("url", "");
        String method = data.optString("method", "GET");
        boolean sse = data.optBoolean("sse", false);
        int timeout = data.optInt("timeout", 15000);
        Map<String, String> headers = parseHeaders(data.optJSONObject("headers"));
        byte[] body = data.has("body")
                ? data.optString("body", "").getBytes(StandardCharsets.UTF_8) : null;

        if (url.isEmpty()) {
            sendError(pkg, id, 0, "请求缺少url字段");
            return;
        }

        totalRequests++;
        notifyStats();

        if (sse) {
            runSse(pkg, id, method, url, headers, body, timeout);
        } else {
            pool.execute(() -> {
                try {
                    HttpAgent.Response resp = HttpAgent.execute(method, url, headers, body, timeout);
                    boolean ok = resp.statusCode >= 200 && resp.statusCode < 300;
                    if (ok) okRequests++; else failedRequests++;
                    notifyStats();
                    sendResponse(pkg, id, resp);
                } catch (Exception e) {
                    failedRequests++;
                    notifyStats();
                    log(pkg, "请求异常 [" + id + "] " + url + " → " + classifyError(e));
                    sendError(pkg, id, 0, classifyError(e));
                }
            });
        }
    }

    private void sendResponse(String pkg, String id, HttpAgent.Response resp) {
        // 小数据且 UTF-8：单条文本
        if (resp.body.length <= TEXT_LIMIT && isValidUtf8(resp.body)) {
            JSONObject h = headersJson(resp.headers);
            sendJson(pkg, J.obj()
                    .put("type", "SF_RESPONSE").put("status", "OK")
                    .put("data", J.obj()
                            .put("id", id)
                            .put("statusCode", resp.statusCode)
                            .put("headers", h)
                            .put("body", new String(resp.body, StandardCharsets.UTF_8))
                            .put("chunk", 0)
                            .put("totalChunks", 0)
                            .done())
                    .done());
            return;
        }

        // 大数据：base64 分片
        String encoded = Base64.encodeToString(resp.body, Base64.NO_WRAP);
        int total = (encoded.length() + TEXT_CHUNK - 1) / TEXT_CHUNK;
        for (int i = 0; i < total; i++) {
            int start = i * TEXT_CHUNK;
            int end = Math.min(start + TEXT_CHUNK, encoded.length());
            J data = J.obj().put("id", id)
                    .put("body", encoded.substring(start, end))
                    .put("chunk", i + 1)
                    .put("totalChunks", total);
            J outer = J.obj().put("type", "SF_RESPONSE").put("status", "OK");
            if (i == 0) {
                data.put("statusCode", resp.statusCode).put("headers", headersJson(resp.headers));
            }
            outer.put("data", data.done());
            sendJson(pkg, outer.done());
        }
    }

    private JSONObject headersJson(Map<String, String> headers) {
        JSONObject h = new JSONObject();
        for (Map.Entry<String, String> e : filterHeaders(headers).entrySet()) {
            try {
                h.put(e.getKey(), e.getValue());
            } catch (Exception ignored) {
            }
        }
        return h;
    }

    private void sendError(String pkg, String id, int statusCode, String message) {
        log(pkg, "请求失败 [" + id + "]: " + message);
        sendJson(pkg, J.obj()
                .put("type", "SF_RESPONSE").put("status", message)
                .put("data", J.obj()
                        .put("id", id)
                        .put("statusCode", statusCode)
                        .put("error", message)
                        .done())
                .done());
    }

    // ==================== SSE ====================

    private void runSse(String pkg, String id, String method, String url,
                        Map<String, String> headers, byte[] body, int timeout) {
        AtomicBoolean cancel = new AtomicBoolean(false);
        sseCancels.put(id, cancel);
        sseByApp.computeIfAbsent(pkg, k -> ConcurrentHashMap.newKeySet()).add(id);

        pool.execute(() -> {
            try {
                HttpAgent.executeSse(method, url, headers, body, timeout, cancel,
                        (event, data) -> {
                            sendJson(pkg, J.obj()
                                    .put("type", "SF_SSE_EVENT").put("status", "OK")
                                    .put("data", J.obj()
                                            .put("id", id)
                                            .put("event", event)
                                            .put("data", data)
                                            .done())
                                    .done());
                            return !cancel.get();
                        });
                sendJson(pkg, sseEnd(id));
                okRequests++;
                notifyStats();
            } catch (Exception e) {
                if (cancel.get()) {
                    sendJson(pkg, sseEnd(id));
                } else {
                    failedRequests++;
                    notifyStats();
                    sendJson(pkg, J.obj()
                            .put("type", "SF_SSE_ERROR").put("status", classifyError(e))
                            .put("data", J.obj().put("id", id).done())
                            .done());
                }
            } finally {
                sseCancels.remove(id);
                Set<String> set = sseByApp.get(pkg);
                if (set != null) set.remove(id);
            }
        });
    }

    private JSONObject sseEnd(String id) {
        return J.obj().put("type", "SF_SSE_END").put("status", "OK")
                .put("data", J.obj().put("id", id).done()).done();
    }

    private void cancelSse(String id) {
        AtomicBoolean flag = sseCancels.get(id);
        if (flag != null) flag.set(true);
    }

    private void cancelAllSse(String pkg) {
        Set<String> ids = sseByApp.get(pkg);
        if (ids == null) return;
        for (String id : new HashSet<>(ids)) {
            AtomicBoolean flag = sseCancels.get(id);
            if (flag != null) flag.set(true);
        }
        ids.clear();
    }

    // ==================== SF_DOWNLOAD ====================

    private void handleDownload(String pkg, JSONObject data) {
        String id = data.optString("id", "");
        String url = data.optString("url", "");
        String filename = data.optString("filename", "");
        Map<String, String> headers = parseHeaders(data.optJSONObject("headers"));

        if (url.isEmpty()) {
            sendDlFail(pkg, id, "下载请求缺少url字段");
            return;
        }

        totalRequests++;
        notifyStats();

        pool.execute(() -> {
            try {
                HttpAgent.Response resp = HttpAgent.execute("GET", url, headers, null, 60000);
                if (resp.statusCode < 200 || resp.statusCode >= 300) {
                    failedRequests++;
                    notifyStats();
                    sendDlFail(pkg, id, "下载失败，HTTP " + resp.statusCode);
                    return;
                }
                byte[] body = resp.body;
                if (body.length == 0) {
                    failedRequests++;
                    notifyStats();
                    sendDlFail(pkg, id, "下载内容为空");
                    return;
                }
                int total = (body.length + DL_CHUNK_BYTES - 1) / DL_CHUNK_BYTES;
                for (int i = 0; i < total; i++) {
                    int start = i * DL_CHUNK_BYTES;
                    int end = Math.min(start + DL_CHUNK_BYTES, body.length);
                    String encoded = Base64.encodeToString(
                            java.util.Arrays.copyOfRange(body, start, end), Base64.NO_WRAP);
                    sendJson(pkg, J.obj()
                            .put("type", "SF_DL_RESPONSE").put("status", "OK")
                            .put("data", J.obj()
                                    .put("id", id)
                                    .put("append", i != 0)
                                    .put("isLast", i == total - 1)
                                    .put("data", encoded)
                                    .done())
                            .done());
                }
                okRequests++;
                notifyStats();
                log(pkg, "下载完成: " + filename + " " + body.length + " 字节 × " + total + " 片");
            } catch (Exception e) {
                failedRequests++;
                notifyStats();
                sendDlFail(pkg, id, classifyError(e));
            }
        });
    }

    private void sendDlFail(String pkg, String id, String message) {
        sendJson(pkg, J.obj()
                .put("type", "SF_DL_FAIL").put("status", message)
                .put("data", J.obj().put("id", id).put("message", message).done())
                .done());
    }

    // ==================== 辅助 ====================

    public void sendJson(String pkg, JSONObject message) {
        session.sendInterconnect(pkg, message.toString());
    }

    private Map<String, String> parseHeaders(JSONObject obj) {
        Map<String, String> map = new HashMap<>();
        if (obj == null) return map;
        Iterator<String> it = obj.keys();
        while (it.hasNext()) {
            String k = it.next();
            Object v = obj.opt(k);
            map.put(k, v == null ? "" : String.valueOf(v));
        }
        return map;
    }

    private Map<String, String> filterHeaders(Map<String, String> headers) {
        Set<String> drop = new HashSet<>(java.util.Arrays.asList(
                "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
                "te", "trailers", "transfer-encoding", "upgrade",
                "content-encoding", "content-length"));
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (!drop.contains(e.getKey().toLowerCase())) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    private boolean isValidUtf8(byte[] data) {
        try {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
            decoder.decode(java.nio.ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String classifyError(Exception e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage()).toLowerCase();
        if (msg.contains("timeout") || msg.contains("timed out")) return "请求超时";
        if (msg.contains("resolve") || msg.contains("unknownhost")) return "DNS解析失败";
        if (msg.contains("refused")) return "连接被拒绝";
        if (msg.contains("unreachable") || msg.contains("network")) return "网络不可达";
        return e == null ? "网络错误" : ("网络错误: " + truncate(e.getMessage()));
    }

    private String truncate(String s) {
        if (s == null) return "";
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    private void setStatus(String pkg, Status st) {
        statuses.put(pkg, st);
        main.post(() -> listener.onStatusChanged(pkg, st));
    }

    private void notifyStats() {
        main.post(listener::onStatsChanged);
    }

    private void log(String pkg, String line) {
        main.post(() -> listener.onLog("[" + pkg + "] " + line));
    }

    // ==================== 查询 ====================

    public Status getStatus(String pkg) {
        return statuses.getOrDefault(pkg, Status.DISCONNECTED);
    }

    public long getTotalRequests() { return totalRequests; }
    public long getOkRequests() { return okRequests; }
    public long getFailedRequests() { return failedRequests; }

    public void shutdown() {
        main.removeCallbacksAndMessages(null);
        pool.shutdownNow();
    }
}
