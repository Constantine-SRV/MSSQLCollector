package model;

import org.w3c.dom.*;
import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.util.List;
import java.util.Map;

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

            // NEW: DbType пишем всегда (значение по умолчанию — MSSQL);
            // Tenant и Cluster — только если непустые.
            DbType t = c.dbType == null ? DbType.MSSQL : c.dbType;
            add(doc, inst, "DbType", t.name());
            if (c.tenant != null && !c.tenant.isBlank())  add(doc, inst, "Tenant",  c.tenant);
            if (c.cluster != null && !c.cluster.isBlank()) add(doc, inst, "Cluster", c.cluster);

            // ExtraLabels (необязательно)
            if (c.extraLabels != null && !c.extraLabels.isEmpty()) {
                Element labels = doc.createElement("ExtraLabels");
                for (Map.Entry<String, String> e : c.extraLabels.entrySet()) {
                    Element lb = doc.createElement("Label");
                    lb.setAttribute("key", e.getKey());
                    lb.setTextContent(e.getValue());
                    labels.appendChild(lb);
                }
                inst.appendChild(labels);
            }
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
