package processor;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Сериализация ResultSet в XML.
 *
 * Формат полностью совпадает с прежним выводом ResponseProcessor:
 *   <Result>
 *     <Row>
 *       <col1>val1</col1>
 *       ...
 *     </Row>
 *     ...
 *   </Result>
 *
 * ci/reqId в XML-теле НЕ дублируются (они пишутся отдельными параметрами при INSERT).
 *
 * При {@code includeDeclaration=true} в начало добавляется
 * {@code <?xml version="1.0" encoding="UTF-8"?>} — для записи в файл;
 * для записи в колонку БД декларация не нужна.
 *
 * При {@code rs == null} (ошибка выше по стеку) пишется компактный {@code <Result/>}.
 */
public class XmlResultFormatter implements ResultFormatter {

    private final boolean includeDeclaration;

    public XmlResultFormatter(boolean includeDeclaration) {
        this.includeDeclaration = includeDeclaration;
    }

    @Override
    public int streamTo(String ci, String reqId, ResultSet rs, Writer w) throws SQLException, IOException {
        if (includeDeclaration) w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");

        if (rs == null) {
            w.write("<Result/>\n");
            return 0;
        }

        ResultSetMetaData md = rs.getMetaData();
        int cols = md.getColumnCount();
        w.write("<Result>\n");
        int rowCnt = 0;
        while (rs.next()) {
            w.write("  <Row>\n");
            for (int c = 1; c <= cols; c++) {
                String col = md.getColumnLabel(c);
                if (col == null || col.isEmpty()) col = md.getColumnName(c);
                String val = rs.getString(c);
                w.write("    <" + col + ">");
                if (val != null) w.write(escape(val));
                w.write("</" + col + ">\n");
            }
            w.write("  </Row>\n");
            rowCnt++;
        }
        w.write("</Result>\n");
        return rowCnt;
    }

    @Override
    public FormatResult format(String ci, String reqId, ResultSet rs) throws SQLException {
        StringWriter sw = new StringWriter();
        int rows;
        try {
            rows = streamTo(ci, reqId, rs, sw);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new FormatResult(sw.toString(), rows);
    }

    @Override
    public String fileExtension() { return ".xml"; }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
