package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Чтение конфигурации MSSQL-инстансов из XML-файла.
 */
public class InstancesConfigReader {

    /**
     * Загружает и парсит файл конфигурации.
     *
     * @param xmlPath путь к файлу XML
     * @return список настроек инстансов
     */
    public static List<InstanceConfig> readConfig(String xmlPath) throws Exception {
        List<InstanceConfig> list = new ArrayList<>();
        File file = new File(xmlPath);
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
            c.port         = (portStr == null || portStr.equals("0")) ? null : Integer.parseInt(portStr);
            c.userName     = get(el, "UserName");
            c.password     = get(el, "Password");
            list.add(c);
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
