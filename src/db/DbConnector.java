package db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.concurrent.CompletableFuture;

public final class DbConnector {

    private DbConnector() {}

    public static CompletableFuture<Connection> getConnectionAsync(
            String url, String user, String password) {

        return CompletableFuture.supplyAsync(() -> {
            try {
                // регистрация драйвера (fat-jar, shadow, plain classpath)
                Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
                System.out.printf("[DB] Connecting: url=%s user=%s%n", url, user);
                return DriverManager.getConnection(url, user, password);
            } catch (ClassNotFoundException e) {
                System.err.println("[DB-ERROR] JDBC driver not found. " +
                        "Проверьте, что mssql-jdbc.jar есть в classpath.");
                throw new RuntimeException(e);
            } catch (Exception ex) {
                System.err.printf("[DB-ERROR] Can't connect: url=%s user=%s – %s%n",
                        url, user, ex.getMessage());
                throw new RuntimeException(ex);
            }
        });
    }
}
