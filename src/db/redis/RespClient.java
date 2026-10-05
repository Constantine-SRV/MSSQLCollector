package db.redis;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Минимальный клиент Redis (протокол RESP2) поверх Socket — без внешних jar.
 *
 * Ответы:
 *   +OK / :123 / $bulk  → String / Long / String (null для $-1)
 *   *array              → List&lt;Object&gt;
 *   -ERR ...            → {@link RedisError}
 *
 * TLS — через стандартный SSLSocketFactory JVM (truststore: -Djavax.net.ssl.trustStore=...).
 * Проверка имени хоста в сертификате: -Dredis.tls.verifyHostname=true (по умолчанию выключена).
 */
public final class RespClient implements Closeable {

    /** Ответ-ошибка Redis (-ERR ..., -WRONGPASS ..., -NOAUTH ...). */
    public record RedisError(String message) {
        @Override public String toString() { return message; }
    }

    private final Socket socket;
    private final BufferedInputStream in;
    private final OutputStream out;

    public RespClient(String host, int port, boolean tls, int connectTimeoutMs, int readTimeoutMs) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            raw.setSoTimeout(readTimeoutMs);
            if (tls) {
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                SSLParameters p = ssl.getSSLParameters();
                try { p.setServerNames(List.of(new SNIHostName(host))); } catch (IllegalArgumentException ignore) { /* IP */ }
                if (Boolean.getBoolean("redis.tls.verifyHostname")) {
                    p.setEndpointIdentificationAlgorithm("HTTPS");
                }
                ssl.setSSLParameters(p);
                ssl.startHandshake();
                socket = ssl;
            } else {
                socket = raw;
            }
        } catch (IOException e) {
            try { raw.close(); } catch (IOException ignore) {}
            throw e;
        }
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** Выполнить команду (аргументы как есть, без разбора кавычек). */
    public Object command(List<String> args) throws IOException {
        StringBuilder sb = new StringBuilder("*").append(args.size()).append("\r\n");
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            buf.write(('$' + String.valueOf(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            buf.write(b);
            buf.write('\r');
            buf.write('\n');
        }
        out.write(buf.toByteArray());
        out.flush();
        return read();
    }

    public Object command(String... args) throws IOException {
        return command(List.of(args));
    }

    private Object read() throws IOException {
        int t = in.read();
        if (t == -1) throw new EOFException("connection closed by server");
        String line = readLine();
        switch (t) {
            case '+': return line;
            case '-': return new RedisError(line);
            case ':': return Long.parseLong(line);
            case '$': {
                int len = Integer.parseInt(line);
                if (len < 0) return null;
                byte[] b = in.readNBytes(len);
                if (b.length < len) throw new EOFException("short bulk reply");
                readLine(); // CRLF
                return new String(b, StandardCharsets.UTF_8);
            }
            case '*': {
                int n = Integer.parseInt(line);
                if (n < 0) return null;
                List<Object> l = new ArrayList<>(n);
                for (int i = 0; i < n; i++) l.add(read());
                return l;
            }
            default:
                throw new IOException("bad RESP type byte: " + (char) t);
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\r') { in.read(); break; }
            b.write(c);
        }
        return b.toString(StandardCharsets.UTF_8);
    }

    /**
     * Разбор строки команды из задания: пробелы разделяют аргументы,
     * "двойные" или 'одинарные' кавычки — аргумент с пробелами.
     */
    public static List<String> tokenize(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean has = false;
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else if (ch == '\\' && i + 1 < cmd.length()) cur.append(cmd.charAt(++i));
                else cur.append(ch);
            } else if (ch == '"' || ch == '\'') {
                quote = ch; has = true;
            } else if (Character.isWhitespace(ch)) {
                if (has) { out.add(cur.toString()); cur.setLength(0); has = false; }
            } else {
                cur.append(ch); has = true;
            }
        }
        if (has) out.add(cur.toString());
        return out;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignore) {}
    }
}
