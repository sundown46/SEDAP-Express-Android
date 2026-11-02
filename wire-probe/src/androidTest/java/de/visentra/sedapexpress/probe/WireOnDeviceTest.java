package de.visentra.sedapexpress.probe;

import android.os.Build;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.junit.Before;
import org.junit.Test;

import de.visentra.sedapexpress.wire.check.FuzzRunner;
import de.visentra.sedapexpress.wire.check.Result;
import de.visentra.sedapexpress.wire.check.SmokeChecks;
import de.visentra.sedapexpress.wire.check.VectorSuite;

/** Writes every result to logcat with the tag SEDAPProbe. The report compares this with the JVM run. */
public class WireOnDeviceTest {

    private static final String TAG = "SEDAPProbe";

    @Before
    public void quiet() {
        Logger.getLogger("").setLevel(Level.OFF);
        Log.i(TAG, "api=" + Build.VERSION.SDK_INT + " abi=" + Build.CPU_ABI);
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

    @Test
    public void vectors() throws Exception {
        final InputStream in = InstrumentationRegistry.getInstrumentation().getContext().getAssets()
                .open("golden-vectors.json");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        for (int n = in.read(buffer); n > 0; n = in.read(buffer)) {
            out.write(buffer, 0, n);
        }
        log("vector", VectorSuite.run(new String(out.toByteArray(), StandardCharsets.UTF_8)));
    }

    @Test
    public void smoke() {
        log("smoke", SmokeChecks.run());
    }

    @Test
    public void fuzz() {
        final FuzzRunner fuzz = FuzzRunner.run(20261102L, 20_000);
        for (final Map.Entry<String, String> escape : fuzz.escapes.entrySet()) {
            Log.i(TAG, "fuzz " + escape.getKey() + " | " + escape.getValue());
        }
        Log.i(TAG, "fuzz iterations=" + fuzz.iterations + " kinds=" + fuzz.escapes.size());
    }
}
