package db;

import java.io.File;
import java.net.URL;
import java.security.CodeSource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Добавляет к MSSQL-строке подключения обязательные параметры.
 * applicationName формируется: <JarName_yyyyMMdd_HHmm> либо <ProjectDir>.
 */
public final class MssqlConnectionStringEnricher {

    /** Кешированное имя приложения. */
    private static final String DEFAULT_APP_NAME = buildDefaultAppName();

    private MssqlConnectionStringEnricher() { }

    /** Обогащаем строку подключения нужными параметрами. */
    public static String enrich(String original) {
        if (original == null || original.isBlank())
            throw new IllegalArgumentException("Connection string must not be null or empty");

        String base  = original.trim();
        String lower = base.toLowerCase(Locale.ROOT);

        StringBuilder sb = new StringBuilder(base);
        if (!base.endsWith(";")) sb.append(';');

        if (!lower.contains("encrypt="))
            sb.append("encrypt=false;");

        if (!lower.contains("trustservercertificate="))
            sb.append("trustServerCertificate=true;");

        if (!lower.contains("multisubnetfailover="))
            sb.append("multiSubnetFailover=true;");

        if (!lower.contains("applicationname="))
            sb.append("applicationName=").append(DEFAULT_APP_NAME).append(';');

        return sb.toString();
    }

    // ────────────────────────── private ──────────────────────────

    /** Строит applicationName: <JarName_yyyyMMdd_HHmm> или <ProjectDir>. */
    private static String buildDefaultAppName() {
        try {
            CodeSource cs = MssqlConnectionStringEnricher.class
                    .getProtectionDomain().getCodeSource();
            if (cs != null) {
                URL loc = cs.getLocation();
                if (loc != null && loc.getPath().endsWith(".jar")) {
                    File jar = new File(loc.toURI());
                    String jarName = jar.getName().replaceFirst("[.]jar$", "");
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmm")
                            .format(new Date(jar.lastModified()));
                    return jarName + '_' + ts;
                }
            }
        } catch (Exception ignored) { }

        /* IDE / класс-путь: берём имя каталога проекта */
        String dir = System.getProperty("user.dir", ".");
        return new File(dir).getName();
    }
}
