package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import logging.LogService;

/**
 * Универсальный загрузчик QueryRequest-ов (заданий) из MSSQL, Mongo, или локального файла.
 */
public class QueryRequestsReader {

    /**
     * Загружает список QueryRequest согласно AppConfig.
     */
    public static List<QueryRequest> read(AppConfig appConfig) throws Exception {
        String type = appConfig.jobsSource.type.trim().toUpperCase();
        switch (type) {
            case "MSSQL":
                return readFromMSSQL(appConfig.jobsSource);
            case "MONGO":
                // Заглушка
                LogService.println("[WARN] MongoDB jobs source is not implemented yet!");
                return new ArrayList<>();
            case "LOCALFILE":
            default:
                String file = appConfig.jobsSource.fileName.isEmpty()
                        ? "QueryRequests.xml"
                        : appConfig.jobsSource.fileName;
                return readFromLocalFile(file);
        }
    }

    /** Читает из MSSQL по JDBC и конфигу SourceConfig (SELECT должен вернуть поля id, queryText) */
    private static List<QueryRequest> readFromMSSQL(SourceConfig cfg) throws Exception {
        List<QueryRequest> list = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(cfg.mssqlConnectionString)) {
            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(cfg.mssqlQuery)) {
                while (rs.next()) {
                    String id   = rs.getString("requestId");
                    String text = rs.getString("queryText");
                    list.add(new QueryRequest(id, text));
                }
            }
        }
        return list;
    }

    /** Читает из локального XML-файла (как раньше) */
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
        LogService.printf("QueryRequestsReader: loaded %d queries from local file '%s'%n", list.size(), file.getAbsolutePath());
        return list;
    }
}
