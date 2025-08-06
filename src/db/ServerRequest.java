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

        // 1) Получаем подключение асинхронно
        // 2) Если подключение удалось — запускаем запросы последовательно
        // 3) В ЛЮБОМ случае закрываем соединение
        // 4) Если подключение НЕ удалось — exceptionally: раздаём ошибку всем reqId
        return DbConnector.getConnectionAsync(url, cfg.userName, cfg.password)
                .thenCompose(conn -> runSequentially(conn, executor)
                        .whenComplete((v, ex) -> closeSilently(conn)))
                .exceptionally(ex -> {
                    // Формируем единый текст ошибки подключения и записываем его как resultExec
                    String errorText = formatConnectError(url, cfg.userName, ex);
                    reportConnectErrorToAllQueries(errorText);
                    // Возвращаем null, так как тип CompletableFuture<Void>
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

    /* ===================== CONNECT ERROR HANDLING ===================== */

    /**
     * Формирует человекочитаемое сообщение об ошибке подключения без секретов.
     * Пример: "[DB-ERROR] Can't connect: url=...; user=... – <rootCause>"
     */
    private static String formatConnectError(String url, String user, Throwable ex) {
        String root = rootCause(ex).getMessage();
        // Ставим ; между полями как в существующем логе, чтобы было привычно.
        return String.format("[DB-ERROR] Can't connect: url=%s; user=%s – %s", url, user, root);
    }

    /** Достаём корневую причину из цепочки CompletionException/RuntimeException и т.п. */
    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur;
    }

    /**
     * Раздаёт ошибку подключения по всем запросам (reqId) данного сервера,
     * вызывая общий обработчик результатов.
     */
    private void reportConnectErrorToAllQueries(String errorText) {
        // Лог один раз на сервер (дубли допустимы по ТЗ; при желании можно удалить эту строку)
        LogService.errorf("[CI=%s] CONNECT-ERROR: %s%n", cfg.ci, errorText);

        for (QueryRequest qr : queries) {
            try {
                // rs = null, resultExec = текст ошибки подключения
                responseProcessor.handle(cfg, qr.requestId(), null, errorText);
            } catch (Exception handleEx) {
                LogService.errorf("[CI=%s][ReqID=%s] handle error after CONNECT fail: %s%n",
                        cfg.ci, qr.requestId(), handleEx.getMessage());
            }
        }
    }

    /* ===================== URL BUILD / CLOSE ===================== */

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
