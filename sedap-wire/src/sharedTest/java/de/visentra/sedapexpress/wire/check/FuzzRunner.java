package de.visentra.sedapexpress.wire.check;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils;

/**
 * Feeds garbage to the parsers with a fixed seed (NFR-REL-02). Anything that escapes deserialize,
 * toString or decompressMessage counts as a finding, grouped by exception type and source frame.
 */
public final class FuzzRunner {

    private static final String[] NAMES = {"OWNUNIT", "CONTACT", "POINT", "EMISSION", "METEO", "TEXT", "GRAPHIC",
        "COMMAND", "STATUS", "ACKNOWLEDGE", "RESEND", "GENERIC", "HEARTBEAT", "TIMESYNC", "KEYEXCHANGE"};
    private static final String[] TOKENS = {"", ";", ";;", "NaN", "Infinity", "-1", "1e999", "FF", "ZZ", "TRUE", "FALSE",
        "BASE64", "NONE", "%%%", "=", "#", ",", "\n", "\u0000", "\u00FF", "99999999999999999999", "0.0.0", "U", "X", "-"};

    public final int iterations;
    /** Finding key mapped to count and one sample input. */
    public final Map<String, String> escapes = new TreeMap<>();
    private final Map<String, Integer> counts = new TreeMap<>();

    private FuzzRunner(int iterations) {
        this.iterations = iterations;
    }

    public static FuzzRunner run(long seed, int iterations) {
        final FuzzRunner runner = new FuzzRunner(iterations);
        final Random random = new Random(seed);
        for (int i = 0; i < iterations; i++) {
            runner.one(next(random));
        }
        // Lines far beyond any real message.
        final StringBuilder longLine = new StringBuilder("TEXT;01;01A19BD5FCD0;MESE;U;;;ORKA;4;NONE;");
        for (int i = 0; i < 1_000_000; i++) {
            longLine.append('x');
        }
        runner.one(longLine.toString());
        final StringBuilder manyFields = new StringBuilder("CONTACT");
        for (int i = 0; i < 200_000; i++) {
            manyFields.append(';');
        }
        runner.one(manyFields.toString());
        return runner;
    }

    private void one(String input) {
        try {
            final SEDAPExpressMessage parsed = SEDAPExpressMessage.deserialize(input);
            if (parsed != null) {
                parsed.toString();
                parsed.hashCode();
            }
        } catch (Throwable t) {
            this.record("deserialize", t, input);
        }
        try {
            CompressUtils.decompressMessage(input);
        } catch (Throwable t) {
            this.record("decompress", t, input);
        }
    }

    private void record(String entry, Throwable t, String input) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String frame = "?";
        for (final StackTraceElement element : root.getStackTrace()) {
            if (element.getClassName().startsWith("de.")) {
                frame = element.getFileName() + ":" + element.getLineNumber();
                break;
            }
        }
        final String key = entry + " " + root.getClass().getSimpleName() + " at " + frame;
        final int count = this.counts.merge(key, 1, Integer::sum);
        this.escapes.put(key, count + "x, e.g. " + printable(input));
    }

    private static String printable(String input) {
        final String cut = input.length() > 120 ? input.substring(0, 120) + "..." : input;
        return cut.replace("\n", "\\n").replace("\u0000", "\\0");
    }

    private static String next(Random random) {
        switch (random.nextInt(4)) {
            case 0:
                return mutate(random, SmokeChecks.ICD_SAMPLES[random.nextInt(SmokeChecks.ICD_SAMPLES.length)]);
            case 1:
                final String sample = SmokeChecks.ICD_SAMPLES[random.nextInt(SmokeChecks.ICD_SAMPLES.length)];
                return sample.substring(0, random.nextInt(sample.length() + 1));
            case 2:
                final StringBuilder built = new StringBuilder(NAMES[random.nextInt(NAMES.length)]);
                final int fields = random.nextInt(40);
                for (int i = 0; i < fields; i++) {
                    built.append(';').append(TOKENS[random.nextInt(TOKENS.length)]);
                }
                return built.toString();
            default:
                final byte[] bytes = new byte[random.nextInt(300)];
                random.nextBytes(bytes);
                return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    private static String mutate(Random random, String sample) {
        final String[] fields = sample.split(";", -1);
        final int changes = 1 + random.nextInt(3);
        for (int i = 0; i < changes; i++) {
            fields[1 + random.nextInt(fields.length - 1)] = TOKENS[random.nextInt(TOKENS.length)];
        }
        return String.join(";", fields);
    }
}
