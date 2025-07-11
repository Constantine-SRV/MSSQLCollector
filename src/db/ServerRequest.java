package db;

import model.InstanceConfig;
import model.QueryRequest;
import processor.ResponseProcessor;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

public record ServerRequest(
        InstanceConfig cfg,
        List<QueryRequest> queries,
        ResponseProcessor responseProcessor // теперь через конструктор!
) {

    public CompletableFuture<Void> execute(Executor executor) {
        String url = buildUrl(cfg);
        System.out.printf("[START] CI=%s url=%s%n", cfg.ci, url);

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

    private void execOne(Connection conn, QueryRequest qr) {
        try (var st = conn.createStatement();
             var rs = st.executeQuery(qr.queryText())) {

            responseProcessor.handle(cfg.ci, qr.requestId(), rs);

        } catch (SQLException ex) {
            System.err.printf("[CI=%s][ReqID=%s] SQL-ERROR: %s%n",
                    cfg.ci, qr.requestId(), ex.getMessage());
        } catch (Exception ex) {
            System.err.printf("[CI=%s][ReqID=%s] ERROR: %s%n",
                    cfg.ci, qr.requestId(), ex.getMessage());
        }
    }

    private static String buildUrl(InstanceConfig ic) {
        StringBuilder sb = new StringBuilder("jdbc:sqlserver://")
                .append(requireNonNull(ic.instanceName));
        if (ic.port != null) sb.append(':').append(ic.port);
        sb.append(";encrypt=false;trustServerCertificate=true");
        return sb.toString();
    }

    private static void closeSilently(Connection c) {
        try { if (c != null && !c.isClosed()) c.close(); } catch (Exception ignored) {}
    }
}
