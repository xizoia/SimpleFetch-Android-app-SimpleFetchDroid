package cn.b4qaq.simplefetchdroid.proto;

import java.util.ArrayList;
import java.util.List;

import cn.b4qaq.simplefetchdroid.crypto.CryptoUtil;

/**
 * 小米 V5 WearPacket 协议消息构建与解析（proto: wear.proto / wear_account.proto / wear_thirdparty_app.proto）。
 */
public final class Wear {

    private Wear() {}

    // ===== WearPacket.Type =====
    public static final int T_ACCOUNT = 1;
    public static final int T_THIRDPARTY_APP = 20;

    // ===== Account.AccountId =====
    public static final int AID_AUTH_VERIFY = 26;
    public static final int AID_AUTH_CONFIRM = 27;

    // ===== ThirdpartyAppID =====
    public static final int APP_GET_INSTALLED_LIST = 0;
    public static final int APP_REMOVE_APP = 3;
    public static final int APP_LAUNCH_APP = 4;
    public static final int APP_SYNC_PHONE_APP_STATUS = 7;
    public static final int APP_SEND_PHONE_MESSAGE = 8;

    // ===== PhoneAppStatus.Status =====
    public static final int APP_STATUS_CONNECTED = 1;

    // ===== CompanionDevice.DeviceType =====
    public static final int DEVICE_TYPE_IOS = 1;   // AstroBox 在 BLE 下用 IOS 规避额外校验
    public static final int DEVICE_TYPE_ANDROID = 0;

    // ==================== 构建器 ====================

    /** 认证第 1 步：Account(id=26).Auth.AppVerify{app_random} */
    public static byte[] buildAuthStep1(byte[] appRandom16) {
        byte[] appVerify = new ProtoWriter()
                .writeBytesField(1, appRandom16)
                .toBytes();
        byte[] account = new ProtoWriter()
                .writeBytesField(30, appVerify) // account.payload.auth_app_verify
                .toBytes();
        return wearPacket(T_ACCOUNT, AID_AUTH_VERIFY, 3, account);
    }

    /**
     * 认证第 2 步：Account(id=27).Auth.AppConfirm{app_sign, encrypt_companion_device}
     *
     * @param encKey            16B 加密密钥（来自 kdf_miwear 展开 [16..32)）
     * @param encNonce          4B 加密 nonce（[36..40)）
     * @param appSign           HMAC(enc_key, phoneNonce||watchNonce) 32B
     * @param deviceName        伴侣设备名称
     */
    public static byte[] buildAuthStep2(byte[] encKey, byte[] encNonce, byte[] appSign,
                                        String deviceName, int deviceType) {
        byte[] companion = new ProtoWriter()
                .writeVarintFieldAlways(1, deviceType)
                .writeStringField(3, deviceName)
                .writeVarintField(4, 0xffff_ffffL) // app_capability
                .toBytes();

        // CCM nonce = encNonce(4B) || counter(8B 全零)
        byte[] ccmNonce = new byte[12];
        System.arraycopy(encNonce, 0, ccmNonce, 0, 4);
        byte[] encryptedDevice = CryptoUtil.aesCcmEncrypt(encKey, ccmNonce, companion);

        byte[] appConfirm = new ProtoWriter()
                .writeBytesField(1, appSign)
                .writeBytesField(2, encryptedDevice)
                .toBytes();
        byte[] account = new ProtoWriter()
                .writeBytesField(32, appConfirm) // account.payload.auth_app_confirm
                .toBytes();
        return wearPacket(T_ACCOUNT, AID_AUTH_CONFIRM, 3, account);
    }

    /** 请求快应用安装列表：ThirdpartyApp(id=0)，payload 为空。 */
    public static byte[] buildGetAppList() {
        return wearPacket(T_THIRDPARTY_APP, APP_GET_INSTALLED_LIST, -1, null);
    }

    /** 启动快应用：ThirdpartyApp(id=4).LaunchInfo{basic_info, uri} */
    public static byte[] buildLaunchApp(String pkg, byte[] fingerprint, String uri) {
        byte[] basic = basicInfo(pkg, fingerprint);
        byte[] launchInfo = new ProtoWriter()
                .writeBytesField(1, basic)
                .writeStringField(2, uri)
                .toBytes();
        byte[] tp = new ProtoWriter()
                .writeBytesField(6, launchInfo)
                .toBytes();
        return wearPacket(T_THIRDPARTY_APP, APP_LAUNCH_APP, 22, tp);
    }

    /** 发送互联消息：ThirdpartyApp(id=8).MessageContent{basic_info, content} */
    public static byte[] buildSendPhoneMessage(String pkg, byte[] fingerprint, byte[] content) {
        byte[] messageContent = new ProtoWriter()
                .writeBytesField(1, basicInfo(pkg, fingerprint))
                .writeBytesField(2, content)
                .toBytes();
        byte[] tp = new ProtoWriter()
                .writeBytesField(9, messageContent)
                .toBytes();
        return wearPacket(T_THIRDPARTY_APP, APP_SEND_PHONE_MESSAGE, 22, tp);
    }

    /** 同步手机端 App 状态：ThirdpartyApp(id=7).PhoneAppStatus{basic_info, status} */
    public static byte[] buildSyncPhoneAppStatus(String pkg, byte[] fingerprint, int status) {
        byte[] statusMsg = new ProtoWriter()
                .writeBytesField(1, basicInfo(pkg, fingerprint))
                .writeVarintFieldAlways(2, status)
                .toBytes();
        byte[] tp = new ProtoWriter()
                .writeBytesField(8, statusMsg)
                .toBytes();
        return wearPacket(T_THIRDPARTY_APP, APP_SYNC_PHONE_APP_STATUS, 22, tp);
    }

    private static byte[] basicInfo(String pkg, byte[] fingerprint) {
        return new ProtoWriter()
                .writeStringField(1, pkg)
                .writeBytesField(2, fingerprint == null ? new byte[0] : fingerprint)
                .toBytes();
    }

    private static byte[] wearPacket(int type, int id, int payloadField, byte[] payload) {
        ProtoWriter w = new ProtoWriter()
                .writeVarintFieldAlways(1, type)
                .writeVarintFieldAlways(2, id);
        if (payloadField > 0 && payload != null) {
            w.writeBytesField(payloadField, payload);
        }
        return w.toBytes();
    }

    // ==================== 解析 ====================

    /** 解析后的 WearPacket 顶层结构。 */
    public static class Packet {
        public int type;
        public int id;
        public byte[] payload; // oneof 原始 bytes（field tag 未解码）
        public int payloadField;
    }

    public static Packet parsePacket(byte[] bytes) {
        Packet p = new Packet();
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int field = r.readTag();
            switch (field) {
                case 1: p.type = (int) r.readVarint(); break;
                case 2: p.id = (int) r.readVarint(); break;
                default:
                    if (r.lastWireType == 2) {
                        p.payload = r.readBytes();
                        p.payloadField = field;
                    } else {
                        r.skipValue();
                    }
                    break;
            }
        }
        return p;
    }

    // ===== Account 相关 =====

    public static class AuthDeviceVerify {
        public byte[] deviceRandom; // 16B
        public byte[] deviceSign;   // 32B
        public boolean confirmResult;
        public boolean isConfirm;   // true = AuthDeviceConfirm
    }

    /** 解析 Account oneof（field 31=auth_device_verify, 33=auth_device_confirm）。 */
    public static AuthDeviceVerify parseAccount(byte[] accountBytes) {
        AuthDeviceVerify out = new AuthDeviceVerify();
        ProtoReader r = new ProtoReader(accountBytes);
        while (r.hasMore()) {
            int field = r.readTag();
            if (r.lastWireType != 2) { r.skipValue(); continue; }
            byte[] msg = r.readBytes();
            ProtoReader inner = new ProtoReader(msg);
            switch (field) {
                case 31: { // auth_device_verify
                    while (inner.hasMore()) {
                        int f = inner.readTag();
                        if (f == 1 && inner.lastWireType == 2) out.deviceRandom = inner.readBytes();
                        else if (f == 2 && inner.lastWireType == 2) out.deviceSign = inner.readBytes();
                        else inner.skipValue();
                    }
                    break;
                }
                case 33: { // auth_device_confirm
                    out.isConfirm = true;
                    while (inner.hasMore()) {
                        int f = inner.readTag();
                        if (f == 1 && inner.lastWireType == 0) out.confirmResult = inner.readVarint() != 0;
                        else inner.skipValue();
                    }
                    break;
                }
                default: break;
            }
        }
        return out;
    }

    // ===== ThirdpartyApp 相关 =====

    public static class AppItem {
        public String packageName = "";
        public byte[] fingerprint = new byte[0];
        public long versionCode;
        public boolean canRemove;
        public String appName = "";
    }

    public static class Message {
        public String packageName = "";
        public byte[] fingerprint = new byte[0];
        public byte[] content = new byte[0];
    }

    /**
     * 解析 ThirdpartyApp oneof。
     * field 1 = app_item_list, 5 = basic_info, 8 = app_status, 9 = message_content
     */
    public static void parseThirdpartyApp(byte[] tpBytes, int packetId,
                                          ThirdpartyHandler handler) {
        ProtoReader r = new ProtoReader(tpBytes);
        while (r.hasMore()) {
            int field = r.readTag();
            if (r.lastWireType != 2) { r.skipValue(); continue; }
            byte[] msg = r.readBytes();
            switch (field) {
                case 1: { // app_item_list
                    if (packetId == APP_GET_INSTALLED_LIST) {
                        List<AppItem> items = new ArrayList<>();
                        ProtoReader list = new ProtoReader(msg);
                        while (list.hasMore()) {
                            int f = list.readTag();
                            if (f == 1 && list.lastWireType == 2) {
                                items.add(parseAppItem(list.readBytes()));
                            } else {
                                list.skipValue();
                            }
                        }
                        handler.onAppList(items);
                    }
                    break;
                }
                case 5: { // basic_info（快应用上线）
                    Message m = new Message();
                    parseBasicInfo(msg, m);
                    handler.onBasicInfo(m);
                    break;
                }
                case 8: { // app_status
                    Message m = new Message();
                    ProtoReader st = new ProtoReader(msg);
                    while (st.hasMore()) {
                        int f = st.readTag();
                        if (f == 1 && st.lastWireType == 2) parseBasicInfo(st.readBytes(), m);
                        else if (f == 2 && st.lastWireType == 0) {
                            // status enum，忽略
                            st.skipValue();
                        } else st.skipValue();
                    }
                    handler.onAppStatus(m);
                    break;
                }
                case 9: { // message_content
                    Message m = new Message();
                    ProtoReader mc = new ProtoReader(msg);
                    while (mc.hasMore()) {
                        int f = mc.readTag();
                        if (f == 1 && mc.lastWireType == 2) parseBasicInfo(mc.readBytes(), m);
                        else if (f == 2 && mc.lastWireType == 2) m.content = mc.readBytes();
                        else mc.skipValue();
                    }
                    handler.onMessageContent(m);
                    break;
                }
                default: break;
            }
        }
    }

    private static AppItem parseAppItem(byte[] bytes) {
        AppItem item = new AppItem();
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int field = r.readTag();
            switch (field) {
                case 1: item.packageName = r.readString(); break;
                case 2: item.fingerprint = r.readBytes(); break;
                case 3: item.versionCode = r.readVarint(); break;
                case 4: item.canRemove = r.readVarint() != 0; break;
                case 5: item.appName = r.readString(); break;
                default: r.skipValue(); break;
            }
        }
        return item;
    }

    private static void parseBasicInfo(byte[] bytes, Message into) {
        ProtoReader r = new ProtoReader(bytes);
        while (r.hasMore()) {
            int field = r.readTag();
            if (field == 1 && r.lastWireType == 2) into.packageName = r.readString();
            else if (field == 2 && r.lastWireType == 2) into.fingerprint = r.readBytes();
            else r.skipValue();
        }
    }

    public interface ThirdpartyHandler {
        void onAppList(List<AppItem> items);
        void onBasicInfo(Message basicInfo);
        void onAppStatus(Message status);
        void onMessageContent(Message message);
    }
}
