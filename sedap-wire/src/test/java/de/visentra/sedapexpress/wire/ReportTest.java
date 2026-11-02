package de.visentra.sedapexpress.wire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.Level;

import org.junit.BeforeClass;
import org.junit.Test;

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;
import de.visentra.sedapexpress.compat.Base64;
import de.visentra.sedapexpress.wire.check.FuzzRunner;
import de.visentra.sedapexpress.wire.check.Result;
import de.visentra.sedapexpress.wire.check.SmokeChecks;
import de.visentra.sedapexpress.wire.check.VectorSuite;

public class ReportTest {

    @BeforeClass
    public static void quiet() {
        java.util.logging.Logger.getLogger("").setLevel(Level.OFF);
        SEDAPExpressMessage.deserialize("HEARTBEAT");
    }

    private static void write(String name, List<String> lines) throws IOException {
        final Path dir = Paths.get("build", "reports", "l0");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), lines, StandardCharsets.UTF_8);
    }

    private static List<Result> runVectors() throws Exception {
        return VectorSuite.run(new String(Files.readAllBytes(Paths.get("..", "vectors", "golden-vectors.json")),
                StandardCharsets.UTF_8));
    }

    private static List<String> lines(List<Result> results) {
        final List<String> lines = new ArrayList<>();
        for (final Result result : results) {
            lines.add(result.toString());
        }
        return lines;
    }

    /**
     * Where upstream differs from the vectors, with the reason. A new difference or one that went away
     * fails the build, so an upstream update cannot change wire behaviour unnoticed.
     */
    private static final Map<String, String> KNOWN = new TreeMap<>();
    static {
        final String strict = "upstream parses leniently, the Kotlin layer must reject this";
        final String twoDigits = "upstream only accepts one digit, the Kotlin layer must read 00-04";
        final String harness = "same meaning, only represented differently";
        KNOWN.put("parse[3] OWNUNIT reject", strict);
        KNOWN.put("parse[6] CONTACT reject", strict);
        KNOWN.put("parse[7] CONTACT reject", strict);
        KNOWN.put("parse[10] CONTACT reject", strict);
        KNOWN.put("parse[11] CONTACT reject", strict);
        KNOWN.put("parse[16] TEXT reject", strict);
        KNOWN.put("text_kind.read[01]", twoDigits);
        KNOWN.put("text_kind.read[02]", twoDigits);
        KNOWN.put("text_kind.read[03]", twoDigits);
        KNOWN.put("text_kind.read[04]", twoDigits);
        KNOWN.put("parse[14] TEXT .kind", twoDigits);
        KNOWN.put("parse[15] TEXT .kind", twoDigits);
        KNOWN.put("parse[2] OWNUNIT .name", harness + ": null instead of empty");
        KNOWN.put("parse[14] TEXT .recipient", harness + ": null instead of empty");
        KNOWN.put("parse[4] CONTACT .source", harness + ": a set, upstream sorts it");
    }

    private static final String[] KNOWN_SMOKE_REJECTS = {
        "GRAPHIC;79;0195238E35AD;910E;U;;;FFDA;;08;1;FF800000;;;BASE64;QXJlYSBBbHBoYQ==;53.43;9.45;0;1000",
        "GRAPHIC;78;0195238E45AD;910E;U;;;A327;;01;1;80808000;;FFFF0000;;Transit;54.23,12.86#54.30,12.9#54.55,13.30",
    };

    @Test
    public void vectors() throws Exception {
        final List<Result> results = runVectors();
        write("vectors.txt", lines(results));
        final Set<String> failed = new TreeSet<>();
        for (final Result result : results) {
            if (result.status == Result.Status.FAIL) {
                failed.add(result.id);
            }
        }
        assertEquals(KNOWN.keySet(), failed);
    }

    /**
     * Documents why the Kotlin layer has to check the MAC over the received line: upstream rebuilds the
     * text from the parsed fields, and that text can differ from what the sender signed.
     */
    @Test
    public void macOverParsedMessageDiffersFromMacOverReceivedLine() throws Exception {
        final byte[] key = "geheim".getBytes(StandardCharsets.ISO_8859_1);
        for (final String line : new String[] {
            "TEXT;7B;0195238E285B;324E;U;;0000;ORKA;04;NONE;hello",
            "CONTACT;5E;0191C643A8AF;83C5;R;;0000;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;Name;AR;SFSPCLFF-------",
            "OWNUNIT;11;0191C643A8AF;22AA;U;FALSE;0000;77.88;-10.12;5577.0",
        }) {
            final javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            final String signedBySender = MACUtils.calcAdler32Checksum(
                    MACUtils.hexFormatter.formatHex(hmac.doFinal(line.getBytes(StandardCharsets.ISO_8859_1))));
            final SEDAPExpressMessage parsed = SEDAPExpressMessage.deserialize(line);
            parsed.setMAC(signedBySender);
            assertFalse(line, MACUtils.isMessageAuthentic(key, parsed));
        }
    }

    @Test
    public void onlyKnownIcdSamplesAreRejected() {
        final Set<String> rejected = new TreeSet<>();
        for (final String line : SmokeChecks.icdSamples()) {
            if (SEDAPExpressMessage.deserialize(line) == null) {
                rejected.add(line);
            }
        }
        assertEquals(new TreeSet<>(java.util.Arrays.asList(KNOWN_SMOKE_REJECTS)), rejected);
    }

    @Test
    public void defaultLocaleDoesNotChangeAnyResult() throws Exception {
        final Locale original = Locale.getDefault();
        final List<String> reference = lines(runVectors());
        try {
            for (final Locale locale : new Locale[] {Locale.GERMANY, new Locale("tr", "TR"), new Locale("ar", "SA")}) {
                Locale.setDefault(locale);
                assertEquals(locale.toString(), reference, lines(runVectors()));
            }
        } finally {
            Locale.setDefault(original);
        }
    }

    @Test
    public void smoke() throws IOException {
        write("smoke.txt", lines(SmokeChecks.run()));
    }

    @Test
    public void compressionBombIsRefused() throws IOException {
        final java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        final java.util.zip.Deflater deflater = new java.util.zip.Deflater(9, true);
        try (java.util.zip.DeflaterOutputStream out = new java.util.zip.DeflaterOutputStream(raw, deflater)) {
            out.write("HEARTBEAT;07;01A19BD5FCD0;MESE;U;;;".getBytes(StandardCharsets.ISO_8859_1));
            out.write(new byte[64 * 1024 * 1024]);
        }
        assertNull(CompressUtils.decompressMessage(Base64.toBase64String(raw.toByteArray())));
    }

    @Test
    public void fuzz() throws IOException {
        final FuzzRunner fuzz = FuzzRunner.run(20261102L, 300_000);
        final List<String> lines = new ArrayList<>();
        lines.add("iterations " + fuzz.iterations);
        for (final Map.Entry<String, String> escape : fuzz.escapes.entrySet()) {
            lines.add(escape.getKey() + " | " + escape.getValue());
        }
        write("fuzz.txt", lines);
    }
}
