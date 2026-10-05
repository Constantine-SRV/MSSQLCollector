-- ============================================================
--  DDL для admintools на OceanBase (MySQL-режим)
--  Все таблицы создаются в БД admintools на системном тенанте
--  или на любом рабочем тенанте — на ваше усмотрение.
-- ============================================================

-- ----------------------------------------------------------------
-- 1. Список инстансов, которые надо опрашивать
--    Может использоваться как ServersSource для MSSQLCollector.
--
--    Стандартные колонки (как и для MSSQL-источника):
--      ci, instanceName, port, userName, password
--    Опциональные колонки для смешанного парка:
--      dbType  ('MSSQL' | 'OCEANBASE')
--      tenant  (только OB, например 'business_tenant')
--      cluster (только OB, например 'obcluster')
--    Любые другие непустые VARCHAR-колонки автоматически попадут
--    в extraLabels (env, dc, app и т.п.)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admintools.tbl_servers_list (
    ci            VARCHAR(64)   NOT NULL PRIMARY KEY,
    instanceName  VARCHAR(255)  NOT NULL,
    port          INT           NULL,
    userName      VARCHAR(64)   NULL,
    password      VARCHAR(255)  NULL,
    dbType        VARCHAR(32)   NULL,          -- MSSQL | OCEANBASE
    tenant        VARCHAR(64)   NULL,          -- для OB
    cluster       VARCHAR(64)   NULL,          -- для OB
    env           VARCHAR(64)   NULL,          -- пример extraLabel
    dc            VARCHAR(64)   NULL,          -- пример extraLabel
    app           VARCHAR(128)  NULL           -- пример extraLabel
);

-- ----------------------------------------------------------------
-- 2. Список запросов (jobs)
--    Может использоваться как JobsSource. SELECT должен вернуть
--    колонки именно с такими именами: requestId, queryText.
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admintools.tbl_query_list (
    requestId   VARCHAR(64)  NOT NULL PRIMARY KEY,
    queryText   LONGTEXT     NOT NULL,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1
);

-- ----------------------------------------------------------------
-- 3. Журнал результатов
--    Используется как ResultsDestination type=OCEANBASE.
--
--    INSERT-запрос, который надо положить в <MSSQLQuery>:
--      INSERT INTO admintools.ResultProcessorLog (ci, req_id, result_json, result_exec)
--      VALUES (?, ?, ?, ?)
-- ----------------------------------------------------------------
CREATE TABLE IF NOT EXISTS admintools.ResultProcessorLog (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    ts          DATETIME(3)  DEFAULT CURRENT_TIMESTAMP(3),
    ci          VARCHAR(64)  NOT NULL,
    req_id      VARCHAR(64)  NOT NULL,
    result_json JSON         NULL,
    result_exec VARCHAR(4000) NULL,
    INDEX idx_ci_req  (ci, req_id),
    INDEX idx_ts      (ts)
);

-- Полезные примеры запросов к result_json:
--
--   -- получить все ошибки за последние 24 часа
--   SELECT ts, ci, req_id, result_exec
--   FROM   admintools.ResultProcessorLog
--   WHERE  ts >= NOW() - INTERVAL 24 HOUR
--     AND  result_exec NOT LIKE 'Ok%';
--
--   -- сколько строк-результатов прислал каждый сервер за час
--   SELECT ci, req_id, JSON_LENGTH(result_json, '$.rows') AS rows_in_result
--   FROM   admintools.ResultProcessorLog
--   WHERE  ts >= NOW() - INTERVAL 1 HOUR;
--
--   -- достать конкретный label из строк
--   SELECT id, JSON_EXTRACT(result_json, '$.rows[0].sql_server') AS srv
--   FROM   admintools.ResultProcessorLog
--   WHERE  req_id = 'PERF'
--   LIMIT  10;
