package cn.b4qaq.simplefetchdroid.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import cn.b4qaq.simplefetchdroid.frame.V2Frame;

/**
 * BLE 客户端：扫描小米 V2/V5 设备（Service 0xFE95）、建立 GATT 连接、
 * 在 RX(0x5e, notify) / TX(0x5f, write) 特征上收发 SPP V2 帧。
 */
@SuppressLint("MissingPermission")
public class BleClient {

    private static final String TAG = "BleClient";

    public static final UUID SERVICE_V2 = UUID.fromString("0000fe95-0000-1000-8000-00805f9b34fb");
    public static final UUID CHAR_RX = UUID.fromString("0000005e-0000-1000-8000-00805f9b34fb"); // 设备 → 手机 (notify)
    public static final UUID CHAR_TX = UUID.fromString("0000005f-0000-1000-8000-00805f9b34fb"); // 手机 → 设备 (write)
    private static final UUID CCC = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    // ===== 标准 GATT 服务（设备信息只读，完全独立于 V5 桥接通道 0xFE95） =====
    private static final UUID SVC_BATTERY = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID CHAR_BATTERY = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    public interface Listener {
        void onScanResult(BluetoothDevice device, String name, int rssi);
        void onConnected(String deviceName);
        void onDisconnected();
        void onMtu(int mtu);
        /** 收到一帧完整 Data 包（已解密或明文）。 */
        void onData(int channel, int opcode, byte[] payload);
        /** 收到 SessionConfig 响应。 */
        void onSessionConfig(int opcode);
        void onError(String stage, String message);
    }

    /** 设备基础信息：通过标准 BLE GATT 服务读取，与 V5 桥接协议互不干扰。 */
    public static class DeviceInfo {
        public String name = "";   // 设备广播名（来自 BluetoothDevice，无需 GATT）
        public int battery = -1;   // 电量百分比，-1 表示设备未提供/未读取
    }

    /** 设备信息读取结果回调（独立于主 Listener，避免触碰 DeviceSession 转发链）。 */
    public interface DeviceInfoListener {
        void onDeviceInfo(DeviceInfo info);
        void onDeviceInfoError(String message);
    }

    private final Context context;
    private volatile Listener listener;
    private final BluetoothAdapter adapter;
    private final Handler ioHandler; // 串行化 GATT 操作

    /** UI 重建后重新绑定监听（会话对象跨 UI 生命周期复用）。 */
    public void setListener(Listener l) {
        this.listener = l;
    }

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic charRx;
    private BluetoothGattCharacteristic charTx;

    private boolean scanning;
    /** 扫描上报模式：false=仅小米可穿戴（软件过滤），true=附近所有 BLE 设备。 */
    private boolean reportAll = false;
    private BluetoothGattCharacteristic pendingWriteChar;
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writing;
    private final ByteArrayOutputStream rxBuf = new ByteArrayOutputStream();

    // ===== 设备信息读取（标准 GATT，与 V5 写入串行） =====
    private DeviceInfoListener deviceInfoListener;
    private volatile boolean reading = false; // 读取进行中：暂停 V5 写入，避免 GATT 并发
    private DeviceInfo pendingInfo;
    private int infoStage = 0; // 0=电量 1=完成

    public BleClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        BluetoothManager bm = (BluetoothManager) this.context.getSystemService(Context.BLUETOOTH_SERVICE);
        this.adapter = bm != null ? bm.getAdapter() : null;
        HandlerThread t = new HandlerThread("ble-io");
        t.start();
        this.ioHandler = new Handler(t.getLooper());
    }

    public boolean isReady() {
        return adapter != null && adapter.isEnabled();
    }

    // ==================== 扫描 ====================

    /** 小米公司 BLE Manufacturer Specific Data 的 Company ID（0x038F = 919）。 */
    private static final int COMPANY_ID_XIAOMI = 0x038F;

    /** 同一设备两次上报的最小间隔（毫秒），降低 UI 回调开销。 */
    private static final long REPORT_THROTTLE_MS = 1500;

    private final Map<String, Long> lastReportAt = new HashMap<>();
    private final Map<String, String> reportedName = new HashMap<>();

    /**
     * 扫描小米可穿戴设备（软件过滤 0xFE95/小米名称）。返回是否成功启动。
     */
    public boolean startScan() {
        reportAll = false;
        return beginScan("扫描已启动（无硬件过滤，软件匹配 0xFE95/小米名称）");
    }

    /**
     * 扫描附近所有 BLE 设备（任意设备，不复用小米品牌过滤）。返回是否成功启动。
     */
    public boolean startScanAll() {
        reportAll = true;
        return beginScan("扫描已启动（附近所有 BLE 设备）");
    }

    /** 当前是否正在扫描。 */
    public boolean isScanning() {
        return scanning;
    }

    /**
     * 全量扫描（不使用硬件 ScanFilter——部分 ROM 对 UUID 位于 scan response 的设备匹配不可靠），
     * 在回调中做软件过滤。返回是否成功启动。
     */
    private boolean beginScan(String logMsg) {
        if (scanning) {
            Log.w(TAG, "startScan: 已在扫描中");
            return true;
        }
        if (adapter == null || adapter.getBluetoothLeScanner() == null) {
            listener.onError("scan", "蓝牙未开启或不可用");
            return false;
        }
        lastReportAt.clear();
        reportedName.clear();
        scanning = true;

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setReportDelay(0) // 立即逐条上报，不做批处理
                .build();

        try {
            // null 过滤器 = 扫描所有 BLE 设备，软件过滤在 onScanResult 中进行
            adapter.getBluetoothLeScanner().startScan(null, settings, scanCallback);
            Log.i(TAG, logMsg);
            return true;
        } catch (Exception e) {
            scanning = false;
            listener.onError("scan", "启动扫描异常: " + e.getMessage());
            return false;
        }
    }

    public void stopScan() {
        if (!scanning || adapter == null) return;
        try {
            adapter.getBluetoothLeScanner().stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        scanning = false;
    }

    /** 软件过滤：识别小米可穿戴设备（V2/V5 协议广播 Service 0xFE95）。 */
    private boolean matchesXiaomi(ScanResult result, String name) {
        android.bluetooth.le.ScanRecord rec = result.getScanRecord();
        if (rec != null) {
            List<ParcelUuid> uuids = rec.getServiceUuids();
            if (uuids != null) {
                for (ParcelUuid u : uuids) {
                    if (SERVICE_V2.equals(u.getUuid())) return true;
                }
            }
            // 小米厂商数据（Company ID 0x038F），部分机型 UUID 只在 scan response 中间歇出现
            if (rec.getManufacturerSpecificData(COMPANY_ID_XIAOMI) != null) return true;
        }
        if (name != null && !name.isEmpty()) {
            String n = name.toLowerCase(Locale.US);
            if (n.contains("xiaomi") || n.contains("redmi") || n.contains("小米")
                    || n.startsWith("mi watch") || n.startsWith("mi band")
                    || n.startsWith("mi smart") || n.startsWith("watch s") || n.startsWith("watch h")) {
                return true;
            }
        }
        return false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            String name = result.getScanRecord() != null && result.getScanRecord().getDeviceName() != null
                    ? result.getScanRecord().getDeviceName()
                    : (result.getDevice().getName() != null ? result.getDevice().getName() : "");
            if (!reportAll && !matchesXiaomi(result, name)) return;

            String mac = result.getDevice().getAddress();
            long now = System.currentTimeMillis();
            Long last = lastReportAt.get(mac);
            String prev = reportedName.get(mac);
            // 节流：同一设备同名字 1.5 秒内不重复上报；名字首次出现（从空变有）立即上报
            if (last != null && now - last < REPORT_THROTTLE_MS
                    && (name == null || name.isEmpty() || name.equals(prev))) {
                return;
            }
            lastReportAt.put(mac, now);
            if (name != null && !name.isEmpty()) reportedName.put(mac, name);
            listener.onScanResult(result.getDevice(), name == null ? "" : name, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            scanning = false; // 关键：失败后必须复位，否则后续 startScan 被永久拦截
            listener.onError("scan", describeScanError(errorCode));
        }
    };

    /** 将 Android 扫描错误码翻译为可操作的中文提示。 */
    private static String describeScanError(int code) {
        switch (code) {
            case 1: // SCAN_FAILED_ALREADY_STARTED
                return "扫描已在进行中（内部状态冲突，请重试）";
            case 2: // SCAN_FAILED_APPLICATION_REGISTRATION_FAILED
                return "蓝牙服务注册失败，请关闭再重新打开蓝牙";
            case 3: // SCAN_FAILED_INTERNAL_ERROR
                return "蓝牙内部错误，请关闭再重新打开蓝牙后重试";
            case 4: // SCAN_FAILED_FEATURE_UNSUPPORTED
                return "此设备不支持 BLE 后台扫描";
            case 5: // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES
                return "蓝牙硬件资源不足，请等待数秒后重试";
            case 6: // SCANNING_TOO_FREQUENTLY
                return "扫描过于频繁，请等待约 30 秒后再试";
            default:
                return "扫描失败（错误码 " + code + "），请重启蓝牙后重试";
        }
    }

    // ==================== 已配对设备 ====================

    /** 获取系统已配对（绑定）设备；蓝牙不可用或异常时返回 null。 */
    public java.util.Set<BluetoothDevice> getBondedDevices() {
        if (adapter == null) return null;
        try {
            return adapter.getBondedDevices();
        } catch (Exception e) {
            Log.w(TAG, "getBondedDevices: " + e);
            return null;
        }
    }

    /** 设备类型（个别 ROM 上 getType 可能抛异常，返回 UNKNOWN 兜底）。 */
    public static int deviceType(BluetoothDevice d) {
        try {
            return d.getType();
        } catch (Exception e) {
            return BluetoothDevice.DEVICE_TYPE_UNKNOWN;
        }
    }

    /** 按 MAC 构造设备对象（本地构造，无需权限）。 */
    public BluetoothDevice getRemoteDevice(String mac) {
        try {
            return adapter != null ? adapter.getRemoteDevice(mac) : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 连接（含自动重连） ====================

    /** 单次连接超时：设备不可达 / 停止广播时 connectGatt 可能长时间不回调。 */
    private static final long CONNECT_TIMEOUT_MS = 20000;
    /** 自动重连次数（首次失败后额外尝试的次数）。 */
    private static final int MAX_CONNECT_RETRY = 2;
    /** 重连间隔。 */
    private static final long RETRY_DELAY_MS = 1500;

    private Runnable connectTimeoutRunnable;
    private BluetoothDevice pendingDevice;
    private int connectRetry = 0;

    private void cancelConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            ioHandler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    /** 用户发起连接（重试计数清零）。 */
    public void connect(BluetoothDevice device) {
        stopScan();
        cancelConnectTimeout();
        pendingDevice = device;
        connectRetry = 0;
        Log.i(TAG, "连接 " + device.getAddress() + "（最多尝试 " + (MAX_CONNECT_RETRY + 1) + " 次）");
        connectInternal(device);
    }

    private void connectInternal(BluetoothDevice device) {
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        connectTimeoutRunnable = () -> {
            Log.w(TAG, "连接超时（" + CONNECT_TIMEOUT_MS / 1000 + " 秒无回调）");
            if (gatt == null) return;
            try {
                gatt.close(); // 未建立连接，close 不触发回调
            } catch (Exception ignored) {
            }
            gatt = null;
            cancelConnectTimeout();
            handleConnectFailure("连接超时（" + CONNECT_TIMEOUT_MS / 1000 + " 秒无响应）——设备可能不在范围内、已停止广播或被其他 App 占用");
        };
        ioHandler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT_MS);
    }

    /**
     * 连接失败统一处理：先复位上层会话（Session/bridge），可重试场景自动重连
     * （关闭旧 GATT 后重新 connectGatt，避免 133 错误导致的蓝牙栈连接堆积），
     * 超过次数则上报并停止。
     */
    private void handleConnectFailure(String reason) {
        listener.onDisconnected(); // 复位上层状态（Session 状态机 / SF 桥 / UI）
        if (connectRetry < MAX_CONNECT_RETRY && pendingDevice != null) {
            connectRetry++;
            final int attempt = connectRetry;
            final BluetoothDevice dev = pendingDevice;
            Log.w(TAG, reason + " → 自动重连 第 " + attempt + "/" + MAX_CONNECT_RETRY + " 次");
            listener.onError("reconnect", reason + "，自动重连中（第 " + attempt + "/" + MAX_CONNECT_RETRY + " 次）");
            ioHandler.postDelayed(() -> {
                // 用户手动断开（disconnect/close 会清 pendingDevice）则放弃重试
                if (pendingDevice == dev && dev != null) {
                    connectInternal(dev);
                }
            }, RETRY_DELAY_MS);
        } else {
            listener.onError("connect", reason + "（已重试 " + connectRetry + " 次仍失败）");
            pendingDevice = null;
        }
    }

    /** GATT 断开状态码是否值得自动重连。 */
    private static boolean isRetryableGattStatus(int s) {
        return s == 8   // HCI 链路超时（距离/干扰）
                || s == 34  // 链路管理超时
                || s == 133 // Android 蓝牙栈错误（连接堆积，close 后重试通常可恢复）
                || s == 257; // GATT 内部错误
    }

    /** 将 GATT 断开状态码翻译为可定位原因的中文提示。 */
    private static String describeGattStatus(int s) {
        switch (s) {
            case 133: return "蓝牙栈错误 133（连接异常断开）";
            case 8: return "链路超时（status 8）：设备距离过远、信号差或设备正忙";
            case 19: return "设备主动断开（status 19）：手表可能仍被「小米运动健康」连接着（它会在后台自动重连抢占）——请强制停止运动健康或解除绑定后再试；也可能是手表拒绝了认证/协议请求";
            case 22: return "本地主机关闭了连接（status 22）";
            case 34: return "链路管理器超时（status 34）：信号不稳定";
            case 62: return "连接建立失败（status 62）：配对信息不匹配，请在系统蓝牙设置中取消配对后重试";
            case 257: return "GATT 内部错误（status 257）";
            default: return "连接断开（status " + s + "）";
        }
    }

    public void disconnect() {
        pendingDevice = null; // 取消自动重连
        cancelConnectTimeout();
        stopScan();
        reading = false;
        deviceInfoListener = null;
        pendingInfo = null;
        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    public void close() {
        pendingDevice = null; // 取消自动重连
        cancelConnectTimeout();
        stopScan();
        reading = false;
        deviceInfoListener = null;
        pendingInfo = null;
        if (gatt != null) {
            try {
                gatt.close();
            } catch (Exception ignored) {
            }
            gatt = null;
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                cancelConnectTimeout();
                g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                cancelConnectTimeout();
                // 复位设备信息读取状态，避免读取中断时 reading 卡住导致 V2 写入永久阻塞
                reading = false;
                deviceInfoListener = null;
                pendingInfo = null;
                mtuExchanged = false;
                if (mtuFallbackRunnable != null) {
                    ioHandler.removeCallbacks(mtuFallbackRunnable);
                    mtuFallbackRunnable = null;
                }
                writeRetryCount = 0;
                lastWrittenPiece = null;
                charRx = null;
                charTx = null;
                writeQueue.clear();
                writing = false;
                rxBuf.reset();
                gatt = null;
                try {
                    g.close();
                } catch (Exception ignored) {
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "连接断开 status=" + status);
                    if (isRetryableGattStatus(status)) {
                        // 瞬时性错误（133/8/34/257）：自动重连，onDisconnected 复位在 handleConnectFailure 内统一处理
                        handleConnectFailure(describeGattStatus(status));
                        return;
                    }
                    // 设备主动断开（19）：多为被抢占/被拒绝，不自动重试，报明原因
                    listener.onError("disconnect", describeGattStatus(status));
                }
                pendingDevice = null;
                listener.onDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onError("discover", "服务发现失败: " + status);
                return;
            }
            charRx = g.getService(SERVICE_V2) != null ? g.getService(SERVICE_V2).getCharacteristic(CHAR_RX) : null;
            charTx = g.getService(SERVICE_V2) != null ? g.getService(SERVICE_V2).getCharacteristic(CHAR_TX) : null;
            if (charRx == null || charTx == null) {
                // 检测 V1 协议特征（手环 8 等老机型使用 0x51/0x52 特征）
                boolean v1 = false;
                android.bluetooth.BluetoothGattService svc = g.getService(SERVICE_V2);
                if (svc != null) {
                    for (android.bluetooth.BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                        String u = c.getUuid().toString();
                        if (u.startsWith("00000051") || u.startsWith("00000052")) {
                            v1 = true;
                            break;
                        }
                    }
                }
                listener.onError("chars", v1
                        ? "该设备为 V1 协议机型（老款手环等），本应用仅支持 V2/V5 协议设备"
                        : "设备缺少 V2 协议特征（0x5e/0x5f），可能不支持本协议版本");
                disconnect();
                return;
            }
            boolean ok = g.setCharacteristicNotification(charRx, true);
            if (!ok) {
                listener.onError("notify", "开启通知失败");
                return;
            }
            // 按特征属性自动适配 writeType：用 DEFAULT 写一个只支持 NO_RESPONSE 的特征会被设备拒绝
            int props = charTx.getProperties();
            if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                charTxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            } else if ((props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                charTxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
            } else {
                charTxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; // 兜底
            }
            Log.i(TAG, "TX 特征属性=0x" + Integer.toHexString(props) + " → writeType=" + charTxWriteType);
            BluetoothGattDescriptor ccc = charRx.getDescriptor(CCC);
            if (ccc != null) {
                if (Build.VERSION.SDK_INT >= 33) {
                    g.writeDescriptor(ccc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                } else {
                    ccc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    g.writeDescriptor(ccc);
                }
            } else {
                // 无 CCC 也尝试继续
                afterNotifyEnabled();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                afterNotifyEnabled();
            } else {
                listener.onError("ccc", "CCD 写入失败: " + status);
            }
        }

        private void afterNotifyEnabled() {
            ioHandler.post(() -> {
                if (gatt == null) return;
                // CCC 订阅完成后才通知连接就绪——保证此后所有写入不与描述符写入并发
                listener.onConnected(gatt.getDevice().getName() != null
                        ? gatt.getDevice().getName() : gatt.getDevice().getAddress());
                // 小米 V2 设备一般会主动发起 MTU 交换；2 秒内未发起再主动请求，
                // 避免与设备自身的 MTU 事务冲突（曾导致特征写入失败 252）
                mtuFallbackRunnable = () -> {
                    if (gatt != null && !mtuExchanged) {
                        Log.i(TAG, "设备未发起 MTU 交换，主动请求 512");
                        try {
                            gatt.requestMtu(512);
                        } catch (Exception ignored) {
                        }
                    }
                };
                ioHandler.postDelayed(mtuFallbackRunnable, MTU_FALLBACK_MS);
            });
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            mtuExchanged = true;
            if (mtuFallbackRunnable != null) ioHandler.removeCallbacks(mtuFallbackRunnable);
            int effective = status == BluetoothGatt.GATT_SUCCESS ? mtu : 23;
            setMtu(effective);
            Log.i(TAG, "MTU=" + effective + "（" + (status == BluetoothGatt.GATT_SUCCESS ? "ok" : "失败 status=" + status) + "）");
            listener.onMtu(effective);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            ioHandler.post(() -> {
                writing = false;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    // 瞬时写入失败（并发冲突/OEM 栈错误如 252）：自动重写一次
                    if (writeRetryCount < 1 && lastWrittenPiece != null) {
                        writeRetryCount++;
                        Log.w(TAG, "特征写入失败 status=" + status + "，自动重写一次");
                        writeQueue.addFirst(lastWrittenPiece);
                    } else {
                        listener.onError("write", "特征写入失败: " + status);
                    }
                } else {
                    writeRetryCount = 0;
                }
                pumpQueue();
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            // API 26..32
            handleRx(c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            // API 33+
            handleRx(value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            // 设备信息读取回调（3 参数版本兼容 API 26+；API 33+ 框架会回退调用此重载）
            ioHandler.post(() -> {
                if (!reading || pendingInfo == null) return;
                if (status == BluetoothGatt.GATT_SUCCESS && c != null) {
                    if (infoStage == 0) {
                        byte[] v = c.getValue();
                        if (v != null && v.length > 0) pendingInfo.battery = v[0] & 0xff;
                    }
                }
                infoStage++;
                readInfoStage();
            });
        }
    };

    // ==================== 接收 ====================

    private void handleRx(byte[] chunk) {
        if (chunk == null || chunk.length == 0) return;
        synchronized (rxBuf) {
            rxBuf.write(chunk, 0, chunk.length);
            processBufferLocked();
        }
    }

    private void processBufferLocked() {
        while (true) {
            byte[] snapshot = rxBuf.toByteArray();
            if (snapshot.length < 8) return;

            int size = V2Frame.packetSize(snapshot);
            if (size == -1) return; // 头部不完整，等待更多数据
            if (size == -2) { // 魔数错误，跳到下一个疑似帧头
                int next = findPreamble(snapshot, 1);
                trimBufferLocked(next < 0 ? snapshot.length : next);
                continue;
            }
            if (snapshot.length < size) return; // 帧未接收完整

            byte[] frameBytes = Arrays.copyOf(snapshot, size);
            trimBufferLocked(size);

            V2Frame.Decoded d = V2Frame.decode(frameBytes);
            if (d == null) continue;

            switch (d.type) {
                case V2Frame.TYPE_SESSION_CONFIG:
                    listener.onSessionConfig(d.opcode);
                    break;
                case V2Frame.TYPE_DATA:
                    // 每包 DATA 都回 ACK
                    sendFrame(V2Frame.encodeAck(d.seq));
                    listener.onData(d.channel, d.opcode, d.data);
                    break;
                case V2Frame.TYPE_ACK:
                default:
                    break;
            }
        }
    }

    private void trimBufferLocked(int n) {
        byte[] remain = rxBuf.toByteArray();
        rxBuf.reset();
        if (n >= 0 && n < remain.length) {
            rxBuf.write(remain, n, remain.length - n);
        }
    }

    private static int findPreamble(byte[] buf, int from) {
        for (int i = from; i < buf.length - 1; i++) {
            if (buf[i] == V2Frame.PREAMBLE[0] && buf[i + 1] == V2Frame.PREAMBLE[1]) return i;
        }
        return -1;
    }

    // ==================== 发送 ====================

    /** 直接发送一帧（自动按 MTU 分块，走写入队列）。 */
    public void sendFrame(byte[] frame) {
        int chunkSize = Math.max(20, lastMtu - 3);
        ioHandler.post(() -> {
            for (int off = 0; off < frame.length; off += chunkSize) {
                int n = Math.min(chunkSize, frame.length - off);
                byte[] piece = Arrays.copyOfRange(frame, off, off + n);
                writeQueue.add(piece);
            }
            pumpQueue();
        });
    }

    // ==================== 设备信息（标准 GATT 只读，安全独立于 V5 桥接） ====================

    /**
     * 读取设备基础信息（电量 / 名称）。
     * 走标准 GATT 服务，不与 0xFE95 上的 V5 protobuf 写入并发，刷新完成后自动恢复桥接写入。
     */
    public void readDeviceInfo(DeviceInfoListener l) {
        this.deviceInfoListener = l; // 复用最新监听（快速重复点击也只交付一次结果）
        if (reading) return;         // 已有读取在进行，复用其结果
        ioHandler.post(this::doReadDeviceInfo);
    }

    private void doReadDeviceInfo() {
        if (gatt == null) {
            if (deviceInfoListener != null) deviceInfoListener.onDeviceInfoError("未连接");
            return;
        }
        reading = true;
        pendingInfo = new DeviceInfo();
        pendingInfo.name = gatt.getDevice().getName() != null ? gatt.getDevice().getName() : "";
        infoStage = 0;
        readInfoStage();
    }

    /** 顺序读取：0=电量(Battery), 1=完成。 */
    private void readInfoStage() {
        if (gatt == null) { finishDeviceInfo(); return; }
        if (infoStage == 0) {
            android.bluetooth.BluetoothGattService s = gatt.getService(SVC_BATTERY);
            BluetoothGattCharacteristic c = s != null ? s.getCharacteristic(CHAR_BATTERY) : null;
            // 特征不存在（部分设备仅走 V5 通道）：跳过该项，避免阻塞
            if (c == null) { infoStage++; readInfoStage(); return; }
            if (!readCharSafe(c)) { infoStage++; readInfoStage(); }
        } else {
            finishDeviceInfo();
        }
    }

    private void finishDeviceInfo() {
        reading = false;
        DeviceInfo info = pendingInfo != null ? pendingInfo : new DeviceInfo();
        pendingInfo = null;
        if (deviceInfoListener != null) deviceInfoListener.onDeviceInfo(info);
        deviceInfoListener = null;
        pumpQueue(); // 恢复 V5 写入队列
    }

    /**
     * 兼容 API 26~35 的 readCharacteristic：API 33+ 返回 int（0=成功发起），旧版本返回 boolean。
     * 通过反射调用，避免按 android-35 编译时把旧签名当 int 处理而在老设备上崩溃。
     */
    @SuppressLint("DiscouragedPrivateApi")
    private boolean readCharSafe(BluetoothGattCharacteristic c) {
        if (gatt == null) return false;
        try {
            java.lang.reflect.Method m = gatt.getClass()
                    .getMethod("readCharacteristic", BluetoothGattCharacteristic.class);
            Object r = m.invoke(gatt, c);
            if (r instanceof Boolean) return (Boolean) r;
            if (r instanceof Integer) return (Integer) r == 0; // API 33+ 返回 int（0=成功）
        } catch (Exception ignored) {
        }
        return false;
    }

    private volatile int lastMtu = 23; // 保守初始值，MTU 协商完成后更新

    /** MTU 兜底请求延时：设备（手环/手表 V2）一般会主动发起 MTU 交换。 */
    private static final long MTU_FALLBACK_MS = 2000;

    private volatile boolean mtuExchanged;
    private Runnable mtuFallbackRunnable;
    /** TX 特征写入类型（按特征属性自动适配）。 */
    private int charTxWriteType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
    /** 最近一次写入的分块（写入失败自动重写用）。 */
    private byte[] lastWrittenPiece;
    private int writeRetryCount = 0;

    public void setMtu(int mtu) {
        lastMtu = mtu;
    }

    private void pumpQueue() {
        if (writing || reading || gatt == null || charTx == null) return;
        byte[] next = writeQueue.poll();
        if (next == null) return;
        writing = true;
        lastWrittenPiece = next;

        boolean ok;
        if (Build.VERSION.SDK_INT >= 33) {
            // BluetoothStatusCodes.SUCCESS == 0 表示成功发起写入
            ok = gatt.writeCharacteristic(charTx, next, charTxWriteType) == 0;
        } else {
            charTx.setValue(next);
            charTx.setWriteType(charTxWriteType);
            ok = gatt.writeCharacteristic(charTx);
        }
        if (!ok) {
            writing = false;
            listener.onError("write", "writeCharacteristic 发起失败");
        }
    }
}
