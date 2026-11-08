package de.visentra.sedapexpress.interop;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import de.bundeswehr.uniity.sedapexpress.messages.ACKNOWLEDGE;
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage;
import de.bundeswehr.uniity.sedapexpress.messages.SEDAPExpressMessage.MessageType;
import de.bundeswehr.uniity.sedapexpress.messages.TEXT;
import de.bundeswehr.uniity.sedapexpress.network.SEDAPExpressTCPServer;
import de.bundeswehr.uniity.sedapexpress.utils.MACUtils;

/**
 * Drives the unmodified Java reference in its own process, the way upstream's SampleTCPServer and
 * SampleUDPClient do, so the tests of sedap-express can talk to it over real sockets (L1.7, D-30).
 * A separate process is needed because the reference has the same package names as sedap-wire.
 *
 * <pre>
 * java ReferencePeer tcp-server &lt;port&gt; [--mac &lt;password&gt;]
 * java ReferencePeer udp &lt;localPort&gt; &lt;remotePort&gt; [--mac &lt;password&gt;]
 * </pre>
 *
 * Output on stdout, UTF-8, one event per line, fields separated by tabs. Upstream also prints its
 * release banner there; readers skip every line that is none of these events:
 * <ul>
 * <li>{@code READY\t<port>}: listening</li>
 * <li>{@code LOG\t<text>}: upstream's input log, e.g. "Added new client ..."</li>
 * <li>{@code RECV\t<type>\t<isMessageAuthentic>\t<macOverReserialization>\t<detail>\t<toString>}: a message
 * the reference parsed. Both MAC columns are "-" without --mac. The first is upstream's
 * {@code MACUtils.isMessageAuthentic}, the second compares upstream's {@code calc32BitHMAC} with the
 * received MAC, "error:&lt;exception&gt;" if the call throws. Detail is the text of a TEXT and "TYPE:number" of an ACKNOWLEDGE, else empty.</li>
 * <li>{@code DROP\t<line>}: a UDP line the reference could not parse</li>
 * <li>{@code SENT\t<line>}: what the reference sent, without terminator</li>
 * <li>{@code ERROR\t<text>}</li>
 * </ul>
 *
 * Commands on stdin, UTF-8: {@code SEND\t<password or empty>\t<line>} parses the line with upstream, signs it with upstream's
 * 32-bit HMAC if a password is given and sends it. {@code QUIT} or the end of stdin stops the peer.
 */
public final class ReferencePeer {

    private final PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private final byte[] key;

    private ReferencePeer(byte[] key) {
        this.key = key;
    }

    public static void main(String[] args) throws Exception {
        quietLogging();
        String password = null;
        for (int i = 0; i < args.length - 1; i++) {
            if ("--mac".equals(args[i])) {
                password = args[i + 1];
            }
        }
        ReferencePeer peer = new ReferencePeer(password == null ? null : password.getBytes(StandardCharsets.ISO_8859_1));
        if (args.length >= 2 && "tcp-server".equals(args[0])) {
            peer.runTcpServer(Integer.parseInt(args[1]));
        } else if (args.length >= 3 && "udp".equals(args[0])) {
            peer.runUdp(Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        } else {
            System.err.println("usage: tcp-server <port> | udp <localPort> <remotePort> [--mac <password>]");
            System.exit(2);
        }
        System.exit(0);
    }

    /** Upstream logs every empty optional field; only warnings matter on stderr. */
    private static void quietLogging() {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            handler.setLevel(Level.WARNING);
        }
    }

    /** Like SampleTCPServer: upstream's server, subscribed to every type. */
    private void runTcpServer(int port) throws IOException {
        SEDAPExpressTCPServer server = new SEDAPExpressTCPServer("127.0.0.1", port);
        server.subscribeForInputLogging(text -> emit("LOG", text));
        server.subscribeMessages(this::received, MessageType.values());
        if (!server.connect()) {
            emit("ERROR", String.valueOf(server.getLastException()));
            System.exit(1);
        }
        emit("READY", Integer.toString(port));
        commands(message -> server.sendSEDAPExpressMessage(message));
        server.stopCommunicator();
    }

    /**
     * Upstream's SEDAPExpressUDPClient cannot run against a peer on the same host: it sends and receives
     * on the same port, and it drops every datagram from a local address, loopback included. So this
     * socket is our own, but it decodes and encodes exactly like that class: ISO-8859-1, split at "\n",
     * SEDAPExpressMessage.deserialize, one datagram per SEDAPExpressMessage.serialize.
     */
    private void runUdp(int localPort, int remotePort) throws IOException {
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        try (DatagramSocket socket = new DatagramSocket(new InetSocketAddress(loopback, localPort))) {
            Thread receiver = new Thread(() -> {
                while (!socket.isClosed()) {
                    try {
                        DatagramPacket packet = new DatagramPacket(new byte[65000], 65000);
                        socket.receive(packet);
                        String text = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.ISO_8859_1);
                        for (String line : text.split("\n")) {
                            SEDAPExpressMessage message = SEDAPExpressMessage.deserialize(line);
                            if (message == null) {
                                emit("DROP", line);
                            } else {
                                received(message);
                            }
                        }
                    } catch (IOException e) {
                        if (!socket.isClosed()) {
                            emit("ERROR", e.toString());
                        }
                    }
                }
            });
            receiver.setDaemon(true);
            receiver.start();
            emit("READY", Integer.toString(socket.getLocalPort()));
            commands(message -> {
                byte[] data = SEDAPExpressMessage.serialize(message).getBytes(StandardCharsets.ISO_8859_1);
                socket.send(new DatagramPacket(data, data.length, loopback, remotePort));
            });
        }
    }

    private interface Sender {
        void send(SEDAPExpressMessage message) throws IOException;
    }

    private void commands(Sender sender) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String command;
        while ((command = in.readLine()) != null) {
            if ("QUIT".equals(command)) {
                return;
            }
            String[] parts = command.split("\t", 3);
            if (parts.length != 3 || !"SEND".equals(parts[0])) {
                emit("ERROR", "unknown command: " + command);
                continue;
            }
            SEDAPExpressMessage message = SEDAPExpressMessage.deserialize(parts[2]);
            if (message == null) {
                emit("ERROR", "reference cannot parse: " + parts[2]);
                continue;
            }
            if (!parts[1].isEmpty()) {
                MACUtils.setMACFieldWith32BitHMAC(parts[1].getBytes(StandardCharsets.ISO_8859_1), message);
            }
            try {
                sender.send(message);
                emit("SENT", message.toString());
            } catch (IOException e) {
                emit("ERROR", e.toString());
            }
        }
    }

    private void received(SEDAPExpressMessage message) {
        String receivedMac = message.getMAC();
        String authentic = "-";
        String reserialized = "-";
        if (this.key != null) {
            // Both MAC calls overwrite the MAC field with "0000", so it is restored after each.
            try {
                authentic = Boolean.toString(MACUtils.isMessageAuthentic(this.key, message));
            } catch (RuntimeException e) {
                authentic = "error:" + e.getClass().getSimpleName();
            }
            message.setMAC(receivedMac);
            reserialized = Boolean.toString(receivedMac != null && MACUtils.calc32BitHMAC(this.key, message).equalsIgnoreCase(receivedMac));
            message.setMAC(receivedMac);
        }
        String detail = "";
        if (message instanceof TEXT text) {
            detail = String.valueOf(text.getTextContent());
        } else if (message instanceof ACKNOWLEDGE ack) {
            Byte number = ack.getNumberOfTheMessage();
            detail = ack.getTypeOfTheMessage() + ":" + (number == null ? "" : SEDAPExpressMessage.HexFormater.toHexDigits(number));
        }
        emit("RECV", message.getMessageType() + "\t" + authentic + "\t" + reserialized + "\t" + detail + "\t" + message);
    }

    private synchronized void emit(String event, String text) {
        this.out.println(event + "\t" + text);
    }
}
