package de.visentra.sedapexpress.compat;

import java.io.ByteArrayOutputStream;

/**
 * Replacement for BouncyCastle's Base64 as upstream uses it. It keeps BouncyCastle out of the
 * library, which could otherwise collide with the copy inside the ATAK process. Like the original
 * it ignores whitespace and rejects anything else that is not Base64.
 */
public final class Base64 {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    private Base64() {
    }

    public static String toBase64String(byte[] data) {
        final StringBuilder out = new StringBuilder((data.length + 2) / 3 * 4);
        for (int i = 0; i < data.length; i += 3) {
            final int remaining = data.length - i;
            final int b0 = data[i] & 0xFF;
            final int b1 = remaining > 1 ? data[i + 1] & 0xFF : 0;
            final int b2 = remaining > 2 ? data[i + 2] & 0xFF : 0;
            out.append(ALPHABET[b0 >> 2]);
            out.append(ALPHABET[((b0 & 0x3) << 4) | (b1 >> 4)]);
            out.append(remaining > 1 ? ALPHABET[((b1 & 0xF) << 2) | (b2 >> 6)] : '=');
            out.append(remaining > 2 ? ALPHABET[b2 & 0x3F] : '=');
        }
        return out.toString();
    }

    public static byte[] decode(String data) {
        final StringBuilder clean = new StringBuilder(data.length());
        for (int i = 0; i < data.length(); i++) {
            final char c = data.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') {
                clean.append(c);
            }
        }
        if (clean.length() % 4 != 0) {
            throw new DecoderException("invalid Base64 length");
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(clean.length() / 4 * 3);
        for (int i = 0; i < clean.length(); i += 4) {
            final boolean last = i + 4 == clean.length();
            final int c0 = value(clean.charAt(i));
            final int c1 = value(clean.charAt(i + 1));
            final char c2Char = clean.charAt(i + 2);
            final char c3Char = clean.charAt(i + 3);
            if (last && c2Char == '=' && c3Char == '=') {
                out.write((c0 << 2) | (c1 >> 4));
            } else if (last && c3Char == '=') {
                final int c2 = value(c2Char);
                out.write((c0 << 2) | (c1 >> 4));
                out.write(((c1 & 0xF) << 4) | (c2 >> 2));
            } else {
                final int c2 = value(c2Char);
                final int c3 = value(c3Char);
                out.write((c0 << 2) | (c1 >> 4));
                out.write(((c1 & 0xF) << 4) | (c2 >> 2));
                out.write(((c2 & 0x3) << 6) | c3);
            }
        }
        return out.toByteArray();
    }

    private static int value(char c) {
        if (c >= 'A' && c <= 'Z') {
            return c - 'A';
        }
        if (c >= 'a' && c <= 'z') {
            return c - 'a' + 26;
        }
        if (c >= '0' && c <= '9') {
            return c - '0' + 52;
        }
        if (c == '+') {
            return 62;
        }
        if (c == '/') {
            return 63;
        }
        throw new DecoderException("invalid Base64 character");
    }
}
