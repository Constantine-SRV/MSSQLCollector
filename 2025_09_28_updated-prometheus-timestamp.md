# MSSQLCollector → VictoriaMetrics (Prometheus) — что было сделано

_Дата первоначальной версии: 2025-08-05 09:44 UTC_  
_Обновлено: 2025-09-28 с добавлением поддержки timestamp_

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
- В каждую метрику всегда добавляются лейбл: `ci` (из `InstanceConfig`).
- Из `InstanceConfig.extraLabels` добавляются **произвольные** пары (ключ→значение)
  — например, `env=prod`, `dc=MSK1` и т.п.
- Источник списка заданий (Jobs) можно задавать локальным файлом (`LOCALFILE`),
  формат — простой XML.
- В лог сервисе добавлено логирование количества прочитанных запросов из локального файла.
- **NEW (2025-09-28):** Добавлена поддержка пользовательских timestamp для метрик из SQL-запросов.

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

### Опциональное поле timestamp (NEW 2025-09-28)

- `timestamp` — **datetime** значение из MSSQL. Если это поле присутствует в ResultSet,
  оно будет использовано как timestamp метрики в формате Prometheus (миллисекунды с эпохи Unix).
  Если поле отсутствует, VictoriaMetrics/Prometheus сами проставят текущее время при получении метрики.

### Labels

- Всегда добавляются: `ci` (из конфигурации сервера) ~~и `reqId` (из атрибута `id` запроса)~~.
  **Примечание:** `reqId` больше не добавляется в labels с версии после 2025-08-05.
- Любые другие столбцы из результата запроса автоматически становятся labels
  вида `имя_колонки="значение"`. Пустые/NULL пропускаются.
- Колонки `metric_name`, `metric_value`, `timestamp`, `ci` не попадают в labels.
- Из `InstanceConfig.extraLabels` добавляются пары, полученные при чтении списка серверов.

---

## Поддержка пользовательских Timestamp (NEW 2025-09-28)

### Зачем это нужно?

По умолчанию Prometheus/VictoriaMetrics проставляют текущее время при получении метрики. 
Это не всегда подходит для исторических данных или данных с точным временем измерения.
Теперь можно передавать точное время измерения прямо из SQL-запроса.

### Как использовать?

Добавьте колонку с именем `timestamp` в ваш SQL-запрос:

```sql
-- Пример 1: Текущее время сервера
SELECT 
    'my_metric' as metric_name,
    100 as metric_value,
    GETDATE() as timestamp
    
-- Пример 2: Историческая метрика задержек дисков
SELECT 
    'disk_latency_ms' as metric_name,
    avg_latency as metric_value,
    measurement_time as timestamp,  -- точное время измерения
    disk_name as disk,
    server_name as server
FROM disk_performance_history
WHERE measurement_time > DATEADD(minute, -20, GETDATE())
```

### Важные моменты при работе с timestamp

1. **Дедупликация**: VictoriaMetrics автоматически дедуплицирует метрики с одинаковыми labels и timestamp.
   При повторной отправке метрики с тем же timestamp старое значение будет перезаписано новым.

2. **Исторические данные**: Можно безопасно отправлять перекрывающиеся временные интервалы.
   Например, каждые 5 минут отправлять данные за последние 20 минут для защиты от потерь.

3. **Формат времени**: Используйте стандартный MSSQL тип `datetime` или `datetime2`. 
   PrometheusResultWriter автоматически конвертирует в миллисекунды Unix epoch.

### Пример запроса с историческими данными

```xml
<Query id="DISK_HISTORY">
  SELECT 
    'disk_io_latency' as metric_name,
    latency_ms as metric_value,
    collect_time as timestamp,        -- Время измерения, а не время запроса!
    disk_name,
    operation_type,
    database_name
  FROM monitoring.disk_latency_history
  WHERE collect_time >= DATEADD(minute, -30, GETDATE())
  ORDER BY collect_time
</Query>
```

В этом примере:
- Метрики будут иметь точное время измерения из `collect_time`
- При повторных запусках дубли автоматически перезапишутся
- Можно анализировать исторические тренды с правильными временными метками

---

## Ключевые изменения в коде

- **`processor/PrometheusResultWriter`**  
  Преобразует `ResultSet` в Prometheus exposition формат, добавляет labels
  (`ci`, ~~`reqId`~~, динамические столбцы результата, `extraLabels`), выполняет HTTP‑POST
  на VictoriaMetrics.  
  **NEW (2025-09-28):** Добавлен метод `extractTimestamp` для извлечения timestamp из ResultSet.
  Если колонка `timestamp` присутствует, её значение конвертируется в миллисекунды и добавляется к метрике.
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

5. **NEW: Проверка метрик с пользовательскими timestamp**:
   ```powershell
   # Запрос метрик с конкретным timestamp
   $victoria = "http://VM_HOST:PORT/api/v1/query"
   $promql = "disk_io_latency{disk_name='C:'}"
   $url = $victoria + "?query=" + [Uri]::EscapeDataString($promql) + "&time=1699891200"
   (Invoke-RestMethod -Uri $url -Method GET).data.result
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

- **NEW: Что если у меня есть колонка timestamp, но я не хочу её использовать для метрики?**  
  Переименуйте колонку, например: `AS [event_time]` вместо `AS [timestamp]`.
  Только колонка с точным именем `timestamp` используется для временной метки.

- **NEW: Можно ли отправлять исторические данные с перекрытием?**  
  Да! VictoriaMetrics автоматически дедуплицирует метрики с одинаковыми labels и timestamp.
  Это безопасный способ защиты от потери данных при сбоях коллектора.

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
     <MongoCollectionName>-</MongoCollectionName>
     <DirectoryPath>-</DirectoryPath>
   </ResultsDestination>
   ```

2. `RequestsConfig.xml` (с примерами timestamp):
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <Queries>
     <!-- Без timestamp - время проставит VictoriaMetrics -->
     <Query id="PERF">
       SELECT
         @@SERVERNAME AS [sql_server],
         'dm_os_performance_counters' AS [source],
         CONVERT(SYSNAME, RTRIM(object_name)) + '/' + CONVERT(SYSNAME, RTRIM(counter_name)) AS [metric_name],
         RTRIM(instance_name) AS [instance_name],
         cntr_value AS [metric_value]
       FROM sys.dm_os_performance_counters
     </Query>
     
     <!-- С timestamp - точное время из базы -->
     <Query id="DISK_STATS">
       SELECT
         'disk_read_latency' AS [metric_name],
         io_stall_read_ms / (num_of_reads + 1) AS [metric_value],
         GETDATE() AS [timestamp],
         DB_NAME(database_id) AS [database],
         type_desc AS [file_type]
       FROM sys.dm_io_virtual_file_stats(NULL, NULL) fs
       JOIN sys.master_files mf ON fs.database_id = mf.database_id 
         AND fs.file_id = mf.file_id
       WHERE num_of_reads > 0
     </Query>
   </Queries>
   ```

3. Запуск сборщика → метрики появляются в VictoriaMetrics с правильными временными метками.

---

## Контрольные точки в логах

- `[START] CI=... url=...` — начало обработки инстанса.  
- `[RESP] (... rows) sent to VictoriaMetrics for CI=..., req=...` — отправка метрик.  
- Ошибки HTTP при отправке: `[VM-ERROR] HTTP ...`.
- **NEW:** `[VM-WARN] Failed to extract timestamp from column ...` — предупреждение о проблеме с извлечением timestamp.

---

## Итого по обновлению 2025-09-28

Добавлена полная поддержка пользовательских timestamp для метрик:
- Автоматическое определение колонки `timestamp` в ResultSet
- Конвертация MSSQL datetime в миллисекунды Unix epoch
- Безопасная обработка исторических данных с дедупликацией
- Обратная совместимость — если колонки timestamp нет, работает как раньше

Это позволяет корректно работать с историческими данными, точными временными метками измерений
и реализовать надёжную доставку метрик с перекрытием временных интервалов.