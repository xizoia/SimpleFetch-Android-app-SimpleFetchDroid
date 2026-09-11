package cn.b4qaq.simplefetchdroid.frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * 小米 SPP V2 帧协议（BLE V2/V5 设备通用）。
 * <p>
 * 帧结构：A5 A5 | type(low 4bit) | seq(1B) | payloadLen(LE16) | crc16(ARC, LE16) | payload
 * <p>
 * DataPacket payload：channel(low 4bit) | opcode(1B) | data
 * channel: 1=Protobuf/Auth, 2=Data, 5=Activity；opcode: 1=明文, 2=AES-CTR 加密
 * <p>
 * 参考实现：Gadgetbridge XiaomiSppPacketV2.java。
 */
public final class V2Frame {

    private V2Frame() {}

    public static final byte[] PREAMBLE = {(byte) 0xa5, (byte) 0xa5};

    public static final int TYPE_ACK = 1;
    public static final int TYPE_SESSION_CONFIG = 2;
    public static final int TYPE_DATA = 3;

    public static final int CH_PROTOBUF = 1;
    public static final int CH_DATA = 2;
    public static final int CH_ACTIVITY = 5;

    public static final int OP_PLAINTEXT = 1;
    public static final int OP_ENCRYPTED = 2;

    public static final int SC_OPCODE_START_SESSION_REQUEST = 1;
    public static final int SC_OPCODE_START_SESSION_RESPONSE = 2;

    // ==================== 编码 ====================

    public static byte[] encodeSessionConfig(int seq, int opcode) {
        // 照抄 Gadgetbridge（来自官方 App 抓包）
        byte[] payload = new byte[]{
                (byte) opcode,
                // VERSION(1) = 01.00.00
                0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
                // MAX_FRAME_SIZE(2) = 64512
                0x02, 0x02, 0x00, 0x00, (byte) 0xfc,
                // TX_WIN(3) = 32
                0x03, 0x02, 0x00, 0x20, 0x00,
                // SEND_TIMEOUT(4) = 10000ms
                0x04, 0x02, 0x10, 0x27,
        };
        return frame(TYPE_SESSION_CONFIG, seq, payload);
    }

    public static byte[] encodeAck(int seq) {
        return frame(TYPE_ACK, seq, new byte[0]);
    }

    /**
     * 编码数据包（明文信道）。
     */
    public static byte[] encodeDataPlain(int seq, int channel, byte[] data) {
        byte[] payload = new byte[2 + data.length];
        payload[0] = (byte) (channel & 0xf);
        payload[1] = (byte) OP_PLAINTEXT;
        System.arraycopy(data, 0, payload, 2, data.length);
        return frame(TYPE_DATA, seq, payload);
    }

    /**
     * 编码数据包（加密信道）：data 先经 AES-CTR 加密再入帧。
     */
    public static byte[] encodeDataEncrypted(int seq, int channel, byte[] encrypted) {
        byte[] payload = new byte[2 + encrypted.length];
        payload[0] = (byte) (channel & 0xf);
        payload[1] = (byte) OP_ENCRYPTED;
        System.arraycopy(encrypted, 0, payload, 2, encrypted.length);
        return frame(TYPE_DATA, seq, payload);
    }

    private static byte[] frame(int type, int seq, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(8 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(PREAMBLE);
        buf.put((byte) (type & 0xf));
        buf.put((byte) (seq & 0xff));
        buf.putShort((short) payload.length);
        buf.putShort((short) crc16Arc(payload));
        buf.put(payload);
        return buf.array();
    }

    // ==================== 解码 ====================

    public static class Decoded {
        public int type;
        public int seq;
        public int opcode;   // SessionConfig opcode 或 Data opcode
        public int channel;  // Data 包信道
        public byte[] data;  // Data 包载荷（未解密）
    }

    /**
     * 解码一帧完整数据。调用方需先确保 {@link #packetSize} 已满足。
     */
    public static Decoded decode(byte[] frameBytes) {
        if (frameBytes.length < 8) return null;
        ByteBuffer buf = ByteBuffer.wrap(frameBytes).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[2];
        buf.get(magic);
        if (!Arrays.equals(PREAMBLE, magic)) return null;

        int type = buf.get() & 0xf;
        int seq = buf.get() & 0xff;
        int len = buf.getShort() & 0xffff;
        int givenCrc = buf.getShort() & 0xffff;
        if (buf.remaining() < len) return null;

        byte[] payload = new byte[len];
        buf.get(payload);
        if (crc16Arc(payload) != givenCrc) return null;

        Decoded d = new Decoded();
        d.type = type;
        d.seq = seq;

        switch (type) {
            case TYPE_SESSION_CONFIG: {
                d.opcode = payload.length > 0 ? payload[0] & 0xff : -1;
                break;
            }
            case TYPE_DATA: {
                if (payload.length >= 2) {
                    d.channel = payload[0] & 0xf;
                    d.opcode = payload[1] & 0xff;
                    d.data = new byte[payload.length - 2];
                    System.arraycopy(payload, 2, d.data, 0, d.data.length);
                }
                break;
            }
            default: break;
        }
        return d;
    }

    /**
     * 返回帧总长度（8 + payloadLen）；若头部不完整返回 -1；若魔数错误返回 -2。
     */
    public static int packetSize(byte[] buffer) {
        if (buffer.length < 8) return -1;
        if (buffer[0] != PREAMBLE[0] || buffer[1] != PREAMBLE[1]) return -2;
        int len = (buffer[4] & 0xff) | ((buffer[5] & 0xff) << 8);
        return 8 + len;
    }

    // ==================== CRC-16/ARC ====================

    /**
     * CRC-16/ARC：poly=0x8005, init=0, refin/refout=true, xorout=0。
     * 照抄 Gadgetbridge 位运算实现。
     */
    public static int crc16Arc(byte[] payload) {
        int crc = 0;
        for (byte b : payload) {
            for (int j = 0; j < 8; j++) {
                crc <<= 1;
                if ((((crc >> 16) & 1) ^ ((b >> j) & 1)) == 1) {
                    crc ^= 0x8005;
                }
            }
        }
        return Integer.reverse(crc) >>> 16;
    }
}
