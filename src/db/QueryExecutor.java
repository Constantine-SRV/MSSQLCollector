package db;

import java.sql.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Вспомогательные методы для выполнения SELECT-запросов.
 */
public final class QueryExecutor {

    private QueryExecutor() { }

    /**
     * Выполняет SELECT-запрос и печатает результат в консоль. Используется
     * при отладке и демонстрации. Возвращает {@link CompletableFuture},
     * который завершается после завершения печати.
     */
    public static CompletableFuture<Void> execAndPrintAsync(
            Connection conn, String sql, int recordId, Executor executor) {

        return CompletableFuture.runAsync(() -> {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {

                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    StringBuilder row = new StringBuilder();
                    row.append("exec ").append(recordId).append(": ");
                    for (int i = 1; i <= cols; i++) {
                        row.append(rs.getString(i))
                                .append(i < cols ? " | " : "");
                    }
                    System.out.println(row);
                }
            } catch (Exception ex) {
                throw new RuntimeException("Query failed", ex);
            }
        }, executor);
    }
}
