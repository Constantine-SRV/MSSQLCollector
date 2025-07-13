package model;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;

/**
 * Записывает объект {@link AppConfig} в XML-файл.
 * Формат совместим с AppConfigReader.
 */
public final class AppConfigWriter {

    private AppConfigWriter() { }

    /**
     * Сохраняет конфигурацию.
     * @param cfg       объект AppConfig
     * @param fileName  имя целевого XML-файла
     */
    public static void write(AppConfig cfg, String fileName) throws Exception {
        Document doc = DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .newDocument();

        Element root = doc.createElement("MSSQLCollectorConfig");
        doc.appendChild(root);

        // Главные поля
        add(doc, root, "TaskName",        cfg.taskName);
        add(doc, root, "ThreadPoolSize",  String.valueOf(cfg.threadPoolSize));

        // Комментарий-подсказка
        root.appendChild(doc.createComment(
                "\n  MSSQLCollector configuration file\n" +
                        "  Type possible values: MSSQL | MONGO | LocalFile | Console\n" +
                        "  Leave parameters empty to use default behavior\n"));

        root.appendChild(doc.createComment(" Source of servers list "));
        root.appendChild(writeSource(doc, "ServersSource", cfg.serversSource));

        root.appendChild(doc.createComment(" Source of jobs list "));
        root.appendChild(writeSource(doc, "JobsSource", cfg.jobsSource));

        root.appendChild(doc.createComment(" Destination for results "));
        root.appendChild(writeDest(doc, "ResultsDestination", cfg.resultsDestination));

        root.appendChild(doc.createComment(" Destination for application logs "));
        root.appendChild(writeDest(doc, "LogsDestination", cfg.logsDestination));

        Transformer tr = TransformerFactory.newInstance().newTransformer();
        tr.setOutputProperty(OutputKeys.INDENT, "yes");
        tr.setOutputProperty(OutputKeys.STANDALONE, "yes");
        tr.transform(new DOMSource(doc), new StreamResult(new File(fileName)));
    }

    /* ---------- private helpers ---------- */

    private static Element writeSource(Document d, String tag, SourceConfig sc) {
        Element el = d.createElement(tag);
        add(d, el, "Type",                  blank(sc.type, "MSSQL"));
        add(d, el, "MSSQLConnectionString", sc.mssqlConnectionString);
        add(d, el, "MSSQLQuery",            sc.mssqlQuery);
        add(d, el, "MongoConnectionString", sc.mongoConnectionString);
        add(d, el, "MongoCollectionName",   sc.mongoCollectionName);
        add(d, el, "FileName",              sc.fileName);
        return el;
    }

    private static Element writeDest(Document d, String tag, DestinationConfig dc) {
        Element el = d.createElement(tag);
        String def = "ResultsDestination".equals(tag) ? "LocalFile" : "Console";
        add(d, el, "Type",                  blank(dc.type, def));
        add(d, el, "MSSQLConnectionString", dc.mssqlConnectionString);
        add(d, el, "MSSQLQuery",            dc.mssqlQuery);
        add(d, el, "MongoConnectionString", dc.mongoConnectionString);
        add(d, el, "MongoCollectionName",   dc.mongoCollectionName);
        add(d, el, "DirectoryPath",         dc.directoryPath);
        return el;
    }

    private static void add(Document d, Node parent, String tag, String val) {
        Element e = d.createElement(tag);
        e.setTextContent(val == null || val.isBlank() ? "-" : val);
        parent.appendChild(e);
    }
    private static String blank(String v, String def) {
        return (v == null || v.isBlank()) ? def : v;
    }
}
