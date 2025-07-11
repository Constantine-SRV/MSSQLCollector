package model;

public class SourceConfig {
    public String type = "LocalFile"; // MSSQL, MONGO, LocalFile
    public String mssqlConnectionString = "";
    public String mssqlQuery = "";
    public String mongoConnectionString = "";
    public String mongoCollectionName = "";
    public String fileName = "InstancesConfig.xml";
}
