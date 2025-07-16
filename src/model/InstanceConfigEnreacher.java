package model;

import java.io.Console;
import java.util.*;

/**
 * Дополняет недостающие поля (например, пароль) для InstanceConfig,
 * пытаясь взять их из переменных окружения, иначе спрашивает у пользователя.
 */
public final class InstanceConfigEnreacher {

    private InstanceConfigEnreacher() {}

    /**
     * Заполняет пароли для всех конфигов:
     * - если в объекте password пустой, сначала ищет в переменных окружения MSSQL_{USER}_PASSWORD,
     * - если не нашёл — спрашивает в консоли.
     */
    public static List<InstanceConfig> enrichWithPasswords(List<InstanceConfig> list) {
        Map<String, List<InstanceConfig>> needPwd = new LinkedHashMap<>();
        for (InstanceConfig cfg : list) {
            if (cfg.password == null || cfg.password.isEmpty()) {
                // Сначала пробуем из переменной окружения
                String user = (cfg.userName == null) ? "" : cfg.userName.trim();
                String envVar = "MSSQL_" + user.toUpperCase(Locale.ROOT) + "_PASSWORD";
                String pwd = System.getenv(envVar);

                if (pwd != null && !pwd.isEmpty()) {
                    cfg.password = pwd;
                } else {
                    // Добавляем к запросу у пользователя
                    needPwd.computeIfAbsent(user, k -> new ArrayList<>()).add(cfg);
                }
            }
        }
        if (needPwd.isEmpty()) return list;

        Console console = System.console();
        Scanner scanner = (console == null) ? new Scanner(System.in) : null;

        for (Map.Entry<String, List<InstanceConfig>> e : needPwd.entrySet()) {
            String account = e.getKey();
            String pwd;

            if (console != null) {
                char[] ch = console.readPassword("Type password for account %s: ", account);
                pwd = new String(ch);
            } else { // IDE/redirected input — fallback
                System.out.printf("Type password for account %s: ", account);
                pwd = scanner.nextLine();
            }
            // применяем ко всем конфигам этого userName
            for (InstanceConfig cfg : e.getValue()) {
                cfg.password = pwd;
            }
        }
        return list;
    }
}
