package de.visentra.sedapexpress.compat;

import java.util.Arrays;
import java.util.stream.IntStream;

/** Replaces {@code String.chars()}, which only exists from API 24 and is not desugared. */
public final class Strings {

    private Strings() {
    }

    public static IntStream chars(String text) {
        final int[] values = new int[text.length()];
        for (int i = 0; i < values.length; i++) {
            values[i] = text.charAt(i);
        }
        return Arrays.stream(values);
    }
}
