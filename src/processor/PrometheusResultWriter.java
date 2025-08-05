package processor;

import logging.LogService;
import model.DestinationConfig;
import model.InstanceConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.Locale;
import java.util.Map;

/**
 * Преобразует ResultSet в Prometheus exposition-формат и
 * отправляет его в VictoriaMetrics.
 *
 *  Требования к ResultSet:
 *    • metric_name   – имя метрики (string). Если нет/пусто → «no_name_metric».
 *    • metric_value  – числовое значение.
 *  Все остальные столбцы автоматически превращаются в labels.
 *
 *  В результирующих лейблах всегда присутствуют:
 *    ci    – идентификатор конфигурации сервера (из InstanceConfig)
 *    reqId – идентификатор запроса
 *  ДОПОЛНЕНО: все пары из InstanceConfig.extraLabels добавляются как лейблы.
 */
public class PrometheusResultWriter {

    private final DestinationConfig destCfg;

    public PrometheusResultWriter(DestinationConfig destCfg) {
        this.destCfg = destCfg;
    }

    /* ────────────────────────── public API ───────────────────────────── */

    /** Обработка одного (InstanceConfig, reqId, ResultSet) */
    public void write(InstanceConfig ic, String reqId, ResultSet rs) throws Exception {
        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int colCnt = md.getColumnCount();
        int rowCnt = 0;

        while (rs.next()) {
            // -------- имя метрики (metric_name) --------
            String metric = safeMetricName(getString(rs, "metric_name"));
            body.append(metric)
                    .append("{ci=\"").append(nullToEmpty(ic.ci))
                    .append("\",reqId=\"").append(nullToEmpty(reqId)).append('"');

            // -------- лейблы из InstanceConfig.extraLabels --------
            if (ic.extraLabels != null && !ic.extraLabels.isEmpty()) {
                for (Map.Entry<String, String> e : ic.extraLabels.entrySet()) {
                    String k = e.getKey();
                    String v = e.getValue();
                    if (k == null || k.isBlank() || v == null || v.isBlank()) continue;
                    body.append(',')
                            .append(safeLabelName(k))
                            .append("=\"")
                            .append(v.replace("\"", "\\\""))
                            .append('"');
                }
            }

            // -------- динамические лейблы из ResultSet --------
            for (int i = 1; i <= colCnt; i++) {
                String col = md.getColumnLabel(i);
                if (col == null || col.isBlank()) col = md.getColumnName(i);
                if (col == null || col.isBlank()) continue;

                String colLower = col.toLowerCase(Locale.ROOT);
                if (colLower.equals("metric_name") || colLower.equals("metric_value"))
                    continue; // не label
                if (colLower.equals("ci") || colLower.equals("reqid"))
                    continue; // добавлены вручную

                String val = rs.getString(i);
                if (val == null || val.isEmpty())
                    continue;

                body.append(',')
                        .append(safeLabelName(col))
                        .append("=\"")
                        .append(val.replace("\"", "\\\""))
                        .append('"');
            }

            // -------- значение (metric_value) --------
            String value = getString(rs, "metric_value");
            if (value == null || value.isBlank()) value = "0"; // перестраховка
            body.append("} ").append(value).append('\n');

            rowCnt++;
        }

        sendToVictoria(body.toString());

        LogService.printf("[RESP] (%d rows) sent to VictoriaMetrics for CI=%s, req=%s%n",
                rowCnt, nullToEmpty(ic.ci), nullToEmpty(reqId));
    }

    /* ─────────────────────── helpers ─────────────────────── */

    private static String nullToEmpty(String s) { return (s == null) ? "" : s; }

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
        if (raw == null) raw = "";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0)))
            cleaned = "_" + cleaned;
        return cleaned;
    }

    /** Безопасное чтение строки по имени колонки (label/name). */
    private static String getString(ResultSet rs, String column) {
        try { return rs.getString(column); } catch (Exception ignore) { return null; }
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
        int respCode = con.getResponseCode();
        if (respCode < 200 || respCode > 299) {
            LogService.error(String.format("[VM-ERROR] HTTP %d sending to Victoria: %s",
                    respCode, destCfg.prometheusUrl));
        }
        con.disconnect();
    }
}
