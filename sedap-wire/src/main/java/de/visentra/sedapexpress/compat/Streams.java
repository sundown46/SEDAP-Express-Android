package de.visentra.sedapexpress.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Replaces {@code InputStream.readAllBytes()}, which only exists from API 33. Unlike the original it
 * stops at a size limit, so that a small compressed message cannot exhaust the memory (NFR-REL-02).
 */
public final class Streams {

    /** Far above any real SEDAP-Express line. */
    public static final int MAX_BYTES = 1024 * 1024;

    private Streams() {
    }

    public static byte[] readAll(InputStream input) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        for (int n = input.read(buffer); n != -1; n = input.read(buffer)) {
            if (out.size() + n > MAX_BYTES) {
                throw new IOException("stream exceeds " + MAX_BYTES + " bytes");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
