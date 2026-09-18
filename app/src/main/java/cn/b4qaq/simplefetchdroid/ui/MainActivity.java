package cn.b4qaq.simplefetchdroid.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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
import java.util.ArrayList;
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
    private LinearLayout appList;
    private TextView logText;
    private ScrollView logScroll;
    private Button refreshAppsBtn;
    private Button aboutBtn;
    private LinearLayout tipsCard;
    private LinearLayout recentList;
    private LinearLayout deviceInfoCard;
    private TextView infoNameText;
    private TextView infoBatteryText;
    private Button refreshInfoBtn;

    // 图1 Hero 连接卡片
    private LinearLayout heroCard;
    private TextView heroPill;
    private TextView heroTitle;
    private TextView heroSubtitle;
    private LinearLayout heroDisconnected;
    private EditText heroMacEdit;
    private Button heroScanBtn;
    private Button heroConnectBtn;
    private Button heroDisconnectBtn;
    private LinearLayout heroContinue;
    private TextView heroContinueText;
    private LinearLayout heroConnecting;
    private Button heroCancelBtn;

    // 图2 设备选择面板（全屏覆盖）
    private LinearLayout devicePanel;
    private android.widget.ImageButton panelBackBtn;
    private LinearLayout panelTopBar;
    private LinearLayout panelPairedList;
    private LinearLayout panelAddBtn;
    private LinearLayout panelNearbyList;
    private LinearLayout panelRescanBtn;
    /** 面板是否处于打开态（含进出场动画期间），用于返回键拦截。 */
    private boolean panelOpen = false;

    // 顶部红色异常横幅（「应用列表为空 + 电量不可读」时显示）
    private TextView alertBanner;

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
    /** 连接健康度：应用列表数量与电量读取结果（两者皆不可读才弹异常横幅）。 */
    private int appListCount = 0;
    private int batteryValue = -1; // -1=未读取，0=设备未提供（视为不可读）
    /** 两项读取是否已完成（避免先返回的一项单独触发横幅而「闪现」）。 */
    private boolean appListResolved = false;
    private boolean batteryResolved = false;
    /** 认证成功后调度一次健康度检测，等待电量/应用列表读取完成。 */
    private static final long HEALTH_CHECK_DELAY_MS = 5000;
    /** 应用上线后重连重握手的间隔（v1.5.15 由 3 秒改为 1.5 秒）。 */
    private static final long REBRIDGE_DELAY_MS = 1500;

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
        appList = findViewById(R.id.app_list);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.log_scroll);
        refreshAppsBtn = findViewById(R.id.btn_refresh_apps);
        aboutBtn = findViewById(R.id.btn_about);
        tipsCard = findViewById(R.id.tips_card);
        recentList = findViewById(R.id.recent_list);
        deviceInfoCard = findViewById(R.id.device_info_card);
        infoNameText = findViewById(R.id.info_name);
        infoBatteryText = findViewById(R.id.info_battery);
        refreshInfoBtn = findViewById(R.id.btn_refresh_device_info);
        alertBanner = findViewById(R.id.alert_banner);

        // 图1 Hero 卡片
        heroCard = findViewById(R.id.hero_card);
        heroPill = findViewById(R.id.hero_pill);
        heroTitle = findViewById(R.id.hero_title);
        heroSubtitle = findViewById(R.id.hero_subtitle);
        heroDisconnected = findViewById(R.id.hero_disconnected);
        heroMacEdit = findViewById(R.id.hero_mac_edit);
        heroScanBtn = findViewById(R.id.hero_scan_btn);
        heroConnectBtn = findViewById(R.id.hero_connect_btn);
        heroDisconnectBtn = findViewById(R.id.hero_disconnect_btn);
        heroContinue = findViewById(R.id.hero_continue);
        heroContinueText = findViewById(R.id.hero_continue_text);
        heroConnecting = findViewById(R.id.hero_connecting);
        heroCancelBtn = findViewById(R.id.hero_cancel_btn);

        // 图2 设备选择面板
        devicePanel = findViewById(R.id.device_panel);
        panelBackBtn = findViewById(R.id.panel_back_btn);
        panelTopBar = findViewById(R.id.panel_top_bar);
        panelPairedList = findViewById(R.id.panel_paired_list);
        panelAddBtn = findViewById(R.id.panel_add_btn);
        panelNearbyList = findViewById(R.id.panel_nearby_list);
        panelRescanBtn = findViewById(R.id.panel_rescan_btn);

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

        heroScanBtn.setOnClickListener(v -> openDevicePanel());
        heroConnectBtn.setOnClickListener(v -> connectByMac(heroMacEdit.getText().toString().trim()));
        heroDisconnectBtn.setOnClickListener(v -> {
            log("用户主动断开连接");
            if (session != null) session.disconnect();
            // 主动断开：立即停止保活服务与通知栏
            cn.b4qaq.simplefetchdroid.service.KeepAliveService.stop(this);
            setStatus("已断开");
        });
        heroContinue.setOnClickListener(v -> continueLastDevice());
        heroCancelBtn.setOnClickListener(v -> cancelConnection());
        panelBackBtn.setOnClickListener(v -> closeDevicePanel());
        panelAddBtn.setOnClickListener(v -> openAuthKeyTool());
        panelRescanBtn.setOnClickListener(v -> startPanelScan());

        refreshAppsBtn.setOnClickListener(v -> {
            if (session != null && session.isAuthed()) {
                session.requestAppList();
                log("已请求刷新应用列表");
            } else {
                toast("设备未连接");
            }
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
        log("流程：首页「扫描」进入设备选择页 → 已配对/附近设备 → 输入 AuthKey → 连接 → 管理快应用桥接");
        log("AuthKey 获取：小米运动健康 → 设备详情 → 开发者选项 → AuthKey");

        renderRecent();
        renderDeviceInfo();
        updateHeroStatus(session != null && session.isAuthed());

        if (session != null && session.isAuthed()) {
            // 恢复已连接会话的界面（后台保活中重新打开 App）
            log("检测到存活的会话，已恢复连接状态");
            setStatus("已连接 · 认证成功");
            tipsCard.setVisibility(View.GONE);
            refreshAppsBtn.setVisibility(View.VISIBLE);
            deviceInfoCard.setVisibility(View.VISIBLE);
            refreshInfoBtn.setVisibility(View.VISIBLE);
            session.requestAppList();
            requestDeviceInfo();
            renderRecent(); // 恢复后台会话后刷新「最近应用」，绑定点击重连监听
            scheduleHealthCheck(); // 恢复会话后也做一次健康度检测
        } else if (session != null && session.getState() != DeviceSession.State.DISCONNECTED) {
            setStatus("连接恢复中…");
            showConnectingState(connectedName);
        } else if (!hasPermissions()) {
            requestPermissions();
        }
        // 未连接时显示使用提示卡，已连接（含上文分支）则已在上面隐藏
        if (session == null || !session.isAuthed()) {
            tipsCard.setVisibility(View.VISIBLE);
        }

        // Hero 卡片入场动效：轻微上移 + 淡入，提升首页质感
        heroCard.setAlpha(0f);
        heroCard.setTranslationY(dp(18));
        heroCard.animate().alpha(1f).translationY(0f)
                .setDuration(360).setInterpolator(new DecelerateInterpolator()).start();
    }

    // ==================== 返回键拦截 ====================

    /** 面板打开时，返回键 = 关闭面板回到首页；否则正常退出。 */
    @Override
    public void onBackPressed() {
        if (panelOpen && devicePanel != null && devicePanel.getVisibility() == View.VISIBLE) {
            closeDevicePanel();
        } else {
            super.onBackPressed();
        }
    }

    // ==================== 图1 Hero 卡片状态 ====================

    /**
     * 根据是否已连接刷新 Hero 卡片的外观：
     *  - 未连接：显示「未连接」灰色药丸、MAC 输入与「扫描 / 连接」按钮；
     *  - 已连接：显示「已连接」绿色药丸与「断开」按钮，隐藏 MAC 输入。
     */
    private void updateHeroStatus(boolean connected) {
        if (heroCard == null) return;
        // 连接中区块默认隐藏，仅 showConnectingState 时显示
        if (heroConnecting != null) heroConnecting.setVisibility(View.GONE);
        if (connected) {
            heroPill.setText(R.string.hero_status_on);
            heroPill.setTextColor(getColor(R.color.md_success));
            heroTitle.setText(connectedName.isEmpty() ? getString(R.string.hero_status_on) : connectedName);
            heroSubtitle.setText(R.string.hero_connected_hint);
            heroDisconnected.setVisibility(View.GONE);
            heroDisconnectBtn.setVisibility(View.VISIBLE);
            if (heroContinue != null) heroContinue.setVisibility(View.GONE);
        } else {
            heroPill.setText(R.string.hero_status_off);
            heroPill.setTextColor(getColor(R.color.md_on_surface_variant));
            heroTitle.setText(R.string.hero_title);
            heroSubtitle.setText(R.string.hero_subtitle);
            heroDisconnected.setVisibility(View.VISIBLE);
            heroDisconnectBtn.setVisibility(View.GONE);
            // 有上次连接的设备时显示「或让我们继续」，点击直接重连
            String lastMac = prefs.getLastDeviceMac();
            if (heroContinue != null) {
                if (!lastMac.isEmpty()) {
                    String lastName = prefs.getLastDeviceName();
                    heroContinueText.setText(getString(R.string.hero_continue) + " · "
                            + (lastName.isEmpty() ? lastMac : lastName));
                    heroContinue.setVisibility(View.VISIBLE);
                } else {
                    heroContinue.setVisibility(View.GONE);
                }
            }
        }
    }

    /** 进入「连接中」视觉态：显示「连接[设备名]中」、进度条与取消键，隐藏输入/扫描/连接/重连选项。 */
    private void showConnectingState(String name) {
        if (heroCard == null) return;
        heroPill.setText(R.string.hero_status_connecting);
        heroPill.setTextColor(getColor(R.color.md_primary));
        heroTitle.setText(getString(R.string.hero_connecting,
                name == null || name.isEmpty() ? getString(R.string.hero_status_off) : name));
        heroSubtitle.setText(R.string.hero_connecting_hint);
        heroDisconnected.setVisibility(View.GONE);
        heroDisconnectBtn.setVisibility(View.GONE);
        if (heroContinue != null) heroContinue.setVisibility(View.GONE);
        if (heroConnecting != null) heroConnecting.setVisibility(View.VISIBLE);
    }

    /** 连接中取消：断开会话并回到未连接态（兜底复位 Hero，避免进度条停留）。 */
    private void cancelConnection() {
        log("用户取消连接");
        if (session != null) session.disconnect();
        // 主动取消：立即停止保活服务与通知栏
        cn.b4qaq.simplefetchdroid.service.KeepAliveService.stop(this);
        connectedMac = "";
        connectedName = "";
        sConnectedMac = "";
        sConnectedName = "";
        setStatus("已断开");
        updateHeroStatus(false); // onDisconnected 也会复位，这里兜底确保退出连接中态
    }

    /** 「获取AuthKey」：弹窗确认后跳转浏览器打开 AuthKey 获取工具页。 */
    private void openAuthKeyTool() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.authkey_tool_title)
                .setMessage(R.string.authkey_tool_msg)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://install.azki.ai/authkey-tool"));
                        startActivity(i);
                    } catch (Exception e) {
                        toast("无法打开浏览器: " + e.getMessage());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** 「或让我们继续」：用上次连接的设备直接重连（AuthKey 已保存则免输入）。 */
    private void continueLastDevice() {
        String mac = prefs.getLastDeviceMac();
        if (mac.isEmpty()) return;
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        BluetoothDevice dev = ble.getRemoteDevice(mac);
        if (dev == null) {
            toast("无法获取设备: " + mac);
            return;
        }
        String name = prefs.getLastDeviceName();
        String key = prefs.getAuthKey(mac);
        if (!key.isEmpty()) {
            // 已保存 AuthKey：自动填写并直接重连
            doConnect(dev, key);
        } else {
            promptAuthKey(dev, name.isEmpty() ? mac : name);
        }
    }

    // ==================== 图2 设备选择面板 ====================

    /** 打开全屏设备选择面板：延伸到状态栏（同色无缝）、渲染已配对设备并滑入。 */
    private void openDevicePanel() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        panelOpen = true;
        // 全屏延伸到状态栏/导航栏区域，并加内边距避让，防止被系统栏遮挡
        applyPanelFullscreen(true);
        renderPairedDevices();
        // 底部滑入 + 淡入动画
        int slide = getResources().getDisplayMetrics().heightPixels;
        devicePanel.setVisibility(View.VISIBLE);
        devicePanel.setAlpha(0.92f);
        devicePanel.setTranslationY(slide);
        devicePanel.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::startPanelScan)
                .start();
    }

    /** 关闭设备选择面板：滑出动画结束后隐藏并还原全屏状态、停止扫描。 */
    private void closeDevicePanel() {
        if (devicePanel.getVisibility() != View.VISIBLE) {
            applyPanelFullscreen(false);
            panelOpen = false;
            return;
        }
        int slide = getResources().getDisplayMetrics().heightPixels;
        devicePanel.animate()
                .alpha(0.92f)
                .translationY(slide)
                .setDuration(260)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    devicePanel.setVisibility(View.GONE);
                    devicePanel.setTranslationY(0f);
                    applyPanelFullscreen(false);
                    panelOpen = false;
                })
                .start();
        ble.stopScan();
    }

    /** 面板全屏态：进入时让窗口延伸到状态栏/导航栏（状态栏同色无缝），退出时还原并加系统栏内边距避让。 */
    private void applyPanelFullscreen(boolean enter) {
        Window w = getWindow();
        if (enter) {
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        int sb = enter ? getStatusBarHeight() : 0;
        int nb = enter ? getNavBarHeight() : 0;
        if (panelTopBar != null) {
            panelTopBar.setPadding(panelTopBar.getPaddingLeft(), sb + dp(14),
                    panelTopBar.getPaddingRight(), panelTopBar.getPaddingBottom());
        }
        if (devicePanel != null) {
            devicePanel.setPadding(0, 0, 0, nb);
        }
    }

    /** 状态栏高度（dp），用于面板头部避让。 */
    private int getStatusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return getResources().getDimensionPixelSize(resId);
        return dp(24);
    }

    /** 导航栏高度（dp），用于面板底部避让（全面屏手势下通常为 0）。 */
    private int getNavBarHeight() {
        int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) return getResources().getDimensionPixelSize(resId);
        return 0;
    }

    /** 面板「+」手动添加：弹输入框输入 MAC，校验后进入 AuthKey 录入。 */
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
            // 权限授予后：若设备选择面板已打开则刷新并扫描，否则无需处理（首页 Hero 不受影响）
            if (devicePanel != null && devicePanel.getVisibility() == View.VISIBLE) {
                main.postDelayed(this::openDevicePanel, 300);
            }
        }
    }

    // ==================== 图2 已配对设备（系统已配对 + 应用已保存 AuthKey 合并去重） ====================

    /**
     * 渲染「已配对设备」列表：合并「系统蓝牙已配对」与「应用已保存 AuthKey」两套来源，
     * 按 MAC 去重。系统已配对设备直接取 BluetoothDevice；仅应用保存的记录（未在系统配对）
     * 通过 MAC 构造远程设备对象。点击任一设备 → 输入 AuthKey → 连接。
     */
    private void renderPairedDevices() {
        if (panelPairedList == null) return;
        panelPairedList.removeAllViews();

        // 系统已配对设备：MAC -> BluetoothDevice（按类型排序，跳过经典蓝牙）
        java.util.Map<String, BluetoothDevice> bondedMap = new java.util.LinkedHashMap<>();
        java.util.Set<BluetoothDevice> bonded = ble.getBondedDevices();
        if (bonded != null) {
            List<BluetoothDevice> list = new java.util.ArrayList<>(bonded);
            java.util.Collections.sort(list, (a, b) ->
                    Integer.compare(typeRank(BleClient.deviceType(a)), typeRank(BleClient.deviceType(b))));
            for (BluetoothDevice d : list) {
                if (BleClient.deviceType(d) == BluetoothDevice.DEVICE_TYPE_CLASSIC) continue; // 耳机/车载，GATT 连不了
                bondedMap.put(d.getAddress(), d);
            }
        }
        // 合并「应用已保存 AuthKey」的设备（补全系统未配对的），按 MAC 去重
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        merged.addAll(bondedMap.keySet());
        merged.addAll(prefs.getSavedDeviceMacs());

        if (merged.isEmpty()) {
            addEmptyRow(panelPairedList, getString(R.string.panel_paired_empty));
            log("已配对设备为空（系统未配对且无已保存记录）");
            return;
        }

        // 排序：已完成连接（已保存 AuthKey）的设备置顶，其次系统已配对，最后按 MAC
        List<String> macs = new java.util.ArrayList<>(merged);
        java.util.Collections.sort(macs, (a, b) -> {
            boolean sa = !prefs.getAuthKey(a).isEmpty();
            boolean sb = !prefs.getAuthKey(b).isEmpty();
            if (sa != sb) return sa ? -1 : 1;                 // 已保存 AuthKey 优先
            boolean ba = bondedMap.containsKey(a);
            boolean bb = bondedMap.containsKey(b);
            if (ba != bb) return ba ? -1 : 1;                 // 系统已配对次之
            return a.compareTo(b);
        });

        int n = 0;
        for (String mac : macs) {
            boolean isBonded = bondedMap.containsKey(mac);
            BluetoothDevice dev = isBonded ? bondedMap.get(mac) : ble.getRemoteDevice(mac);
            if (dev == null) continue;
            String btName = dev.getName();
            String savedName = prefs.getDeviceName(mac);
            String name = (btName != null && !btName.isEmpty()) ? btName
                    : (savedName != null && !savedName.isEmpty()) ? savedName : mac;
            boolean saved = !prefs.getAuthKey(mac).isEmpty();
            addPairedRow(dev, name, mac, isBonded, saved);
            n++;
        }
        if (n == 0) {
            addEmptyRow(panelPairedList, "已配对设备均为经典蓝牙（耳机/车载），无法用 GATT 连接手环/手表");
        } else {
            log("已加载 " + n + " 台已配对设备（已完成连接的置顶，系统配对 + 应用保存合并）");
        }
    }

    /** 在指定容器内显示一行占位提示（无设备时）。 */
    private void addEmptyRow(LinearLayout container, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(getColor(R.color.md_on_surface_variant));
        t.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        container.addView(t, lp);
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
        // 扫描结果进入图2 面板下半「附近设备」列表；首个设备到达时清掉「正在扫描」占位
        if (panelNearbyList != null && foundDevices.isEmpty()) {
            panelNearbyList.removeAllViews();
        }
        addRow(panelNearbyList, device, name, rssi, false, false);
    }

    /** 添加「已配对设备」条目（图2 上半）：bonded=系统已配对，saved=应用已保存 AuthKey。 */
    private void addPairedRow(BluetoothDevice device, String name, String mac, boolean bonded, boolean saved) {
        addRow(panelPairedList, device, name, 127, bonded, saved);
    }

    /** 通用设备条目构建：渲染到指定容器，点击「连接」进入 AuthKey 录入。 */
    private void addRow(LinearLayout container, BluetoothDevice device, String name, int rssi, boolean bonded, boolean saved) {
        if (container == null) return;
        String mac = device.getAddress();
        foundDevices.put(mac, device);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        TextView label = new TextView(this);
        String shown = name == null || name.isEmpty() ? mac : name;
        StringBuilder suffix = new StringBuilder();
        if (bonded) suffix.append(" · 已配对");
        if (saved) suffix.append(" · ").append(getString(R.string.panel_saved_tag));
        label.setTag(name == null || name.isEmpty() ? "" : name);
        String rssiText = rssi == 127 ? "直连" : rssi + " dBm";
        label.setText(String.format(Locale.getDefault(), "%s%s\n%s  (%s)", shown, suffix, mac, rssiText));
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.md_on_surface));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button btn = new Button(this, null, 0, R.style.Widget_SFD_Button);
        btn.setText(saved ? R.string.reconnect : R.string.connect);
        btn.setOnClickListener(v -> onDeviceConnect(device, shown, saved));

        row.addView(label);
        if (saved) {
            // 已保存 AuthKey：旁边提供「忘记」选项，点击后清空并重新输入
            TextView forget = new TextView(this);
            forget.setText(R.string.forget);
            forget.setTextColor(getColor(R.color.md_primary));
            forget.setTextSize(13);
            forget.setPadding(dp(10), dp(8), dp(10), dp(8));
            forget.setClickable(true);
            forget.setFocusable(true);
            forget.setOnClickListener(v -> forgetAuthKey(device, shown));
            row.addView(forget);
        }
        row.addView(btn);
        row.setBackgroundResource(bonded ? R.drawable.card_bg_accent : R.drawable.card_bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        container.addView(row, lp);
        // 列表项淡入动效
        row.setAlpha(0f);
        row.animate().alpha(1f).setDuration(220).setInterpolator(new DecelerateInterpolator()).start();
        scanRows.put(mac, label);
        if (name != null && !name.isEmpty()) {
            log("设备: " + name + suffix + " (" + mac + ")");
        }
    }

    /**
     * 设备条目「连接 / 重连」点击：已保存 AuthKey 直接重连（自动填写），否则弹出输入框录入。
     * saved 表示该设备渲染时已被判定为已保存 AuthKey，但最终仍以实时读取的 AuthKey 为准。
     */
    private void onDeviceConnect(BluetoothDevice device, String name, boolean saved) {
        String key = prefs.getAuthKey(device.getAddress());
        if (saved && !key.isEmpty()) {
            doConnect(device, key); // 自动填写已保存的 AuthKey 并直接重连
        } else {
            promptAuthKey(device, name);
        }
    }

    /** 「忘记」：清空该设备已保存的 AuthKey 并立即重新输入（点击连接改为手动录入）。 */
    private void forgetAuthKey(BluetoothDevice device, String name) {
        String mac = device.getAddress();
        prefs.saveAuthKey(mac, "");
        toast("已忘记该设备的 AuthKey");
        // 立即刷新列表：移除「忘记」按钮，连接改为输入
        if (devicePanel != null && devicePanel.getVisibility() == View.VISIBLE) {
            renderPairedDevices();
        }
        promptAuthKey(device, name);
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
        // 连接新设备：复位健康度状态（横幅隐藏，等待本次认证后重新评估）
        appListCount = 0;
        batteryValue = -1;
        appListResolved = false;
        batteryResolved = false;
        hideConnAlert();
        sConnectedMac = connectedMac;
        sConnectedName = connectedName;
        // 记住此设备（下次启动一键连接），并保存名称供「已配对设备」展示
        prefs.saveLastDevice(connectedMac, connectedName);
        prefs.saveDeviceName(connectedMac, connectedName);
        setStatus("连接中…");
        showConnectingState(connectedName);
        log("连接设备: " + connectedName + " (" + connectedMac + ")");
        // 从设备选择面板发起的连接：关闭面板回到首页（带滑出动画并还原全屏状态）
        if (devicePanel != null && devicePanel.getVisibility() == View.VISIBLE) {
            closeDevicePanel();
        }
        ble.stopScan();

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
            appListCount = 0;
            batteryValue = -1;
            appListResolved = false;
            batteryResolved = false;
            hideConnAlert();
            updateHeroStatus(false);
            log("设备连接已断开");
            persistStats();
            sessionStart = 0;
            sSessionStart = 0;
            if (refreshAppsBtn != null) refreshAppsBtn.setVisibility(View.GONE);
            if (deviceInfoCard != null) deviceInfoCard.setVisibility(View.GONE);
            if (refreshInfoBtn != null) refreshInfoBtn.setVisibility(View.GONE);
            if (tipsCard != null) tipsCard.setVisibility(View.VISIBLE);
            // 若设备选择面板仍打开，刷新「已配对设备」列表
            if (devicePanel != null && devicePanel.getVisibility() == View.VISIBLE) {
                renderPairedDevices();
            }
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
                    updateHeroStatus(false); // 终端失败：退出连接中态
                    break;
                case "chars":
                    setStatus("不支持的设备");
                    toast(message);
                    updateHeroStatus(false);
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
                // 认证成功：记住设备、刷新 Hero 为已连接态、启动后台保活服务
                prefs.saveLastDevice(connectedMac, connectedName);
                prefs.saveDeviceName(connectedMac, connectedName);
                updateHeroStatus(true);
                tipsCard.setVisibility(View.GONE);
                refreshAppsBtn.setVisibility(View.VISIBLE);
                deviceInfoCard.setVisibility(View.VISIBLE);
                refreshInfoBtn.setVisibility(View.VISIBLE);
                cn.b4qaq.simplefetchdroid.service.KeepAliveService.start(this);
                log("已进入后台保活模式：退出界面后仍会继续处理快应用请求");
                // 认证成功后读取设备信息（电量/名称）
                appListCount = 0;
                batteryValue = -1;
                appListResolved = false;
                batteryResolved = false;
                requestDeviceInfo();
                // 认证成功后再渲染「最近应用」，确保点击监听已绑定（可点击重连）
                renderRecent();
                // 调度一次健康度检测：等待电量/应用列表读取完成后判断是否异常
                scheduleHealthCheck();
            } else {
                toast(message);
                setStatus("认证失败");
                updateHeroStatus(false); // 退出连接中态，回到未连接界面
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
            appListCount = items.size();
            appListResolved = true;
            renderApps();
            log("获取到 " + items.size() + " 个快应用");
            evaluateConnectionHealth();
        });
    }

    @Override
    public void onAppOnline(String pkg) {
        // 最近连接过的应用（本次会话已桥接 或 持久化「最近应用」）再次上线时处理：
        //  - 当前未桥接（DISCONNECTED/FAILED）：直接自动桥接，无需用户操作；
        //  - 当前已桥接（CONNECTED/HANDSHAKING）：多为应用重启，先断开再于 1.5 秒后重连握手。
        // 冷却去重：本机发起连接后应用启动会上报 basic_info（≈「上线」），该信号在冷却窗口内忽略，
        // 否则会陷入「上线→连接→上线→断开…」的死循环。
        if (bridge == null || session == null || !session.isAuthed()) {
            return;
        }
        if (!isAutoBridgeTarget(pkg)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastConnectAt.get(pkg);
        if (last != null && now - last < APP_ONLINE_COOLDOWN_MS) {
            log("检测到「" + pkg + "」上线（本机连接启动后的信号），忽略");
            return;
        }
        lastConnectAt.put(pkg, now); // 锁定冷却窗口，抑制本次重连/桥接引发的重复上线
        SfBridge.Status st = bridge.getStatus(pkg);
        if (st == SfBridge.Status.CONNECTED || st == SfBridge.Status.HANDSHAKING) {
            // 已桥接却再次上线（可能应用重启）：断开旧的，1.5 秒后重连重握手
            log("检测到「" + pkg + "」上线，刷新桥接（先断开）");
            bridge.disconnectApp(pkg);
            final SfBridge b = bridge;
            final String fp = pkg;
            main.postDelayed(() -> {
                if (b != null && session != null && session.isAuthed()
                        && isAutoBridgeTarget(fp)
                        && bridge.getStatus(fp) != SfBridge.Status.CONNECTED
                        && bridge.getStatus(fp) != SfBridge.Status.HANDSHAKING) {
                    log(REBRIDGE_DELAY_MS + "ms 后重新握手「" + pkg + "」");
                    bridgeConnect(fp); // 启动应用 → SF 握手（与用户点击「连接」一致）
                }
            }, REBRIDGE_DELAY_MS);
        } else {
            // 未桥接：自动桥接（最近应用上线即恢复桥接）
            log("检测到「" + pkg + "」上线且未桥接，自动桥接");
            bridgeConnect(pkg);
        }
    }

    /** 是否纳入「上线自动桥接」的目标：本次会话已桥接，或持久化「最近应用」列表中的包。 */
    private boolean isAutoBridgeTarget(String pkg) {
        if (autoBridgePkgs.contains(pkg)) return true;
        org.json.JSONArray arr = prefs.getRecentApps();
        for (int i = 0; i < arr.length(); i++) {
            if (arr.optJSONObject(i).optString("pkg", "").equals(pkg)) return true;
        }
        return false;
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
                    batteryValue = info.battery;
                    batteryResolved = true;
                    renderDeviceInfo();
                    log("设备信息：名称 " + (info.name.isEmpty() ? "未知" : info.name)
                            + " · 电量 " + (info.battery >= 0 ? info.battery + "%" : "未知"));
                    evaluateConnectionHealth();
                });
            }

            @Override
            public void onDeviceInfoError(String message) {
                batteryResolved = true; // 读取失败也算「已结束」，参与健康度判定
                batteryValue = -1;
                log("设备信息读取失败: " + message);
                // 此回调由 BleClient 的 ble-io 线程发出，评估涉及 View，须切回主线程
                runOnUiThread(() -> evaluateConnectionHealth());
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

    // ==================== 连接健康度检测 ====================

    /** 调度一次健康度检测（超时后强制判定，用于读取一直没有回调的情况）。 */
    private void scheduleHealthCheck() {
        main.postDelayed(() -> evaluateConnectionHealth(true), HEALTH_CHECK_DELAY_MS);
    }

    /** 单次读取完成后的评估（两项都完成才判定，避免横幅闪现）。 */
    private void evaluateConnectionHealth() {
        evaluateConnectionHealth(false);
    }

    /**
     * 连接健康度判断：仅当「应用列表为空」且「电量不可读（0 或从未读取）」同时成立时，
     * 顶部显示红色异常横幅；任一项恢复正常即隐藏。认证未成功不参与判断。
     *
     * @param force true 表示忽略「两项都完成」门控（定时兜底判定）
     */
    private void evaluateConnectionHealth(boolean force) {
        if (session == null || !session.isAuthed()) {
            hideConnAlert();
            return;
        }
        // 非强制：等待应用列表与电量读取都结束再判定，避免先返回的一项单独触发横幅
        if (!force && (!appListResolved || !batteryResolved)) {
            return;
        }
        boolean batteryUnreadable = batteryValue <= 0; // -1 未读取 / 0 视为不可读
        boolean appEmpty = appListCount <= 0;
        if (appEmpty && batteryUnreadable) {
            showConnAlert();
        } else {
            hideConnAlert();
        }
    }

    private void showConnAlert() {
        if (alertBanner == null) return;
        if (alertBanner.getVisibility() != View.VISIBLE) {
            alertBanner.setVisibility(View.VISIBLE);
            log("检测到连接异常：应用列表为空且电量不可读");
        }
    }

    private void hideConnAlert() {
        if (alertBanner != null) alertBanner.setVisibility(View.GONE);
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

    // ==================== 手动 MAC 连接 ====================

    /** 手动输入 MAC 连接：校验格式后走现有 getRemoteDevice → promptAuthKey（GATT 直连，无需系统已配对）。 */
    private void connectByMac(String rawMac) {
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        String mac = rawMac == null ? "" : rawMac.trim();
        if (!mac.matches("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")) {
            toast("MAC 格式不正确，应为 AA:BB:CC:DD:EE:FF");
            return;
        }
        BluetoothDevice dev = ble.getRemoteDevice(mac);
        if (dev == null) {
            toast("无法获取设备: " + mac);
            return;
        }
        promptAuthKey(dev, mac);
    }

    // ==================== 图2 扫描附近设备 ====================

    /** 面板「扫描并添加」：扫描附近所有 BLE 设备（不限品牌）并渲染到面板下半列表；12 秒自动停止。 */
    private void startPanelScan() {
        if (!hasPermissions()) {
            requestPermissions();
            return;
        }
        if (!ble.isReady()) {
            toast("蓝牙未开启");
            return;
        }
        scanRows.clear();
        foundDevices.clear();
        if (panelNearbyList != null) panelNearbyList.removeAllViews();
        // 先放一条占位，扫描到设备后逐条追加
        addEmptyRow(panelNearbyList, getString(R.string.panel_nearby_empty));
        log("开始扫描附近设备（任意 BLE 设备）…");
        ble.startScanAll();
        // 12 秒后自动停止，避免后台持续扫描耗电
        main.postDelayed(() -> {
            if (ble.isScanning()) {
                ble.stopScan();
                log("扫描结束（自动停止，可再次点击扫描）");
                // 若仍无任何设备，给出提示
                if (panelNearbyList != null && panelNearbyList.getChildCount() <= 1) {
                    panelNearbyList.removeAllViews();
                    addEmptyRow(panelNearbyList, "未扫描到附近设备 —— 请确保手表处于可被发现状态");
                }
            }
        }, 12000);
    }
}
