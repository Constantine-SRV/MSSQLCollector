package model;

import logging.LogService;

import java.io.File;
import java.sql.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.*;

/**
 * Читает список инстансов MSSQL из источника, заданного в AppConfig:
 *  - MSSQL: выполняет запрос и мапит колонки в поля {@link InstanceConfig}
 *  - LocalFile: читает локальный XML
 *  - MONGO: (заглушка)
 *
 * Новое: любые дополнительные столбцы (не входящие в стандартный набор полей)
 * при чтении из MSSQL автоматически сохраняются в  extraLabels.
 * При чтении из локального XML поддерживается блок:
 *
 * <ExtraLabels>
 *    <Label key="env">prod</Label>
 *    <Label key="dc">MSK-1</Label>
 * </ExtraLabels>
 *
 * Значения пустые/NULL — игнорируются.
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
            case "MSSQL"     -> { return readFromMSSQL(sc); }
            case "LOCALFILE" -> { return readFromLocalFile(sc); }
            case "FILE"      -> { return readFromLocalFile(sc); } // синоним
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

    /* ======================== MSSQL ========================= */

    private static List<InstanceConfig> readFromMSSQL(SourceConfig sc) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();

        String url = sc.mssqlConnectionString;
        String sql = sc.mssqlQuery;
        if (url == null || url.isBlank() || sql == null || sql.isBlank()) {
            LogService.errorln("InstancesConfigReader(MSSQL): empty connection string or query.");
            return list;
        }

        try (Connection c = DriverManager.getConnection(url);
             Statement st  = c.createStatement();
             ResultSet rs  = st.executeQuery(sql)) {

            ResultSetMetaData md = rs.getMetaData();
            int colCount = md.getColumnCount();

            while (rs.next()) {
                InstanceConfig ic = new InstanceConfig();

                // ---- стандартные поля (оставляем как и раньше) ----
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

                // ---- дополнительные лейблы (автоматически) ----
                // все не-стандартные НЕПУСТЫЕ столбцы -> extraLabels
                Set<String> std = Set.of(
                        "ci", "instancename", "instance", "server", "servername", "host",
                        "port", "username", "user", "login", "password", "pwd"
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

                    // "varchar(8000)" по смыслу: мягкое усечение
                    if (val.length() > 8000) val = val.substring(0, 8000);
                    ic.extraLabels.put(col, val);
                }

                // лёгкая валидация
                if (ic.ci == null || ic.ci.isBlank() || ic.instanceName == null || ic.instanceName.isBlank()) {
                    LogService.errorln("InstancesConfigReader(MSSQL): row skipped (ci/instanceName is empty).");
                    continue;
                }

                list.add(ic);
            }

            LogService.printf("InstancesConfigReader: loaded %d servers from MSSQL.%n", list.size());
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

    /** Возвращает содержимое первого найденного тега, иначе пустую строку. */
    private static String text(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        String s = nl.item(0).getTextContent();
        return s == null ? "" : s.trim();
    }

    /**
     * Пытается взять строковое значение из любого из указанных имён колонок.
     * Возвращает null, если ни одно имя не найдено или значение NULL.
     */
    private static String pickStr(ResultSet rs, ResultSetMetaData md, String... names) throws SQLException {
        // Предварительно построим map: lower(label/name) -> index
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
