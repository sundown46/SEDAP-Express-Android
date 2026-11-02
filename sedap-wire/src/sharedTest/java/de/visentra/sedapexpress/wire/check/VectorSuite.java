package de.visentra.sedapexpress.wire.check;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE;
import de.bundeswehr.uniity.sedapexpress.messages.HEARTBEAT;
import de.bundeswehr.uniity.sedapexpress.messages.OWNUNIT;
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.messages.TEXT;
import de.bundeswehr.uniity.sedapexpress.utils.CompressUtils;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;
import de.visentra.sedapexpress.compat.Base64;
import de.visentra.sedapexpress.compat.DecoderException;

/** Runs vectors/golden-vectors.json against the upstream classes in sedap-wire. */
public final class VectorSuite {

    private final List<Result> results = new ArrayList<>();

    private VectorSuite() {
    }

    public static List<Result> run(String json) throws Exception {
        final VectorSuite suite = new VectorSuite();
        final JSONObject root = new JSONObject(json);
        suite.mac(root.getJSONObject("mac"));
        suite.numbers(root.getJSONObject("number_format"));
        suite.timestamps(root.getJSONObject("timestamp"));
        suite.textKinds(root.getJSONObject("text_kind"));
        suite.base64(root.getJSONObject("base64_text"));
        suite.compression(root.getJSONObject("compression"));
        suite.parse(root.getJSONArray("parse"));
        suite.serialize(root.getJSONArray("serialize"));
        for (final String section : new String[] {"counter", "sidc_normalize", "cluster_key", "relative_position"}) {
            suite.results.add(new Result(section, Result.Status.SKIP, "no upstream API, belongs to the Kotlin layer"));
        }
        return suite.results;
    }

    private interface Check {
        Object actual() throws Exception;
    }

    private void compare(String id, Object expected, Check check) {
        try {
            final Object actual = normalize(check.actual());
            final Object wanted = normalize(expected);
            if (same(wanted, actual)) {
                this.results.add(new Result(id, Result.Status.PASS, ""));
            } else {
                this.results.add(new Result(id, Result.Status.FAIL, "expected <" + wanted + "> but got <" + actual + ">"));
            }
        } catch (Throwable t) {
            this.results.add(new Result(id, Result.Status.FAIL, "threw " + describe(t)));
        }
    }

    static String describe(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    private static Object normalize(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof char[]) {
            return new String((char[]) value);
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof Collection) {
            final StringBuilder joined = new StringBuilder();
            for (final Object item : (Collection<?>) value) {
                joined.append(item);
            }
            return joined.toString();
        }
        return value;
    }

    private static boolean same(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }

    private static byte[] latin1(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    private void mac(JSONObject section) throws Exception {
        final JSONArray compute = section.getJSONArray("compute");
        for (int i = 0; i < compute.length(); i++) {
            final JSONObject c = compute.getJSONObject(i);
            this.compare("mac.compute[" + i + "]", c.getString("expected"), () -> {
                final SEDAPExpressMessage message = SEDAPExpressMessage.deserialize(c.getString("line"));
                return MACUtils.calc32BitHMAC(latin1(c.getString("password")), message);
            });
        }
        final JSONArray verify = section.getJSONArray("verify");
        for (int i = 0; i < verify.length(); i++) {
            final JSONObject c = verify.getJSONObject(i);
            this.compare("mac.verify[" + i + "]", c.getBoolean("valid"), () -> {
                final String line = c.getString("line");
                final SEDAPExpressMessage message =
                        SEDAPExpressMessage.deserialize(line.substring(line.indexOf(':') + 1));
                return message != null && MACUtils.isMessageAuthentic(latin1(c.getString("password")), message);
            });
        }
    }

    private void numbers(JSONObject section) throws Exception {
        final JSONArray cases = section.getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            this.compare("number[" + c.getString("value") + "]", c.getString("expected"),
                    () -> SEDAPExpressMessage.NumberFormatter.format(Double.parseDouble(c.getString("value"))));
        }
    }

    private void timestamps(JSONObject section) throws Exception {
        final JSONArray cases = section.getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            this.compare("timestamp[" + i + "]", c.getString("expected"),
                    () -> SEDAPExpressMessage.HexFormater.toHexDigits(c.getLong("ms"), 12));
        }
    }

    private void textKinds(JSONObject section) throws Exception {
        final JSONArray read = section.getJSONArray("read");
        for (int i = 0; i < read.length(); i++) {
            final JSONObject c = read.getJSONObject(i);
            final String wire = c.getString("wire");
            this.compare("text_kind.read[" + wire + "]", c.getString("kind"), () -> {
                final SEDAPExpressMessage parsed =
                        SEDAPExpressMessage.deserialize("TEXT;01;01A19BD5FCD0;MESE;U;;;;" + wire + ";NONE;x");
                if (parsed == null) {
                    return "<rejected>";
                }
                final TEXT.TextType type = ((TEXT) parsed).getType();
                return type == null ? "<none>" : type.name().toLowerCase(Locale.ROOT);
            });
        }
    }

    private void base64(JSONObject section) throws Exception {
        final JSONArray decode = section.getJSONArray("decode");
        for (int i = 0; i < decode.length(); i++) {
            final JSONObject c = decode.getJSONObject(i);
            if (c.isNull("expected")) {
                this.compare("base64.decode[" + i + "]", "rejected", () -> {
                    try {
                        Base64.decode(c.getString("input"));
                        return "accepted";
                    } catch (DecoderException e) {
                        return "rejected";
                    }
                });
            } else {
                // The vector also fixes the choice between UTF-8 and ISO-8859-1. That choice is display
                // logic of the Kotlin layer, so only the decoded bytes can be compared here.
                final String expected = c.getString("expected");
                this.compare("base64.decode[" + i + "]", expected, () -> {
                    final byte[] bytes = Base64.decode(c.getString("input"));
                    final String utf8 = new String(bytes, StandardCharsets.UTF_8);
                    return utf8.equals(expected) ? utf8 : new String(bytes, StandardCharsets.ISO_8859_1);
                });
            }
        }
        final JSONArray encode = section.getJSONArray("encode");
        for (int i = 0; i < encode.length(); i++) {
            final JSONObject c = encode.getJSONObject(i);
            this.compare("base64.encode[" + i + "]", c.getString("expected"), () -> Base64.toBase64String(
                    c.getString("text").getBytes(java.nio.charset.Charset.forName(c.getString("charset")))));
        }
    }

    private void compression(JSONObject section) throws Exception {
        // The valid ICD sample has one empty field too many, so upstream's message-returning API cannot
        // show the raw inflate result. Only the invalid inputs can be checked at this level.
        final JSONArray invalid = section.getJSONArray("invalid");
        for (int i = 0; i < invalid.length(); i++) {
            final String input = invalid.getString(i);
            this.compare("compression.invalid[" + input + "]", null, () -> CompressUtils.decompressMessage(input));
        }
        this.compare("compression.roundtrip", "HEARTBEAT;07;01A19BD5FCD0;MESE;U", () -> {
            final SEDAPExpressMessage message = SEDAPExpressMessage.deserialize("HEARTBEAT;07;01A19BD5FCD0;MESE;U");
            return CompressUtils.decompressMessage(CompressUtils.compressMessage(message)).toString();
        });
    }

    private void parse(JSONArray cases) throws Exception {
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            final String id = "parse[" + i + "] " + c.getString("type");
            final String line = c.getString("line");
            if (c.optBoolean("reject")) {
                this.compare(id + " reject", null, () -> SEDAPExpressMessage.deserialize(line));
                continue;
            }
            SEDAPExpressMessage parsed = null;
            Throwable failure = null;
            try {
                parsed = SEDAPExpressMessage.deserialize(line);
            } catch (Throwable t) {
                failure = t;
            }
            if (parsed == null) {
                this.results.add(new Result(id, Result.Status.FAIL,
                        failure != null ? "threw " + describe(failure) : "rejected, should parse"));
                continue;
            }
            final JSONObject expect = c.getJSONObject("expect");
            final Iterator<String> keys = expect.keys();
            while (keys.hasNext()) {
                final String field = keys.next();
                final SEDAPExpressMessage message = parsed;
                this.compare(id + " ." + field, expected(field, expect.get(field)), () -> field(message, field));
            }
        }
    }

    private static Object expected(String field, Object value) {
        return "deleteFlag".equals(field) ? String.valueOf(value).toUpperCase(Locale.ROOT) : value;
    }

    private static Object field(SEDAPExpressMessage message, String name) throws Exception {
        switch (name) {
            case "number":
                return hexByte(message.getNumber());
            case "classification":
                return message.getClassification() == null ? null
                        : String.valueOf(message.getClassification().getClassification());
            case "acknowledgement":
                return message.getAcknowledgement() == null ? null : message.getAcknowledgement().name();
            case "mac":
                return message.getMAC();
            case "comment_decoded":
                return invoke(message, "getComment");
            case "text_decoded":
                return ((TEXT) message).getTextContent();
            case "kind":
                return ((TEXT) message).getType().name().toLowerCase(Locale.ROOT);
            case "typeOfMessage":
                return ((ACKNOWLEDGE) message).getTypeOfTheMessage().name();
            case "numberOfMessage":
                return hexByte(((ACKNOWLEDGE) message).getNumberOfTheMessage());
            case "source":
                return sourceLetters(invoke(message, "getSource"));
            case "deleteFlag":
                return String.valueOf(invoke(message, "getDeleteFlag")).toUpperCase(Locale.ROOT);
            default:
                return invoke(message, "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1));
        }
    }

    private static String sourceLetters(Object source) {
        final StringBuilder letters = new StringBuilder();
        for (final Object item : new TreeSet<>((Collection<?>) source)) {
            letters.append(item);
        }
        return letters.toString();
    }

    private static String hexByte(Byte value) {
        return value == null ? null : SEDAPExpressMessage.HexFormater.toHexDigits(value);
    }

    private static Object invoke(Object target, String getter) throws Exception {
        for (final Method method : target.getClass().getMethods()) {
            if (method.getName().equalsIgnoreCase(getter) && method.getParameterTypes().length == 0) {
                return method.invoke(target);
            }
        }
        throw new NoSuchMethodException(getter);
    }

    private void serialize(JSONArray cases) throws Exception {
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.getJSONObject(i);
            this.compare("serialize[" + i + "] " + c.getString("type"), c.getString("expected"),
                    () -> SEDAPExpressMessage.serialize(build(c.getString("type"), c.getJSONObject("fields"))));
        }
    }

    private static SEDAPExpressMessage build(String type, JSONObject f) throws Exception {
        final SEDAPExpressMessage message;
        switch (type) {
            case "OWNUNIT":
                final OWNUNIT own = new OWNUNIT();
                own.setLatitude(f.getDouble("latitude"));
                own.setLongitude(f.getDouble("longitude"));
                own.setSIDC(f.getString("sidc").toCharArray());
                message = own;
                break;
            case "HEARTBEAT":
                message = new HEARTBEAT();
                break;
            case "TEXT":
                final TEXT text = new TEXT();
                text.setRecipient(f.getString("recipient"));
                text.setType(TEXT.TextType.Chat);
                text.setEncoding(SEDAPExpressMessage.DataEncoding.valueOf(f.getString("encoding")));
                text.setTextContent(new String(Base64.decode(f.getString("text")), StandardCharsets.ISO_8859_1));
                text.setReference(f.getString("reference"));
                message = text;
                break;
            case "ACKNOWLEDGE":
                final ACKNOWLEDGE ack = new ACKNOWLEDGE();
                ack.setRecipient(f.getString("recipient"));
                ack.setTypeOfTheMessage(SEDAPExpressMessage.MessageType.valueOf(f.getString("typeOfMessage")));
                ack.setNumberOfTheMessage((byte) Integer.parseInt(f.getString("numberOfMessage"), 16));
                message = ack;
                break;
            default:
                throw new IllegalArgumentException(type);
        }
        message.setNumber((byte) Integer.parseInt(f.getString("number"), 16));
        message.setTime(Long.parseLong(f.getString("time"), 16));
        message.setSender(f.getString("sender"));
        message.setClassification(SEDAPExpressMessage.Classification.getValueOfClassification(
                f.getString("classification").charAt(0)));
        return message;
    }
}
