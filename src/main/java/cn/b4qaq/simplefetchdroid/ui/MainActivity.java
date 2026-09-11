package cn.b4qaq.simplefetchdroid.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import cn.b4qaq.simplefetchdroid.BuildConfig;
import cn.b4qaq.simplefetchdroid.R;
import cn.b4qaq.simplefetchdroid.ble.BleClient;
import cn.b4qaq.simplefetchdroid.device.DeviceSession;
import cn.b4qaq.simplefetchdroid.proto.Wear;
import cn.b4qaq.simplefetchdroid.sf.SfBridge;
import cn.b4qaq.simplefetchdroid.store.Prefs;

/**
 * SimpleFetchDroid 主界面：扫描 → 连接（AuthKey）→ 快应用列表 → SF 桥接管理。
 */
public class MainActivity extends Activity implements
        BleClient.Listener, DeviceSession.Listener, SfBridge.Listener {

    private static final int REQ_PERMS = 1;
    /** 本机发起连接后应用启动会上报 basic_info（≈「上线」），此窗口内忽略该信号，避免断开-重连死循环。 */
    private static final long APP_ONLINE_COOLDOWN_MS = 8000;

    // ==================== 跨 UI 生命周期的会话对象（static 复用） ====================
    // UI（Activity）销毁后这些对象继续存活，由前台服务保活——后台持续处理快应用请求；
    // 用户重新打开 App 时恢复绑定与界面状态。
    private static BleClient sBle;
    private static DeviceSession sSession;
    private static SfBridge sBridge;
    private static String sConnectedMac = "";
    private static String sConnectedName = "";
    private static long sSessionStart = 0;
    /** 最近一次读取到的设备信息，跨 UI 重建保留（后台重开 App 时直接展示）。 */
    private static BleClient.DeviceInfo sDeviceInfo;

    private BleClient ble;
    private DeviceSession session;
    private SfBridge bridge;
    private Prefs prefs;

    private TextView statusText;
    private TextView statsText;
    private LinearLayout scanList;
    private LinearLayout appList;
    private TextView logText;
    private ScrollView logScroll;
    private Button bondedBtn;
    private Button refreshAppsBtn;
    private Button disconnectBtn;
    private Button aboutBtn;
    private LinearLayout quickCard;
    private LinearLayout tipsCard;
    private LinearLayout recentList;
    private TextView quickNameText;
    private TextView quickMacText;
    private Button quickConnectBtn;
    private LinearLayout sectionDevices;
    private LinearLayout deviceInfoCard;
    private TextView infoNameText;
    private TextView infoBatteryText;
    private Button refreshInfoBtn;

    private final Map<String, BluetoothDevice> foundDevices = new LinkedHashMap<>();
    private final Map<String, TextView> scanRows = new LinkedHashMap<>();
    private final Map<String, Wear.AppItem> apps = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<String> logBuffer = new ArrayDeque<>();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private String connectedMac = "";
    private String connectedName = "";
    private long sessionStart = 0;
    private long lastStatsPersist = 0;
    /** 已成功桥接、需要「再次上线时自动重握手」的快应用包名集合（手动断开时移出）。 */
    private final Set<String> autoBridgePkgs = new HashSet<>();
    /** 本机最近一次发起连接的时间（按包名），用于「快应用上线」信号的冷却去重。 */
    private final Map<String, Long> lastConnectAt = new HashMap<>();

    /** 供保活服务自检：会话是否仍存活。 */
    public static DeviceSession getStaticSession() {
        return sSession;
    }

    /** 供保活服务读取请求统计与连接设备名，刷新通知栏。 */
    public static SfBridge getStaticBridge() {
        return sBridge;
    }

    public static String getStaticName() {
        return sConnectedName;
    }

    /** UI 销毁后的空监听器：吸收回调，避免操作已销毁的视图。 */
    private static final class DetachedListener
            implements BleClient.Listener, DeviceSession.Listener, SfBridge.Listener {
        @Override public void onScanResult(BluetoothDevice d, String n, int r) { }
        @Override public void onConnected(String deviceName) { }
        @Override public void onDisconnected() { }
        @Override public void onMtu(int mtu) { }
        @Override public void onData(int channel, int opcode, byte[] payload) { }
        @Override public void onSessionConfig(int opcode) { }
        @Override public void onError(String stage, String message) { }
        @Override public void onStateChanged(DeviceSession.State state) { }
        @Override public void onAuthResult(boolean ok, String message) { }
        @Override public void onAppList(java.util.List<Wear.AppItem> items) { }
        @Override public void onAppOnline(String pkgName) { }
        @Override public void onInterconnectMessage(String pkgName, String text) { }
        @Override public void onLog(String line) { }
        @Override public void onStatusChanged(String pkg, SfBridge.Status status) { }
        @Override public void onStatsChanged() { }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status);
        statsText = findViewById(R.id.stats);
        scanList = findViewById(R.id.scan_list);
        appList = findViewById(R.id.app_list);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        bondedBtn = findViewById(R.id.btn_bonded);
        refreshAppsBtn = findViewById(R.id.btn_refresh_apps);
        disconnectBtn = findViewById(R.id.btn_disconnect);
        aboutBtn = findViewById(R.id.btn_about);
        quickCard = findViewById(R.id.quick_connect_card);
        tipsCard = findViewById(R.id.tips_card);
        recentList = findViewById(R.id.recent_list);
        quickNameText = findViewById(R.id.quick_device_name);
        quickMacText = findViewById(R.id.quick_device_mac);
        quickConnectBtn = findViewById(R.id.btn_quick_connect);
        sectionDevices = findViewById(R.id.section_devices);
        deviceInfoCard = findViewById(R.id.device_info_card);
        infoNameText = findViewById(R.id.info_name);
        infoBatteryText = findViewById(R.id.info_battery);
        refreshInfoBtn = findViewById(R.id.btn_refresh_device_info);

        logText.setMovementMethod(new ScrollingMovementMethod());

        prefs = new Prefs(this);
        // 复用跨 UI 生命周期的会话对象（后台保活中重开 App 时恢复连接与状态）
        if (sBle == null) {
            ble = new BleClient(getApplicationContext(), this);
            sBle = ble;
        } else {
            ble = sBle;
            ble.setListener(this);
        }
        session = sSession;
        bridge = sBridge;
        connectedMac = sConnectedMac;
        connectedName = sConnectedName;
        sessionStart = sSessionStart;
        if (session != null) session.setListener(this);
        if (bridge != null) bridge.setListener(this);

        quickConnectBtn.setOnClickListener(v -> quickConnect());

        bondedBtn.setOnClickListener(v -> refreshBonded());
        refreshAppsBtn.setOnClickListener(v -> {
            if (session != null && session.isAuthed()) {
                session.requestAppList();
                log("已请求刷新应用列表");
            } else {
                toast("设备未连接");
            }
        });
        disconnectBtn.setOnClickListener(v -> {
            log("用户主动断开连接");
            if (session != null) session.disconnect();
            // 主动断开：立即停止保活服务与通知栏
            cn.b4qaq.simplefetchdroid.service.KeepAliveService.stop(this);
            setStatus("已断开");
        });
        aboutBtn.setOnClickListener(v -> openAbout());

        refreshInfoBtn.setOnClickListener(v -> {
            if (session != null && session.isAuthed()) {
                requestDeviceInfo();
            } else {
                toast("设备未连接");
            }
        });

        log("SimpleFetchDroid v1.5 已启动");
        log("流程：从「已配对设备」选择 → 输入 AuthKey → 连接 → 管理快应用桥接");
        log("AuthKey 获取：小米运动健康 → 设备详情 → 开发者选项 → AuthKey");

        renderQuickConnect();
        renderRecent();
        renderDeviceInfo();

        if (session != null && session.isAuthed()) {
            // 恢复已连接会话的界面（后台保活中重新打开 App）
            log("检测到存活的会话，已恢复连接状态");
            setStatus("已连接 · 认证成功");
            sectionDevices.setVisibility(View.GONE);
            quickCard.setVisibility(View.GONE);
            tipsCard.setVisibility(View.GONE);
            bondedBtn.setVisibility(View.GONE);
            refreshAppsBtn.setVisibility(View.VISIBLE);
            disconnectBtn.setVisibility(View.VISIBLE);
            deviceInfoCard.setVisibility(View.VISIBLE);
            refreshInfoBtn.setVisibility(View.VISIBLE);
            session.requestAppList();
            requestDeviceInfo();
            renderRecent(); // 恢复后台会话后刷新「最近应用」，绑定点击重连监听
        } else if (session != null && session.getState() != DeviceSession.State.DISCONNECTED) {
            setStatus("连接恢复中…");
        } else if (hasPermissions()) {
            refreshBonded();
        } else {
            requestPermissions();
        }
        // 未连接时显示使用提示卡，已连接（含上文分支）则已在上面隐藏
        if (session == null || !session.isAuthed()) {
            tipsCard.setVisibility(View.VISIBLE);
        }
    }

    // ==================== 记住设备（快速连接） ====================

    /** 有记住的设备时显示快速连接卡片。 */
    private void renderQuickConnect() {
        String mac = prefs.getLastDeviceMac();
        if (mac.isEmpty() || isDeviceActive()) {
            quickCard.setVisibility(View.GONE);
            return;
        }
        quickNameText.setText(prefs.getLastDeviceName().isEmpty() ? mac : prefs.getLastDeviceName());
        quickMacText.setText(mac);
        quickCard.setVisibility(View.VISIBLE);
    }

    private boolean isDeviceActive() {
        return session != null && session.isAuthed();
    }

    /** 一键连接上次设备：有 AuthKey 直接连，没有则弹输入框。 */
    private void quickConnect() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        String mac = prefs.getLastDeviceMac();
        BluetoothDevice dev = ble.getRemoteDevice(mac);
        if (dev == null) {
            toast("无法获取设备: " + mac);
            return;
        }
        String key = prefs.getAuthKey(mac);
        if (key.isEmpty()) {
            promptAuthKey(dev, prefs.getLastDeviceName().isEmpty() ? mac : prefs.getLastDeviceName());
        } else {
            doConnect(dev, key);
        }
    }

    // ==================== 权限 ====================

    private boolean hasPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN);
            list.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        // 后台保活的常驻通知需要通知权限（Android 13+）
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        requestPermissions(list.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && hasPermissions()) {
            main.postDelayed(this::refreshBonded, 300);
        }
    }

    // ==================== 已配对设备 ====================

    /**
     * 列出系统蓝牙中已配对（绑定）的设备并渲染到设备区。不再进行广播扫描，
     * 仅依赖系统已配对列表，避免手动开启位置信息与 ROM 扫描过滤异常。
     */
    private void refreshBonded() {
        if (session != null && session.isAuthed()) return; // 已连接则无需展示
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        scanRows.clear();
        scanList.removeAllViews();
        foundDevices.clear();
        java.util.Set<BluetoothDevice> bonded = ble.getBondedDevices();
        if (bonded == null || bonded.isEmpty()) {
            addEmptyRow("没有已配对设备 —— 请在手机「设置 → 蓝牙」中配对手表后点「刷新已配对设备」");
            log("系统无已配对设备");
            return;
        }
        List<BluetoothDevice> list = new java.util.ArrayList<>(bonded);
        java.util.Collections.sort(list, (a, b) ->
                Integer.compare(typeRank(BleClient.deviceType(a)), typeRank(BleClient.deviceType(b))));
        int n = 0;
        for (BluetoothDevice d : list) {
            if (BleClient.deviceType(d) == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue; // 耳机/车载，GATT 连不了
            addDeviceRow(d, d.getName() != null ? d.getName() : "", 127, true);
            n++;
        }
        if (n == 0) {
            addEmptyRow("已配对设备均为经典蓝牙（耳机/车载），无法用 GATT 连接手环/手表");
        } else {
            log("已加载 " + n + " 台已配对设备，点击「连接」并输入 AuthKey");
        }
    }

    /** 在设备区显示一行占位提示（无设备时）。 */
    private void addEmptyRow(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.md_on_surface_variant));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        scanList.addView(t, lp);
    }

    /** BLE/双模排最前，UNKNOWN 次之，CLASSIC（耳机/车载）最后。 */
    private static int typeRank(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_LE: return 0;
            case BluetoothDevice.DEVICE_TYPE_DUAL: return 1;
            case BluetoothDevice.DEVICE_TYPE_UNKNOWN: return 2;
            default: return 3; // CLASSIC
        }
    }

    private static String typeName(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_LE: return "BLE";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "双模";
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "经典蓝牙";
            default: return "未知类型";
        }
    }

    /** Android 11 及以下扫描 BLE 需要「位置信息」系统开关处于开启状态。 */
    private boolean isLocationEnabled() {
        try {
            android.location.LocationManager lm =
                    (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm == null) return true;
            return lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
                    || lm.isProviderEnabled(android.location.LocationManager.PASSIVE_PROVIDER);
        } catch (Exception e) {
            return true; // 无法判断时不阻塞扫描
        }
    }

    @Override
    public void onScanResult(BluetoothDevice device, String name, int rssi) {
        String mac = device.getAddress();
        if (foundDevices.containsKey(mac)) {
            // 已在列表中：若此前无名字而现在广播出了名字，则更新显示
            TextView label = scanRows.get(mac);
            if (label != null && name != null && !name.isEmpty()) {
                String current = (String) label.getTag();
                if (current == null || current.isEmpty()) {
                    label.setTag(name);
                    String rssiText = rssi == 127 ? "信号未知" : rssi + " dBm";
                    label.setText(String.format(Locale.getDefault(), "%s\n%s  (%s)", name, mac, rssiText));
                }
            }
            return;
        }
        addDeviceRow(device, name, rssi, false);
    }

    /** 添加设备条目。rssi==127 表示无信号值（如已配对设备）；bonded=true 追加「已配对」标记。 */
    private void addDeviceRow(BluetoothDevice device, String name, int rssi, boolean bonded) {
        String mac = device.getAddress();
        foundDevices.put(mac, device);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));

        TextView label = new TextView(this);
        String shown = name == null || name.isEmpty() ? mac : name;
        String suffix = bonded ? "（已配对）" : "";
        label.setTag(name == null || name.isEmpty() ? "" : name);
        String rssiText = rssi == 127 ? "已配对 · 直连" : rssi + " dBm";
        label.setText(String.format(Locale.getDefault(), "%s%s\n%s  (%s)", shown, suffix, mac, rssiText));
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.md_on_surface));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btn = new Button(this, null, 0, R.style.Widget_SFD_Button);
        btn.setText(R.string.connect);
        btn.setOnClickListener(v -> promptAuthKey(device, shown));

        row.addView(label);
        row.addView(btn);
        row.setBackgroundResource(bonded ? R.drawable.card_bg_accent : R.drawable.card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        scanList.addView(row, lp);
        scanRows.put(mac, label);
        if (name != null && !name.isEmpty()) {
            log("设备: " + name + suffix + " (" + mac + ")");
        }
    }

    private void promptAuthKey(BluetoothDevice device, String name) {
        ble.stopScan();
        String saved = prefs.getAuthKey(device.getAddress());
        EditText input = new EditText(this);
        input.setHint("32 位十六进制 AuthKey");
        input.setText(saved);
        input.setTextSize(14);

        new AlertDialog.Builder(this)
                .setTitle(name)
                .setMessage("输入该设备的 AuthKey（小米运动健康 → 设备 → 开发者选项）")
                .setView(input)
                .setPositiveButton("连接", (d, w) -> {
                    String key = input.getText().toString().trim();
                    if (key.replaceAll("[0-9a-fA-F]", "").replace("0x", "").length() > 0) {
                        toast("AuthKey 格式不正确（应为 32 位十六进制）");
                        return;
                    }
                    prefs.saveAuthKey(device.getAddress(), key);
                    doConnect(device, key);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void doConnect(BluetoothDevice device, String authKey) {
        // 清理旧会话
        if (bridge != null) {
            bridge.shutdown();
            bridge = null;
            sBridge = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
            sSession = null;
        }
        connectedMac = device.getAddress();
        connectedName = device.getName() != null ? device.getName() : device.getAddress();
        sConnectedMac = connectedMac;
        sConnectedName = connectedName;
        // 记住此设备（下次启动一键连接）
        prefs.saveLastDevice(connectedMac, connectedName);
        setStatus("连接中…");
        log("连接设备: " + connectedName + " (" + connectedMac + ")");

        session = new DeviceSession(ble, this, authKey);
        sSession = session;
        bridge = new SfBridge(session, this);
        sBridge = bridge;
        ble.connect(device);
    }

    // ==================== BleClient.Listener（转发给 DeviceSession） ====================

    @Override
    public void onConnected(String deviceName) {
        if (session != null) session.onConnected(deviceName);
        runOnUiThread(() -> setStatus("已连接 · 会话握手中"));
    }

    @Override
    public void onDisconnected() {
        // 复位会话状态机（不递归回调自身）；保活服务由其 15 秒自检自动停止
        if (session != null) session.onBleDisconnected();
        runOnUiThread(() -> {
            setStatus("已断开");
            if (bridge != null) bridge.shutdown();
            log("设备连接已断开");
            persistStats();
            sessionStart = 0;
            sSessionStart = 0;
            // 恢复设备选择区与快速连接卡片
            sectionDevices.setVisibility(View.VISIBLE);
            renderQuickConnect();
            if (bondedBtn != null) bondedBtn.setVisibility(View.VISIBLE);
            if (refreshAppsBtn != null) refreshAppsBtn.setVisibility(View.GONE);
            if (disconnectBtn != null) disconnectBtn.setVisibility(View.GONE);
            if (deviceInfoCard != null) deviceInfoCard.setVisibility(View.GONE);
            if (refreshInfoBtn != null) refreshInfoBtn.setVisibility(View.GONE);
            if (tipsCard != null) tipsCard.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void onMtu(int mtu) {
        if (session != null) session.onMtu(mtu);
    }

    @Override
    public void onData(int channel, int opcode, byte[] payload) {
        if (session != null) session.onData(channel, opcode, payload);
    }

    @Override
    public void onSessionConfig(int opcode) {
        if (session != null) session.onSessionConfig(opcode);
    }

    @Override
    public void onError(String stage, String message) {
        if (session != null) session.onError(stage, message);
        runOnUiThread(() -> {
            log("蓝牙错误[" + stage + "]: " + message);
            switch (stage) {
                case "reconnect": // 连接失败，正在自动重连
                    setStatus("连接失败 · 自动重连中");
                    break;
                case "connect": // 重试次数用尽仍失败
                case "disconnect": // 设备主动断开（多为被抢占/拒绝）
                    setStatus("连接失败");
                    toast(message);
                    break;
                case "chars":
                    setStatus("不支持的设备");
                    toast(message);
                    break;
                default:
                    break;
            }
        });
    }

    // ==================== DeviceSession.Listener ====================

    @Override
    public void onStateChanged(DeviceSession.State state) {
        switch (state) {
            case SESSION_INIT: setStatus("已连接 · 会话握手中"); break;
            case AUTHENTICATING: setStatus("认证中…"); break;
            case AUTHED: {
                setStatus("已连接 · 认证成功");
                sessionStart = System.currentTimeMillis();
                break;
            }
            case DISCONNECTED: setStatus("已断开"); break;
            default: break;
        }
    }

    @Override
    public void onAuthResult(boolean ok, String message) {
        runOnUiThread(() -> {
            if (ok) {
                toast("认证成功");
                sessionStart = System.currentTimeMillis();
                sSessionStart = sessionStart;
                // 认证成功：记住设备、隐藏设备选择区、启动后台保活服务
                prefs.saveLastDevice(connectedMac, connectedName);
                sectionDevices.setVisibility(View.GONE);
                quickCard.setVisibility(View.GONE);
                tipsCard.setVisibility(View.GONE);
                bondedBtn.setVisibility(View.GONE);
                refreshAppsBtn.setVisibility(View.VISIBLE);
                disconnectBtn.setVisibility(View.VISIBLE);
                deviceInfoCard.setVisibility(View.VISIBLE);
                refreshInfoBtn.setVisibility(View.VISIBLE);
                cn.b4qaq.simplefetchdroid.service.KeepAliveService.start(this);
                log("已进入后台保活模式：退出界面后仍会继续处理快应用请求");
                // 认证成功后读取设备信息（电量/名称）
                requestDeviceInfo();
                // 认证成功后再渲染「最近应用」，确保点击监听已绑定（可点击重连）
                renderRecent();
            } else {
                toast(message);
                setStatus("认证失败");
            }
            log(message);
        });
    }

    @Override
    public void onAppList(List<Wear.AppItem> items) {
        runOnUiThread(() -> {
            apps.clear();
            for (Wear.AppItem a : items) {
                apps.put(a.packageName, a);
            }
            renderApps();
            log("获取到 " + items.size() + " 个快应用");
        });
    }

    @Override
    public void onAppOnline(String pkg) {
        // 已成功桥接过的快应用再次上线：立即断开（与点击「断开」一致），预约等待约 3 秒后
        // 再重新执行完整连接（与点击「连接」一致）——无论之前是心跳超时、手表主动关闭，还是应用重启。
        // 冷却去重：本机发起连接后应用启动会上报 basic_info（≈「上线」），该信号在冷却窗口内忽略，
        // 否则会陷入「上线→断开→连接→上线→断开…」的死循环。
        if (!autoBridgePkgs.contains(pkg) || bridge == null || session == null || !session.isAuthed()) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastConnectAt.get(pkg);
        if (last != null && now - last < APP_ONLINE_COOLDOWN_MS) {
            log("检测到「" + pkg + "」上线（本机连接启动后的信号），忽略");
            return;
        }
        lastConnectAt.put(pkg, now); // 锁定冷却窗口，抑制本次重连引发的重复上线
        log("检测到「" + pkg + "」上线，先断开桥接");
        bridge.disconnectApp(pkg); // 立即断开（与点击「断开」一致）
        final SfBridge b = bridge;
        final String fp = pkg;
        // 预约约 3 秒后再重新连接（与点击「连接」一致），给设备/应用留出稳定时间
        main.postDelayed(() -> {
            if (b != null && session != null && session.isAuthed()
                    && autoBridgePkgs.contains(fp)
                    && bridge.getStatus(fp) != SfBridge.Status.CONNECTED
                    && bridge.getStatus(fp) != SfBridge.Status.HANDSHAKING) {
                log("3 秒后重新连接「" + pkg + "」");
                bridgeConnect(fp); // 启动应用 → SF 握手（与用户点击「连接」一致）
            }
        }, 3000);
    }

    @Override
    public void onInterconnectMessage(String pkgName, String text) {
        if (bridge != null) bridge.onMessage(pkgName, text);
    }

    @Override
    public void onLog(String line) {
        log(line);
    }

    // ==================== SfBridge.Listener ====================

    @Override
    public void onStatusChanged(String pkg, SfBridge.Status status) {
        if (status == SfBridge.Status.CONNECTED) {
            // 桥接握手成功：记录到「最近应用」（仅成功才显示），并纳入自动重握手集合
            autoBridgePkgs.add(pkg);
            addRecentApp(pkg);
        }
        runOnUiThread(this::renderApps);
    }

    @Override
    public void onStatsChanged() {
        runOnUiThread(this::renderStats);
        long now = System.currentTimeMillis();
        if (now - lastStatsPersist > 10000) {
            lastStatsPersist = now;
            persistStats();
        }
    }

    // ==================== 渲染 ====================

    private void renderApps() {
        appList.removeAllViews();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(bridge != null && session != null && session.isAuthed()
                    ? "（点按上方“刷新应用”加载列表）" : "（设备认证后自动加载）");
            empty.setTextColor(getColor(R.color.md_on_surface_variant));
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            appList.addView(empty);
            return;
        }
        for (Wear.AppItem app : apps.values()) {
            appList.addView(buildAppRow(app));
        }
    }

    private View buildAppRow(Wear.AppItem app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.card_bg);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        String name = app.appName.isEmpty() ? app.packageName : app.appName;
        label.setText(name + "\n" + app.packageName);
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.md_on_surface));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        SfBridge.Status st = bridge != null ? bridge.getStatus(app.packageName) : SfBridge.Status.DISCONNECTED;
        Button btn = new Button(this, null, 0, R.style.Widget_SFD_Button);
        switch (st) {
            case CONNECTED:
                btn.setText(R.string.disconnect);
                btn.setOnClickListener(v -> disconnectAppBridge(app.packageName));
                break;
            case HANDSHAKING:
                btn.setText(R.string.handshaking);
                btn.setEnabled(false);
                break;
            case FAILED:
                btn.setText(R.string.retry);
                btn.setOnClickListener(v -> bridgeConnect(app.packageName));
                break;
            default:
                btn.setText(R.string.connect);
                btn.setOnClickListener(v -> bridgeConnect(app.packageName));
                break;
        }

        top.addView(label);
        top.addView(btn);

        TextView statusLine = new TextView(this);
        statusLine.setTextSize(11);
        switch (st) {
            case CONNECTED: statusLine.setText("● 桥接中"); statusLine.setTextColor(getColor(R.color.md_success)); break;
            case HANDSHAKING: statusLine.setText("◌ 握手中"); statusLine.setTextColor(getColor(R.color.md_warning)); break;
            case FAILED: statusLine.setText("✕ 握手失败"); statusLine.setTextColor(getColor(R.color.md_error)); break;
            default: statusLine.setText("○ 未连接"); statusLine.setTextColor(getColor(R.color.md_on_surface_variant)); break;
        }
        statusLine.setPadding(0, dp(4), 0, 0);

        row.addView(top);
        row.addView(statusLine);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        return row;
    }

    /** 手动断开某应用的桥接：移出自动重握手集合（避免反复重连用户主动断开的应用）。 */
    private void disconnectAppBridge(String pkg) {
        autoBridgePkgs.remove(pkg);
        if (bridge != null) bridge.disconnectApp(pkg);
    }

    /** 统一发起快应用连接：记录发起时间，供「快应用上线」信号冷却去重，避免自触发死循环。 */
    private void bridgeConnect(String pkg) {
        lastConnectAt.put(pkg, System.currentTimeMillis());
        if (bridge != null) bridge.connectApp(pkg);
    }

    private void renderStats() {
        if (bridge == null) return;
        long uptime = sessionStart > 0 ? (System.currentTimeMillis() - sessionStart) / 1000 : 0;
        String stats = String.format(Locale.getDefault(),
                "总请求 %d · 成功 %d · 失败 %d · 在线 %d:%02d",
                bridge.getTotalRequests(), bridge.getOkRequests(), bridge.getFailedRequests(),
                uptime / 60, uptime % 60);
        statsText.setText(stats);
    }

    // ==================== 最近应用（成功桥接的 4 个） ====================

    /** 桥接成功（Status.CONNECTED）时记录：置顶去重、最多 4 条、持久化。 */
    private void addRecentApp(String pkg) {
        Wear.AppItem app = apps.get(pkg);
        String name = (app != null && app.appName != null && !app.appName.isEmpty()) ? app.appName : pkg;
        try {
            org.json.JSONArray old = prefs.getRecentApps();
            java.util.List<org.json.JSONObject> keep = new java.util.ArrayList<>();
            for (int i = 0; i < old.length(); i++) {
                org.json.JSONObject o = old.getJSONObject(i);
                if (!o.optString("pkg", "").equals(pkg)) keep.add(o);
            }
            org.json.JSONArray next = new org.json.JSONArray();
            org.json.JSONObject entry = new org.json.JSONObject();
            entry.put("pkg", pkg);
            entry.put("name", name);
            next.put(entry);
            int n = 0;
            for (org.json.JSONObject o : keep) {
                if (n++ >= 3) break; // 加上新置顶共 4 条
                next.put(o);
            }
            prefs.saveRecentApps(next);
        } catch (Exception e) {
            log("最近应用记录失败: " + e.getMessage());
        }
        renderRecent();
    }

    /** 渲染「最近应用」区：最多显示 4 个成功桥接的应用，点击可重连。 */
    private void renderRecent() {
        if (recentList == null) return;
        recentList.removeAllViews();
        org.json.JSONArray arr = prefs.getRecentApps();
        if (arr.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText(R.string.recent_empty);
            empty.setTextColor(getColor(R.color.md_on_surface_variant));
            empty.setTextSize(13);
            empty.setPadding(dp(8), dp(12), dp(8), dp(12));
            recentList.addView(empty);
            return;
        }
        int count = Math.min(arr.length(), 4);
        for (int i = 0; i < count; i++) {
            final String pkg;
            String name;
            try {
                org.json.JSONObject o = arr.getJSONObject(i);
                pkg = o.optString("pkg", "");
                name = o.optString("name", pkg);
            } catch (Exception e) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackgroundResource(R.drawable.card_bg);

            TextView label = new TextView(this);
            label.setText(name + "\n" + pkg);
            label.setTextSize(13);
            label.setTextColor(getColor(R.color.md_on_surface));
            row.addView(label);

            TextView hint = new TextView(this);
            hint.setText("● 已成功桥接 · 点击重连");
            hint.setTextSize(11);
            hint.setTextColor(getColor(R.color.md_success));
            hint.setPadding(0, dp(4), 0, 0);
            row.addView(hint);

            final String fp = pkg;
            // 点击「最近应用」即重新桥接对应应用（与「快应用」列表里的「连接」一致）。
            // 监听始终绑定，未连接设备时给出提示而不是静默无反应。
            row.setOnClickListener(v -> {
                if (bridge != null && session != null && session.isAuthed()) {
                    bridgeConnect(fp);
                    toast("正在桥接: " + name);
                } else {
                    toast("设备未连接，请先连接设备");
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            recentList.addView(row, lp);
        }
    }

    // ==================== 设备信息 ====================

    /** 读取设备基础信息（电量/序列号/系统版本/名称），结果写入静态 sDeviceInfo 并刷新界面。 */
    private void requestDeviceInfo() {
        if (ble == null || !ble.isReady()) return;
        log("正在读取设备信息（标准 GATT 服务）…");
        ble.readDeviceInfo(new BleClient.DeviceInfoListener() {
            @Override
            public void onDeviceInfo(BleClient.DeviceInfo info) {
                sDeviceInfo = info;
                runOnUiThread(() -> {
                    renderDeviceInfo();
                    log("设备信息：名称 " + (info.name.isEmpty() ? "未知" : info.name)
                            + " · 电量 " + (info.battery >= 0 ? info.battery + "%" : "未知"));
                });
            }

            @Override
            public void onDeviceInfoError(String message) {
                log("设备信息读取失败: " + message);
            }
        });
    }

    /** 将 sDeviceInfo 渲染到设备信息卡片（视图为空或界面已销毁时安全跳过）。 */
    private void renderDeviceInfo() {
        if (deviceInfoCard == null || isFinishing() || isDestroyed()) return;
        BleClient.DeviceInfo info = sDeviceInfo;
        if (info == null) {
            infoNameText.setText(R.string.info_unknown);
            infoBatteryText.setText(R.string.info_unknown);
            return;
        }
        infoNameText.setText(info.name.isEmpty() ? getString(R.string.info_unknown) : info.name);
        infoBatteryText.setText(info.battery >= 0 ? (info.battery + "%") : getString(R.string.info_unknown));
    }

    // ==================== 关于 ====================

    /** 打开「关于」大页面（列出开源项目 + 跳转本项目开源代码）。 */
    private void openAbout() {
        startActivity(new android.content.Intent(this, AboutActivity.class));
    }

    private void setStatus(String s) {
        statusText.setText(String.format("%s%s", s,
                connectedName.isEmpty() ? "" : "  ·  " + connectedName));
    }

    // ==================== 日志 ====================

    private void log(String line) {
        runOnUiThread(() -> {
            String entry = timeFmt.format(new Date()) + "  " + line;
            logBuffer.addLast(entry);
            while (logBuffer.size() > 400) logBuffer.removeFirst();
            StringBuilder sb = new StringBuilder();
            for (String l : logBuffer) sb.append(l).append('\n');
            logText.setText(sb.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void persistStats() {
        if (bridge == null) return;
        // 断开时把当前会话统计累计到历史
        prefs.addStats(bridge.getTotalRequests(), bridge.getOkRequests(), bridge.getFailedRequests());
    }

    @Override
    protected void onDestroy() {
        boolean keepAlive = session != null && session.isAuthed();
        if (keepAlive) {
            // 会话仍活跃：切换为静默监听（后台继续处理请求，UI 销毁不影响 BLE/桥接）
            DetachedListener dummy = new DetachedListener();
            ble.setListener(dummy);
            session.setListener(dummy);
            bridge.setListener(dummy);
            log("界面已退出，后台继续为快应用处理请求");
        } else {
            // 无活跃会话：完整清理
            if (bridge != null) {
                bridge.shutdown();
                sBridge = null;
            }
            if (session != null) {
                session.disconnect();
                sSession = null;
            }
            if (ble != null) {
                ble.close();
                sBle = null;
            }
            sConnectedMac = "";
            sConnectedName = "";
            sSessionStart = 0;
            cn.b4qaq.simplefetchdroid.service.KeepAliveService.stop(this);
        }
        super.onDestroy();
    }
}
