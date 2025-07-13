package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.List;

/** Сохраняет список QueryRequest в XML (формат QueryRequestsReader). */
public final class QueryRequestsWriter {

    private QueryRequestsWriter() {}

    public static void write(List<QueryRequest> list, String fileName) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        Document doc = f.newDocumentBuilder().newDocument();

        Element root = doc.createElement("Queries");
        doc.appendChild(root);

        for (QueryRequest qr : list) {
            Element q = doc.createElement("Query");
            q.setAttribute("id", qr.requestId());
            q.setTextContent(qr.queryText());
            root.appendChild(q);
        }
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.transform(new DOMSource(doc), new StreamResult(new File(fileName)));
    }
}
