
# MSSQLCollector → VictoriaMetrics (Prometheus) — что было сделано

_Дата: 2025-08-05 09:44 UTC_

Этот документ описывает доработки проекта **MSSQLCollector**, которые позволяют
отправлять результаты SQL‑запросов в **VictoriaMetrics** в формате Prometheus exposition
без внешних библиотек, а также добавлять произвольные лейблы (labels) из конфигурации
инстансов в каждую метрику.

---

## Кратко

- Добавлено новое направление выгрузки результатов: `PROMETHEUS` (VictoriaMetrics).
- Реализован конвертер `ResultSet → Prometheus exposition format` и HTTP‑POST
  на эндпоинт `/api/v1/import/prometheus`.
- Лейблы формируются **автоматически** из всех колонок результата, кроме служебных:
  `metric_name` и `metric_value` (названия метрики и её числовое значение).
- В каждую метрику всегда добавляются лейблы: `ci` (из `InstanceConfig`) и `reqId`
  (идентификатор запроса).
- Из `InstanceConfig.extraLabels` добавляются **произвольные** пары (ключ→значение)
  — например, `env=prod`, `dc=MSK1` и т.п.
- Источник списка заданий (Jobs) можно задавать локальным файлом (`LOCALFILE`),
  формат — простой XML.
- В лог сервисе добавлено логирование количества прочитанных запросов из локального файла.

---

## Обновления конфигурации

### 1) `MSSQLCollectorConfig.xml` — раздел результата

```xml
<ResultsDestination>
    <Type>PROMETHEUS</Type>
    <!-- Полный URL до end-point импорта Prometheus в VictoriaMetrics -->
    <PrometheusUrl>http://VICTORIA_HOST:PORT/api/v1/import/prometheus</PrometheusUrl>
    <!-- Не обязателен. Сейчас имя метрики берётся из данных (см. ниже). -->
    <MetricName>sqlserver_counter_value</MetricName>
    <!-- Остальные поля можно оставить '-' -->
    <MSSQLConnectionString>-</MSSQLConnectionString>
    <MSSQLQuery>-</MSSQLQuery>
    <MongoConnectionString>-</MongoConnectionString>
    <MongoCollectionName>-</MongoCollectionName>
    <DirectoryPath>-</DirectoryPath>
</ResultsDestination>
```

### 2) Источник заданий (Jobs) из файла

```xml
<JobsSource>
    <Type>LOCALFILE</Type>
    <FileName>RequestsConfig.xml</FileName>
    <MSSQLConnectionString>-</MSSQLConnectionString>
    <MSSQLQuery>-</MSSQLQuery>
    <MongoConnectionString>-</MongoConnectionString>
    <MongoCollectionName>-</MongoCollectionName>
</JobsSource>
```

Файл `RequestsConfig.xml` (пример ниже) содержится рядом с JAR или в указанном пути.

### 3) Дополнительные лейблы для серверов

В запросе, из которого читается список серверов, можно вернуть дополнительные столбцы
с произвольными именами и значениями (тип `varchar(8000)`). Пустые/NULL значения
не добавляются. Эти пары попадают в `InstanceConfig.extraLabels` и затем в каждую
метрику как labels.

Примеры колонок: `env`, `dc`, `team`, `app`, `cluster` и т.д.

---

## Формат файла запросов (`RequestsConfig.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Queries>
  <!--
    Каждый <Query> — это один SQL для выполнения на всех серверах.
    Атрибут id → попадает в метрики как label reqId.
  -->
  <Query id="PERF">
    SELECT
      @@SERVERNAME                                           AS [sql_server],
      'dm_os_performance_counters'                           AS [source],
      CONVERT(SYSNAME, RTRIM(object_name)) + '/' +
      CONVERT(SYSNAME, RTRIM(counter_name))                  AS [metric_name],
      RTRIM(instance_name)                                   AS [instance_name],
      cntr_value                                             AS [metric_value]
    FROM sys.dm_os_performance_counters
  </Query>
</Queries>
```

### Обязательные поля результата

- `metric_name` — имя метрики (строка). Если пусто/NULL → подставляется `no_name_metric`.
  Имя автоматически санируется под требования Prometheus (только `[A-Za-z0-9_:]`,
  первый символ — не цифра; идущие подряд недопустимые символы заменяются `_`).  
- `metric_value` — **числовое** значение. Если поле отсутствует или пустое —
  строка будет отклонена VictoriaMetrics (в обработчике добавлена защитная подстановка `0`,
  но корректные запросы должны возвращать число).

### Labels

- Всегда добавляются: `ci` (из конфигурации сервера) и `reqId` (из атрибута `id` запроса).
- Любые другие столбцы из результата запроса автоматически становятся labels
  вида `имя_колонки="значение"`. Пустые/NULL пропускаются.
- Из `InstanceConfig.extraLabels` добавляются пары, полученные при чтении списка серверов.

---

## Ключевые изменения в коде

- **`processor/PrometheusResultWriter`**  
  Преобразует `ResultSet` в Prometheus exposition формат, добавляет labels
  (`ci`, `reqId`, динамические столбцы результата, `extraLabels`), выполняет HTTP‑POST
  на VictoriaMetrics.  
  Основной метод: `write(InstanceConfig ic, String reqId, ResultSet rs)`.

- **`processor/ResponseProcessor`**  
  Сигнатура `handle(...)` изменена: теперь принимает **весь** `InstanceConfig`,
  чтобы пробрасывать `extraLabels` в `PrometheusResultWriter`. Коммутаторы типов
  дополнили веткой `PROMETHEUS`.

- **`db/ServerRequest`**  
  Вызов обработчика результата обновлён на `responseProcessor.handle(cfg, reqId, rs)`
  (раньше передавался только `ci`).

- **`model/InstancesConfigReader`**  
  Наполняет `InstanceConfig.extraLabels` парами «имя→значение» из результата источника серверов
  (любые дополнительные `varchar(8000)`-колонки; пустые/NULL не добавляются).

- **`model/QueryRequestsReader`**  
  Добавлен лог: сколько запросов прочитано из локального файла.

---

## Проверка, что метрики дошли

### Через API VictoriaMetrics

1. Найти **любые** метрики:  
   ```powershell
   Invoke-RestMethod -Uri "http://VM_HOST:PORT/api/v1/label/__name__/values" -Method GET
   ```

2. Экспорт сырых временных рядов по имени:  
   ```powershell
   Invoke-RestMethod `
     -Uri "http://VM_HOST:PORT/api/v1/export?match[]=<metric_name>" `
     -Method GET
   ```

3. Любые метрики за последние 5 минут по конкретному серверу (label `sql_server`):  
   ```powershell
   $VictoriaMetricsUrl = "http://VM_HOST:PORT/api/v1/query_range"
   $SqlServer          = "SQLUPDATE2019"
   $StepSeconds        = 60
   $endUnix   = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
   $startUnix = ([DateTimeOffset]::UtcNow.AddMinutes(-5)).ToUnixTimeSeconds()
   $promqlEsc = [uri]::EscapeDataString("{sql_server=`"$SqlServer`"}")
   $url = $VictoriaMetricsUrl + "?query=" + $promqlEsc + "&start=" + $startUnix + "&end=" + $endUnix + "&step=" + $StepSeconds
   $resp = Invoke-RestMethod -Uri $url -Method GET
   $resp.data.result | Select-Object -First 10
   ```

4. По `ci` (показать любые серии):  
   ```powershell
   $victoria = "http://VM_HOST:PORT/api/v1/query"
   $ci       = "CI00002"
   $promql   = "{ci=`"$ci`"}"
   $url      = $victoria + "?query=" + [Uri]::EscapeDataString($promql)
   (Invoke-RestMethod -Uri $url -Method GET).data.result | Select-Object -First 10
   ```

---

## Частые вопросы

- **Почему имя метрики иногда «уродуется»?**  
  Мы санируем имя под правила Prometheus: только `[A-Za-z0-9_:]`,
  без ведущей цифры. Недопустимые символы заменяются на `_`.

- **Где взять дополнительные лейблы (env/dc/…)?**  
  Верните их как отдельные текстовые колонки в источнике серверов — они будут
  автоматически добавлены в `InstanceConfig.extraLabels` и затем в метрику.

- **Что будет, если `metric_value` не число?**  
  VictoriaMetrics отбросит такую строку. Пишите запросы так, чтобы возвращать число.

---

## Минимальный пример end-to-end

1. `MSSQLCollectorConfig.xml`:
   ```xml
   <JobsSource>
     <Type>LOCALFILE</Type>
     <FileName>RequestsConfig.xml</FileName>
     <MSSQLConnectionString>-</MSSQLConnectionString>
     <MSSQLQuery>-</MSSQLQuery>
     <MongoConnectionString>-</MongoConnectionString>
     <MongoCollectionName>-</MongoCollectionName>
   </JobsSource>

   <ResultsDestination>
     <Type>PROMETHEUS</Type>
     <PrometheusUrl>http://192.168.55.31:1102/api/v1/import/prometheus</PrometheusUrl>
     <MetricName>sqlserver_counter_value</MetricName>
     <MSSQLConnectionString>-</MSSQLConnectionString>
     <MSSQLQuery>-</MSSQLQuery>
     <MongoConnectionString>-</MongoConnectionString>
     <MongoCollectionName>-</MongoConnectionName>
     <DirectoryPath>-</DirectoryPath>
   </ResultsDestination>
   ```

2. `RequestsConfig.xml`:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <Queries>
     <Query id="PERF">
       SELECT
         @@SERVERNAME AS [sql_server],
         'dm_os_performance_counters' AS [source],
         CONVERT(SYSNAME, RTRIM(object_name)) + '/' + CONVERT(SYSNAME, RTRIM(counter_name)) AS [metric_name],
         RTRIM(instance_name) AS [instance_name],
         cntr_value AS [metric_value]
       FROM sys.dm_os_performance_counters
     </Query>
   </Queries>
   ```

3. Запуск сборщика → метрики появляются в VictoriaMetrics и видны в Grafana/через API.

---

## Контрольные точки в логах

- `[START] CI=... url=...` — начало обработки инстанса.  
- `[RESP] (... rows) sent to VictoriaMetrics for CI=..., req=...` — отправка метрик.  
- Ошибки HTTP при отправке: `[VM-ERROR] HTTP ...`.

---

Готово. Если захочется отправлять и **наборы gauge/counter** с `help/type` — можно расширить `PrometheusResultWriter` до текстового экспозиционного формата с директивами `# HELP` / `# TYPE`.
