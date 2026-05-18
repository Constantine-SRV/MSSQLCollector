package model;

/**
 * Конфигурация места назначения, куда будут сохраняться результаты
 * работы приложения или его лог. Поддерживаются различные типы
 * получателей: MSSQL, OCEANBASE (OB), MongoDB, локальные файлы, консоль или PROMETHEUS.
 */
public class DestinationConfig {
    /** Тип назначения: MSSQL | OCEANBASE | MONGO | LOCALFILE | CONSOLE | PROMETHEUS */
    public String type;

    // Общая JDBC-конфигурация (используется для MSSQL и OCEANBASE).
    // Имена полей сохранены как mssql* для обратной совместимости с прежним XML.
    public String mssqlConnectionString;
    public String mssqlQuery;

    // Mongo-specific
    public String mongoConnectionString;
    public String mongoCollectionName;

    // LocalFile-specific
    public String directoryPath;

    /**
     * URL сервера VictoriaMetrics/Prometheus (например, http://xxxxx/api/v1/import/prometheus).
     * Поддерживается несколько адресов через ';' или ','.
     */
    public String prometheusUrl;

    /**
     * Формат сериализации результата для MSSQL/OCEANBASE/LOCALFILE: XML или JSON.
     * Пустое/null → дефолт:
     *   - OCEANBASE → JSON
     *   - всё остальное → XML (обратная совместимость).
     */
    public String resultFormat;
}
