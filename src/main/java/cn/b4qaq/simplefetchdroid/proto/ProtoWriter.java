package cn.b4qaq.simplefetchdroid.proto;

import java.io.ByteArrayOutputStream;

/**
 * 极简 protobuf wire 格式编码器（proto2 风格，全部显式编号）。
 */
public final class ProtoWriter {

    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(64);

    public ProtoWriter writeVarintField(int field, long value) {
        if (value == 0) return this; // proto2 默认值可省略（设备端兼容）
        writeTag(field, 0);
        writeVarint(value);
        return this;
    }

    /** 显式写 varint（含 0），用于 enum/bool 等必须出现的字段。 */
    public ProtoWriter writeVarintFieldAlways(int field, long value) {
        writeTag(field, 0);
        writeVarint(value);
        return this;
    }

    public ProtoWriter writeBytesField(int field, byte[] data) {
        writeTag(field, 2);
        writeVarint(data.length);
        buf.write(data, 0, data.length);
        return this;
    }

    public ProtoWriter writeStringField(int field, String s) {
        return writeBytesField(field, s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public ProtoWriter writeMessageField(int field, byte[] nested) {
        return writeBytesField(field, nested);
    }

    private void writeTag(int field, int wireType) {
        writeVarint(((long) field << 3) | wireType);
    }

    private void writeVarint(long v) {
        while (true) {
            if ((v & ~0x7fL) == 0) {
                buf.write((int) v);
                return;
            }
            buf.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
    }

    public byte[] toBytes() {
        return buf.toByteArray();
    }
}
