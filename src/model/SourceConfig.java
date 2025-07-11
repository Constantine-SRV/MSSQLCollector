package model;

/**
 * Настройки источника данных. Определяет, откуда брать список серверов
 * или перечень заданий. Все параметры могут быть оставлены пустыми,
 * тогда будут использованы значения по умолчанию.
 */
public class SourceConfig {

    /** Тип источника: {@code MSSQL}, {@code MONGO} или {@code LocalFile}. */
    public String type = "LocalFile";
    /** JDBC-строка подключения к MSSQL (для типа {@code MSSQL}). */
    public String mssqlConnectionString = "";
    /** Запрос, возвращающий требуемые данные из MSSQL. */
    public String mssqlQuery = "";
    /** Строка подключения к MongoDB (для типа {@code MONGO}). */
    public String mongoConnectionString = "";
    /** Имя коллекции MongoDB. */
    public String mongoCollectionName = "";
    /** Путь к локальному файлу (для типа {@code LocalFile}). */
    public String fileName = "InstancesConfig.xml";
}
