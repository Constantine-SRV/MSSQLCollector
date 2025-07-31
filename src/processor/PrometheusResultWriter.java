package processor;

import logging.LogService;
import model.DestinationConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

public class PrometheusResultWriter {
    private final DestinationConfig destCfg;

    public PrometheusResultWriter(DestinationConfig destCfg) {
        this.destCfg = destCfg;
    }

    /**
     * Преобразует ResultSet в Prometheus exposition format и отправляет в VictoriaMetrics.
     * Ожидается, что в ResultSet будут столбцы:
     *   - counter_name (имя метрики: string)
     *   - counter_value (значение: number)
     *   - [sql_server], [source], [instance_name] (метки)
     */
    public void write(String ci, String reqId, ResultSet rs) throws Exception {
        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int colCount = md.getColumnCount();

        int rowCnt = 0;
        while (rs.next()) {
            String metricName = destCfg.metricName != null ? destCfg.metricName : "sqlserver_counter_value";
            String name = safePromName(rs, "counter_name", metricName);

            // Метки: CI, sql_server, source, instance_name
            body.append(name)
                    .append("{ci=\"").append(ci).append("\"");

            appendLabel(body, rs, "sql_server");
            appendLabel(body, rs, "source");
            appendLabel(body, rs, "instance_name");

            body.append("} ").append(rs.getString("counter_value")).append('\n');
            rowCnt++;
        }

        // Отправка HTTP POST
        sendToVictoria(body.toString());

        LogService.printf("[RESP] (%d rows) sent to VictoriaMetrics for CI=%s, req=%s%n", rowCnt, ci, reqId);
    }

    private static String safePromName(ResultSet rs, String field, String defaultName) {
        try {
            String val = rs.getString(field);
            if (val == null || val.isEmpty()) return defaultName;
            // Преобразуем в допустимое имя метрики Prometheus (только буквы, цифры, _: )
            return val.replaceAll("[^a-zA-Z0-9_:]", "_");
        } catch (Exception e) {
            return defaultName;
        }
    }

    private static void appendLabel(StringBuilder sb, ResultSet rs, String col) {
        try {
            String val = rs.getString(col);
            if (val != null && !val.isEmpty())
                sb.append(',').append(col).append("=\"").append(val.replace("\"", "\\\"")).append("\"");
        } catch (Exception ignored) { }
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
        int respCode = con.getResponseCode();
        if (respCode < 200 || respCode > 299) {
            LogService.errorf("[VM-ERROR] HTTP %d sending to Victoria: %s%n", respCode, destCfg.prometheusUrl);
        }
        con.disconnect();
    }
}
