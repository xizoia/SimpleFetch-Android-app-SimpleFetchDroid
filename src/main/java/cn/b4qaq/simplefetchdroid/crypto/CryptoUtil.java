package cn.b4qaq.simplefetchdroid.crypto;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;

/**
 * 小米 V5 协议加密原语。
 * <p>
 * 参考实现：
 * - AstroBox-NG-Module-Core: components/auth.rs (kdf_miwear, aes128_ccm_encrypt)
 * - Gadgetbridge: XiaomiAuthService.java (computeAuthStep3Hmac, encryptV2/decryptV2)
 */
public final class CryptoUtil {

    private CryptoUtil() {}

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] data) {
        if (data == null) return "null";
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(HEX[(b >> 4) & 0xf]).append(HEX[b & 0xf]);
        }
        return sb.toString();
    }

    public static byte[] parseHex(String s) {
        s = s.trim().toLowerCase();
        if (s.startsWith("0x")) s = s.substring(2);
        s = s.replaceAll("[^0-9a-f]", "");
        if (s.length() % 2 != 0) throw new IllegalArgumentException("hex length must be even");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    public static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    public static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("hmac failed", e);
        }
    }

    /**
     * kdf_miwear：从 authkey + 双向随机数展开出 64 字节会话密钥材料。
     * 输出布局：dec_key[0..16) enc_key[16..32) dec_nonce[32..36) enc_nonce[36..40)
     */
    public static byte[] kdfMiwear(byte[] secretKey16, byte[] phoneNonce16, byte[] watchNonce16) {
        byte[] tag = "miwear-auth".getBytes();

        // 1) hmac_key = HMAC(phoneNonce||watchNonce, secretKey)
        byte[] initKey = concat(phoneNonce16, watchNonce16);
        byte[] hmacKey = hmacSha256(initKey, secretKey16);

        // 2) HKDF 风格展开至 64 字节
        byte[] okm = new byte[64];
        byte[] prev = new byte[0];
        int offset = 0;
        byte counter = 1;
        while (offset < 64) {
            Mac mac;
            try {
                mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            mac.update(prev);
            mac.update(tag);
            mac.update(counter);
            prev = mac.doFinal();
            int n = Math.min(prev.length, 64 - offset);
            System.arraycopy(prev, 0, okm, offset, n);
            offset += n;
            counter++;
        }
        return okm;
    }

    // ==================== AES-CCM (macSize = 32 bit) ====================

    /**
     * AES-128-CCM 加密（无 AAD，tag 长度 4 字节），输出 = 密文 || tag。
     * 已通过 RFC 3610 Packet Vector #1 与 pyca cryptography 向量验证。
     * <p>
     * 要点（RFC 3610）：B0 与 A_i 的 flags 不同——
     * B0 = (M'<<3)|(L-1)，A_i = (L-1)；长度与计数器均为 big-endian。
     */
    public static byte[] aesCcmEncrypt(byte[] key16, byte[] nonce12, byte[] plaintext) {
        try {
            int L = 15 - nonce12.length; // = 3
            int macLen = 4;

            // ---- CBC-MAC ----
            // B0 = flags || nonce || msgLen(L 字节 big-endian)
            int b0Flags = ((macLen - 2) / 2) << 3 | (L - 1); // 0x0A
            byte[] b0 = new byte[16];
            b0[0] = (byte) b0Flags;
            System.arraycopy(nonce12, 0, b0, 1, nonce12.length);
            writeBe(b0, 16 - L, plaintext.length, L);

            Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key16, "AES"));

            byte[] mac = aes.doFinal(b0.clone()); // CBC-MAC 第一块

            byte[] block = new byte[16];
            int offset = 0;
            while (offset < plaintext.length) {
                java.util.Arrays.fill(block, (byte) 0);
                int n = Math.min(16, plaintext.length - offset);
                System.arraycopy(plaintext, offset, block, 0, n);
                for (int i = 0; i < 16; i++) mac[i] ^= block[i];
                mac = aes.doFinal(mac);
                offset += n;
            }
            byte[] t = java.util.Arrays.copyOf(mac, macLen);

            // ---- CTR 加密 ----
            // A_i = aiFlags(L-1) || nonce || counter(L 字节 big-endian)
            byte[] ctrBase = new byte[16];
            ctrBase[0] = (byte) (L - 1); // A_i flags 仅含 L'
            System.arraycopy(nonce12, 0, ctrBase, 1, nonce12.length);

            byte[] s0 = aes.doFinal(withCounter(ctrBase, 0, L));
            byte[] tagOut = new byte[macLen];
            for (int i = 0; i < macLen; i++) tagOut[i] = (byte) (t[i] ^ s0[i]);

            byte[] out = new byte[plaintext.length + macLen];
            offset = 0;
            int ctr = 1;
            while (offset < plaintext.length) {
                byte[] si = aes.doFinal(withCounter(ctrBase, ctr, L));
                int n = Math.min(16, plaintext.length - offset);
                for (int i = 0; i < n; i++) {
                    out[offset + i] = (byte) (plaintext[offset + i] ^ si[i]);
                }
                offset += n;
                ctr++;
            }
            System.arraycopy(tagOut, 0, out, plaintext.length, macLen);
            return out;
        } catch (Exception e) {
            throw new RuntimeException("aes-ccm encrypt failed", e);
        }
    }

    private static byte[] withCounter(byte[] base, int counter, int L) {
        byte[] out = base.clone();
        writeBe(out, 16 - L, counter, L);
        return out;
    }

    private static void writeBe(byte[] buf, int pos, long value, int len) {
        for (int i = 0; i < len; i++) {
            buf[pos + i] = (byte) ((value >> (8 * (len - 1 - i))) & 0xff);
        }
    }

    // ==================== AES-CTR（V2 数据信道） ====================

    /**
     * V2/V5 数据包加密：AES-CTR，密钥本身充当 IV（照抄 Gadgetbridge encryptV2）。
     */
    public static byte[] aesCtr(byte[] key16, byte[] data, boolean encrypt) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key16, "AES"),
                    new IvParameterSpec(key16));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("aes-ctr failed", e);
        }
    }
}
