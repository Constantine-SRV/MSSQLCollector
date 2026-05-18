package model;

import logging.LogService;

import java.io.File;
import java.sql.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

/**
 * Читает список инстансов из источника, заданного в AppConfig:
 *  - MSSQL     : выполняет запрос (драйвер mssql-jdbc)
 *  - OCEANBASE : выполняет запрос (драйвер mysql-connector-j)
 *  - LocalFile : читает локальный XML
 *  - MONGO     : (заглушка)
 *
 * Дополнительные опциональные колонки:
 *  - dbType   / db_type  → InstanceConfig.dbType
 *  - tenant              → InstanceConfig.tenant
 *  - cluster             → InstanceConfig.cluster
 *
 * Все остальные не-стандартные непустые столбцы автоматически сохраняются в extraLabels.
 *
 * При чтении из локального XML поддерживается блок:
 *
 * <Instance>
 *    ...
 *    <DbType>OCEANBASE</DbType>
 *    <Tenant>business_tenant</Tenant>
 *    <Cluster>obcluster</Cluster>
 *    <ExtraLabels>
 *       <Label key="env">prod</Label>
 *       <Label key="dc">MSK-1</Label>
 *    </ExtraLabels>
 * </Instance>
 */
public final class InstancesConfigReader {

    private InstancesConfigReader() {}

    /**
     * Главная точка: читает конфиг источника и возвращает список инстансов.
     */
    public static List<InstanceConfig> readConfig(AppConfig cfg) throws Exception {
        SourceConfig sc = cfg.serversSource;
        if (sc == null || sc.type == null) {
            LogService.errorln("InstancesConfigReader: serversSource not configured.");
            return List.of();
        }
        String type = sc.type.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "MSSQL"     -> { return readFromJdbc(sc, DbType.MSSQL); }
            case "OCEANBASE", "OB"
                             -> { return readFromJdbc(sc, DbType.OCEANBASE); }
            case "LOCALFILE",
                 "FILE"      -> { return readFromLocalFile(sc); }
            case "MONGO"     -> {
                LogService.println("InstancesConfigReader: MONGO source is not implemented yet.");
                return List.of();
            }
            default          -> {
                LogService.errorln("InstancesConfigReader: unknown source type: " + sc.type);
                return List.of();
            }
        }
    }

    /* ======================== JDBC (MSSQL / OCEANBASE) ========================= */

    private static List<InstanceConfig> readFromJdbc(SourceConfig sc, DbType srcDbType) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();

        String url = sc.mssqlConnectionString;
        String sql = sc.mssqlQuery;
        if (url == null || url.isBlank() || sql == null || sql.isBlank()) {
            LogService.errorf("InstancesConfigReader(%s): empty connection string or query.%n", srcDbType);
            return list;
        }

        // Явная загрузка драйвера — на случай fat-jar / нестандартного classloader
        try {
            Class.forName(srcDbType.driverClass());
        } catch (ClassNotFoundException e) {
            LogService.errorf("InstancesConfigReader(%s): JDBC driver not found: %s%n",
                    srcDbType, srcDbType.driverClass());
            throw e;
        }

        try (Connection c = DriverManager.getConnection(url);
             Statement st  = c.createStatement();
             ResultSet rs  = st.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();

            while (rs.next()) {
                InstanceConfig ic = new InstanceConfig();

                // ---- стандартные поля ----
                ic.ci           = pickStr(rs, md, "ci", "CI", "id", "Id");
                ic.instanceName = pickStr(rs, md, "instanceName", "InstanceName", "instance", "server", "servername", "ServerName", "host");
                ic.userName     = pickStr(rs, md, "userName", "UserName", "user", "login");
                ic.password     = pickStr(rs, md, "password", "Password", "pwd");

                String portStr  = pickStr(rs, md, "port", "Port");
                if (portStr != null && !portStr.isBlank()) {
                    try {
                        ic.port = Integer.parseInt(portStr.trim());
                    } catch (NumberFormatException ignore) {
                        ic.port = null;
                    }
                }

                // ---- NEW: dbType / tenant / cluster ----
                String dbTypeStr = pickStr(rs, md, "dbType", "DbType", "db_type", "DBType");
                // Если колонка вообще отсутствует / пустая — для MSSQL-источника считаем MSSQL,
                // для OCEANBASE-источника по умолчанию ставим OCEANBASE (логичный дефолт).
                ic.dbType = (dbTypeStr == null || dbTypeStr.isBlank())
                        ? srcDbType
                        : DbType.parse(dbTypeStr);

                ic.tenant  = nullIfBlank(pickStr(rs, md, "tenant",  "Tenant",  "ob_tenant"));
                ic.cluster = nullIfBlank(pickStr(rs, md, "cluster", "Cluster", "ob_cluster"));

                // ---- дополнительные лейблы (всё нестандартное → extraLabels) ----
                Set<String> std = Set.of(
                        "ci", "instancename", "instance", "server", "servername", "host",
                        "port", "username", "user", "login", "password", "pwd",
                        "dbtype", "db_type",
                        "tenant", "ob_tenant",
                        "cluster", "ob_cluster"
                );
                for (int i = 1; i <= colCount; i++) {
                    String col = md.getColumnLabel(i);
                    if (col == null || col.isBlank()) col = md.getColumnName(i);
                    if (col == null || col.isBlank()) continue;

                    String keyLower = col.toLowerCase(Locale.ROOT);
                    if (std.contains(keyLower)) continue;

                    String val = rs.getString(i);
                    if (val == null) continue;
                    val = val.trim();
                    if (val.isEmpty()) continue;

                    if (val.length() > 8000) val = val.substring(0, 8000);
                    ic.extraLabels.put(col, val);
                }

                // лёгкая валидация
                if (ic.ci == null || ic.ci.isBlank() || ic.instanceName == null || ic.instanceName.isBlank()) {
                    LogService.errorf("InstancesConfigReader(%s): row skipped (ci/instanceName is empty).%n", srcDbType);
                    continue;
                }

                list.add(ic);
            }

            LogService.printf("InstancesConfigReader: loaded %d servers from %s.%n", list.size(), srcDbType);
            return list;
        }
    }

    /* ======================== LocalFile (XML) ========================= */

    private static List<InstanceConfig> readFromLocalFile(SourceConfig sc) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();

        String fileName = sc.fileName;
        if (fileName == null || fileName.isBlank()) {
            LogService.errorln("InstancesConfigReader(LocalFile): file name is empty.");
            return list;
        }

        File f = new File(fileName);
        if (!f.exists()) {
            LogService.errorln("InstancesConfigReader(LocalFile): file not found: " + f.getAbsolutePath());
            return list;
        }

        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f);
        NodeList nodes = doc.getElementsByTagName("Instance");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node n = nodes.item(i);
            if (!(n instanceof Element el)) continue;

            InstanceConfig ic = new InstanceConfig();
            ic.ci           = text(el, "CI");
            ic.instanceName = text(el, "InstanceName");
            ic.userName     = text(el, "UserName");
            ic.password     = text(el, "Password");

            String portStr  = text(el, "Port");
            if (!portStr.isEmpty()) {
                try { ic.port = Integer.parseInt(portStr); } catch (NumberFormatException ignore) {}
            }

            // NEW: DbType / Tenant / Cluster
            String dbTypeStr = text(el, "DbType");
            ic.dbType  = (dbTypeStr.isEmpty()) ? DbType.MSSQL : DbType.parse(dbTypeStr);
            ic.tenant  = nullIfBlank(text(el, "Tenant"));
            ic.cluster = nullIfBlank(text(el, "Cluster"));

            // ExtraLabels (необязательно)
            NodeList labelsBlocks = el.getElementsByTagName("ExtraLabels");
            if (labelsBlocks.getLength() > 0) {
                Element lb = (Element) labelsBlocks.item(0);
                NodeList all = lb.getElementsByTagName("Label");
                for (int j = 0; j < all.getLength(); j++) {
                    Node ln = all.item(j);
                    if (!(ln instanceof Element le)) continue;
                    String key = le.getAttribute("key");
                    String val = le.getTextContent() == null ? "" : le.getTextContent().trim();
                    if (key != null && !key.isBlank() && !val.isEmpty()) {
                        if (val.length() > 8000) val = val.substring(0, 8000);
                        ic.extraLabels.put(key, val);
                    }
                }
            }

            if (ic.ci == null || ic.ci.isBlank() || ic.instanceName == null || ic.instanceName.isBlank()) {
                LogService.errorln("InstancesConfigReader(LocalFile): <Instance> skipped (ci/instanceName empty).");
                continue;
            }
            list.add(ic);
        }

        LogService.printf("InstancesConfigReader: loaded %d servers from local file '%s'%n",
                list.size(), f.getAbsolutePath());
        return list;
    }

    /* ======================== helpers ========================= */

    private static String text(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        String s = nl.item(0).getTextContent();
        return s == null ? "" : s.trim();
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String pickStr(ResultSet rs, ResultSetMetaData md, String... names) throws SQLException {
        Map<String, Integer> byName = new HashMap<>();
        int cols = md.getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String label = md.getColumnLabel(i);
            if (label != null && !label.isBlank()) {
                byName.putIfAbsent(label.toLowerCase(Locale.ROOT), i);
            }
            String name = md.getColumnName(i);
            if (name != null && !name.isBlank()) {
                byName.putIfAbsent(name.toLowerCase(Locale.ROOT), i);
            }
        }
        for (String n : names) {
            Integer idx = byName.get(n.toLowerCase(Locale.ROOT));
            if (idx != null) {
                String v = rs.getString(idx);
                return (v == null) ? null : v.trim();
            }
        }
        return null;
    }
}
