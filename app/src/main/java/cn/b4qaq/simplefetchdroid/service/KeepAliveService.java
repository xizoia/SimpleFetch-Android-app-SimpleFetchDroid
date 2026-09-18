package cn.b4qaq.simplefetchdroid.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.Locale;

import cn.b4qaq.simplefetchdroid.R;
import cn.b4qaq.simplefetchdroid.device.DeviceSession;
import cn.b4qaq.simplefetchdroid.sf.SfBridge;
import cn.b4qaq.simplefetchdroid.ui.MainActivity;

/**
 * 连接保活前台服务：设备认证成功后启动。
 * <p>
 * 作用：
 * 1. 把进程优先级提升到前台级，避免 UI 退出/熄屏后系统回收进程——
 *    BLE 会话与 SF 代理在 UI 之外继续工作；
 * 2. 持有 PARTIAL_WAKE_LOCK，防止 CPU 深度休眠导致互联消息/HTTP 响应延迟；
 * 3. 周期自检：会话不存在/未认证时自动停止，避免空转耗电。
 */
public class KeepAliveService extends Service {

    private static final String TAG = "KeepAliveService";
    private static final String CHANNEL_ID = "keepalive";
    private static final int NOTIFICATION_ID = 1;
    /** 自检间隔：会话消失后停止服务。 */
    private static final long SELF_CHECK_MS = 15_000;

    private static volatile boolean running = false;

    private PowerManager.WakeLock wakeLock;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable selfCheck = new Runnable() {
        @Override
        public void run() {
            DeviceSession s = MainActivity.getStaticSession();
            if (s == null || !s.isAuthed()) {
                Log.i(TAG, "会话已结束，停止保活服务");
                stopSelf();
                return;
            }
            handler.postDelayed(this, SELF_CHECK_MS);
        }
    };

    /** 每 2 秒刷新通知栏：连接状态 + 总请求/成功/失败。 */
    private final Runnable noticeTick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            updateNotification();
            handler.postDelayed(this, 2000);
        }
    };

    public static boolean isRunning() {
        return running;
    }

    /** 认证成功后由 UI 调用启动。 */
    public static void start(Context context) {
        Intent intent = new Intent(context, KeepAliveService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, KeepAliveService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "后台代理保活", NotificationManager.IMPORTANCE_LOW));
        }
        Notification notification = buildNotification("SimpleFetchDroid", "正在为快应用处理请求");
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        // 防 CPU 休眠：熄屏时保持互联消息与 HTTP 响应的即时性
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SimpleFetchDroid:keepalive");
            wakeLock.setReferenceCounted(false);
            try {
                wakeLock.acquire();
            } catch (Exception e) {
                Log.w(TAG, "WakeLock 获取失败: " + e);
            }
        }

        handler.postDelayed(selfCheck, SELF_CHECK_MS);
        handler.postDelayed(noticeTick, 2000);
        Log.i(TAG, "保活服务已启动");
    }

    private Notification buildNotification(String title, String text) {
        Intent open = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** 刷新通知栏：连接状态 + 总请求 / 成功 / 失败。 */
    private void updateNotification() {
        DeviceSession s = MainActivity.getStaticSession();
        SfBridge br = MainActivity.getStaticBridge();
        boolean authed = s != null && s.isAuthed();
        String name = MainActivity.getStaticName();
        long total = br != null ? br.getTotalRequests() : 0;
        long ok = br != null ? br.getOkRequests() : 0;
        long fail = br != null ? br.getFailedRequests() : 0;
        String title, text;
        if (authed) {
            title = "已连接 · " + (name == null || name.isEmpty() ? "设备" : name);
            text = String.format(Locale.getDefault(), "总请求 %d · 成功 %d · 失败 %d", total, ok, fail);
        } else {
            title = "SimpleFetchDroid";
            text = "未连接 · 等待快应用请求";
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(title, text));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, "保活服务已停止");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
