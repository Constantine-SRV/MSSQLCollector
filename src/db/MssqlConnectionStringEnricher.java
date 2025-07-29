package db;

import model.InstanceConfigEnreacher;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * «Обогатитель» JDBC‑строки:
 *   • гарантирует наличие полезных параметров (encrypt / trustServerCertificate …)
 *   • добавляет applicationName=<JarName>_<yyyyMMdd_HHmm>
 *   • если указана учётка (user= / username=) и ПАРОЛЬ НЕ указан,
 *     запрашивает/ищет его через InstanceConfigEnreacher.resolvePassword()
 *
 * Использование:
 *   String enriched = MssqlConnectionStringEnricher.enrich(baseConnStr);
 */
public final class MssqlConnectionStringEnricher {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmm");

    private MssqlConnectionStringEnricher() {}

    /* ------------------------------------------------------------------ */
    /*  Публичный API                                                     */
    /* ------------------------------------------------------------------ */
    public static String enrich(String connStr) {

        /* 1. разбираем "key=value;" -> map */
        Map<String,String> p = parse(connStr);

        /* 2. добавляем/исправляем стандартные параметры ----------------- */
        putIfAbsent(p, "encrypt",              "false");
        putIfAbsent(p, "trustServerCertificate","true");
        putIfAbsent(p, "multiSubnetFailover",  "true");

        /* 3. applicationName=<jar>_<YYYYMMDD_HHMM> ---------------------- */
        if (!hasKey(p, "applicationName")) {
            p.put("applicationName", buildAppName());
        }

        /* 4. пароль для явной учётки ------------------------------------ */
        if (!isIntegratedAuth(p)) {
            String user = getFirst(p, "user", "username");
            if (user != null && !user.isEmpty()
                    && !hasKey(p, "password")) {

                String pwd = InstanceConfigEnreacher.resolvePassword(user);
                p.put("password", pwd);
            }
        }

        /* 5. собираем обратно в строку ---------------------------------- */
        return toString(p);
    }

    /* ------------------------------------------------------------------ */
    /*  Вспомогательные методы                                            */
    /* ------------------------------------------------------------------ */

    /** разбор  a=1;b=2; -> map (без учёта регистра ключа) */
    private static Map<String,String> parse(String s) {
        Map<String,String> map = new LinkedHashMap<>();
        for (String part : s.split("(?i);")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String v = part.substring(eq+1).trim();
                if (!k.isEmpty()) map.put(k, v);
            } else if (!part.isBlank()) {
                // host[,port]  / host\instance  кусок без =
                map.put("_server", part.trim());
            }
        }
        return map;
    }

    /** вернуть соединённую строку */
    private static String toString(Map<String,String> p) {
        StringBuilder sb = new StringBuilder();

        // вначале «серверную» часть (ключ "_server")
        sb.append(p.getOrDefault("_server", ""));
        sb.append(";");

        // затем остальные параметры
        p.forEach((k,v) -> {
            if (!k.equals("_server"))
                sb.append(k).append("=").append(v).append(";");
        });
        return sb.toString();
    }

    /** key1/key2 … без учёта регистра */
    private static boolean hasKey(Map<String,String> p, String... keys) {
        for (String k : keys) if (p.containsKey(k.toLowerCase(Locale.ROOT))) return true;
        return false;
    }
    private static String getFirst(Map<String,String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k.toLowerCase(Locale.ROOT));
            if (v != null) return v;
        }
        return null;
    }
    private static void putIfAbsent(Map<String,String> p, String k, String v) {
        p.putIfAbsent(k.toLowerCase(Locale.ROOT), v);
    }

    /** true если используется интегрированная аутентификация */
    private static boolean isIntegratedAuth(Map<String,String> p) {
        String v = getFirst(p, "integratedSecurity", "authenticationScheme");
        return v != null && v.equalsIgnoreCase("true");
    }

    /** applicationName=<jar|Main>_20250724_1210  */
    private static String buildAppName() {
        String jar = Optional.ofNullable(
                        MssqlConnectionStringEnricher.class
                                .getProtectionDomain()
                                .getCodeSource())
                .map(cs -> cs.getLocation().getPath())
                .map(path -> {
                    int slash = path.lastIndexOf('/');
                    return (slash >= 0) ? path.substring(slash+1) : path;
                })
                .orElse("Main");
        // убираем .jar, если есть
        if (jar.endsWith(".jar")) jar = jar.substring(0, jar.length()-4);

        String host = "UnknownHost";
        try { host = InetAddress.getLocalHost().getHostName(); } catch (Exception ignore) {}

        return jar + "_" + host + "_" + LocalDateTime.now().format(TS);
    }
}
