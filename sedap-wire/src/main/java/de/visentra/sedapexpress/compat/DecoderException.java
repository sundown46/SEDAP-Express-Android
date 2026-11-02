package de.visentra.sedapexpress.compat;

/** Same role as BouncyCastle's DecoderException: signals invalid Base64 input. */
public class DecoderException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public DecoderException(String message) {
        super(message);
    }
}
