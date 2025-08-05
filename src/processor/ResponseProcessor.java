package processor;

import model.DestinationConfig;
import logging.LogService;
import model.InstanceConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Универсальный обработчик результатов: пишет их либо в файл, либо в БД через SP, либо (заглушка) в Mongo.
 * ДОПОЛНЕНО: режим PROMETHEUS — отправка в VictoriaMetrics. Для этого
 * в метод handle теперь передаётся весь InstanceConfig, чтобы использовать extraLabels.
 */
public class ResponseProcessor {
    private final DestinationConfig destCfg;
    private final String outDirName;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    /**
     * Конструктор: получает параметры, нужные для сохранения результата.
     */
    public ResponseProcessor(DestinationConfig destCfg) {
        this.destCfg = destCfg;
        // Для файлового режима генерируем имя каталога с таймстампом
        this.outDirName = "out_" + LocalDateTime.now().format(TS_FMT);
    }

    /**
     * Главный метод ― обрабатывает результат для одного (InstanceConfig, reqId, ResultSet)
     * РАНЕЕ здесь был (ci, reqId, rs); теперь передаём весь ic.
     */
    public void handle(InstanceConfig ic, String reqId, ResultSet rs) throws Exception {
        String type = (destCfg.type == null ? "" : destCfg.type.trim().toUpperCase());
        switch (type) {
            case "MSSQL" -> saveToMSSQL(ic.ci, reqId, rs);
            case "MONGO" -> LogService.printf("[RESP] MONGO write not implemented for %s_%s%n", ic.ci, reqId);
            case "PROMETHEUS" -> {
                // Отправка в VictoriaMetrics (Prometheus exposition format)
                PrometheusResultWriter writer = new PrometheusResultWriter(destCfg);
                writer.write(ic, reqId, rs);
            }
            case "LOCALFILE" -> saveToLocalFile(ic.ci, reqId, rs);
            default -> saveToLocalFile(ic.ci, reqId, rs);
        }
    }

    /**
     * Сохраняет результат в файл формата XML (для ручного анализа).
     */
    private void saveToLocalFile(String ci, String reqId, ResultSet rs) throws SQLException, IOException {
        Path outDir = Paths.get(outDirName);
        if (!Files.exists(outDir)) Files.createDirectory(outDir);

        File outFile = outDir.resolve(ci + "_" + reqId + ".xml").toFile();
        try (BufferedWriter w = Files.newBufferedWriter(outFile.toPath(), StandardCharsets.UTF_8)) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            // Декларация только для файла!
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Result>\n");
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
            LogService.printf("[RESP] %s_%s (%d rows) -> %s%n", ci, reqId, rowCnt, outFile.getName());
        }
    }

    /**
     * Сохраняет результат в MSSQL через хранимую процедуру.
     * Передает параметры: CI, reqId, XML как строки.
     * Декларация encoding НЕ добавляется (иначе ошибка "unable to switch the encoding").
     */
    private void saveToMSSQL(String ci, String reqId, ResultSet rs) {
        StringBuilder xml = new StringBuilder();
        int rowCnt = 0;
        try {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();

            // Без декларации encoding!
            xml.append("<Result>\n");
            while (rs.next()) {
                xml.append("  <Row>\n");
                for (int c = 1; c <= cols; c++) {
                    String col = md.getColumnLabel(c);
                    if (col == null || col.isEmpty()) col = md.getColumnName(c);
                    String val = rs.getString(c);
                    xml.append("    <").append(col).append(">");
                    if (val != null) xml.append(escape(val));
                    xml.append("</").append(col).append(">\n");
                }
                xml.append("  </Row>\n");
                rowCnt++;
            }
            xml.append("</Result>\n");

            // Отладочный вывод
            LogService.printf("[DEBUG] MSSQL Call: SQL=%s, ci=%s, reqId=%s, rows=%d, xml-len=%d\n",
                    destCfg.mssqlQuery, ci, reqId, rowCnt, xml.length());

            try (Connection conn = DriverManager.getConnection(destCfg.mssqlConnectionString)) {
                // Универсальное определение: SP или SQL
                String sql = destCfg.mssqlQuery.trim();
                boolean isSP = sql.matches("(?i)^([\\[]?\\w+[\\]]?\\.)?[\\[]?\\w+[\\]]?$"); // примитивно: одно слово или schema.sp

                if (isSP) {
                    try (CallableStatement cs = conn.prepareCall("{call " + sql + " (?, ?, ?)}")) {
                        cs.setString(1, ci);
                        cs.setString(2, reqId);
                        cs.setString(3, xml.toString());
                        cs.execute();
                    }
                } else {
                    // считаем, что в sql три параметра типа ?, ?, ?
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, ci);
                        ps.setString(2, reqId);
                        ps.setString(3, xml.toString());
                        ps.execute();
                    }
                }
                LogService.printf("[RESP] %s_%s -> MSSQL OK (%d rows)%n", ci, reqId, rowCnt);
            }
        } catch (SQLException ex) {
            LogService.error(String.format("[CI=%s][ReqID=%s] SQL-ERROR: %s", ci, reqId, ex.getMessage()));
            printSqlErrorChain(ex);
        } catch (Exception ex) {
            LogService.error(String.format("[CI=%s][ReqID=%s] ERROR: %s", ci, reqId, ex.getMessage()));
            ex.printStackTrace();
        }
    }

    /**
     * Выводит цепочку всех SQL-исключений, включая сообщения SQL Server,
     * коды ошибок, состояния, тексты RAISERROR и т.п.
     */
    private static void printSqlErrorChain(SQLException ex) {
        SQLException next = ex;
        while (next != null) {
            LogService.errorln("[SQL-ERROR] code: " + next.getErrorCode() +
                    ", state: " + next.getSQLState() +
                    ", message: " + next.getMessage());
            next = next.getNextException();
        }
        ex.printStackTrace(); // для полной картины (номер строки и т.д.)
    }

    /**
     * Простейший экранировщик спецсимволов для XML.
     */
    private static String escape(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
