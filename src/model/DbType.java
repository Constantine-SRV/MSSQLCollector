package model;

import java.util.Locale;

/**
 * Тип СУБД, с которой работает компонент:
 *  - MSSQL     — Microsoft SQL Server (драйвер mssql-jdbc)
 *  - OCEANBASE — OceanBase в MySQL-режиме (драйвер mysql-connector-j)
 *  - REDIS     — Redis (собственный RESP-клиент, без внешних jar)
 *
 * Для обратной совместимости: если значение не указано / пустое — считаем MSSQL.
 */
public enum DbType {
    MSSQL,
    OCEANBASE,
    REDIS;

    /**
     * Парсинг с алиасами (OB, MYSQL, SQLSERVER). Пустое → MSSQL.
     * Неизвестное значение → IllegalArgumentException (раньше молча становилось MSSQL).
     */
    public static DbType parse(String s) {
        if (s == null) return MSSQL;
        String norm = s.trim().toUpperCase(Locale.ROOT);
        if (norm.isEmpty() || norm.equals("-")) return MSSQL;
        return switch (norm) {
            case "OCEANBASE", "OB", "MYSQL" -> OCEANBASE;
            case "MSSQL", "SQLSERVER"       -> MSSQL;
            case "REDIS"                    -> REDIS;
            default -> throw new IllegalArgumentException(
                    "Unknown DbType '" + s + "'. Allowed: MSSQL | OCEANBASE | REDIS");
        };
    }

    /** Пустое → null (не задано), иначе как {@link #parse(String)}. */
    public static DbType parseOrNull(String s) {
        if (s == null || s.isBlank() || s.trim().equals("-")) return null;
        return parse(s);
    }

    /** true — работаем через JDBC (MSSQL, OCEANBASE). */
    public boolean isJdbc() {
        return this != REDIS;
    }

    /** Имя JDBC-драйвера (null для не-JDBC типов). */
    public String driverClass() {
        return switch (this) {
            case MSSQL     -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case OCEANBASE -> "com.mysql.cj.jdbc.Driver";
            case REDIS     -> null;
        };
    }
}
