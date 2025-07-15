package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import logging.LogService;

/**
 * Универсальный загрузчик конфигурации MSSQL-инстансов.
 */
public class InstancesConfigReader {

    /**
     * Загружает и парсит список инстансов согласно AppConfig.
     *
     * @param appConfig конфиг приложения (где искать и как читать)
     * @return список InstanceConfig
     */
    public static List<InstanceConfig> readConfig(AppConfig appConfig) throws Exception {
        String type = appConfig.serversSource.type.trim().toUpperCase();
        switch (type) {
            case "MSSQL":
                return readFromMSSQL(appConfig.serversSource);
            case "MONGO":
                // Заглушка: возвращает пустой список и пишет в консоль
                LogService.println("[WARN] MongoDB sources are not implemented yet!");
                return new ArrayList<>();
            case "LOCALFILE":
            default:
                // Имя файла: если не задано — используем InstancesConfig.xml
                String file = appConfig.serversSource.fileName.isEmpty()
                        ? "InstancesConfig.xml"
                        : appConfig.serversSource.fileName;
                return readFromLocalFile(file);
        }
    }

    /** Читает из локального XML-файла (старый код, немного улучшен) */
    private static List<InstanceConfig> readFromLocalFile(String xmlPath) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();
        File file = new File(xmlPath);
        if (!file.exists()) {
            LogService.println("[WARN] Config file not found: " + xmlPath);
            return list;
        }
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        DocumentBuilder b = f.newDocumentBuilder();
        Document doc = b.parse(file);

        NodeList nodes = doc.getElementsByTagName("Instance");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            InstanceConfig c = new InstanceConfig();
            c.ci           = get(el, "CI");
            c.instanceName = get(el, "InstanceName");
            String portStr = get(el, "Port");
            c.port         = (portStr == null || portStr.equals("0") || portStr.isEmpty())
                    ? null : Integer.parseInt(portStr);
            c.userName     = get(el, "UserName");
            c.password     = get(el, "Password");
            list.add(c);
        }
        return list;
    }

    /** Читает из MSSQL по JDBC и конфигу SourceConfig (SELECT должен вернуть нужные поля) */
    private static List<InstanceConfig> readFromMSSQL(SourceConfig cfg) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();
        try (Connection con = DriverManager.getConnection(cfg.mssqlConnectionString)) {

            try (Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery(cfg.mssqlQuery)) {
                while (rs.next()) {
                    InstanceConfig c = new InstanceConfig();
                    c.ci           = rs.getString("CI");
                    c.instanceName = rs.getString("InstanceName");
                    String portStr = rs.getString("Port");
                    c.port         = (portStr == null || portStr.equals("0") || portStr.isEmpty())
                            ? null : Integer.parseInt(portStr);
                    c.userName     = rs.getString("UserName");
                    c.password     = rs.getString("Password");
                    list.add(c);
                }
            }
        }
        return list;
    }

    /**
     * Извлекает текст из указанного дочернего элемента либо возвращает null.
     */
    private static String get(Element e, String tag) {
        NodeList n = e.getElementsByTagName(tag);
        return n.getLength() == 0 ? null : n.item(0).getTextContent().trim();
    }
}
