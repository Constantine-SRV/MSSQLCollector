package model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфигурация подключения к одному MSSQL-инстансу. Поля заполняются при
 * чтении XML/БД и используются {@link db.ServerRequest} для подключения.
 */
public class InstanceConfig {
    /** идентификатор сервера */
    public String ci;
    /** имя инстанса (DNS-имя или host\instance) */
    public String instanceName;
    /** номер порта (null — динамический/именованный инстанс) */
    public Integer port;     // null → динамический / Browser
    /** учётная запись */
    public String userName;
    /** пароль учётной записи */
    public String password;

    /**
     * Произвольные дополнительные «лейблы» (теги) инстанса:
     * пара "имя → значение". Используются для меток при отправке метрик.
     *
     * Примеры: env=prod, dc=msk, team=core, и т.д.
     * Если значение пустое/NULL — в список не добавляется (см. reader).
     */
    public Map<String, String> extraLabels = new LinkedHashMap<>();
}
