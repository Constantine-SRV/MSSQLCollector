package model;

import db.MssqlConnectionStringEnricher;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

/**
 * Читает конфигурацию MSSQLCollector из XML-файла.
 *  ─ поддерживает теги:
 *      <TaskName>           RUN | SAVE_CONFIGS | PROCESS_XML_RESULT
 *      <ThreadPoolSize>     int
 *      блоки ServersSource, JobsSource, ResultsDestination, LogsDestination
 *  ─ строки подключения MSSQL автоматически «обогащаются»
 *    (encrypt, trustServerCertificate, multiSubnetFailover, applicationName).
 */
public final class AppConfigReader {

    private AppConfigReader() { }

    /**
     * @param fileName имя XML-файла
     * @return объект AppConfig или null, если файл не найден
     */
    public static AppConfig read(String fileName) throws Exception {
        File f = new File(fileName);
        if (!f.exists()) return null;

        Document doc = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f);

        AppConfig cfg = new AppConfig();

        // --- TaskName -------------------------------------------------
        cfg.taskName = get(doc.getDocumentElement(), "TaskName")
                .toUpperCase().trim();
        if (cfg.taskName.isEmpty()) cfg.taskName = "RUN";

        // --- ThreadPoolSize ------------------------------------------
        String pool = get(doc.getDocumentElement(), "ThreadPoolSize");
        try { cfg.threadPoolSize = Integer.parseInt(pool); }
        catch (NumberFormatException ignored) { }

        // --- Блоки источников / приёмников ----------------------------
        cfg.serversSource      = readSource(doc, "ServersSource");
        cfg.jobsSource         = readSource(doc, "JobsSource");
        cfg.resultsDestination = readDest(doc,  "ResultsDestination");
        cfg.logsDestination    = readDest(doc,  "LogsDestination");

        return cfg;
    }

    /* ---------- private helpers ---------- */

    private static SourceConfig readSource(Document d, String tag) {
        SourceConfig sc = new SourceConfig();
        Element el = (Element) d.getElementsByTagName(tag).item(0);
        if (el == null) return sc;

        sc.type = get(el, "Type");

        String raw = get(el, "MSSQLConnectionString");
        sc.mssqlConnectionString =
                raw.isBlank() ? raw : MssqlConnectionStringEnricher.enrich(raw);

        sc.mssqlQuery            = get(el, "MSSQLQuery");
        sc.mongoConnectionString = get(el, "MongoConnectionString");
        sc.mongoCollectionName   = get(el, "MongoCollectionName");
        sc.fileName              = get(el, "FileName");
        return sc;
    }

    private static DestinationConfig readDest(Document d, String tag) {
        DestinationConfig dc = new DestinationConfig();
        Element el = (Element) d.getElementsByTagName(tag).item(0);
        if (el == null) return dc;

        dc.type = get(el, "Type");

        String raw = get(el, "MSSQLConnectionString");
        dc.mssqlConnectionString =
                raw.isBlank() ? raw : MssqlConnectionStringEnricher.enrich(raw);

        dc.mssqlQuery            = get(el, "MSSQLQuery");
        dc.mongoConnectionString = get(el, "MongoConnectionString");
        dc.mongoCollectionName   = get(el, "MongoCollectionName");
        dc.directoryPath         = get(el, "DirectoryPath");
        return dc;
    }

    private static String get(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }
}
