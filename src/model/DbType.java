package model;

import java.util.Locale;

/**
 * Тип СУБД, с которой работает компонент:
 *  - MSSQL     — Microsoft SQL Server (драйвер mssql-jdbc)
 *  - OCEANBASE — OceanBase в MySQL-режиме (драйвер mysql-connector-j)
 *
 * Для обратной совместимости: если значение не указано / пустое — считаем MSSQL.
 */
public enum DbType {
    MSSQL,
    OCEANBASE;

    /** Парсинг строки без падений. Принимает алиасы: OB, MYSQL, SQLSERVER. */
    public static DbType parse(String s) {
        if (s == null) return MSSQL;
        String norm = s.trim().toUpperCase(Locale.ROOT);
        if (norm.isEmpty()) return MSSQL;
        return switch (norm) {
            case "OCEANBASE", "OB", "MYSQL" -> OCEANBASE;
            case "MSSQL", "SQLSERVER"       -> MSSQL;
            default                         -> MSSQL;
        };
    }

    /** Имя JDBC-драйвера по умолчанию для каждого типа. */
    public String driverClass() {
        return switch (this) {
            case MSSQL     -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case OCEANBASE -> "com.mysql.cj.jdbc.Driver";
        };
    }
}
