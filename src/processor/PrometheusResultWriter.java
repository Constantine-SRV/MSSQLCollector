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

    /** Теперь сюда тоже прокидывается resultExec */
    public void write(InstanceConfig ic, String reqId, ResultSet rs, String resultExec) throws Exception {
        if (!"Ok".equalsIgnoreCase(resultExec.trim())) {
            LogService.errorf("[VM-SKIP] CI=%s, req=%s: Not sending to Victoria due to exec error: %s%n", ic.ci, reqId, resultExec);
            return;
        }
        if (rs == null) {
            LogService.errorf("[VM-SKIP] CI=%s, req=%s: ResultSet is null (exec error above).%n", ic.ci, reqId);
            return;
        }

        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int colCnt = md.getColumnCount();
        int rowCnt = 0;

        while (rs.next()) {
            String metric = safeMetricName(rs.getString("metric_name"));
            String value  = rs.getString("metric_value");

            body.append(metric)
                    .append("{ci=\"").append(ic.ci)
                    .append("\",reqId=\"").append(reqId).append("\"");

            // Динамические лейблы из RS (кроме metric_name/metric_value/ci/reqId)
            for (int i = 1; i <= colCnt; i++) {
                String col = md.getColumnLabel(i);
                if (col == null) col = md.getColumnName(i);
                if (col == null) continue;

                String colLower = col.toLowerCase(Locale.ROOT);
                if (colLower.equals("metric_name") || colLower.equals("metric_value")) continue;
                if (colLower.equals("ci") || colLower.equals("reqid")) continue;

                String v = rs.getString(i);
                if (v == null || v.isEmpty()) continue;

                body.append(',')
                        .append(safeLabelName(col))
                        .append("=\"")
                        .append(v.replace("\"", "\\\""))
                        .append('"');
            }

            // Дополнительные статические лейблы из InstanceConfig (если есть)
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

            body.append("} ").append(value).append('\n');
            rowCnt++;
        }

        sendToVictoria(body.toString());
        LogService.printf("[RESP] (%d rows) sent to VictoriaMetrics for CI=%s, req=%s%n",
                rowCnt, ic.ci, reqId);
    }

    private static String safeMetricName(String raw) {
        if (raw == null) raw = "";
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_:]", "_")
                .replaceAll("_+", "_");
        if (cleaned.isEmpty()) cleaned = "no_name_metric";
        if (Character.isDigit(cleaned.charAt(0)))
            cleaned = "_" + cleaned;
        return cleaned;
    }

    private static String safeLabelName(String raw) {
        String cleaned = raw.replaceAll("[^a-zA-Z0-9_]", "_")
                .replaceAll("_+", "_");
        if (cleaned.isEmpty() || Character.isDigit(cleaned.charAt(0)))
            cleaned = "_" + cleaned;
        return cleaned;
    }

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
