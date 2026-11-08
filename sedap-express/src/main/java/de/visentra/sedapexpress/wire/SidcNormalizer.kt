package de.visentra.sedapexpress.wire

import java.util.Locale

/** Brings a received SIDC into the form the symbol renderer expects. */
public object SidcNormalizer {

    private const val SIDC_LENGTH = 15

    /**
     * Trims, uppercases, maps the 2525 wildcard `*` to `-` and right-pads short codes to 15.
     * Returns null for empty input or characters outside A-Z, 0-9 and `-`.
     */
    public fun normalize(raw: String?): String? {
        if (raw == null) return null
        val sidc = raw.trim().uppercase(Locale.ROOT).replace('*', '-')
        if (sidc.isEmpty() || !sidc.all { it in 'A'..'Z' || it in '0'..'9' || it == '-' }) return null
        return sidc.padEnd(SIDC_LENGTH, '-')
    }
}
