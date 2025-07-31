package processor;

import logging.LogService;
import model.DestinationConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Locale;

/**
 * Преобразует ResultSet в Prometheus exposition-формат и
 * отправляет его в VictoriaMetrics.
 *
 *  Требования к ResultSet:
 *    • counter_name   – имя метрики (string). Если нет → «no_name_metric».
 *    • counter_value  – числовое значение.
 *  Все остальные столбцы автоматически превращаются в labels.
 *
 *  В результирующих лейблах всегда присутствуют:
 *    ci   – идентификатор конфигурации сервера (наружный параметр)
 *    reqId – идентификатор запроса                    (наружный параметр)
 */
public class PrometheusResultWriter {

    private final DestinationConfig destCfg;

    public PrometheusResultWriter(DestinationConfig destCfg) {
        this.destCfg = destCfg;
    }

    /* ────────────────────────── public API ───────────────────────────── */

    /** Обработка одного (CI, reqId, ResultSet) */
    public void write(String ci, String reqId, ResultSet rs) throws Exception {
        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int colCnt = md.getColumnCount();
        int rowCnt = 0;

        while (rs.next()) {

            // -------- имя метрики --------
            String metric = safeMetricName(rs.getString("counter_name"));

            body.append(metric)
                    .append("{ci=\"").append(ci)
                    .append("\",reqId=\"").append(reqId).append("\"");

            // -------- динамические лейблы --------
            for (int i = 1; i <= colCnt; i++) {
                String col = md.getColumnLabel(i);
                if (col == null) col = md.getColumnName(i);
                if (col == null) continue;

                String colLower = col.toLowerCase(Locale.ROOT);
                if (colLower.equals("counter_name") || colLower.equals("counter_value"))
                    continue;            // это не label
                if (colLower.equals("ci") || colLower.equals("reqid"))
                    continue;            // уже добавили вручную

                String val = rs.getString(i);
                if (val == null || val.isEmpty())
                    continue;

                body.append(',')
                        .append(safeLabelName(col))
                        .append("=\"")
                        .append(val.replace("\"", "\\\""))
                        .append('"');
            }

            // -------- значение --------
            body.append("} ")
                    .append(rs.getString("counter_value"))
                    .append('\n');

            rowCnt++;
        }

        sendToVictoria(body.toString());

        LogService.printf("[RESP] (%d rows) sent to VictoriaMetrics for CI=%s, req=%s%n",
                rowCnt, ci, reqId);
    }

    /* ─────────────────────── helpers ─────────────────────── */

    /** Санация имени метрики. */
    private static String safeMetricName(String raw) {
        if (raw == null) raw = "";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_:]", "_")
                .replaceAll("_+", "_");
        if (cleaned.isEmpty()) cleaned = "no_name_metric";
        if (Character.isDigit(cleaned.charAt(0)))
            cleaned = "_" + cleaned;
        return cleaned;
    }

    /** Санация имени label-поля. */
    private static String safeLabelName(String raw) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0)))
            cleaned = "_" + cleaned;
        return cleaned;
    }

    /** HTTP-POST в VictoriaMetrics. */
    private void sendToVictoria(String body) throws Exception {
        if (body.isEmpty()) return;

        URL url = new URL(destCfg.prometheusUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "text/plain; version=0.0.4");
        con.setConnectTimeout(4_000);
        con.setReadTimeout(6_000);

        try (OutputStream os = con.getOutputStream()) {
            os.write(body.getBytes());
        }

        int resp = con.getResponseCode();
        if (resp < 200 || resp > 299) {
            LogService.errorf("[VM-ERROR] HTTP %d sending to Victoria: %s%n",
                    resp, destCfg.prometheusUrl);
        }

        con.disconnect();
    }
}
