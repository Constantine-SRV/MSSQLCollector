package model;

import logging.LogService;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.util.Locale;

/**
 * Чтение конфигурационного файла приложения MSSQLCollector.
 *
 * Новый формат подключения (для ServersSource / JobsSource / ResultsDestination):
 * <pre>
 *   &lt;Type&gt;OCEANBASE&lt;/Type&gt;
 *   &lt;Connection&gt; Host / Port / User / Tenant / Cluster / Password / Database / Params &lt;/Connection&gt;
 *   &lt;Query&gt;SELECT ...&lt;/Query&gt;
 * </pre>
 * или готовый URL: {@code <ConnectionString>jdbc:...</ConnectionString>}.
 *
 * Обратная совместимость: {@code <MSSQLConnectionString>} и {@code <MSSQLQuery>}
 * читаются как раньше (с предупреждением в логе).
 * Значение "-" трактуется как пустое.
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
        String task = getText(root, "TaskName");
        cfg.taskName       = task.isEmpty() ? "RUN" : task;
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
            sc.type                  = getText(el, "Type");
            sc.connection            = readConnection(el, tag);
            sc.query                 = readQuery(el, tag);
            sc.mongoConnectionString = getText(el, "MongoConnectionString");
            sc.mongoCollectionName   = getText(el, "MongoCollectionName");
            sc.fileName              = getText(el, "FileName");
            sc.targetDbType          = DbType.parseOrNull(getText(el, "TargetDbType"));
        }
        return sc;
    }

    private static DestinationConfig readDestination(Document doc, String tag) {
        DestinationConfig dc = new DestinationConfig();
        Node n = doc.getElementsByTagName(tag).item(0);
        if (n instanceof Element el) {
            dc.type                  = getText(el, "Type");
            dc.connection            = readConnection(el, tag);
            dc.query                 = readQuery(el, tag);
            dc.mongoConnectionString = getText(el, "MongoConnectionString");
            dc.mongoCollectionName   = getText(el, "MongoCollectionName");
            dc.directoryPath         = getText(el, "DirectoryPath");
            dc.prometheusUrl         = getText(el, "PrometheusUrl");
            dc.resultFormat          = getText(el, "ResultFormat");
        }
        return dc;
    }

    /** {@code <Connection>} + {@code <ConnectionString>} / устаревший {@code <MSSQLConnectionString>}. */
    private static ConnectionConfig readConnection(Element parent, String sectionTag) {
        ConnectionConfig cc = new ConnectionConfig();

        Element c = child(parent, "Connection");
        if (c != null) {
            cc.host     = nullIfBlank(getText(c, "Host"));
            String port = getText(c, "Port");
            if (!port.isEmpty()) {
                try { cc.port = Integer.parseInt(port); }
                catch (NumberFormatException e) {
                    throw new IllegalArgumentException(sectionTag + "/Connection/Port is not a number: " + port);
                }
            }
            cc.user     = nullIfBlank(getText(c, "User"));
            cc.password = nullIfBlank(getText(c, "Password"));
            cc.tenant   = nullIfBlank(getText(c, "Tenant"));
            cc.cluster  = nullIfBlank(getText(c, "Cluster"));
            cc.database = nullIfBlank(getText(c, "Database"));
            cc.params   = nullIfBlank(getText(c, "Params"));
            cc.integratedSecurity = "true".equalsIgnoreCase(getText(c, "IntegratedSecurity"));
        }

        String url = getText(parent, "ConnectionString");
        if (url.isEmpty()) {
            url = getText(parent, "MSSQLConnectionString");
            if (!url.isEmpty()) {
                LogService.printf("[CFG] %s: <MSSQLConnectionString> is deprecated, use <Connection> or <ConnectionString>%n",
                        sectionTag);
            }
        }
        // Многострочные URL из старых конфигов: убираем переводы строк и пробелы по краям частей
        if (!url.isEmpty()) cc.connectionString = url.replaceAll("\\s*[\\r\\n]+\\s*", "");
        return cc;
    }

    /** {@code <Query>} / устаревший {@code <MSSQLQuery>}. */
    private static String readQuery(Element parent, String sectionTag) {
        String q = getText(parent, "Query");
        if (q.isEmpty()) {
            q = getText(parent, "MSSQLQuery");
            if (!q.isEmpty()) {
                LogService.printf("[CFG] %s: <MSSQLQuery> is deprecated, use <Query>%n", sectionTag);
            }
        }
        return q;
    }

    // ──────────────────────────────────────────────────────────────

    /** Текст первого потомка с данным тегом; "-" и отсутствие → "". */
    private static String getText(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        String s = nl.item(0).getTextContent();
        s = s == null ? "" : s.trim();
        return s.equals("-") ? "" : s;
    }

    private static Element child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) return e;
        }
        return null;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static int parseIntSafe(String s, int defVal) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return defVal; }
    }

    /** Нормализованный тип (UPPER, без пробелов); пусто → "". */
    public static String norm(String type) {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }
}
