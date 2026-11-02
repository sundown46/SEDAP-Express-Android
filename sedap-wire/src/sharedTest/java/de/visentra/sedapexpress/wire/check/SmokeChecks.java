package de.visentra.sedapexpress.wire.check;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.bundeswehr.uniity.sedapexpress.messages.COMMAND;
import de.bundeswehr.uniity.sedapexpress.messages.GRAPHIC;
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;

/**
 * Exercises every code path that needs newer platform APIs, so that a missing method shows up on an
 * old device. It checks that nothing breaks, not the wire content. That is the job of the vectors.
 */
public final class SmokeChecks {

    /** Sample lines from the public ICD, one or more per message type. */
    static final String[] ICD_SAMPLES = {
        "OWNUNIT;11;0191C643A8AF;22AA;U;FALSE;4389F10D;77.88;-10.12;5577.0;33.44;55.66;1.1;-2.2;3.3;Ownunit;SFGPIB----H----",
        "CONTACT;5E;0191C643A8AF;83C5;R;;;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;FGS Bayern;AR;SFSPCLFF-------;;;;VXNlIENIMjI=",
        "POINT;5E;0000661D4410;66A3;R;;;100;FALSE;53.32;8.11;0;;;;120;275;;;;;;;Target Alpha;gffppt---------;;VGFyZ2V0IHBvaW50",
        "EMISSION;5E;0195238E15AD;66A3;R;;;100;;53.32;8.11;0;;;;20;8725000.0#8735000.0;20000;3;00;02;06;10233;;U0EtOA==",
        "METEO;1C;0195238E25AD;74BE;U;;;15.4;15.5;;;;;10.2;72;1005;25;111;50;2500;33;RefPoint1",
        "TEXT;7B;0195238E285B;324E;U;;;ORKA;04;BASE64;IlRoaXMgaXMgYSBjaGF0IG1lc3NhZ2UhIg==",
        "GRAPHIC;79;0195238E35AD;910E;U;;;FFDA;;08;1;FF800000;;;BASE64;QXJlYSBBbHBoYQ==;53.43;9.45;0;1000",
        "GRAPHIC;78;0195238E45AD;910E;U;;;A327;;01;1;80808000;;FFFF0000;;Transit;54.23,12.86#54.30,12.9#54.55,13.30",
        // Same as the ICD samples above, but with a one-digit graphic type, the only form upstream accepts.
        "GRAPHIC;79;0195238E35AD;910E;U;;;FFDA;;8;1;FF800000;;;BASE64;QXJlYSBBbHBoYQ==;53.43;9.45;0;1000",
        "GRAPHIC;78;0195238E45AD;910E;U;;;A327;;1;1;80808000;;FFFF0000;;Transit;54.23,12.86#54.30,12.9#54.55,13.30",
        "COMMAND;55;0195238E25AD;5BCD;S;TRUE;;ORKA;1111;01;;24;53.32;8.11;1000;5",
        "COMMAND;29;0195238E35AD;E4B3;C;TRUE;;Drone1;;00;;FF;OPEN_BAY",
        "STATUS;15;0195238E25AD;75DA;U;;;4;2;MLG#20;;Accu1#50;;443D;01;MTAuMC4wLjEzMg==;;RnVsbHkgb3BlcmF0aW9uYWw=",
        "ACKNOWLEDGE;18;0195238E25AD;129E;R;;;LASSY;COMMAND;2B",
        "RESEND;5D;0195238E25AD;129E;R;;;LASSY;TEXT;5D",
        "GENERIC;5E;0195238E25AD;66A3;R;TRUE;;BINARY;BASE64;U2FtcGxlIGJpbmFyeSBkYXRhIEdyZWV0aW5ncyA6RA==",
        "HEARTBEAT;42;0195238E25AD;89AD;U;;;ORKA",
        "TIMESYNC;12;;FE2A;U;;;0191C643A8AF",
        "KEYEXCHANGE;12;0195238E25AD;FE2A;U;;;ORKA",
    };

    private SmokeChecks() {
    }

    public static String[] icdSamples() {
        return ICD_SAMPLES.clone();
    }

    private interface Body {
        void run() throws Exception;
    }

    private static void check(List<Result> results, String id, Body body) {
        try {
            body.run();
            results.add(new Result(id, Result.Status.PASS, ""));
        } catch (Throwable t) {
            results.add(new Result(id, Result.Status.FAIL, "threw " + VectorSuite.describe(t)));
        }
    }

    public static List<Result> run() {
        final List<Result> results = new ArrayList<>();
        final byte[] key = "expressexpressex".getBytes(StandardCharsets.ISO_8859_1);
        final byte[] iv = "0123456789AB".getBytes(StandardCharsets.ISO_8859_1);

        for (final String line : ICD_SAMPLES) {
            final String id = "smoke.roundtrip " + line.substring(0, line.indexOf(';')) + " " + line.hashCode();
            check(results, id, () -> {
                final SEDAPExpressMessage message = SEDAPExpressMessage.deserialize(line);
                if (message == null) {
                    throw new IllegalStateException("rejected");
                }
                message.toString();
                message.hashCode();
                message.equals(SEDAPExpressMessage.deserialize(line));
            });
        }
        final SEDAPExpressMessage sample = SEDAPExpressMessage.deserialize(ICD_SAMPLES[0]);
        check(results, "smoke.hmac", () -> MACUtils.calc32BitHMAC(key, sample));
        check(results, "smoke.cmac", () -> MACUtils.calc32BitCMAC(key, sample));
        check(results, "smoke.gmac", () -> MACUtils.calc32BitGMAC(key, iv, sample));
        check(results, "smoke.compress", () -> {
            if (CompressUtils.decompressMessage(CompressUtils.compressMessage(sample)) == null) {
                throw new IllegalStateException("round trip returned null");
            }
        });
        check(results, "smoke.recordToCsv.multiple", () -> expect("1.5;2.5;3.5",
                SEDAPExpressMessage.objectToCSV(new COMMAND.Rotate(1.5, 2.5, 3.5))));
        check(results, "smoke.recordToCsv.empty", () -> expect("",
                SEDAPExpressMessage.objectToCSV(new COMMAND.Restart())));
        check(results, "smoke.recordToCsv.graphic", () -> expect("1;2;3",
                SEDAPExpressMessage.objectToCSV(new GRAPHIC.Point(1, 2, 3))));
        return results;
    }

    private static void expect(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException("expected <" + expected + "> but got <" + actual + ">");
        }
    }
}
