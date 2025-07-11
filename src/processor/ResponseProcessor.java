package processor;

import model.DestinationConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Универсальный обработчик результатов: пишет их либо в файл, либо в БД через SP, либо (заглушка) в Mongo.
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
     * Главный метод ― обрабатывает результат для одного (CI, reqId, ResultSet)
     */
    public void handle(String ci, String reqId, ResultSet rs) throws Exception {
        switch (destCfg.type.trim().toUpperCase()) {
            case "MSSQL":
                saveToMSSQL(ci, reqId, rs);
                break;
            case "MONGO":
                System.out.printf("[RESP] MONGO write not implemented for %s_%s%n", ci, reqId);
                break;
            case "LOCALFILE":
            default:
                saveToLocalFile(ci, reqId, rs);
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
                    String val = rs.getString(c);
                    w.write("    <" + col + ">");
                    if (val != null) w.write(escape(val));
                    w.write("</" + col + ">\n");
                }
                w.write("  </Row>\n");
                rowCnt++;
            }
            w.write("</Result>\n");
            System.out.printf("[RESP] %s_%s (%d rows) -> %s%n", ci, reqId, rowCnt, outFile.getName());
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
                    String val = rs.getString(c);
                    xml.append("    <").append(col).append(">");
                    if (val != null) xml.append(escape(val));
                    xml.append("</").append(col).append(">\n");
                }
                xml.append("  </Row>\n");
                rowCnt++;
            }
            xml.append("</Result>\n");

            // Отладочный вывод (иногда полезно увидеть длину XML)
            System.out.printf("[DEBUG] MSSQL Call: SP=%s, ci=%s, reqId=%s, rows=%d, xml-len=%d\n",
                    destCfg.mssqlQuery, ci, reqId, rowCnt, xml.length());

            try (Connection conn = DriverManager.getConnection(destCfg.mssqlConnectionString)) {
                try (CallableStatement cs = conn.prepareCall(
                        "{call " + destCfg.mssqlQuery + " (?, ?, ?)}")) {
                    cs.setString(1, ci);
                    cs.setString(2, reqId);
                    cs.setString(3, xml.toString());
                    cs.execute();
                    System.out.printf("[RESP] %s_%s -> MSSQL SP OK (%d rows)%n", ci, reqId, rowCnt);
                }
            }
        } catch (SQLException ex) {
            System.err.printf("[CI=%s][ReqID=%s] SQL-ERROR: %s%n", ci, reqId, ex.getMessage());
            printSqlErrorChain(ex); // Показывает всю цепочку ошибок SQL Server
        } catch (Exception ex) {
            System.err.printf("[CI=%s][ReqID=%s] ERROR: %s%n", ci, reqId, ex.getMessage());
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
            System.err.println("[SQL-ERROR] code: " + next.getErrorCode() +
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
