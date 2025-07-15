package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;
import logging.LogService;

/**
 * Утилитный класс для получения JDBC-соединения с MSSQL.
 */
public final class DbConnector {

    private DbConnector() {}

    /**
     * Открывает соединение с базой данных асинхронно. Метод вызывается из
     * {@link db.ServerRequest#execute(java.util.concurrent.Executor)} и
     * возвращает {@link CompletableFuture}, завершающийся успешным
     * подключением либо исключением.
     */
    public static CompletableFuture<Connection> getConnectionAsync(
            String url, String user, String password) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // регистрация драйвера (fat-jar, shadow, plain classpath)
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                LogService.printf("[DB] Connecting: url=%s user=%s%n", url, user);
                return DriverManager.getConnection(url, user, password);
            } catch (ClassNotFoundException e) {
                LogService.errorln("[DB-ERROR] JDBC driver not found. " +
                        "Проверьте, что mssql-jdbc.jar есть в classpath.");
                throw new RuntimeException(e);
            } catch (Exception ex) {
                LogService.errorf("[DB-ERROR] Can't connect: url=%s user=%s – %s%n",
                        url, user, ex.getMessage());
                throw new RuntimeException(ex);
            }
        });
    }
}
