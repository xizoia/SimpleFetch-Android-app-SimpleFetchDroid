package cn.b4qaq.simplefetchdroid.store;

import android.content.Context;
import android.content.SharedPreferences;

/** 设备 AuthKey 与请求统计的持久化。 */
public final class Prefs {

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getSharedPreferences("sfd_prefs", Context.MODE_PRIVATE);
    }

    /** 按设备 MAC 保存 AuthKey（32 位 hex）。 */
    public void saveAuthKey(String mac, String key) {
        sp.edit().putString("key_" + mac, key.trim()).apply();
    }

    public String getAuthKey(String mac) {
        return sp.getString("key_" + mac, "").trim();
    }

    /** 保存设备名称（与 AuthKey 同生命周期，供「已配对设备」展示去重合并）。 */
    public void saveDeviceName(String mac, String name) {
        sp.edit().putString("name_" + mac, name == null ? "" : name.trim()).apply();
    }

    public String getDeviceName(String mac) {
        return sp.getString("name_" + mac, "").trim();
    }

    /**
     * 列出「应用已保存 AuthKey」的设备 MAC（扫描 sp 中以 {@code key_} 开头的键）。
     * 用于图2「已配对设备」与系统已配对蓝牙设备合并去重。
     */
    public java.util.Set<String> getSavedDeviceMacs() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String k : sp.getAll().keySet()) {
            if (k.startsWith("key_") && k.length() > 4) {
                out.add(k.substring(4));
            }
        }
        return out;
    }

    /** 记住最近一次成功连接的设备（快速重连）。 */
    public void saveLastDevice(String mac, String name) {
        sp.edit().putString("last_mac", mac)
                .putString("last_name", name == null ? "" : name)
                .apply();
    }

    public String getLastDeviceMac() {
        return sp.getString("last_mac", "");
    }

    public String getLastDeviceName() {
        return sp.getString("last_name", "");
    }

    public void addStats(long total, long ok, long fail) {
        sp.edit()
                .putLong("stat_total", sp.getLong("stat_total", 0) + total)
                .putLong("stat_ok", sp.getLong("stat_ok", 0) + ok)
                .putLong("stat_fail", sp.getLong("stat_fail", 0) + fail)
                .apply();
    }

    public long[] getStats() {
        return new long[]{
                sp.getLong("stat_total", 0),
                sp.getLong("stat_ok", 0),
                sp.getLong("stat_fail", 0),
        };
    }

    /** 最近成功桥接的应用（JSON 数组，每项 {pkg, name}，最多 4 条，新在前）。 */
    public void saveRecentApps(org.json.JSONArray arr) {
        sp.edit().putString("recent_apps", arr.toString()).apply();
    }

    public org.json.JSONArray getRecentApps() {
        String s = sp.getString("recent_apps", "");
        if (s.isEmpty()) return new org.json.JSONArray();
        try {
            return new org.json.JSONArray(s);
        } catch (Exception e) {
            return new org.json.JSONArray();
        }
    }
}
