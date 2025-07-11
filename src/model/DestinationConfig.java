package model;

/**
 * Конфигурация места назначения, куда будут сохраняться результаты
 * работы приложения или его лог. Поддерживаются различные типы
 * получателей: база MSSQL, MongoDB, локальные файлы или консоль.
 */
public class DestinationConfig {

    /** Тип приёмника: {@code MSSQL}, {@code MONGO}, {@code LocalFile} или {@code Console}. */
    public String type = "";
    /** Строка подключения к MSSQL, если выбран соответствующий тип. */
    public String mssqlConnectionString = "";
    /** Имя процедуры или запрос для записи в MSSQL. */
    public String mssqlQuery = "";
    /** Строка подключения к MongoDB. */
    public String mongoConnectionString = "";
    /** Имя коллекции MongoDB. */
    public String mongoCollectionName = "";
    /** Путь к каталогу при файловом выводе. */
    public String directoryPath = "";
}
