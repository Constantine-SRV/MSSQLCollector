package model;

/**
 * Конфигурация места назначения, куда будут сохраняться результаты
 * работы приложения или его лог. Поддерживаются различные типы
 * получателей: база MSSQL, MongoDB, локальные файлы или консоль.
 */
public class DestinationConfig {
    public String type;

    // MSSQL-specific
    public String mssqlConnectionString;
    public String mssqlQuery;

    // Mongo-specific
    public String mongoConnectionString;
    public String mongoCollectionName;

    // LocalFile-specific
    public String directoryPath;

    // --- Новое для Prometheus/VictoriaMetrics ---
    /**
     * URL сервера VictoriaMetrics/Prometheus (например, http://xxxxx/api/v1/import/prometheus)
     */
    public String prometheusUrl;


}
