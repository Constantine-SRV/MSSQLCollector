package model;

import db.MssqlConnectionStringEnricher;
import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.*;
import javax.xml.transform.stream.*;
import java.io.File;

/**
 * Чтение/запись основного конфигурационного файла приложения MSSQLCollector.
 * Строки подключения к MSSQL автоматически обогащаются полезными параметрами.
 */
public class AppConfigReaderWriter {

    /**
     * Читает AppConfig из XML. Если файла нет, возвращает null.
     */
    public static AppConfig readConfig(String fileName) throws Exception {
        File file = new File(fileName);
        if (!file.exists()) return null;

        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.parse(file);

        AppConfig cfg = new AppConfig();

        // --- Чтение ThreadPoolSize, если есть ---
        NodeList poolNodes = doc.getElementsByTagName("ThreadPoolSize");
        if (poolNodes.getLength() > 0) {
            try {
                cfg.threadPoolSize = Integer.parseInt(poolNodes.item(0).getTextContent().trim());
            } catch (NumberFormatException e) {
                cfg.threadPoolSize = 8; // дефолт
            }
        }

        cfg.serversSource      = readSource(doc, "ServersSource");
        cfg.jobsSource         = readSource(doc, "JobsSource");
        cfg.resultsDestination = readDestination(doc, "ResultsDestination");
        cfg.logsDestination    = readDestination(doc, "LogsDestination");
        return cfg;
    }

    /**
     * Записывает AppConfig в XML, с дефолтными значениями и комментариями.
     */
    public static void writeConfig(AppConfig cfg, String fileName) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        Document doc = db.newDocument();

        // Root element
        Element root = doc.createElement("MSSQLCollectorConfig");
        doc.appendChild(root);

        // ThreadPoolSize первым (или где хочешь)
        root.appendChild(makeElem(doc, "ThreadPoolSize", String.valueOf(cfg.threadPoolSize)));

        // Comment at the top
        root.appendChild(doc.createComment(
                "\n  MSSQLCollector configuration file\n" +
                        "  Type possible values: MSSQL | MONGO | LocalFile | Console\n" +
                        "  Leave parameters empty to use default behavior\n"
        ));

        // ServersSource section
        root.appendChild(doc.createComment(" Source of servers list "));
        root.appendChild(writeSource(doc, "ServersSource", cfg.serversSource));

        // JobsSource section
        root.appendChild(doc.createComment(" Source of jobs list "));
        root.appendChild(writeSource(doc, "JobsSource", cfg.jobsSource));

        // ResultsDestination section
        root.appendChild(doc.createComment(" Destination for results "));
        root.appendChild(writeDestination(doc, "ResultsDestination", cfg.resultsDestination));

        // LogsDestination section
        root.appendChild(doc.createComment(" Destination for application logs "));
        root.appendChild(writeDestination(doc, "LogsDestination", cfg.logsDestination));

        // Write XML to file
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.STANDALONE, "yes");
        t.transform(new DOMSource(doc), new StreamResult(new File(fileName)));
    }

    // ──────────────────────────────────────────────────────────────

    /**
     * Читает блок SourceConfig из XML и обогащает строку подключения MSSQL.
     */
    private static SourceConfig readSource(Document doc, String tag) {
        SourceConfig sc = new SourceConfig();
        Node node = doc.getElementsByTagName(tag).item(0);
        if (node != null && node instanceof Element el) {
            sc.type                  = getText(el, "Type");
            // enrich сразу при чтении!
            String rawConn = getText(el, "MSSQLConnectionString");
            sc.mssqlConnectionString = (rawConn == null || rawConn.equals("-") || rawConn.isBlank())
                    ? rawConn
                    : db.MssqlConnectionStringEnricher.enrich(rawConn);
            sc.mssqlQuery            = getText(el, "MSSQLQuery");
            sc.mongoConnectionString = getText(el, "MongoConnectionString");
            sc.mongoCollectionName   = getText(el, "MongoCollectionName");
            sc.fileName              = getText(el, "FileName");
        }
        return sc;
    }

    /**
     * Читает блок DestinationConfig из XML и обогащает строку подключения MSSQL.
     */
    private static DestinationConfig readDestination(Document doc, String tag) {
        DestinationConfig dc = new DestinationConfig();
        Node node = doc.getElementsByTagName(tag).item(0);
        if (node != null && node instanceof Element el) {
            dc.type                  = getText(el, "Type");
            String rawConn = getText(el, "MSSQLConnectionString");
            dc.mssqlConnectionString = (rawConn == null || rawConn.equals("-") || rawConn.isBlank())
                    ? rawConn
                    : db.MssqlConnectionStringEnricher.enrich(rawConn);
            dc.mssqlQuery            = getText(el, "MSSQLQuery");
            dc.mongoConnectionString = getText(el, "MongoConnectionString");
            dc.mongoCollectionName   = getText(el, "MongoCollectionName");
            dc.directoryPath         = getText(el, "DirectoryPath");
        }
        return dc;
    }

    /**
     * Создаёт XML-элемент с заданным значением.
     * Если строка пустая — ставим прочерк, чтобы тег был раскрытым
     */
    private static Element makeElem(Document doc, String tag, String val) {
        Element e = doc.createElement(tag);
        e.setTextContent(val == null || val.isEmpty() ? "-" : val);
        return e;
    }

    /**
     * Получает текстовое значение из указанного дочернего элемента.
     * Если тег не найден — возвращает пустую строку.
     */
    private static String getText(Element el, String tag) {
        NodeList nl = el.getElementsByTagName(tag);
        if (nl.getLength() == 0) return "";
        return nl.item(0).getTextContent().trim();
    }

    /**
     * Генерирует блок SourceConfig для сохранения в XML
     */
    private static Element writeSource(Document doc, String tag, SourceConfig sc) {
        Element el = doc.createElement(tag);
        // Use MSSQL as default for sources if not set
        el.appendChild(makeElem(doc, "Type", sc.type == null || sc.type.isEmpty() ? "MSSQL" : sc.type));
        el.appendChild(makeElem(doc, "MSSQLConnectionString", sc.mssqlConnectionString));
        el.appendChild(makeElem(doc, "MSSQLQuery", sc.mssqlQuery));
        el.appendChild(makeElem(doc, "MongoConnectionString", sc.mongoConnectionString));
        el.appendChild(makeElem(doc, "MongoCollectionName", sc.mongoCollectionName));
        el.appendChild(makeElem(doc, "FileName", sc.fileName));
        return el;
    }

    /**
     * Генерирует блок DestinationConfig для сохранения в XML
     */
    private static Element writeDestination(Document doc, String tag, DestinationConfig dc) {
        Element el = doc.createElement(tag);
        // Use LocalFile/Console as default for destinations if not set
        String defaultType = "ResultsDestination".equals(tag) ? "LocalFile" : "Console";
        el.appendChild(makeElem(doc, "Type", dc.type == null || dc.type.isEmpty() ? defaultType : dc.type));
        el.appendChild(makeElem(doc, "MSSQLConnectionString", dc.mssqlConnectionString));
        el.appendChild(makeElem(doc, "MSSQLQuery", dc.mssqlQuery));
        el.appendChild(makeElem(doc, "MongoConnectionString", dc.mongoConnectionString));
        el.appendChild(makeElem(doc, "MongoCollectionName", dc.mongoCollectionName));
        el.appendChild(makeElem(doc, "DirectoryPath", dc.directoryPath));
        return el;
    }
}
