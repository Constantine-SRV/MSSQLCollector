package model;

/**
 * Конфигурация подключения к одному MSSQL-инстансу. Поля заполняются при
 * чтении XML-файла и используются {@link db.ServerRequest} для подключения.
 */
public class InstanceConfig {
    /** идентификатор сервера */
    public String ci;
    /** имя инстанса */
    public String instanceName;
    /** номер порта (null — динамический) */
    public Integer port;     // null → динамический
    /** учётная запись */
    public String userName;
    /** пароль учётной записи */
    public String password;
}
