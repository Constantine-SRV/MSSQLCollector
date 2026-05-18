package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import logging.LogService;

/**
 * Универсальный загрузчик QueryRequest-ов (заданий).
 *
 * Поддерживаемые источники:
 *   - MSSQL      (драйвер mssql-jdbc)
 *   - OCEANBASE  (драйвер mysql-connector-j)
 *   - LOCALFILE  (XML)
 *   - MONGO      (заглушка)
 *
 * Для JDBC-источников SELECT должен вернуть поля {@code requestId} и {@code queryText}.
 */
public class QueryRequestsReader {

    public static List<QueryRequest> read(AppConfig appConfig) throws Exception {
        String type = appConfig.jobsSource.type == null
                ? ""
                : appConfig.jobsSource.type.trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "MSSQL":
                return readFromJdbc(appConfig.jobsSource, DbType.MSSQL);
            case "OCEANBASE":
            case "OB":
                return readFromJdbc(appConfig.jobsSource, DbType.OCEANBASE);
            case "MONGO":
                LogService.println("[WARN] MongoDB jobs source is not implemented yet!");
                return new ArrayList<>();
            case "LOCALFILE":
            default:
                String file = appConfig.jobsSource.fileName == null || appConfig.jobsSource.fileName.isEmpty()
                        ? "QueryRequests.xml"
                        : appConfig.jobsSource.fileName;
                return readFromLocalFile(file);
        }
    }

    /** Читает из JDBC-источника (MSSQL/OCEANBASE). SELECT должен вернуть колонки requestId, queryText. */
    private static List<QueryRequest> readFromJdbc(SourceConfig cfg, DbType dbType) throws Exception {
        List<QueryRequest> list = new ArrayList<>();

        try {
            Class.forName(dbType.driverClass());
        } catch (ClassNotFoundException e) {
            LogService.errorf("QueryRequestsReader(%s): JDBC driver not found: %s%n",
                    dbType, dbType.driverClass());
            throw e;
        }

        try (Connection con = DriverManager.getConnection(cfg.mssqlConnectionString);
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(cfg.mssqlQuery)) {
            while (rs.next()) {
                String id   = rs.getString("requestId");
                String text = rs.getString("queryText");
                list.add(new QueryRequest(id, text));
            }
        }
        LogService.printf("QueryRequestsReader: loaded %d queries from %s%n", list.size(), dbType);
        return list;
    }

    /** Читает из локального XML-файла. */
    private static List<QueryRequest> readFromLocalFile(String xmlPath) throws Exception {
        List<QueryRequest> list = new ArrayList<>();
        File file = new File(xmlPath);
        if (!file.exists()) {
            LogService.println("[WARN] QueryRequests file not found: " + xmlPath);
            return list;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(file);

        NodeList nodes = doc.getElementsByTagName("Query");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String id = el.getAttribute("id");
            String text = el.getTextContent().trim();
            list.add(new QueryRequest(id, text));
        }
        LogService.printf("QueryRequestsReader: loaded %d queries from local file '%s'%n",
                list.size(), file.getAbsolutePath());
        return list;
    }
}
