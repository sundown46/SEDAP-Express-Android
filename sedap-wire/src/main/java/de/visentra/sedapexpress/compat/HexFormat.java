package de.visentra.sedapexpress.compat;

/**
 * Replacement for the parts of {@code java.util.HexFormat} that upstream uses. That class only
 * exists from API 34. Behaviour matches the JDK class, checked by HexFormatCompatTest.
 */
public final class HexFormat {

    private static final char[] UPPER = "0123456789ABCDEF".toCharArray();
    private static final char[] LOWER = "0123456789abcdef".toCharArray();
    private static final HexFormat LOWER_CASE = new HexFormat(LOWER);
    private static final HexFormat UPPER_CASE = new HexFormat(UPPER);

    private final char[] digits;

    private HexFormat(char[] digits) {
        this.digits = digits;
    }

    public static HexFormat of() {
        return LOWER_CASE;
    }

    public HexFormat withUpperCase() {
        return UPPER_CASE;
    }

    public String formatHex(byte[] bytes) {
        final StringBuilder result = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            result.append(this.digits[(value >> 4) & 0xF]).append(this.digits[value & 0xF]);
        }
        return result.toString();
    }

    public String toHexDigits(byte value) {
        return this.toHexDigits(value & 0xFFL, 2);
    }

    public String toHexDigits(short value) {
        return this.toHexDigits(value & 0xFFFFL, 4);
    }

    public String toHexDigits(int value) {
        return this.toHexDigits(value & 0xFFFFFFFFL, 8);
    }

    public String toHexDigits(long value) {
        return this.toHexDigits(value, 16);
    }

    public String toHexDigits(long value, int digitCount) {
        if (digitCount < 0 || digitCount > 16) {
            throw new IllegalArgumentException("number of digits: " + digitCount);
        }
        final char[] result = new char[digitCount];
        long rest = value;
        for (int i = digitCount - 1; i >= 0; i--) {
            result[i] = this.digits[(int) (rest & 0xF)];
            rest >>>= 4;
        }
        return new String(result);
    }

    public static int fromHexDigits(CharSequence text) {
        return (int) parse(text, 8);
    }

    public static long fromHexDigitsToLong(CharSequence text) {
        return parse(text, 16);
    }

    private static long parse(CharSequence text, int maxDigits) {
        final int length = text.length();
        if (length > maxDigits) {
            throw new IllegalArgumentException("more than " + maxDigits + " hexadecimal digits: " + text);
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            final int digit = Character.digit(text.charAt(i), 16);
            if (digit < 0 || text.charAt(i) > 'f') {
                throw new NumberFormatException("not a hexadecimal digit: \"" + text.charAt(i) + "\"");
            }
            value = (value << 4) | digit;
        }
        return value;
    }
}
