package de.visentra.sedapexpress.probe;

import android.content.Context;

import java.nio.charset.StandardCharsets;

import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;

final class ProbeEntry {

    private ProbeEntry() {
    }

    static String run(Context context) {
        // The Kotlin layer, so that R8 keeps what a small app or plugin would use of it.
        new Thread(LibChecks::runAndLog).start();
        final SEDAPExpressMessage message = SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U");
        return SEDAPExpressMessage.serialize(message)
                + MACUtils.calc32BitHMAC("key".getBytes(StandardCharsets.ISO_8859_1), message)
                + CompressUtils.compressMessage(message);
    }
}
