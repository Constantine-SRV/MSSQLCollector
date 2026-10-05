package processor;

import model.DbType;
import model.DestinationConfig;
import logging.LogService;
import model.InstanceConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Универсальный обработчик результатов. Куда писать определяется
 * {@code destCfg.type}:
 *   - MSSQL      → INSERT через mssql-jdbc
 *   - OCEANBASE  → INSERT через mysql-connector-j
 *   - PROMETHEUS → отправка в VictoriaMetrics
 *   - LOCALFILE  → файл out_/<ci>_<reqId>.<ext>
 *   - MONGO      → заглушка
 *
 * Формат сериализации результата задаётся {@code destCfg.resultFormat}:
 *   - XML  — обратная совместимость, дефолт для всех типов кроме OCEANBASE
 *   - JSON — рекомендован для OCEANBASE (нативный JSON-тип)
 *
 * Если ResultFormat пуст и type=OCEANBASE — автоматически берётся JSON.
 */
public class ResponseProcessor {
    private final DestinationConfig destCfg;
    private final String outDirName;
    private final String type;
    private final PrometheusResultWriter prometheus;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    public ResponseProcessor(DestinationConfig destCfg) {
        this.destCfg = destCfg;
        this.outDirName = "out_" + LocalDateTime.now().format(TS_FMT);
        this.type = destCfg.type == null ? "" : destCfg.type.trim().toUpperCase(Locale.ROOT);
        // Неизвестный тип — ошибка на старте (раньше молча писали в локальные файлы,
        // например при опечатке PROMETEUS).
        switch (type) {
            case "MSSQL", "OCEANBASE", "OB", "PROMETHEUS", "MONGO", "LOCALFILE", "FILE", "" -> { }
            default -> throw new IllegalArgumentException("ResultsDestination: unknown Type '" + destCfg.type
                    + "'. Allowed: MSSQL | OCEANBASE | PROMETHEUS | LOCALFILE");
        }
        this.prometheus = type.equals("PROMETHEUS") ? new PrometheusResultWriter(destCfg) : null;
    }

    /**
     * Главный метод обработки. rs может быть null (ошибка подключения/выполнения SQL).
     */
    public void handle(InstanceConfig ic, String reqId, ResultSet rs, String resultExec) throws Exception {
        switch (type) {
            case "MSSQL" ->
                    saveToJdbc(DbType.MSSQL, ic.ci, reqId, rs, resultExec);
            case "OCEANBASE", "OB" ->
                    saveToJdbc(DbType.OCEANBASE, ic.ci, reqId, rs, resultExec);
            case "PROMETHEUS" ->
                    prometheus.write(ic, reqId, rs, resultExec);
            case "MONGO" ->
                    LogService.printf("[RESP] MONGO write not implemented for %s_%s%n", ic.ci, reqId);
            default ->   // LOCALFILE / FILE / ""
                    saveToLocalFile(ic.ci, reqId, rs, resultExec);
        }
    }

    /* ============================================================
       Выбор форматтера: XML / JSON по конфигу,
       JSON по умолчанию для OCEANBASE.
       ============================================================ */
    private ResultFormatter chooseFormatter(boolean forFile) {
        String fmt = destCfg.resultFormat;
        if (fmt == null || fmt.isBlank()) {
            String t = destCfg.type == null ? "" : destCfg.type.trim().toUpperCase(Locale.ROOT);
            fmt = (t.equals("OCEANBASE") || t.equals("OB")) ? "JSON" : "XML";
        }
        if ("JSON".equalsIgnoreCase(fmt.trim())) {
            return new JsonResultFormatter();
        }
        // XML — для файла включаем декларацию, для INSERT — нет
        return new XmlResultFormatter(forFile);
    }

    /* ============================================================
       Запись результата в JDBC-получатель (MSSQL или OCEANBASE)
       ============================================================ */
    protected void saveToJdbc(DbType dbType, String ci, String reqId, ResultSet rs, String resultExec) {
        ResultFormatter fmt = chooseFormatter(false);

        String body;
        int rowCnt = 0;
        try {
            ResultFormatter.FormatResult fr = fmt.format(ci, reqId, rs);
            body = fr.body();
            rowCnt = fr.rowCount();
        } catch (Exception ex) {
            LogService.errorf("[CI=%s][ReqID=%s] body build error: %s%n", ci, reqId, ex.getMessage());
            // При ошибке формирования тела — пустая строка; ошибка уйдёт в resultExec
            body = "";
        }

        try {
            LogService.printf("[DEBUG] %s Call: SQL=%s, ci=%s, reqId=%s, rows=%d, body-len=%d%n",
                    dbType, destCfg.query, ci, reqId, rowCnt, body.length());

            try (Connection conn = db.JdbcConnections.open(dbType, destCfg.connection)) {
                String sql = destCfg.query == null ? "" : destCfg.query.trim();

                // Эвристика: одно слово (или schema.sp) — считаем именем хранимой процедуры.
                boolean isSP = sql.matches("(?i)^([\\[]?\\w+[\\]]?\\.)?[\\[]?\\w+[\\]]?$");

                if (isSP) {
                    try (CallableStatement cs = conn.prepareCall("{call " + sql + " (?, ?, ?, ?)}")) {
                        cs.setString(1, ci);
                        cs.setString(2, reqId);
                        cs.setString(3, body);
                        cs.setString(4, resultExec);
                        cs.execute();
                    }
                } else {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, ci);
                        ps.setString(2, reqId);
                        ps.setString(3, body);
                        ps.setString(4, resultExec);
                        ps.execute();
                    }
                }
                LogService.printf("[RESP] %s_%s -> %s OK (%d rows)%n", ci, reqId, dbType, rowCnt);
            }
        } catch (SQLException ex) {
            LogService.error(String.format("[CI=%s][ReqID=%s] SQL-ERROR: %s", ci, reqId, ex.getMessage()));
            printSqlErrorChain(ex);
        } catch (Exception ex) {
            LogService.error(String.format("[CI=%s][ReqID=%s] ERROR: %s", ci, reqId, ex.getMessage()));
            ex.printStackTrace();
        }
    }

    /* ============================================================
       Запись в локальный файл (формат — по resultFormat).
       ============================================================ */
    private void saveToLocalFile(String ci, String reqId, ResultSet rs, String resultExec)
            throws SQLException, IOException {

        Path outDir = Paths.get(outDirName);
        if (!Files.exists(outDir)) Files.createDirectory(outDir);

        ResultFormatter fmt = chooseFormatter(true);
        File outFile = outDir.resolve(ci + "_" + reqId + fmt.fileExtension()).toFile();

        try (BufferedWriter w = Files.newBufferedWriter(outFile.toPath(), StandardCharsets.UTF_8)) {
            // Для XML при rs==null оставляем человеко-читаемый комментарий с ошибкой
            if (rs == null && fmt instanceof XmlResultFormatter) {
                w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                if (resultExec != null && !resultExec.isBlank()) {
                    w.write("<!-- " + xmlEscape(resultExec) + " -->\n");
                }
                w.write("<Result/>\n");
                LogService.printf("[RESP] %s_%s (ERROR, no rows) -> %s%n", ci, reqId, outFile.getName());
                return;
            }

            int rows = fmt.streamTo(ci, reqId, rs, w);
            LogService.printf("[RESP] %s_%s (%d rows) -> %s%n", ci, reqId, rows, outFile.getName());
        }
    }

    /* ============================================================
       Утилиты
       ============================================================ */
    private static String xmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void printSqlErrorChain(SQLException ex) {
        SQLException next = ex;
        while (next != null) {
            LogService.errorln("[SQL-ERROR] code: " + next.getErrorCode() +
                    ", state: " + next.getSQLState() +
                    ", message: " + next.getMessage());
            next = next.getNextException();
        }
        ex.printStackTrace();
    }
}
