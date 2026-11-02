package de.visentra.sedapexpress.wire;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.engines.AESLightEngine;
import org.bouncycastle.crypto.macs.CMac;
import org.bouncycastle.crypto.macs.GMac;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.ParametersWithIV;
import org.junit.Test;

import de.bundeswehr.uniity.sedapexpress.messages.COMMAND;
import de.bundeswehr.uniity.sedapexpress.messages.GRAPHIC;
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;
import de.visentra.sedapexpress.compat.Base64;
import de.visentra.sedapexpress.compat.DecoderException;
import de.visentra.sedapexpress.compat.HexFormat;
import de.visentra.sedapexpress.compat.RecordComponent;

/** Compares each replacement with the class it stands in for. */
public class CompatTest {

    private static final java.util.HexFormat JDK_HEX = java.util.HexFormat.of().withUpperCase();
    private static final byte[] KEY = "expressexpressex".getBytes(StandardCharsets.ISO_8859_1);

    @Test
    public void hexFormatMatchesJdk() {
        final Random random = new Random(1);
        final HexFormat ours = HexFormat.of().withUpperCase();
        for (int i = 0; i < 20_000; i++) {
            final long value = random.nextLong();
            final int digits = random.nextInt(17);
            assertEquals(JDK_HEX.toHexDigits(value, digits), ours.toHexDigits(value, digits));
            assertEquals(JDK_HEX.toHexDigits((byte) value), ours.toHexDigits((byte) value));
            assertEquals(JDK_HEX.toHexDigits((short) value), ours.toHexDigits((short) value));
            assertEquals(JDK_HEX.toHexDigits((int) value), ours.toHexDigits((int) value));
            assertEquals(JDK_HEX.toHexDigits(value), ours.toHexDigits(value));
            final byte[] bytes = new byte[random.nextInt(20)];
            random.nextBytes(bytes);
            assertEquals(JDK_HEX.formatHex(bytes), ours.formatHex(bytes));
            final String hex = JDK_HEX.toHexDigits(value, 1 + random.nextInt(8));
            assertEquals(java.util.HexFormat.fromHexDigits(hex), HexFormat.fromHexDigits(hex));
            assertEquals(java.util.HexFormat.fromHexDigitsToLong(hex), HexFormat.fromHexDigitsToLong(hex));
        }
    }

    @Test
    public void hexFormatRejectsWhatJdkRejects() {
        assertEquals(java.util.HexFormat.fromHexDigits(""), HexFormat.fromHexDigits(""));
        for (final String bad : new String[] {"G", "123456789", "-1", " 1", "١", "１"}) {
            assertThrows(bad, IllegalArgumentException.class, () -> java.util.HexFormat.fromHexDigits(bad));
            assertThrows(bad, IllegalArgumentException.class, () -> HexFormat.fromHexDigits(bad));
        }
    }

    @Test
    public void base64MatchesBouncyCastle() {
        final Random random = new Random(2);
        for (int i = 0; i < 20_000; i++) {
            final byte[] bytes = new byte[random.nextInt(64)];
            random.nextBytes(bytes);
            final String encoded = org.bouncycastle.util.encoders.Base64.toBase64String(bytes);
            assertEquals(encoded, Base64.toBase64String(bytes));
            assertArrayEquals(bytes, Base64.decode(encoded));
        }
        assertArrayEquals(org.bouncycastle.util.encoders.Base64.decode("VXNl IENI\nMjI="), Base64.decode("VXNl IENI\nMjI="));
    }

    @Test
    public void base64RejectsInvalidInput() {
        for (final String bad : new String[] {"%%%", "not base64!", "A", "AB", "ABC", "A===", "=AAA", "AA=A", "AAAA===="}) {
            assertThrows(bad, DecoderException.class, () -> Base64.decode(bad));
        }
    }

    @Test
    public void macAlgorithmsMatchBouncyCastle() {
        final Random random = new Random(3);
        final byte[] iv = new byte[12];
        for (int i = 0; i < 2_000; i++) {
            final byte[] key = new byte[new int[] {16, 24, 32}[random.nextInt(3)]];
            random.nextBytes(key);
            random.nextBytes(iv);
            final StringBuilder name = new StringBuilder();
            for (int c = random.nextInt(200); c > 0; c--) {
                name.append((char) ('a' + random.nextInt(26)));
            }
            final SEDAPExpressMessage message =
                    SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U;;;" + name);
            message.setMAC("0000");
            final byte[] content = message.toString().getBytes(StandardCharsets.ISO_8859_1);

            final HMac hmac = new HMac(new SHA256Digest());
            hmac.init(new KeyParameter(key));
            hmac.update(content, 0, content.length);
            final byte[] hmacOut = new byte[hmac.getMacSize()];
            hmac.doFinal(hmacOut, 0);
            assertEquals(JDK_HEX.formatHex(hmacOut), MACUtils.calcHMAC(key, message));

            final CMac cmac = new CMac(new AESLightEngine(), 32);
            cmac.init(new KeyParameter(key));
            cmac.update(content, 0, content.length);
            final byte[] cmacOut = new byte[cmac.getMacSize()];
            cmac.doFinal(cmacOut, 0);
            assertEquals(JDK_HEX.formatHex(cmacOut), MACUtils.calcCMAC(key, message));

            final GMac gmac = new GMac(GCMBlockCipher.newInstance(new AESLightEngine()), 32);
            gmac.init(new ParametersWithIV(new KeyParameter(key), iv));
            gmac.update(content, 0, content.length);
            final byte[] gmacOut = new byte[gmac.getMacSize()];
            gmac.doFinal(gmacOut, 0);
            assertEquals(JDK_HEX.formatHex(gmacOut), MACUtils.calcGMAC(key, iv, message));
        }
    }

    @Test
    public void cmacMatchesAtBlockBoundaries() {
        // Message lengths around the 16-byte block size take different branches in AES-CMAC.
        final byte[] key = new byte[16];
        final CMac cmac = new CMac(new AESLightEngine(), 32);
        for (int length = 0; length < 70; length++) {
            final StringBuilder name = new StringBuilder();
            for (int i = 0; i < length; i++) {
                name.append('x');
            }
            final SEDAPExpressMessage message =
                    SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U;;;" + name);
            message.setMAC("0000");
            final byte[] content = message.toString().getBytes(StandardCharsets.ISO_8859_1);
            cmac.init(new KeyParameter(key));
            cmac.update(content, 0, content.length);
            final byte[] out = new byte[cmac.getMacSize()];
            cmac.doFinal(out, 0);
            assertEquals(JDK_HEX.formatHex(out), MACUtils.calcCMAC(key, message));
        }
    }

    @Test
    public void macOfIcdSampleMatches() {
        // ICD 3.1: 32-bit CMAC with the password "expressexpressex".
        final SEDAPExpressMessage message = SEDAPExpressMessage.deserialize(
                "OWNUNIT;5E;0195236D151A;66A3;R;;089A01E7;53.32;8.11;0;5.5;21;22;;;FGS Bayern");
        assertEquals("089A01E7", MACUtils.calc32BitCMAC(KEY, message));
    }

    @Test
    public void authenticityCheckComparesTheReceivedMac() {
        final SEDAPExpressMessage good = SEDAPExpressMessage.deserialize(
                "OWNUNIT;5E;0195236D151A;66A3;R;;089A01E7;53.32;8.11;0;5.5;21;22;;;FGS Bayern");
        assertTrue(MACUtils.isMessageAuthentic(KEY, good));
        assertEquals("the received MAC stays in the message", "089A01E7", good.getMAC());

        final SEDAPExpressMessage forged = SEDAPExpressMessage.deserialize(
                "OWNUNIT;5E;0195236D151A;66A3;R;;0000;53.32;8.11;0;5.5;21;22;;;FGS Bayern");
        assertFalse("a MAC of 0000 must not count as authentic", MACUtils.isMessageAuthentic(KEY, forged));

        final SEDAPExpressMessage none = SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U");
        assertFalse(MACUtils.isMessageAuthentic(KEY, none));

        final SEDAPExpressMessage shortKey = SEDAPExpressMessage.deserialize("HEARTBEAT;00;01A19BD5FCD0;MESE;U;;CBD70E18");
        assertTrue("a key that is no AES key must not throw",
                MACUtils.isMessageAuthentic("geheim".getBytes(StandardCharsets.ISO_8859_1), shortKey));
    }

    @Test
    public void recordLayoutsMatchRealRecords() {
        int checked = 0;
        for (final Class<?> outer : new Class<?>[] {COMMAND.class, GRAPHIC.class}) {
            for (final Class<?> nested : outer.getDeclaredClasses()) {
                if (!nested.isRecord()) {
                    assertNull(nested.getName(), RecordComponent.of(nested));
                    continue;
                }
                final java.lang.reflect.RecordComponent[] real = nested.getRecordComponents();
                final RecordComponent[] ours = RecordComponent.of(nested);
                assertNotNull(nested.getName(), ours);
                assertEquals(nested.getName(), real.length, ours.length);
                for (int i = 0; i < real.length; i++) {
                    assertEquals(nested.getName(), real[i].getName(), ours[i].getName());
                    assertEquals(real[i].getAccessor(), ours[i].getAccessor());
                }
                checked++;
            }
        }
        assertEquals("all records declared upstream", 65, checked);
    }
}
