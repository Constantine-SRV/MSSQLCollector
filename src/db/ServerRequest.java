package db;

import model.DbType;
import model.InstanceConfig;
import model.QueryRequest;
import processor.ResponseProcessor;
import logging.LogService;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/**
 * Задача на выполнение набора запросов для одного инстанса (MSSQL/OceanBase).
 * Последовательно выполняет запросы и передаёт результаты в {@link processor.ResponseProcessor}.
 */
public record ServerRequest(
        InstanceConfig cfg,
        List<QueryRequest> queries,
        ResponseProcessor responseProcessor
) {

    public CompletableFuture<Void> execute(Executor executor) {
        DbType dbType = cfg.dbType == null ? DbType.MSSQL : cfg.dbType;
        String url = buildUrl(cfg, dbType);
        String effectiveUser = buildUserName(cfg, dbType);
        LogService.printf("[START] CI=%s dbType=%s url=%s user=%s%n",
                cfg.ci, dbType, url, effectiveUser);

        return DbConnector.getConnectionAsync(dbType, url, effectiveUser, cfg.password)
                .thenCompose(conn -> runSequentially(conn, executor)
                        .whenComplete((v, ex) -> closeSilently(conn)))
                .exceptionally(ex -> {
                    String errorText = formatConnectError(url, effectiveUser, ex);
                    reportConnectErrorToAllQueries(errorText);
                    return null;
                });
    }

    private CompletableFuture<Void> runSequentially(Connection conn, Executor executor) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (QueryRequest qr : queries) {
            chain = chain.thenCompose(v ->
                    CompletableFuture.runAsync(() -> execOne(conn, qr), executor));
        }
        return chain;
    }

    /** Выполнить один запрос для сервера */
    private void execOne(Connection conn, QueryRequest qr) {
        String resultExec = "Ok";

        try (var st = conn.createStatement();
             var rs = st.executeQuery(qr.queryText())) {
            responseProcessor.handle(cfg, qr.requestId(), rs, resultExec);
        } catch (SQLException ex) {
            resultExec = "Error: " + ex.getMessage();
            LogService.errorf("[CI=%s][ReqID=%s] SQL-ERROR: %s%n", cfg.ci, qr.requestId(), ex.getMessage());
            try {
                responseProcessor.handle(cfg, qr.requestId(), null, resultExec);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after SQL fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        } catch (Exception ex) {
            resultExec = "Error: " + ex.getMessage();
            LogService.errorf("[CI=%s][ReqID=%s] ERROR: %s%n", cfg.ci, qr.requestId(), ex.getMessage());
            try {
                responseProcessor.handle(cfg, qr.requestId(), null, resultExec);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after General fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        }
    }

    /* ===================== CONNECT ERROR HANDLING ===================== */

    private static String formatConnectError(String url, String user, Throwable ex) {
        String root = rootCause(ex).getMessage();
        return String.format("[DB-ERROR] Can't connect: url=%s; user=%s – %s", url, user, root);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    private void reportConnectErrorToAllQueries(String errorText) {
        LogService.errorf("[CI=%s] CONNECT-ERROR: %s%n", cfg.ci, errorText);

        for (QueryRequest qr : queries) {
            try {
                responseProcessor.handle(cfg, qr.requestId(), null, errorText);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after CONNECT fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        }
    }

    /* ===================== URL / USER / CLOSE ===================== */

    /**
     * Строит JDBC URL для соответствующего типа СУБД.
     *
     *  - MSSQL    : jdbc:sqlserver://host[:port];encrypt=false;trustServerCertificate=true + enrich
     *  - OCEANBASE: jdbc:mysql://host[:port]/?useSSL=false&allowPublicKeyRetrieval=true&...
     */
    private static String buildUrl(InstanceConfig ic, DbType dbType) {
        requireNonNull(ic.instanceName, "instanceName");
        if (dbType == DbType.OCEANBASE) {
            StringBuilder sb = new StringBuilder("jdbc:mysql://").append(ic.instanceName);
            if (ic.port != null) sb.append(':').append(ic.port);
            // Базовые безопасные параметры для OB-прокси
            sb.append("/?useSSL=false")
              .append("&allowPublicKeyRetrieval=true")
              .append("&characterEncoding=utf8")
              .append("&connectTimeout=5000")
              .append("&socketTimeout=15000");
            return sb.toString();
        }
        // MSSQL (default) — сохраняем существующее поведение
        StringBuilder sb = new StringBuilder("jdbc:sqlserver://").append(ic.instanceName);
        if (ic.port != null) sb.append(':').append(ic.port);
        sb.append(";encrypt=false;trustServerCertificate=true");
        return MssqlConnectionStringEnricher.enrich(sb.toString());
    }

    /**
     * Для OCEANBASE склеивает логин вида {@code user@tenant#cluster}.
     * Если tenant пустой — возвращается просто userName (например, sys-пользователь).
     * Для MSSQL — userName без изменений.
     */
    private static String buildUserName(InstanceConfig ic, DbType dbType) {
        String user = ic.userName == null ? "" : ic.userName;
        if (dbType != DbType.OCEANBASE) return user;

        // Если пользователь уже содержит '@' — считаем, что строка уже сформирована
        // (back-compat: можно положить "userJava@business_tenant#obcluster" прямо в UserName).
        if (user.contains("@")) return user;

        if (ic.tenant == null || ic.tenant.isBlank()) return user;

        StringBuilder sb = new StringBuilder(user).append('@').append(ic.tenant.trim());
        if (ic.cluster != null && !ic.cluster.isBlank()) {
            sb.append('#').append(ic.cluster.trim());
        }
        return sb.toString();
    }

    private static void closeSilently(Connection c) {
        try { if (c != null && !c.isClosed()) c.close(); } catch (Exception ignored) {}
    }
}
