package db;

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
 * Задача на выполнение набора запросов для одного инстанса MSSQL.
 * Используется в {@link Main}: последовательно выполняет запросы и
 * передаёт результаты в {@link processor.ResponseProcessor}.
 */
public record ServerRequest(
        InstanceConfig cfg,
        List<QueryRequest> queries,
        ResponseProcessor responseProcessor
) {

    public CompletableFuture<Void> execute(Executor executor) {
        String url = buildUrl(cfg);
        LogService.printf("[START] CI=%s url=%s%n", cfg.ci, url);

        return DbConnector.getConnectionAsync(url, cfg.userName, cfg.password)
                .thenCompose(conn -> runSequentially(conn, executor)
                        .whenComplete((v, ex) -> closeSilently(conn)))
                .exceptionally(ex -> {                     // ← перехват
                    LogService.errorf("[DB-ERROR] Can't connect: %s – %s%n",
                            url, ex.getCause() != null ? ex.getCause().getMessage()
                                    : ex.getMessage());
                    return null;                           // не роняем всю программу
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

    private void execOne(Connection conn, QueryRequest qr) {
        try (var st = conn.createStatement();
             var rs = st.executeQuery(qr.queryText())) {

            responseProcessor.handle(cfg.ci, qr.requestId(), rs);

        } catch (SQLException ex) {
            LogService.errorf("[CI=%s][ReqID=%s] SQL-ERROR: %s%n",
                    cfg.ci, qr.requestId(), ex.getMessage());
        } catch (Exception ex) {
            LogService.errorf("[CI=%s][ReqID=%s] ERROR: %s%n",
                    cfg.ci, qr.requestId(), ex.getMessage());
        }
    }

    private static String buildUrl(InstanceConfig ic) {
        // host[,port]  ИЛИ  host\instance (без порта)
        StringBuilder sb = new StringBuilder("jdbc:sqlserver://");

        String host = ic.instanceName;     // то, что из XML <InstanceName>

        if (ic.port != null) {
            // если порт задан — используем host,port (забываем про \instance)
            int slash = host.indexOf('\\');
            if (slash >= 0) host = host.substring(0, slash);  // только host
            sb.append(host).append(',').append(ic.port);
        } else {
            // порт не задан → оставляем как есть (возможно host\instance)
            sb.append(host);
        }

        sb.append(";encrypt=false;trustServerCertificate=true");
        return db.MssqlConnectionStringEnricher.enrich(sb.toString());
    }


    private static void closeSilently(Connection c) {
        try { if (c != null && !c.isClosed()) c.close(); } catch (Exception ignored) {}
    }
}
