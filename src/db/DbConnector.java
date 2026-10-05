package db;

import model.DbType;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import logging.LogService;

/**
 * Утилитный класс для получения JDBC-соединения.
 *
 * Поддерживает MSSQL и OCEANBASE (mysql-connector-j). Конкретный драйвер
 * выбирается по {@link DbType}.
 */
public final class DbConnector {

    private DbConnector() {}

    /**
     * Открывает соединение с базой данных асинхронно. Метод вызывается из
     * {@link db.ServerRequest#execute(java.util.concurrent.Executor)} и
     * возвращает {@link CompletableFuture}, завершающийся успешным
     * подключением либо исключением.
     *
     * ВАЖНО: исключения НЕ проглатываются — future завершается exceptionally,
     * чтобы вызывающий смог доставить текст ошибки до ResponseProcessor.
     */
    public static CompletableFuture<Connection> getConnectionAsync(
            DbType dbType, String url, String user, String password, Executor executor) {

        // Раньше подключение шло в общем ForkJoinPool (ядра-1 потоков) и не учитывало ThreadPoolSize.
        return CompletableFuture.supplyAsync(() -> {
            try {
                JdbcConnections.loadDriver(dbType);
                LogService.printf("[DB] Connecting [%s]: url=%s user=%s%n", dbType, url, user);
                return DriverManager.getConnection(url, user, password);
            } catch (Exception ex) {
                LogService.errorf("[DB-ERROR] Can't connect: url=%s user=%s – %s%n",
                        url, user, ex.getMessage());
                throw new RuntimeException(ex);
            }
        }, executor);
    }
}
