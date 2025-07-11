package model;

public class DestinationConfig {
    public String type = ""; // MSSQL, MONGO, LocalFile, Console
    public String mssqlConnectionString = "";
    public String mssqlQuery = "";
    public String mongoConnectionString = "";
    public String mongoCollectionName = "";
    public String directoryPath = "";
}
