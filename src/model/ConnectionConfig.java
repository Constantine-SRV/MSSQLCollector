package model;

/**
 * Параметры подключения для источников и приёмников (ServersSource / JobsSource /
 * ResultsDestination). Те же поля, что у опрашиваемого инстанса, и тот же
 * механизм сборки URL / логина / пароля ({@link db.JdbcConnections}).
 *
 * XML:
 * <pre>
 *   &lt;Connection&gt;
 *       &lt;Host&gt;192.168.55.200&lt;/Host&gt;
 *       &lt;Port&gt;2883&lt;/Port&gt;
 *       &lt;User&gt;root&lt;/User&gt;
 *       &lt;Tenant&gt;app_tenant&lt;/Tenant&gt;          (только OCEANBASE)
 *       &lt;Cluster&gt;&lt;/Cluster&gt;                  (только OCEANBASE)
 *       &lt;Password&gt;${OB_PASSWORD}&lt;/Password&gt;  (пусто → env MSSQL_&lt;USER&gt;_PASSWORD / консоль)
 *       &lt;Database&gt;Inventory&lt;/Database&gt;
 *       &lt;IntegratedSecurity&gt;false&lt;/IntegratedSecurity&gt; (только MSSQL)
 *       &lt;Params&gt;socketTimeout=60000&lt;/Params&gt;  (доп. параметры URL, переопределяют дефолты)
 *   &lt;/Connection&gt;
 * </pre>
 *
 * Альтернатива — готовый URL в {@code <ConnectionString>} (или устаревший
 * {@code <MSSQLConnectionString>}). Если задан URL, он используется как есть,
 * а User/Password из блока (если указаны) передаются отдельно.
 */
public class ConnectionConfig {
    public String host;
    public Integer port;
    public String user;
    public String password;
    public String tenant;
    public String cluster;
    public String database;
    public String params;
    public boolean integratedSecurity;

    /** Готовый JDBC URL (переопределяет host/port/database/params). */
    public String connectionString;

    public boolean hasUrl() {
        return connectionString != null && !connectionString.isBlank();
    }

    public boolean hasHost() {
        return host != null && !host.isBlank();
    }

    /** Задано ли хоть что-то для подключения. */
    public boolean isConfigured() {
        return hasUrl() || hasHost();
    }

    /** Для логов — без пароля. */
    public String describe() {
        if (hasHost()) return host + (port != null ? ":" + port : "") + (database != null ? "/" + database : "");
        if (hasUrl()) return connectionString.replaceAll("(?i)(password=)[^;&]*", "$1***");
        return "(not configured)";
    }
}
