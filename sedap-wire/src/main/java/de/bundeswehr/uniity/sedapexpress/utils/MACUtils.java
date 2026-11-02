package de.bundeswehr.uniity.sedapexpress.utils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.Adler32;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.visentra.sedapexpress.compat.HexFormat;

/**
 * Android replacement for upstream's MACUtils with the same package and API, but without
 * BouncyCastle: the upstream class registers the BouncyCastle provider globally, which is not
 * acceptable inside the ATAK process. MacCompatibilityTest compares every algorithm with
 * BouncyCastle.
 *
 * <p>Deliberate difference: isMessageAuthentic keeps the received MAC and compares against it.
 * Upstream's calc methods overwrite the MAC field first, so its check compares with "0000". It also
 * does not throw for keys that are invalid for AES, and it ignores the case of the received MAC.
 */
public class MACUtils {

    private static final int TRUNCATED_MAC_BYTES = 4;
    private static final int AES_BLOCK_BYTES = 16;
    private static final String MAC_PLACEHOLDER = "0000";

    public static HexFormat hexFormatter = HexFormat.of().withUpperCase();

    public static SEDAPExpressMessage setMACFieldWith32BitHMAC(final byte[] key, final SEDAPExpressMessage message) {
        message.setMAC(MACUtils.calc32BitHMAC(key, message));
        return message;
    }

    public static SEDAPExpressMessage setMACFieldWith32BitCMAC(final byte[] key, final SEDAPExpressMessage message) {
        message.setMAC(MACUtils.calc32BitCMAC(key, message));
        return message;
    }

    public static SEDAPExpressMessage setMACFieldWith32BitGMAC(final byte[] key, final byte[] initVector,
            final SEDAPExpressMessage message) {
        message.setMAC(MACUtils.calc32BitGMAC(key, initVector, message));
        return message;
    }

    public static String calcAdler32Checksum(String str) {
        final Adler32 adler = new Adler32();
        adler.update(str.getBytes(StandardCharsets.ISO_8859_1));
        return MACUtils.hexFormatter.toHexDigits(adler.getValue(), 8);
    }

    public static String calc32BitHMAC(final byte[] key, final SEDAPExpressMessage message) {
        return MACUtils.calcAdler32Checksum(MACUtils.calcHMAC(key, message));
    }

    public static String calcHMAC(final byte[] key, final SEDAPExpressMessage message) {
        try {
            final Mac mac = Mac.getInstance("HmacSHA256");
            // SecretKeySpec rejects an empty key. HMAC pads keys with zero bytes, so one zero byte
            // gives the same result as the empty key BouncyCastle would accept.
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, "HmacSHA256"));
            return MACUtils.hexFormatter.formatHex(mac.doFinal(MACUtils.macContent(message)));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("HMAC calculation failed", e);
        }
    }

    public static String calc32BitCMAC(final byte[] key, final SEDAPExpressMessage message) {
        return MACUtils.calcAdler32Checksum(MACUtils.calcCMAC(key, message));
    }

    /** AES-CMAC (NIST SP 800-38B), truncated to 32 bits like upstream. */
    public static String calcCMAC(final byte[] key, final SEDAPExpressMessage message) {
        final byte[] content = MACUtils.macContent(message);
        try {
            final Cipher aes = Cipher.getInstance("AES/ECB/NoPadding");
            aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            final byte[] tag = MACUtils.aesCmac(aes, content);
            return MACUtils.hexFormatter.formatHex(Arrays.copyOf(tag, TRUNCATED_MAC_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("CMAC calculation failed", e);
        }
    }

    public static String calc32BitGMAC(final byte[] key, final byte[] initVector, final SEDAPExpressMessage message) {
        return MACUtils.calcAdler32Checksum(MACUtils.calcGMAC(key, initVector, message));
    }

    /** AES-GMAC (NIST SP 800-38D), truncated to 32 bits like upstream. */
    public static String calcGMAC(final byte[] key, final byte[] initVector, final SEDAPExpressMessage message) {
        final byte[] content = MACUtils.macContent(message);
        try {
            final Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            // JCA only allows tags of at least 96 bits. A shorter GCM tag is defined as the leading
            // bits of the full tag, so the full tag is computed and then cut.
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, initVector));
            gcm.updateAAD(content);
            return MACUtils.hexFormatter.formatHex(Arrays.copyOf(gcm.doFinal(), TRUNCATED_MAC_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("GMAC calculation failed", e);
        }
    }

    public static boolean isMessageAuthentic(final byte[] key, final SEDAPExpressMessage message) {
        return MACUtils.isMessageAuthentic(key, null, message);
    }

    /**
     * @param initVector IV for GMAC, or null to check HMAC and CMAC
     * @return true if the MAC of the message matches. The MAC field keeps its received value.
     */
    public static boolean isMessageAuthentic(final byte[] key, final byte[] initVector,
            final SEDAPExpressMessage message) {
        final String received = message.getMAC();
        if (received == null || received.isEmpty()) {
            return false;
        }
        try {
            if (initVector != null) {
                return MACUtils.matches(received, MACUtils.calcGMAC(key, initVector, message))
                        | MACUtils.matches(received, MACUtils.calc32BitGMAC(key, initVector, message));
            }
            boolean authentic = MACUtils.matches(received, MACUtils.calcHMAC(key, message))
                    | MACUtils.matches(received, MACUtils.calc32BitHMAC(key, message));
            if (MACUtils.isAesKeyLength(key)) {
                authentic |= MACUtils.matches(received, MACUtils.calcCMAC(key, message))
                        | MACUtils.matches(received, MACUtils.calc32BitCMAC(key, message));
            }
            return authentic;
        } catch (IllegalArgumentException e) {
            return false;
        } finally {
            message.setMAC(received);
        }
    }

    private static byte[] macContent(final SEDAPExpressMessage message) {
        message.setMAC(MAC_PLACEHOLDER);
        return message.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static boolean matches(String received, String calculated) {
        return MessageDigest.isEqual(received.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.ISO_8859_1),
                calculated.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static boolean isAesKeyLength(byte[] key) {
        return key.length == 16 || key.length == 24 || key.length == 32;
    }

    private static byte[] aesCmac(Cipher aes, byte[] content) throws GeneralSecurityException {
        final byte[] k1 = MACUtils.doubled(aes.doFinal(new byte[AES_BLOCK_BYTES]));
        final byte[] k2 = MACUtils.doubled(k1);

        final int blocks = content.length == 0 ? 1 : (content.length + AES_BLOCK_BYTES - 1) / AES_BLOCK_BYTES;
        final int lastStart = (blocks - 1) * AES_BLOCK_BYTES;
        final boolean complete = content.length != 0 && content.length - lastStart == AES_BLOCK_BYTES;

        final byte[] last = new byte[AES_BLOCK_BYTES];
        System.arraycopy(content, lastStart, last, 0, content.length - lastStart);
        if (!complete) {
            last[content.length - lastStart] = (byte) 0x80;
        }
        MACUtils.xorInto(last, complete ? k1 : k2, 0);

        byte[] state = new byte[AES_BLOCK_BYTES];
        for (int block = 0; block < blocks - 1; block++) {
            MACUtils.xorInto(state, content, block * AES_BLOCK_BYTES);
            state = aes.doFinal(state);
        }
        MACUtils.xorInto(state, last, 0);
        return aes.doFinal(state);
    }

    private static void xorInto(byte[] target, byte[] source, int offset) {
        for (int i = 0; i < AES_BLOCK_BYTES; i++) {
            target[i] ^= source[offset + i];
        }
    }

    private static byte[] doubled(byte[] block) {
        final byte[] result = new byte[AES_BLOCK_BYTES];
        for (int i = 0; i < AES_BLOCK_BYTES; i++) {
            final int next = i + 1 < AES_BLOCK_BYTES ? (block[i + 1] & 0xFF) >> 7 : 0;
            result[i] = (byte) ((block[i] << 1) | next);
        }
        if ((block[0] & 0x80) != 0) {
            result[AES_BLOCK_BYTES - 1] ^= (byte) 0x87;
        }
        return result;
    }
}
