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
                        .whenComplete((v, ex) -> closeSilently(conn)));
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
             var rs = st.executeQuery(qr.queryText())) { // rs в try-with-resources
            responseProcessor.handle(cfg, qr.requestId(), rs, resultExec);
        } catch (SQLException ex) {
            resultExec = "Error: " + ex.getMessage();
            LogService.errorf("[CI=%s][ReqID=%s] SQL-ERROR: %s%n", cfg.ci, qr.requestId(), ex.getMessage());
            // передача null в случае ошибки
            try {
                responseProcessor.handle(cfg, qr.requestId(), null, resultExec);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after SQL fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        } catch (Exception ex) {
            resultExec = "Error: " + ex.getMessage();
            LogService.errorf("[CI=%s][ReqID=%s] ERROR: %s%n", cfg.ci, qr.requestId(), ex.getMessage());
            // аналогично передача null в случае ошибки
            try {
                responseProcessor.handle(cfg, qr.requestId(), null, resultExec);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after General fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        }
    }


    private static String buildUrl(InstanceConfig ic) {
        StringBuilder sb = new StringBuilder("jdbc:sqlserver://")
                .append(requireNonNull(ic.instanceName));
        if (ic.port != null) sb.append(':').append(ic.port);
        sb.append(";encrypt=false;trustServerCertificate=true");
        String baseConnStr = sb.toString();

        // Вызовите энричер, чтобы добавить encrypt, trustServerCertificate, applicationName и т.д.
        String enriched = db.MssqlConnectionStringEnricher.enrich(baseConnStr);
        return enriched;
    }

    private static void closeSilently(Connection c) {
        try { if (c != null && !c.isClosed()) c.close(); } catch (Exception ignored) {}
    }
}