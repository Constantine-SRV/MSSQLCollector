package model;

/**
 * Настройки источника данных. Определяет, откуда брать список серверов
 * или перечень заданий.
 *
 * Поддерживаемые значения {@link #type}:
 *   MSSQL | OCEANBASE | MONGO | LocalFile.
 *
 * Подключение для JDBC-источников — {@link #connection}
 * (блок {@code <Connection>} или {@code <ConnectionString>};
 * устаревший {@code <MSSQLConnectionString>} тоже читается).
 * Запрос — {@link #query} ({@code <Query>}, устаревший {@code <MSSQLQuery>}).
 */
public class SourceConfig {

    /** Тип источника: MSSQL | OCEANBASE | MONGO | LocalFile. */
    public String type = "LocalFile";
    /** Подключение (для MSSQL/OCEANBASE). */
    public ConnectionConfig connection = new ConnectionConfig();
    /** Запрос, возвращающий требуемые данные. */
    public String query = "";
    /** Строка подключения к MongoDB (для типа {@code MONGO}). */
    public String mongoConnectionString = "";
    /** Имя коллекции MongoDB. */
    public String mongoCollectionName = "";
    /** Путь к локальному файлу (для типа {@code LocalFile}). */
    public String fileName = "InstancesConfig.xml";

    /**
     * Только для JobsSource: тип опрашиваемых баз ({@code <TargetDbType>}).
     * Задан — опрашиваются только инстансы этого типа.
     * Не задан (null) — все JDBC-инстансы (MSSQL + OCEANBASE), как раньше; REDIS пропускается.
     */
    public DbType targetDbType;
}
