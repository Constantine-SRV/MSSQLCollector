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
 */
public class PrometheusResultWriter {

    private final DestinationConfig destCfg;

    public PrometheusResultWriter(DestinationConfig destCfg) {
        this.destCfg = destCfg;
    }

    /** Главный метод отправки метрик */
    public void write(InstanceConfig ic, String reqId, ResultSet rs, String resultExec) throws Exception {
        if (isConnectError(resultExec)) {
            sendAvailabilityMetric(ic, reqId, 0);
            LogService.printf("[RESP] availability=0 sent to VictoriaMetrics for CI=%s, req=%s%n",
                    ic.ci, reqId);
            return;
        }

        if (!"Ok".equalsIgnoreCase(resultExec) || rs == null) {
            LogService.errorf("[VM-SKIP] CI=%s, req=%s: Skipped sending due to exec error: %s%n",
                    ic.ci, reqId, resultExec);
            return;
        }

        String metricsBody = buildMetricsFromResultSet(ic, reqId, rs);
        sendToVictoria(metricsBody);

        int rowCount = metricsBody.split("\n").length;
        LogService.printf("[RESP] (%d rows) sent to VictoriaMetrics for CI=%s, req=%s%n",
                rowCount, ic.ci, reqId);
    }

    /* ===== availability ===== */

    private void sendAvailabilityMetric(InstanceConfig ic, String reqId, int availabilityValue) throws Exception {
        StringBuilder body = new StringBuilder("availability");
        appendLabels(body, ic, reqId, null);   // reqId сейчас не добавляется
        body.append(' ').append(availabilityValue).append('\n');
        sendToVictoria(body.toString());
    }

    /* ===== обычные метрики ===== */

    private String buildMetricsFromResultSet(InstanceConfig ic, String reqId, ResultSet rs) throws Exception {
        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int colCnt = md.getColumnCount();

        while (rs.next()) {
            String metric = safeMetricName(rs.getString("metric_name"));
            String value  = rs.getString("metric_value");

            body.append(metric);
            appendLabels(body, ic, reqId, rs); // reqId внутри больше не пишем
            body.append(' ').append(value).append('\n');
        }

        return body.toString();
    }

    /* ===== универсальное добавление лейблов (без reqId) ===== */

    private void appendLabels(StringBuilder body, InstanceConfig ic, String /*unused*/ reqId,
                              ResultSet rs) throws Exception {

        // Стандартный обязательный лейбл ci
        body.append("{ci=\"").append(ic.ci).append('"');

        /* ---- динамические лейблы из ResultSet (если есть) ---- */
        if (rs != null) {
            ResultSetMetaData md = rs.getMetaData();
            int colCnt = md.getColumnCount();

            for (int i = 1; i <= colCnt; i++) {
                String col = md.getColumnLabel(i);
                if (col == null || col.isBlank()) col = md.getColumnName(i);
                if (col == null) continue;

                String colLower = col.toLowerCase(Locale.ROOT);
                if (colLower.equals("metric_name") || colLower.equals("metric_value")
                        || colLower.equals("ci") || colLower.equals("reqid"))
                    continue;

                String val = rs.getString(i);
                if (val == null || val.isEmpty()) continue;

                body.append(',')
                        .append(safeLabelName(col))
                        .append("=\"")
                        .append(val.replace("\"", "\\\""))
                        .append('"');
            }
        }

        /* ---- статические extraLabels ---- */
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

        body.append('}');
    }

    /* ===== утилиты ===== */

    private boolean isConnectError(String resultExec) {
        if (resultExec == null) return false;
        String err = resultExec.toLowerCase(Locale.ROOT);
        return err.contains("connect")
                || err.contains("tcp/ip")
                || err.contains("unknown host")
                || err.contains("no such host")
                || err.contains("login timed out");
    }

    private static String safeMetricName(String raw) {
        if (raw == null || raw.isBlank()) return "no_name_metric";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_:]", "_").replaceAll("_+", "_");
        if (cleaned.isEmpty()) cleaned = "no_name_metric";
        if (Character.isDigit(cleaned.charAt(0))) cleaned = "_" + cleaned;
        return cleaned;
    }

    private static String safeLabelName(String raw) {
        if (raw == null || raw.isBlank()) return "_";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("_+", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0))) cleaned = "_" + cleaned;
        return cleaned;
    }

    private void sendToVictoria(String body) throws Exception {
        if (body.isEmpty()) return;

        URL url = new URL(destCfg.prometheusUrl);
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setRequestProperty("Content-Type", "text/plain; version=0.0.4");
        con.setConnectTimeout(4000);
        con.setReadTimeout(6000);

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
