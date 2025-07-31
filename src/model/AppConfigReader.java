package model;

import db.MssqlConnectionStringEnricher;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

/**
 * Чтение конфигурационного файла приложения MSSQLCollector.
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
        Element root = doc.getDocumentElement();               // <—- корневой элемент
        cfg.taskName       = getText(root, "TaskName");
        cfg.threadPoolSize = parseIntSafe(getText(root,"ThreadPoolSize"), 8);

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

            /*  <<< ВОССТАНОВЛЕНО >>>
                Сразу обогащаем строку подключения обязательными параметрами
             */
            String raw = getText(el, "MSSQLConnectionString");
            sc.mssqlConnectionString = MssqlConnectionStringEnricher.enrich(raw);

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

            /*  <<< ВОССТАНОВЛЕНО >>>  */
            String raw = getText(el, "MSSQLConnectionString");
            dc.mssqlConnectionString = MssqlConnectionStringEnricher.enrich(raw);

            dc.mssqlQuery            = getText(el, "MSSQLQuery");
            dc.mongoConnectionString = getText(el, "MongoConnectionString");
            dc.mongoCollectionName   = getText(el, "MongoCollectionName");
            dc.directoryPath         = getText(el, "DirectoryPath");

            //  Prometheus
            dc.prometheusUrl         = getText(el, "PrometheusUrl");

        }
        return dc;
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
