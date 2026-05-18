package processor;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Сериализация ResultSet в JSON ровно следующей формы:
 *
 *   {"ci":"CI00002","reqId":"PERF","rows":[
 *      {"col1":"val1","col2":42,...},
 *      ...
 *   ]}
 *
 * Числовые SQL-типы пишутся как JSON-числа, остальное — как строки.
 * NULL значения сериализуются как JSON null.
 *
 * Без внешних библиотек — экранирование сделано вручную, спецсимволы и управляющие
 * символы конвертируются в \uXXXX при необходимости.
 *
 * Если {@code rs == null} (ошибка выше) — возвращается каркас с пустым массивом rows.
 */
public class JsonResultFormatter implements ResultFormatter {

    @Override
    public int streamTo(String ci, String reqId, ResultSet rs, Writer w) throws SQLException, IOException {
        w.write("{\"ci\":");
        writeString(w, ci);
        w.write(",\"reqId\":");
        writeString(w, reqId);
        w.write(",\"rows\":[");

        int rowCnt = 0;
        if (rs != null) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            boolean firstRow = true;
            while (rs.next()) {
                if (!firstRow) w.write(",");
                firstRow = false;
                w.write("{");
                for (int c = 1; c <= cols; c++) {
                    if (c > 1) w.write(",");
                    String col = md.getColumnLabel(c);
                    if (col == null || col.isEmpty()) col = md.getColumnName(c);
                    writeString(w, col);
                    w.write(":");
                    writeJsonValue(w, rs, c, md.getColumnType(c));
                }
                w.write("}");
                rowCnt++;
            }
        }
        w.write("]}");
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
    public String fileExtension() { return ".json"; }

    /* ===== внутренности ===== */

    private static void writeJsonValue(Writer w, ResultSet rs, int col, int sqlType)
            throws SQLException, IOException {
        // Сначала проверяем NULL — getString и getBoolean ведут себя по-разному с NULL
        String val = rs.getString(col);
        if (val == null) { w.write("null"); return; }

        switch (sqlType) {
            case Types.BIT, Types.BOOLEAN -> {
                boolean b = rs.getBoolean(col);
                w.write(b ? "true" : "false");
            }
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.REAL,    Types.FLOAT,    Types.DOUBLE,
                 Types.DECIMAL, Types.NUMERIC -> {
                if (isJsonNumber(val)) {
                    w.write(val);
                } else {
                    // если в строке мусор — пишем как строку, чтобы не сломать JSON
                    writeString(w, val);
                }
            }
            default -> writeString(w, val);
        }
    }

    private static boolean isJsonNumber(String s) {
        if (s == null || s.isEmpty()) return false;
        // быстрая, но надёжная проверка
        try { Double.parseDouble(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    private static void writeString(Writer w, String s) throws IOException {
        w.write('"');
        if (s != null) {
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                switch (ch) {
                    case '"'  -> w.write("\\\"");
                    case '\\' -> w.write("\\\\");
                    case '\b' -> w.write("\\b");
                    case '\f' -> w.write("\\f");
                    case '\n' -> w.write("\\n");
                    case '\r' -> w.write("\\r");
                    case '\t' -> w.write("\\t");
                    default -> {
                        if (ch < 0x20) {
                            w.write(String.format("\\u%04x", (int) ch));
                        } else {
                            w.write(ch);
                        }
                    }
                }
            }
        }
        w.write('"');
    }
}
