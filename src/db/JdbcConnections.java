package db;

import logging.LogService;
import model.ConnectionConfig;
import model.DbType;
import model.InstanceConfig;
import model.InstanceConfigEnreacher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Единая точка сборки JDBC URL / логина / пароля и открытия соединения.
 * Используется и для опрашиваемых инстансов ({@link ServerRequest}),
 * и для источников/приёмников (ServersSource, JobsSource, ResultsDestination).
 *
 *  - MSSQL    : jdbc:sqlserver://host[:port];encrypt=false;trustServerCertificate=true;... + enrich
 *  - OCEANBASE: jdbc:mysql://host[:port]/[database]?useSSL=false&...   логин user@tenant[#cluster]
 */
public final class JdbcConnections {

    private JdbcConnections() {}

    /** Параметры URL по умолчанию для OceanBase (через OBProxy). Переопределяются &lt;Params&gt;. */
    private static final String OB_DEFAULT_PARAMS =
            "useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&connectTimeout=5000&socketTimeout=15000";

    /* ======================= источники / приёмники ======================= */

    /** Открыть соединение по {@link ConnectionConfig} (для источников/приёмников). */
    public static Connection open(DbType type, ConnectionConfig cc) throws SQLException {
        if (type == null || !type.isJdbc()) {
            throw new IllegalArgumentException("Not a JDBC type: " + type);
        }
        if (cc == null || !cc.isConfigured()) {
            throw new IllegalArgumentException(type + ": connection is not configured " +
                    "(need <Connection><Host>... or <ConnectionString>)");
        }
        loadDriver(type);

        String url = cc.hasUrl()
                ? (type == DbType.MSSQL ? MssqlConnectionStringEnricher.enrich(cc.connectionString) : cc.connectionString)
                : buildUrl(type, cc.host, cc.port, cc.database, cc.params, cc.integratedSecurity);

        String user = buildUserName(type, cc.user, cc.tenant, cc.cluster);
        if (user.isEmpty() || cc.integratedSecurity) {
            // логин/пароль внутри URL или Windows-аутентификация
            return DriverManager.getConnection(url);
        }
        String pwd = resolvePassword(cc.user, cc.password);
        return DriverManager.getConnection(url, user, pwd);
    }

    /* ======================= опрашиваемые инстансы ======================= */

    public static String buildUrl(InstanceConfig ic, DbType type) {
        return buildUrl(type, ic.instanceName, ic.port, null, null, false);
    }

    public static String buildUserName(InstanceConfig ic, DbType type) {
        return buildUserName(type, ic.userName, ic.tenant, ic.cluster);
    }

    /* ======================= общие ======================= */

    public static String buildUrl(DbType type, String host, Integer port,
                                  String database, String params, boolean integratedSecurity) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host is empty");

        if (type == DbType.OCEANBASE) {
            StringBuilder sb = new StringBuilder("jdbc:mysql://").append(host.trim());
            if (port != null) sb.append(':').append(port);
            sb.append('/');
            if (database != null && !database.isBlank()) sb.append(database.trim());

            Map<String, String> p = parseParams(OB_DEFAULT_PARAMS, "&");
            mergeParams(p, parseParams(params, "[&;]"));
            sb.append('?');
            p.forEach((k, v) -> sb.append(k).append('=').append(v).append('&'));
            sb.setLength(sb.length() - 1);
            return sb.toString();
        }

        if (type == DbType.MSSQL) {
            // сохраняем прежнее поведение для инстансов MSSQL
            StringBuilder sb = new StringBuilder("jdbc:sqlserver://").append(host.trim());
            if (port != null) sb.append(':').append(port);
            sb.append(";encrypt=false;trustServerCertificate=true");
            if (database != null && !database.isBlank()) sb.append(";databaseName=").append(database.trim());
            if (integratedSecurity) sb.append(";integratedSecurity=true");
            for (Map.Entry<String, String> e : parseParams(params, "[&;]").entrySet()) {
                sb.append(';').append(e.getKey()).append('=').append(e.getValue());
            }
            return MssqlConnectionStringEnricher.enrich(sb.toString());
        }

        throw new IllegalArgumentException("No JDBC URL for " + type);
    }

    /**
     * Для OCEANBASE склеивает логин вида {@code user@tenant#cluster}.
     * Если user уже содержит '@' или tenant пустой — возвращается как есть.
     */
    public static String buildUserName(DbType type, String user, String tenant, String cluster) {
        String u = user == null ? "" : user.trim();
        if (type != DbType.OCEANBASE || u.isEmpty() || u.contains("@")) return u;
        if (tenant == null || tenant.isBlank()) return u;

        StringBuilder sb = new StringBuilder(u).append('@').append(tenant.trim());
        if (cluster != null && !cluster.isBlank()) sb.append('#').append(cluster.trim());
        return sb.toString();
    }

    /**
     * Пароль: ${ENV_VAR} → значение переменной окружения; пусто →
     * env MSSQL_&lt;USER&gt;_PASSWORD или ввод с консоли (как у инстансов).
     */
    public static String resolvePassword(String user, String password) {
        String p = expandEnv(password);
        if (p != null && !p.isEmpty()) return p;
        return InstanceConfigEnreacher.resolvePassword(user);
    }

    /** "${NAME}" → System.getenv(NAME); иначе строка как есть. */
    public static String expandEnv(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.startsWith("${") && t.endsWith("}") && t.length() > 3) {
            String name = t.substring(2, t.length() - 1);
            String v = System.getenv(name);
            if (v == null) {
                LogService.errorf("[CFG] environment variable %s is not set%n", name);
                return "";
            }
            return v;
        }
        return s;
    }

    public static void loadDriver(DbType type) {
        // В fat-jar META-INF/services/java.sql.Driver от одного драйвера перетирает другой,
        // поэтому драйвер регистрируем явно.
        try {
            Class.forName(type.driverClass());
        } catch (ClassNotFoundException e) {
            LogService.errorf("[DB-ERROR] JDBC driver not found for %s: %s. Check lib/ / fat jar.%n",
                    type, type.driverClass());
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> parseParams(String s, String sepRegex) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isBlank()) return m;
        for (String part : s.trim().split(sepRegex)) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String k = part.substring(0, eq).trim();
            String v = part.substring(eq + 1).trim();
            m.keySet().removeIf(x -> x.equalsIgnoreCase(k));
            m.put(k, v);
        }
        return m;
    }

    /** override → base, ключи без учёта регистра. */
    private static void mergeParams(Map<String, String> base, Map<String, String> override) {
        for (Map.Entry<String, String> e : override.entrySet()) {
            base.keySet().removeIf(k -> k.equalsIgnoreCase(e.getKey()));
            base.put(e.getKey(), e.getValue());
        }
    }
}
