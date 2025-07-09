package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class QueryRequestsReader {

    private QueryRequestsReader() { }

    public static List<QueryRequest> read(String xmlPath) throws Exception {
        List<QueryRequest> list = new ArrayList<>();
        File file = new File(xmlPath);

        DocumentBuilder db = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder();
        Document doc = db.parse(file);

        NodeList nodes = doc.getElementsByTagName("Query");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element qEl = (Element) nodes.item(i);

            String id  = qEl.getAttribute("id").trim();
            String sql = qEl.getTextContent().trim();

            list.add(new QueryRequest(id, sql));
        }
        return list;
    }
}
