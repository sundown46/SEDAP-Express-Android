package de.visentra.sedapexpress.compat;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

/** Decodes a Diffie-Hellman public key through the platform provider instead of BouncyCastle. */
public final class DhPublicKeys {

    private DhPublicKeys() {
    }

    public static PublicKey decode(byte[] encoded) {
        try {
            return KeyFactory.getInstance("DH").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("invalid DH public key", e);
        }
    }
}
