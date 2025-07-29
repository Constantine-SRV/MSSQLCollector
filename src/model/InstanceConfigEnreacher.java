package model;

import java.io.Console;
import java.util.*;

/**
 * Заполняет поле {@code password} в {@link InstanceConfig}.
 * Логика вынесена в два уровня:
 *  ▸ resolvePassword(user) — получить пароль для конкретной учётки
 *  ▸ enrichWithPasswords(list) — пробежать список конфигов и подставить пароль
 *
 * Пароль ищем так:
 *   1. переменная окружения  MSSQL_<USER>_PASSWORD
 *   2. если нет — спрашиваем у пользователя один раз,
 *      кешируем в static‑мапу и используем повторно.
 */
public final class InstanceConfigEnreacher {

    /** cache: userName → password (заполняется по ходу работы) */
    private static final Map<String, String> cache = new HashMap<>();

    private InstanceConfigEnreacher() { }

    /* ------------------------------------------------------------ */
    /*  1. получить пароль для логина (из env или интерактивно)     */
    /* ------------------------------------------------------------ */
    public static String resolvePassword(String user) {
        if (user == null) user = "";
        user = user.trim();

        /* 1) уже кешировали? */
        if (cache.containsKey(user))
            return cache.get(user);

        /* 2) переменная окружения */
        String envVar = "MSSQL_" + user.toUpperCase(Locale.ROOT) + "_PASSWORD";
        String pwd = System.getenv(envVar);
        if (pwd != null && !pwd.isEmpty()) {
            cache.put(user, pwd);
            return pwd;
        }

        /* 3) спросить у человека (один раз) */
        Console console = System.console();
        if (console != null) {
            char[] ch = console.readPassword("Type password for account %s: ", user);
            pwd = new String(ch);
        } else {
            // IDE / non‑interactive fallback
            System.out.printf("Type password for account %s: ", user);
            pwd = new Scanner(System.in).nextLine();
        }
        cache.put(user, pwd);
        return pwd;
    }

    /* ------------------------------------------------------------ */
    /*  2. пройти список InstanceConfig и расставить пароли          */
    /* ------------------------------------------------------------ */
    public static List<InstanceConfig> enrichWithPasswords(List<InstanceConfig> list) {
        for (InstanceConfig cfg : list) {
            if (cfg.password == null || cfg.password.isEmpty()) {
                cfg.password = resolvePassword(cfg.userName);
            }
        }
        return list;
    }
}
