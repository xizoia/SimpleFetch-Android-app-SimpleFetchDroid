package cn.b4qaq.simplefetchdroid.proto;

import java.nio.charset.StandardCharsets;

/**
 * 极简 protobuf wire 格式解析器：按字段逐个读取，调用方按 field number 取值。
 */
public final class ProtoReader {

    public final byte[] data;
    public int pos;

    public ProtoReader(byte[] data) {
        this.data = data;
        this.pos = 0;
    }

    public boolean hasMore() {
        return pos < data.length;
    }

    /** 读取下一个 tag，返回 field number；wireType 存入成员 lastWireType。 */
    public int lastWireType;

    public int readTag() {
        long tag = readVarint();
        lastWireType = (int) (tag & 0x7);
        return (int) (tag >>> 3);
    }

    public long readVarint() {
        long result = 0;
        int shift = 0;
        while (true) {
            if (pos >= data.length) throw new IllegalStateException("varint overflow");
            byte b = data[pos++];
            result |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 64) throw new IllegalStateException("varint too long");
        }
    }

    public byte[] readBytes() {
        int len = (int) readVarint();
        if (len < 0 || pos + len > data.length) throw new IllegalStateException("bytes overflow");
        byte[] out = new byte[len];
        System.arraycopy(data, pos, out, 0, len);
        pos += len;
        return out;
    }

    public String readString() {
        return new String(readBytes(), StandardCharsets.UTF_8);
    }

    public double readDouble() {
        byte[] b = readBytesFixed(8);
        long bits = 0;
        for (int i = 0; i < 8; i++) bits |= (long) (b[i] & 0xff) << (8 * i);
        return Double.longBitsToDouble(bits);
    }

    public float readFloat() {
        byte[] b = readBytesFixed(4);
        int bits = 0;
        for (int i = 0; i < 4; i++) bits |= (b[i] & 0xff) << (8 * i);
        return Float.intBitsToFloat(bits);
    }

    private byte[] readBytesFixed(int n) {
        if (pos + n > data.length) throw new IllegalStateException("fixed overflow");
        byte[] out = new byte[n];
        System.arraycopy(data, pos, out, 0, n);
        pos += n;
        return out;
    }

    /** 跳过当前 wireType 对应的值。 */
    public void skipValue() {
        switch (lastWireType) {
            case 0: readVarint(); break;
            case 1: pos += 8; break;
            case 2: {
                int len = (int) readVarint();
                pos += len;
                break;
            }
            case 5: pos += 4; break;
            default: throw new IllegalStateException("unknown wire type " + lastWireType);
        }
    }
}
