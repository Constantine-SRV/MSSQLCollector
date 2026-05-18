package model;

import db.MssqlConnectionStringEnricher;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Locale;

/**
 * Чтение конфигурационного файла приложения MSSQLCollector.
 *
 * Совместимо со старыми конфигами: новые теги (ResultFormat, OCEANBASE и т.д.)
 * опциональны и при их отсутствии работает прежнее поведение.
 */
public class AppConfigReader {

    /**
     * Читает AppConfig из XML. Если файла нет, возвращает null.
     */
    public static AppConfig read(String fileName) throws Exception {
        File file = new File(fileName);
        if (!file.exists()) return null;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);

        AppConfig cfg = new AppConfig();

        // --- TaskName / ThreadPoolSize ---
        Element root = doc.getDocumentElement();
        cfg.taskName       = getText(root, "TaskName");
        cfg.threadPoolSize = parseIntSafe(getText(root, "ThreadPoolSize"), 8);

        cfg.serversSource      = readSource(doc, "ServersSource");
        cfg.jobsSource         = readSource(doc, "JobsSource");
        cfg.resultsDestination = readDestination(doc, "ResultsDestination");
        cfg.logsDestination    = readDestination(doc, "LogsDestination");
        return cfg;
    }

    // ──────────────────────────────────────────────────────────────
    private static SourceConfig readSource(Document doc, String tag) {
        SourceConfig sc = new SourceConfig();
        Node n = doc.getElementsByTagName(tag).item(0);
        if (n instanceof Element el) {
            sc.type = getText(el, "Type");

            // Сырая JDBC-строка
            String raw = getText(el, "MSSQLConnectionString");

            // MSSQL-Enricher применяется только для MSSQL.
            // Для OCEANBASE и прочих типов (LocalFile, Mongo, ...) — оставляем строку как есть.
            if (isMssqlJdbcType(sc.type)) {
                sc.mssqlConnectionString = MssqlConnectionStringEnricher.enrich(raw);
            } else {
                sc.mssqlConnectionString = raw == null ? "" : raw;
            }

            sc.mssqlQuery            = getText(el, "MSSQLQuery");
            sc.mongoConnectionString = getText(el, "MongoConnectionString");
            sc.mongoCollectionName   = getText(el, "MongoCollectionName");
            sc.fileName              = getText(el, "FileName");
        }
        return sc;
    }

    private static DestinationConfig readDestination(Document doc, String tag) {
        DestinationConfig dc = new DestinationConfig();
        Node n = doc.getElementsByTagName(tag).item(0);
        if (n instanceof Element el) {
            dc.type = getText(el, "Type");

            String raw = getText(el, "MSSQLConnectionString");
            if (isMssqlJdbcType(dc.type)) {
                dc.mssqlConnectionString = MssqlConnectionStringEnricher.enrich(raw);
            } else {
                dc.mssqlConnectionString = raw == null ? "" : raw;
            }

            dc.mssqlQuery            = getText(el, "MSSQLQuery");
            dc.mongoConnectionString = getText(el, "MongoConnectionString");
            dc.mongoCollectionName   = getText(el, "MongoCollectionName");
            dc.directoryPath         = getText(el, "DirectoryPath");

            // Prometheus
            dc.prometheusUrl         = getText(el, "PrometheusUrl");

            // NEW: формат сериализации результата (XML|JSON). Пустое → разрулится в ResponseProcessor.
            dc.resultFormat          = getText(el, "ResultFormat");
        }
        return dc;
    }

    /**
     * Должен ли вообще применяться MSSQL-enricher к строке подключения?
     * Применяем только если явно указан MSSQL (или тип не указан вообще —
     * исторический дефолт для обратной совместимости).
     */
    private static boolean isMssqlJdbcType(String type) {
        if (type == null) return true;
        String n = type.trim().toUpperCase(Locale.ROOT);
        if (n.isEmpty()) return true;
        return n.equals("MSSQL") || n.equals("SQLSERVER");
    }

    // ──────────────────────────────────────────────────────────────
    private static String getText(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        return (nl.getLength() == 0) ? "" : nl.item(0).getTextContent().trim();
    }

    private static int parseIntSafe(String s, int defVal) {
        try { return Integer.parseInt(s); } catch (Exception e) { return defVal; }
    }
}
