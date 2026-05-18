package model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Конфигурация подключения к одному инстансу СУБД. Поля заполняются при
 * чтении XML/БД и используются {@link db.ServerRequest} для подключения.
 *
 * Поддерживаются:
 *  - MSSQL — драйвер mssql-jdbc;
 *  - OCEANBASE — драйвер mysql-connector-j (OB в MySQL-режиме).
 */
public class InstanceConfig {
    /** идентификатор сервера */
    public String ci;
    /** имя инстанса (DNS-имя или host\instance, либо просто host для OB-прокси) */
    public String instanceName;
    /** номер порта (null — динамический/именованный инстанс) */
    public Integer port;     // null → динамический / Browser
    /** учётная запись */
    public String userName;
    /** пароль учётной записи */
    public String password;

    /**
     * Тип СУБД. По умолчанию MSSQL — чтобы существующие конфиги
     * без этого поля продолжали работать как раньше.
     */
    public DbType dbType = DbType.MSSQL;

    /**
     * Имя тенанта в OceanBase (опционально, актуально только для OCEANBASE).
     * Если задано — итоговый логин будет {@code user@tenant} либо
     * {@code user@tenant#cluster} при наличии cluster.
     */
    public String tenant;

    /**
     * Имя кластера в OceanBase (опционально). Может быть не задан, если
     * у вас один кластер и OBProxy маршрутизирует автоматически.
     */
    public String cluster;

    /**
     * Произвольные дополнительные «лейблы» (теги) инстанса:
     * пара "имя → значение". Используются для меток при отправке метрик.
     *
     * Примеры: env=prod, dc=msk, team=core, и т.д.
     * Если значение пустое/NULL — в список не добавляется (см. reader).
     */
    public Map<String, String> extraLabels = new LinkedHashMap<>();
}
