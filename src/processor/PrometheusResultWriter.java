package processor;

import logging.LogService;
import model.DestinationConfig;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/**
 * Преобразует ResultSet в Prometheus exposition-формат и
 * отправляет его в VictoriaMetrics.
 *
 * Требования к ResultSet:
 *   • counter_name   – имя метрики (string). Если отсутствует / пусто,
 *                      будет использовано "no_name_metric".
 *   • counter_value  – числовое значение.
 *   • sql_server     – (optional) метка sql_server
 *   • source         – (optional) метка source
 *   • instance_name  – (optional) метка instance_name
 *
 * Дополнительные метки добавляются по мере наличия столбцов.
 */
public class PrometheusResultWriter {

    private final DestinationConfig destCfg;

    public PrometheusResultWriter(DestinationConfig destCfg) {
        this.destCfg = destCfg;
    }

    /**
     * Обрабатывает один (CI, reqId, ResultSet):
     * формирует тело в формате Prometheus 0.0.4 и шлёт POST-запрос в Victoria.
     */
    public void write(String ci, String reqId, ResultSet rs) throws Exception {
        StringBuilder body = new StringBuilder();
        ResultSetMetaData md = rs.getMetaData();
        int rowCnt = 0;

        while (rs.next()) {
            String name = safePromName(rs, "counter_name");

            body.append(name)
                    .append("{ci=\"").append(ci).append("\"");

            appendLabel(body, rs, "sql_server");
            appendLabel(body, rs, "source");
            appendLabel(body, rs, "instance_name");

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

    /** Возвращает корректное имя метрики Prometheus или "no_name_metric". */
    private static String safePromName(ResultSet rs, String field) {
        try {
            String val = rs.getString(field);
            if (val == null) val = "";
            // Заменяем все недопустимые символы на подчёркивания
            val = val.replaceAll("[^a-zA-Z0-9_:]", "_").replaceAll("_+", "_");
            if (val.isEmpty()) return "no_name_metric";
            // Имя не может начинаться с цифры
            if (Character.isDigit(val.charAt(0))) val = "_" + val;
            return val;
        } catch (Exception e) {
            return "no_name_metric";
        }
    }

    /** Добавляет label, если столбец присутствует и не пустой. */
    private static void appendLabel(StringBuilder sb, ResultSet rs, String col) {
        try {
            String val = rs.getString(col);
            if (val != null && !val.isEmpty()) {
                sb.append(',')
                        .append(col)
                        .append("=\"")
                        .append(val.replace("\"", "\\\""))
                        .append('"');
            }
        } catch (Exception ignored) { }
    }

    /** Отправляет сформированное тело в VictoriaMetrics. */
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
            LogService.errorf("[VM-ERROR] HTTP %d sending to Victoria: %s%n", resp, destCfg.prometheusUrl);
        }

        con.disconnect();
    }
}
