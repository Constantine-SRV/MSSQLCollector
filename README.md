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
        <MSSQLQuery>
            INSERT INTO adminTools.dbo.ResultProcessorLog (ci, reqId, resultXml) VALUES (?, ?, ?)
        </MSSQLQuery>
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
Пример использования переменных окружения

В PowerShell:

$env:MSSQL_USERJAVA_PASSWORD="secret123"

В Linux bash:

export MSSQL_USERJAVA_PASSWORD='secret123'

## Подключение к источникам и приёмникам (новый формат)

Для `ServersSource`, `JobsSource`, `ResultsDestination` с типом `MSSQL` или `OCEANBASE`
подключение задаётся блоком `<Connection>`, запрос — тегом `<Query>`:

```xml
<ServersSource>
    <Type>OCEANBASE</Type>
    <Connection>
        <Host>192.168.55.200</Host>
        <Port>2883</Port>
        <User>root</User>
        <Tenant>app_tenant</Tenant>        <!-- только OCEANBASE: логин = User@Tenant[#Cluster] -->
        <Cluster></Cluster>
        <Password>${OB_PASSWORD}</Password> <!-- значение | ${ENV} | пусто → env MSSQL_<USER>_PASSWORD / консоль -->
        <Database>Inventory</Database>
        <Params>socketTimeout=60000</Params> <!-- доп. параметры URL, переопределяют дефолты -->
    </Connection>
    <Query>SELECT ci, instanceName, port, userName, password, dbType, tenant, cluster FROM tbl_servers_list</Query>
</ServersSource>
```

Для MSSQL — те же поля (без Tenant/Cluster) плюс `<IntegratedSecurity>true</IntegratedSecurity>`.
Готовый URL можно указать в `<ConnectionString>`.

Старые теги `<MSSQLConnectionString>` и `<MSSQLQuery>` продолжают работать (в лог пишется предупреждение).
Значение `-` в любом теге = пусто.

Неизвестный `Type` в `ResultsDestination` / `JobsSource` теперь ошибка при старте
(раньше, например, `PROMETEUS` молча писал результаты в локальные файлы).

## Redis

Redis поддерживается как тип опрашиваемого сервера (`DbType=REDIS`), без внешних jar.
Источник серверов/заданий и приёмник результатов — как обычно (LocalFile, MSSQL, OCEANBASE, PROMETHEUS).

Один запуск опрашивает один тип баз — он задаётся в `JobsSource`:

```xml
<JobsSource>
    <Type>LOCALFILE</Type>
    <TargetDbType>REDIS</TargetDbType>   <!-- MSSQL | OCEANBASE | REDIS -->
    <FileName>RequestsRedis.xml</FileName>
</JobsSource>
```

`TargetDbType` не задан — опрашиваются все MSSQL и OCEANBASE инстансы (как раньше), REDIS пропускается.

Инстанс Redis: `DbType=REDIS`, `Port` (пусто/0 → 6379), `Tls` (true/1), `UserName` (ACL, Redis 6+;
пусто → AUTH только паролем), `Password`. Из БД — колонки `dbType`, `tls`.

Задание — команда Redis (`INFO replication`, `CLUSTER NODES`, `CONFIG GET maxmemory*` ...)
или встроенное `TOPOLOGY` (роль, мастер/реплики, узлы кластера и слоты).
`INFO` / `CLUSTER INFO` раскладываются в `section | metric_name | metric_value` и подходят для PROMETHEUS
(нечисловые значения отбрасываются).

Минимальные права ACL:
```
ACL SETUSER monitor on >*** -@all +ping +info +cluster|info +cluster|nodes +config|get
```

TLS использует truststore JVM: `-Djavax.net.ssl.trustStore=... -Djavax.net.ssl.trustStorePassword=...`.
Проверка имени хоста в сертификате: `-Dredis.tls.verifyHostname=true`.
Таймауты: `-Dredis.connectTimeoutMs=5000 -Dredis.readTimeoutMs=15000`.

Примеры конфигов: `examples/redis/`, OceanBase в новом формате: `examples/oceanbase/`.
