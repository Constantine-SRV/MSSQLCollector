package db.redis;

import logging.LogService;
import model.InstanceConfig;
import model.QueryRequest;
import processor.ResponseProcessor;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Опрос одного Redis-инстанса: одно соединение, задания выполняются последовательно.
 *
 * Задание (queryText):
 *   - любая команда Redis: {@code INFO replication}, {@code CLUSTER NODES}, {@code CONFIG GET maxmemory} ...
 *   - {@code TOPOLOGY} — встроенный отчёт о роли/репликах/кластере.
 *
 * Аутентификация: AUTH user pass (Redis 6+ ACL) или AUTH pass, если userName пустой.
 * Если пусты и userName, и password — AUTH не выполняется.
 *
 * Таймауты (мс): -Dredis.connectTimeoutMs=5000 -Dredis.readTimeoutMs=15000
 */
public final class RedisServerRequest {

    public static final int DEFAULT_PORT = 6379;
    private static final int CONNECT_TIMEOUT = Integer.getInteger("redis.connectTimeoutMs", 5000);
    private static final int READ_TIMEOUT    = Integer.getInteger("redis.readTimeoutMs", 15000);

    private RedisServerRequest() {}

    public static CompletableFuture<Void> execute(InstanceConfig cfg, List<QueryRequest> queries,
                                                  ResponseProcessor rp, Executor executor) {
        return CompletableFuture.runAsync(() -> run(cfg, queries, rp), executor);
    }

    private static void run(InstanceConfig cfg, List<QueryRequest> queries, ResponseProcessor rp) {
        String host = cfg.instanceName.trim();
        int port = (cfg.port == null || cfg.port <= 0) ? DEFAULT_PORT : cfg.port;
        String self = host + ":" + port;
        String user = cfg.userName == null ? "" : cfg.userName.trim();
        String url = (cfg.tls ? "rediss://" : "redis://") + self;

        LogService.printf("[START] CI=%s dbType=REDIS url=%s user=%s%n", cfg.ci, url, user);

        RespClient client;
        try {
            client = connect(host, port, cfg.tls, user, cfg.password);
        } catch (Exception ex) {
            // Текст как у JDBC — PrometheusResultWriter по нему шлёт availability=0
            String err = String.format("[DB-ERROR] Can't connect: url=%s; user=%s – %s", url, user, msg(ex));
            LogService.errorf("[CI=%s] CONNECT-ERROR: %s%n", cfg.ci, err);
            for (QueryRequest qr : queries) safeHandle(rp, cfg, qr.requestId(), null, err);
            return;
        }

        try {
            for (QueryRequest qr : queries) {
                if (client == null) {
                    // соединение потеряно на предыдущем задании — пробуем переподключиться
                    try {
                        client = connect(host, port, cfg.tls, user, cfg.password);
                    } catch (Exception ex) {
                        safeHandle(rp, cfg, qr.requestId(), null,
                                String.format("[DB-ERROR] Can't connect: url=%s; user=%s – %s", url, user, msg(ex)));
                        continue;
                    }
                }
                try {
                    execOne(client, cfg, qr, self, rp);
                } catch (IOException ex) {
                    String err = "Error: " + msg(ex);
                    LogService.errorf("[CI=%s][ReqID=%s] REDIS-ERROR: %s%n", cfg.ci, qr.requestId(), msg(ex));
                    safeHandle(rp, cfg, qr.requestId(), null, err);
                    client.close();
                    client = null;
                }
            }
        } finally {
            if (client != null) client.close();
        }
    }

    private static void execOne(RespClient client, InstanceConfig cfg, QueryRequest qr,
                                String self, ResponseProcessor rp) throws IOException {
        String text = qr.queryText() == null ? "" : qr.queryText().trim();
        RowSetTable table;

        if (text.equalsIgnoreCase("TOPOLOGY")) {
            table = RedisResultMapper.topology(client, self);
        } else {
            List<String> cmd = RespClient.tokenize(text);
            if (cmd.isEmpty()) {
                safeHandle(rp, cfg, qr.requestId(), null, "Error: empty command");
                return;
            }
            Object reply = client.command(cmd);
            if (reply instanceof RespClient.RedisError e) {
                LogService.errorf("[CI=%s][ReqID=%s] REDIS-ERROR: %s%n", cfg.ci, qr.requestId(), e.message());
                safeHandle(rp, cfg, qr.requestId(), null, "Error: " + e.message());
                return;
            }
            table = RedisResultMapper.map(cmd, reply);
        }

        try {
            rp.handle(cfg, qr.requestId(), table.toResultSet(), "Ok");
        } catch (Exception ex) {
            LogService.errorf("[CI=%s][ReqID=%s] handle error: %s%n", cfg.ci, qr.requestId(), msg(ex));
        }
    }

    private static RespClient connect(String host, int port, boolean tls, String user, String password)
            throws IOException {
        RespClient c = new RespClient(host, port, tls, CONNECT_TIMEOUT, READ_TIMEOUT);
        String pwd = password == null ? "" : password;
        if (!pwd.isEmpty() || !user.isEmpty()) {
            Object r = user.isEmpty() ? c.command("AUTH", pwd) : c.command("AUTH", user, pwd);
            if (r instanceof RespClient.RedisError e) {
                c.close();
                throw new IOException("AUTH failed: " + e.message());
            }
        }
        return c;
    }

    private static void safeHandle(ResponseProcessor rp, InstanceConfig cfg, String reqId,
                                   java.sql.ResultSet rs, String resultExec) {
        try {
            rp.handle(cfg, reqId, rs, resultExec);
        } catch (Exception ex) {
            LogService.errorf("[CI=%s][ReqID=%s] handle error: %s%n", cfg.ci, reqId, msg(ex));
        }
    }

    private static String msg(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        String m = c.getMessage();
        return (m == null || m.isBlank()) ? c.getClass().getSimpleName() : m;
    }
}
