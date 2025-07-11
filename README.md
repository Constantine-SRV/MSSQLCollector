# MSSQLCollector

Простое консольное приложение для выполнения SQL-запросов на наборе серверов
и сохранения результатов. Настройки задаются в файле `MSSQLCollectorConfig.xml`
(в репозиторий не попадает, пример приведён ниже).

## Формат конфигурационного файла
Описание основных элементов `MSSQLCollectorConfig`:

- **ThreadPoolSize** – размер пула потоков для параллельной работы.
- **ServersSource** – источник списка серверов.
- **JobsSource** – источник списка выполняемых запросов.
- **ResultsDestination** – место сохранения результатов.
- **LogsDestination** – куда выводятся логи приложения.

Каждый блок `*Source` и `*Destination` имеет одинаковую структуру:

```
<Type>...</Type>                <!-- MSSQL | MONGO | LocalFile | Console -->
<MSSQLConnectionString>...</MSSQLConnectionString>
<MSSQLQuery>...</MSSQLQuery>  <!-- запрос или имя процедуры -->
<MongoConnectionString>...</MongoConnectionString>
<MongoCollectionName>...</MongoCollectionName>
<FileName>...</FileName>        <!-- только для Source -->
<DirectoryPath>...</DirectoryPath> <!-- только для Destination -->
```

### Пример конфига
```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<MSSQLCollectorConfig>
    <!--
      MSSQLCollector configuration file
      Type possible values: MSSQL | MONGO | LocalFile | Console
      Leave parameters empty to use default behavior
    -->
    <ThreadPoolSize>16</ThreadPoolSize>
    <!-- Source of servers list -->
    <ServersSource>
        <Type>MSSQL</Type>
        <MSSQLConnectionString>jdbc:sqlserver://HOST:PORT;encrypt=false;trustServerCertificate=true;user=USER;password=PASS</MSSQLConnectionString>
        <MSSQLQuery>SELECT * FROM [adminTools].[dbo].[tbl_servers_list]</MSSQLQuery>
        <MongoConnectionString>-</MongoConnectionString>
        <MongoCollectionName>-</MongoCollectionName>
        <FileName>InstancesConfig.xml</FileName>
    </ServersSource>
    <!-- Source of jobs list -->
    <JobsSource>
        <Type>MSSQL</Type>
        <MSSQLConnectionString>jdbc:sqlserver://HOST:PORT;encrypt=false;trustServerCertificate=true;user=USER;password=PASS</MSSQLConnectionString>
        <MSSQLQuery>SELECT * FROM [adminTools].[dbo].[tbl_query_list]</MSSQLQuery>
        <MongoConnectionString>-</MongoConnectionString>
        <MongoCollectionName>-</MongoCollectionName>
        <FileName>QueryRequests.xml</FileName>
    </JobsSource>
    <!-- Destination for results -->
    <ResultsDestination>
        <Type>MSSQL</Type>
        <MSSQLConnectionString>jdbc:sqlserver://HOST:PORT;encrypt=false;trustServerCertificate=true;user=USER;password=PASS</MSSQLConnectionString>
        <MSSQLQuery>admintools.dbo.usp_resultProcessor</MSSQLQuery>
        <MongoConnectionString>-</MongoConnectionString>
        <MongoCollectionName>-</MongoCollectionName>
        <DirectoryPath>-</DirectoryPath>
    </ResultsDestination>
    <!-- Destination for application logs -->
    <LogsDestination>
        <Type>Console</Type>
        <MSSQLConnectionString>-</MSSQLConnectionString>
        <MSSQLQuery>-</MSSQLQuery>
        <MongoConnectionString>-</MongoConnectionString>
        <MongoCollectionName>-</MongoCollectionName>
        <DirectoryPath>-</DirectoryPath>
    </LogsDestination>
</MSSQLCollectorConfig>
```
