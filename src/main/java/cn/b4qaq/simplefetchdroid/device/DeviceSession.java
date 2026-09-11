package cn.b4qaq.simplefetchdroid.device;

import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import cn.b4qaq.simplefetchdroid.ble.BleClient;
import cn.b4qaq.simplefetchdroid.crypto.CryptoUtil;
import cn.b4qaq.simplefetchdroid.frame.V2Frame;
import cn.b4qaq.simplefetchdroid.proto.Wear;

/**
 * 小米 V5 设备会话：V2 帧层之上的认证与 protobuf 业务分发。
 * <p>
 * 流程：SessionConfig 握手 → AuthVerify/AppVerify → AuthDeviceVerify →
 * kdf_miwear → AuthAppConfirm → AuthDeviceConfirm → 已认证。
 */
public class DeviceSession implements BleClient.Listener {

    public enum State { DISCONNECTED, CONNECTING, SESSION_INIT, AUTHENTICATING, AUTHED }

    public interface Listener {
        void onStateChanged(State state);
        void onAuthResult(boolean ok, String message);
        void onAppList(List<Wear.AppItem> apps);
        void onAppOnline(String pkgName);
        void onInterconnectMessage(String pkgName, String text);
        void onLog(String line);
        void onDisconnected();
    }

    private static final String COMPANION_NAME = "SimpleFetchDroid";

    private final BleClient ble;
    private volatile Listener listener;
    private final String authKeyHex;

    /** UI 重建后重新绑定监听（会话对象跨 UI 生命周期复用）。 */
    public void setListener(Listener l) {
        this.listener = l;
    }

    private volatile State state = State.DISCONNECTED;
    private final AtomicInteger seqCounter = new AtomicInteger(0);

    // 认证材料
    private byte[] phoneNonce;
    private byte[] decKey = new byte[0];
    private byte[] encKey = new byte[0];
    private byte[] decNonce = new byte[0];
    private byte[] encNonce = new byte[0];
    private boolean authed = false;

    // 各快应用指纹缓存（发消息需要 basic_info.fingerprint）
    private final java.util.Map<String, byte[]> fingerprints = new java.util.concurrent.ConcurrentHashMap<>();

    public DeviceSession(BleClient ble, Listener listener, String authKeyHex) {
        this.ble = ble;
        this.listener = listener;
        this.authKeyHex = authKeyHex;
    }

    // ==================== BleClient.Listener ====================

    @Override
    public void onScanResult(android.bluetooth.BluetoothDevice device, String name, int rssi) {
        // 设备会话不关心扫描结果
    }

    @Override
    public void onConnected(String deviceName) {
        // 自动重连/二次连接场景：清空上一轮的密钥与会话状态
        decKey = new byte[0];
        encKey = new byte[0];
        decNonce = new byte[0];
        encNonce = new byte[0];
        setState(State.SESSION_INIT);
        // CCC 订阅已完成（BleClient 在 afterNotifyEnabled 后才回调），此时写入安全。
        // SessionConfig 帧很小（<20 字节），无需等待 MTU 协商。
        log("已连接: " + deviceName + "，发送会话初始化请求");
        sendSessionRequest();
        // 兜底：3 秒未收到 SessionConfig RESPONSE 则重发一次（如首次写入瞬时失败被丢弃）
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (state == State.SESSION_INIT) {
                log("未收到会话响应，重发会话初始化请求");
                sendSessionRequest();
            }
        }, 3000);
    }

    private void sendSessionRequest() {
        ble.sendFrame(V2Frame.encodeSessionConfig(0, V2Frame.SC_OPCODE_START_SESSION_REQUEST));
    }

    @Override
    public void onMtu(int mtu) {
        // MTU 只影响后续大帧的分块大小；握手已在 onConnected 发出
        log("MTU 就绪: " + mtu);
    }

    @Override
    public void onDisconnected() {
        // 完整断开路径：本地复位 + 通知上层
        onBleDisconnected();
        listener.onDisconnected();
    }

    /**
     * BLE 层断开的本地复位（不回调 listener）。
     * 供 MainActivity.onDisconnected 转发调用，避免 BleClient→MainActivity→Session→MainActivity 递归。
     */
    public void onBleDisconnected() {
        authed = false;
        setState(State.DISCONNECTED);
    }

    @Override
    public void onSessionConfig(int opcode) {
        if (opcode == V2Frame.SC_OPCODE_START_SESSION_RESPONSE) {
            if (state != State.SESSION_INIT) return; // 防重复响应重复认证
            log("会话已建立（opcode=2），开始认证");
            setState(State.AUTHENTICATING);
            startAuth();
        }
    }

    @Override
    public void onData(int channel, int opcode, byte[] payload) {
        if (channel != V2Frame.CH_PROTOBUF) return;

        byte[] raw;
        if (opcode == V2Frame.OP_ENCRYPTED) {
            if (decKey.length != 16) return;
            try {
                raw = CryptoUtil.aesCtr(decKey, payload, false);
            } catch (Exception e) {
                log("解密失败: " + e.getMessage());
                return;
            }
        } else {
            raw = payload;
        }
        handleProtobuf(raw);
    }

    @Override
    public void onError(String stage, String message) {
        log("错误[" + stage + "]: " + message);
        if ("chars".equals(stage) || "discover".equals(stage)) {
            listener.onAuthResult(false, message);
        }
    }

    // ==================== 认证 ====================

    private void startAuth() {
        phoneNonce = CryptoUtil.randomBytes(16);
        sendPlain(Wear.buildAuthStep1(phoneNonce));
        log("已发送 AuthVerify（AppVerify nonce）");
    }

    private void handleProtobuf(byte[] bytes) {
        Wear.Packet p;
        try {
            p = Wear.parsePacket(bytes);
        } catch (Exception e) {
            log("protobuf 解析异常: " + e.getMessage());
            return;
        }

        switch (p.type) {
            case Wear.T_ACCOUNT: {
                if (p.payloadField != 3 || p.payload == null) return;
                Wear.AuthDeviceVerify v = Wear.parseAccount(p.payload);
                if (v.isConfirm) {
                    if (v.confirmResult) {
                        authed = true;
                        setState(State.AUTHED);
                        log("认证成功 ✓ 设备已就绪");
                        listener.onAuthResult(true, "认证成功");
                        // 自动拉取应用列表
                        sendEncryptedIfAuthed(Wear.buildGetAppList());
                        log("已请求快应用列表");
                    } else {
                        log("设备拒绝认证（confirm_result=false）");
                        listener.onAuthResult(false, "设备拒绝认证，请检查 AuthKey");
                    }
                    return;
                }
                if (v.deviceRandom != null && v.deviceSign != null) {
                    handleDeviceVerify(v);
                }
                return;
            }
            case Wear.T_THIRDPARTY_APP: {
                if (p.payloadField != 22 || p.payload == null) return;
                Wear.parseThirdpartyApp(p.payload, p.id, new Wear.ThirdpartyHandler() {
                    @Override
                    public void onAppList(List<Wear.AppItem> items) {
                        for (Wear.AppItem a : items) {
                            if (a.fingerprint != null && a.fingerprint.length > 0) {
                                fingerprints.put(a.packageName, a.fingerprint);
                            }
                        }
                        listener.onAppList(items);
                    }

                    @Override
                    public void onBasicInfo(Wear.Message basicInfo) {
                        // 快应用上线：按 AstroBox 行为回 SyncPhoneAppStatus(Connected)
                        log("快应用上线: " + basicInfo.packageName);
                        fingerprints.put(basicInfo.packageName, basicInfo.fingerprint);
                        sendEncryptedIfAuthed(Wear.buildSyncPhoneAppStatus(
                                basicInfo.packageName, basicInfo.fingerprint, Wear.APP_STATUS_CONNECTED));
                        // 通知上层：该应用再次上线（用于自动重握手已桥接的应用）
                        listener.onAppOnline(basicInfo.packageName);
                    }

                    @Override
                    public void onAppStatus(Wear.Message status) {
                        log("App 状态上报: " + status.packageName);
                    }

                    @Override
                    public void onMessageContent(Wear.Message message) {
                        String text = new String(message.content, java.nio.charset.StandardCharsets.UTF_8);
                        listener.onInterconnectMessage(message.packageName, text);
                    }
                });
                return;
            }
            default:
                // 其他类型消息忽略
                break;
        }
    }

    private void handleDeviceVerify(Wear.AuthDeviceVerify v) {
        try {
            if (v.deviceRandom.length != 16 || v.deviceSign.length != 32) {
                listener.onAuthResult(false, "DeviceVerify 长度异常");
                return;
            }
            byte[] secret = parseAuthKey();
            byte[] block = CryptoUtil.kdfMiwear(secret, phoneNonce, v.deviceRandom);

            byte[] decKey = java.util.Arrays.copyOfRange(block, 0, 16);
            byte[] encKey = java.util.Arrays.copyOfRange(block, 16, 32);
            byte[] decNonce = java.util.Arrays.copyOfRange(block, 32, 36);
            byte[] encNonce = java.util.Arrays.copyOfRange(block, 36, 40);

            // 校验设备签名：HMAC(dec_key, watchNonce||phoneNonce)
            byte[] expect = CryptoUtil.hmacSha256(decKey, CryptoUtil.concat(v.deviceRandom, phoneNonce));
            if (!java.util.Arrays.equals(expect, v.deviceSign)) {
                listener.onAuthResult(false, "HMAC 校验失败：AuthKey 不正确");
                log("AuthKey 校验失败");
                ble.disconnect();
                return;
            }

            this.decKey = decKey;
            this.encKey = encKey;
            this.decNonce = decNonce;
            this.encNonce = encNonce;

            // app_sign = HMAC(enc_key, phoneNonce||watchNonce)
            byte[] appSign = CryptoUtil.hmacSha256(encKey, CryptoUtil.concat(phoneNonce, v.deviceRandom));

            sendPlain(Wear.buildAuthStep2(encKey, encNonce, appSign, COMPANION_NAME, Wear.DEVICE_TYPE_IOS));
            log("已发送 AuthAppConfirm（密钥已就绪）");
        } catch (Exception e) {
            listener.onAuthResult(false, "认证异常: " + e.getMessage());
        }
    }

    private byte[] parseAuthKey() {
        byte[] key = new byte[16];
        try {
            byte[] parsed = CryptoUtil.parseHex(authKeyHex);
            System.arraycopy(parsed, 0, key, 0, Math.min(16, parsed.length));
        } catch (Exception e) {
            log("AuthKey 格式非法，使用全零密钥");
        }
        return key;
    }

    // ==================== 对外操作 ====================

    /** 拉取快应用安装列表。 */
    public void requestAppList() {
        sendEncryptedIfAuthed(Wear.buildGetAppList());
    }

    /** 启动快应用（默认 uri=/index）。 */
    public void launchApp(String pkg, String uri) {
        byte[] fp = fingerprints.getOrDefault(pkg, new byte[0]);
        sendEncryptedIfAuthed(Wear.buildLaunchApp(pkg, fp, uri == null || uri.isEmpty() ? "/index" : uri));
        log("已发送启动指令: " + pkg);
    }

    /** 向快应用发送互联文本消息（SF 协议 JSON）。 */
    public void sendInterconnect(String pkg, String text) {
        byte[] fp = fingerprints.getOrDefault(pkg, new byte[0]);
        sendEncryptedIfAuthed(Wear.buildSendPhoneMessage(pkg, fp, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public boolean isAuthed() {
        return authed;
    }

    public State getState() {
        return state;
    }

    public void disconnect() {
        ble.disconnect();
    }

    // ==================== 发送辅助 ====================

    private void sendPlain(byte[] protobuf) {
        ble.sendFrame(V2Frame.encodeDataPlain(seqCounter.getAndIncrement(), V2Frame.CH_PROTOBUF, protobuf));
    }

    private void sendEncryptedIfAuthed(byte[] protobuf) {
        if (!authed || encKey.length != 16) {
            log("未认证，消息被丢弃");
            return;
        }
        byte[] encrypted = CryptoUtil.aesCtr(encKey, protobuf, true);
        ble.sendFrame(V2Frame.encodeDataEncrypted(seqCounter.getAndIncrement(), V2Frame.CH_PROTOBUF, encrypted));
    }

    private void setState(State s) {
        state = s;
        new Handler(Looper.getMainLooper()).post(() -> listener.onStateChanged(s));
    }

    private void log(String line) {
        new Handler(Looper.getMainLooper()).post(() -> listener.onLog(line));
    }
}
