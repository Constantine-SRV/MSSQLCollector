package model;

import java.io.Console;
import java.util.*;

/**
 * Запрашивает у пользователя пароль для всех учёток, у которых поле
 * {@code password} пустое. Используется в {@link Main} перед подключением
 * к серверам.
 */
public final class InstanceConfigEnreacher {

    private InstanceConfigEnreacher() { }

    /**
     * Проходит список конфигов, группируя их по имени пользователя, и
     * единожды спрашивает пароль для каждой учётки. Возвращает тот же список,
     * где у нужных элементов заполнено поле {@code password}.
     *
     * @param list исходные конфигурации
     * @return список с заполненными паролями
     */
    public static List<InstanceConfig> enrichWithPasswords(List<InstanceConfig> list) {
        // собираем userName → List<InstanceConfig> без пароля
        Map<String, List<InstanceConfig>> needPwd = new LinkedHashMap<>();
        for (InstanceConfig cfg : list) {
            if (cfg.password == null || cfg.password.isEmpty()) {
                needPwd.computeIfAbsent(cfg.userName, k -> new ArrayList<>()).add(cfg);
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
