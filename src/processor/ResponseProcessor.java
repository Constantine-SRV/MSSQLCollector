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
     * Главный метод — обрабатывает результат для одного (InstanceConfig, reqId, ResultSet, resultExec)
     * resultExec всегда прокидывается дальше, даже если ошибка.
     * ВАЖНО: rs может быть null в случае ошибки (например, ошибка подключения или выполнения SQL).
     */
    public void handle(InstanceConfig ic, String reqId, ResultSet rs, String resultExec) throws Exception {
        switch (destCfg.type.trim().toUpperCase()) {
            case "MSSQL":
                saveToMSSQL(ic.ci, reqId, rs, resultExec);
                break;
            case "PROMETHEUS":
                // Предполагается, что PrometheusResultWriter корректно обрабатывает rs == null и resultExec != "Ok"
                PrometheusResultWriter writer = new PrometheusResultWriter(destCfg);
                writer.write(ic, reqId, rs, resultExec);
                break;
            case "MONGO":
                LogService.printf("[RESP] MONGO write not implemented for %s_%s%n", ic.ci, reqId);
                break;
            case "LOCALFILE":
            default:
                saveToLocalFile(ic.ci, reqId, rs, resultExec);
        }
    }

    /**
     * Сохранение результата в MSSQL (через SP или произвольный SQL).
     * Теперь метод безопасен при rs == null: в таком случае в XML уходит пустое тело (<Result/>),
     * а подробности ошибки — в параметр resultExec (например, "[DB-ERROR] ...").
     */
    protected void saveToMSSQL(String ci, String reqId, ResultSet rs, String resultExec) {
        StringBuilder xml = new StringBuilder();
        int rowCnt = 0;
        try {
            // Генерируем XML только если rs != null
            if (rs != null) {
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
            } else {
                // Ошибка — пустой результат; сам текст ошибки передаём через resultExec
                xml.append("<Result/>\n");
            }
        } catch (Exception ex)  {
            // Здесь не падаем: даже если построение XML не удалось, идём дальше и передаём то, что есть
            LogService.errorf("[CI=%s][ReqID=%s] XML build error: %s%n", ci, reqId, ex.getMessage());
        }

        try {
            // Отладочный вывод
            LogService.printf("[DEBUG] MSSQL Call: SQL=%s, ci=%s, reqId=%s, rows=%d, xml-len=%d\n",
                    destCfg.mssqlQuery, ci, reqId, rowCnt, xml.length());

            try (Connection conn = DriverManager.getConnection(destCfg.mssqlConnectionString)) {
                // Универсальное определение: SP или SQL
                String sql = destCfg.mssqlQuery.trim();
                boolean isSP = sql.matches("(?i)^([\\[]?\\w+[\\]]?\\.)?[\\[]?\\w+[\\]]?$"); // примитивно: одно слово или schema.sp

                if (isSP) {
                    try (CallableStatement cs = conn.prepareCall("{call " + sql + " (?, ?, ?, ?)}")) {
                        cs.setString(1, ci);
                        cs.setString(2, reqId);
                        cs.setString(3, xml.toString());
                        cs.setString(4, resultExec);
                        cs.execute();
                    }
                } else {
                    // NB: комментарий ниже устарел — фактически 4 параметра, т.к. передаём resultExec
                    // считаем, что в sql три параметра типа ?, ?, ?
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, ci);
                        ps.setString(2, reqId);
                        ps.setString(3, xml.toString());
                        ps.setString(4, resultExec);
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
        // ... твоя старая логика сохранения ...
    }

    /**
     * Сохраняет результат в файл формата XML (для ручного анализа).
     * Теперь метод безопасен при rs == null: пишется пустой <Result/> и,
     * опционально, комментарий с текстом ошибки (resultExec).
     */
    private void saveToLocalFile(String ci, String reqId, ResultSet rs, String resultExec) throws SQLException, IOException {
        Path outDir = Paths.get(outDirName);
        if (!Files.exists(outDir)) Files.createDirectory(outDir);

        File outFile = outDir.resolve(ci + "_" + reqId + ".xml").toFile();
        try (BufferedWriter w = Files.newBufferedWriter(outFile.toPath(), StandardCharsets.UTF_8)) {
            if (rs == null) {
                // Делаем компактный файл с пустым результатом; полезно оставить текст ошибки в комментарии
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                if (resultExec != null && !resultExec.isBlank()) {
                    w.write("<!-- " + escape(resultExec) + " -->\n");
                }
                w.write("<Result/>\n");
                LogService.printf("[RESP] %s_%s (ERROR, no rows) -> %s%n", ci, reqId, outFile.getName());
                return;
            }

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
