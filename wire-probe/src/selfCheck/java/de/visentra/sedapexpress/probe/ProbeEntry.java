package de.visentra.sedapexpress.probe;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.visentra.sedapexpress.wire.check.FuzzRunner;
import de.visentra.sedapexpress.wire.check.Result;
import de.visentra.sedapexpress.wire.check.SmokeChecks;
import de.visentra.sedapexpress.wire.check.VectorSuite;

/**
 * Runs all checks inside the app, so that R8 treats them like real callers of the library. This is
 * what a minified product sees. A separate test APK would not show that.
 */
final class ProbeEntry {

    private static final String TAG = "SEDAPProbe";

    private ProbeEntry() {
    }

    static String run(Context context) {
        new Thread(() -> runChecks(context)).start();
        return "running, see logcat tag " + TAG;
    }

    private static void runChecks(Context context) {
        Logger.getLogger("").setLevel(Level.OFF);
        try {
            log("vector", VectorSuite.run(readVectors(context)));
            log("smoke", SmokeChecks.run());
            final FuzzRunner fuzz = FuzzRunner.run(20261102L, 20_000);
            for (final Map.Entry<String, String> escape : fuzz.escapes.entrySet()) {
                Log.i(TAG, "fuzz " + escape.getKey() + " | " + escape.getValue());
            }
            Log.i(TAG, "fuzz iterations=" + fuzz.iterations + " kinds=" + fuzz.escapes.size());
            LibChecks.runAndLog();
        } catch (Throwable t) {
            Log.e(TAG, "aborted", t);
        }
        Log.i(TAG, "done");
    }

    private static String readVectors(Context context) throws IOException {
        try (InputStream in = context.getAssets().open("golden-vectors.json")) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void log(String section, List<Result> results) {
        int fail = 0;
        for (final Result result : results) {
            Log.i(TAG, section + " " + result);
            if (result.status == Result.Status.FAIL) {
                fail++;
            }
        }
        Log.i(TAG, section + " total=" + results.size() + " fail=" + fail);
    }
}
