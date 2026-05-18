package model;

/**
 * Настройки источника данных. Определяет, откуда брать список серверов
 * или перечень заданий.
 *
 * Поддерживаемые значения {@link #type}:
 *   MSSQL | OCEANBASE | MONGO | LocalFile.
 *
 * Поля {@code mssqlConnectionString} и {@code mssqlQuery} используются
 * для обоих JDBC-источников (MSSQL и OCEANBASE) — имя оставлено
 * прежним для обратной совместимости с существующим XML.
 */
public class SourceConfig {

    /** Тип источника: MSSQL | OCEANBASE | MONGO | LocalFile. */
    public String type = "LocalFile";
    /** JDBC-строка подключения (для MSSQL/OCEANBASE). */
    public String mssqlConnectionString = "";
    /** Запрос, возвращающий требуемые данные. */
    public String mssqlQuery = "";
    /** Строка подключения к MongoDB (для типа {@code MONGO}). */
    public String mongoConnectionString = "";
    /** Имя коллекции MongoDB. */
    public String mongoCollectionName = "";
    /** Путь к локальному файлу (для типа {@code LocalFile}). */
    public String fileName = "InstancesConfig.xml";
}
