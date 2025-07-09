package processor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Сохраняет ResultSet в XML-файл:
 *   out_yyyyMMdd_HHmm / <CI>_<reqId>.xml
 */
public final class ResponseProcessor {

    private static volatile File baseDir;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private ResponseProcessor() { }

    public static void handle(String ci, String reqId, ResultSet rs)
            throws SQLException, IOException {

        File dir = ensureBaseDir();
        File outFile = new File(dir, ci + "_" + reqId + ".xml");

        try (BufferedWriter w = Files.newBufferedWriter(
                outFile.toPath(), StandardCharsets.UTF_8)) {

            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Result>\n");
            int rowCnt = 0;

            while (rs.next()) {
                w.write("  <Row>\n");
                for (int c = 1; c <= cols; c++) {
                    String col = md.getColumnLabel(c);
                    String val = rs.getString(c);

                    w.write("    <");
                    w.write(col);
                    w.write(">");

                    if (val != null)
                        w.write(escape(val));

                    w.write("</");
                    w.write(col);
                    w.write(">\n");
                }
                w.write("  </Row>\n");
                rowCnt++;
            }
            w.write("</Result>\n");

            System.out.printf("[RESP] %s (%d rows) -> %s%n",
                    ci + "_" + reqId, rowCnt, outFile.getName());
        }
    }

    // ───────────────────────────────────────────────────────────

    private static File ensureBaseDir() {
        if (baseDir == null) {
            synchronized (ResponseProcessor.class) {
                if (baseDir == null) {
                    String name = "out_" + LocalDateTime.now().format(TS_FMT);
                    baseDir = new File(name);
                    if (!baseDir.exists() && baseDir.mkdir())
                        System.out.println("[RESP] output dir = " + baseDir.getAbsolutePath());
                }
            }
        }
        return baseDir;
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
