package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.List;

/** Сохраняет список InstanceConfig в XML-файл (формат, совместимый с InstancesConfigReader). */
public final class InstancesConfigWriter {

    private InstancesConfigWriter() {}

    public static void write(List<InstanceConfig> list, String fileName) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        Document doc = f.newDocumentBuilder().newDocument();

        Element root = doc.createElement("Instances");
        doc.appendChild(root);

        for (InstanceConfig c : list) {
            Element inst = doc.createElement("Instance");
            root.appendChild(inst);

            add(doc, inst, "CI",            c.ci);
            add(doc, inst, "InstanceName",  c.instanceName);
            add(doc, inst, "Port",          c.port == null ? "0" : c.port.toString());
            add(doc, inst, "UserName",      c.userName);
            add(doc, inst, "Password",      c.password);
        }
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.transform(new DOMSource(doc), new StreamResult(new File(fileName)));
    }
    private static void add(Document d, Element p, String tag, String val) {
        Element e = d.createElement(tag);
        e.setTextContent(val == null ? "" : val);
        p.appendChild(e);
    }
}
