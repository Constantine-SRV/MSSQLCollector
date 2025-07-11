package model;

public class AppConfig {
    public SourceConfig serversSource = new SourceConfig();
    public SourceConfig jobsSource = new SourceConfig();
    public DestinationConfig resultsDestination = new DestinationConfig();
    public DestinationConfig logsDestination = new DestinationConfig();

    // Можно добавить геттеры для имён файлов
    public String getServersFileName() {
        return serversSource.fileName.isEmpty() ? "InstancesConfig.xml" : serversSource.fileName;
    }
    public String getJobsFileName() {
        return jobsSource.fileName.isEmpty() ? "QueryRequests.xml" : jobsSource.fileName;
    }
}
