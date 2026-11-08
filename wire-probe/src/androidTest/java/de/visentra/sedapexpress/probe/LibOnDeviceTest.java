package de.visentra.sedapexpress.probe;

import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/** The Kotlin layer on the device (L1.8). Logs to SEDAPProbe like the wire checks, and fails on any FAIL. */
public class LibOnDeviceTest {

    @Test
    public void lib() {
        final List<String> results = LibChecks.runAndLog();
        for (final String result : results) {
            assertTrue(result, result.startsWith("ok "));
        }
    }
}
