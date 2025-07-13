package model;

/**
 * Основные настройки приложения. Содержит информацию об источниках
 * данных, местах сохранения результатов и размере пула потоков.
 */
public class AppConfig {

    /** RUN | SAVE_CONFIGS | PROCESS_XML_RESULT */
    public String taskName = "RUN";
    /** Размер пула потоков для выполнения запросов. */
    public int threadPoolSize = 32;
    /** Источник списка серверов. */
    public SourceConfig serversSource = new SourceConfig();
    /** Источник списка SQL-заданий. */
    public SourceConfig jobsSource = new SourceConfig();
    /** Куда сохранять результаты выполнения запросов. */
    public DestinationConfig resultsDestination = new DestinationConfig();
    /** Куда писать логи приложения. */
    public DestinationConfig logsDestination = new DestinationConfig();

    // Можно добавить геттеры для имён файлов
    public String getServersFileName() {
        return serversSource.fileName.isEmpty() ? "InstancesConfig.xml" : serversSource.fileName;
    }
    public String getJobsFileName() {
        return jobsSource.fileName.isEmpty() ? "QueryRequests.xml" : jobsSource.fileName;
    }
}
