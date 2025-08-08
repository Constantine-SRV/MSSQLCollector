import db.ServerRequest;
import logging.LogService;
import model.*;
import processor.ResponseProcessor;

import java.security.Security;          // <--  NEW
import java.util.List;
import java.util.concurrent.*;
 /*
        Get-ChildItem -File err_* | Where-Object { $_.LastWriteTime -lt (Get-Date).AddHours(-48) } | Remove-Item -Force
        Get-ChildItem -File log_* | Where-Object { $_.LastWriteTime -lt (Get-Date).AddHours(-48) } | Remove-Item -Force
  */
/** Точка входа приложения. */
public class Main {

    /* ------------------------------------------------------------------
       Глобально снимаем ВСЕ ограничения Java-TLS для данного процесса.
       Делается один раз при загрузке класса Main, ещё до метода main().
       ------------------------------------------------------------------ */
    static {
        // 1. Запреты на использования «опасных» алгоритмов в канале TLS
        Security.setProperty("jdk.tls.disabledAlgorithms", "");

        // 2. Запреты на алгоритмы у сертификатов (SHA-1, MD5 и т.д.)
        Security.setProperty("jdk.certpath.disabledAlgorithms", "");

        // 3. (опционально) блокировки в Jar-подписях
        Security.setProperty("jdk.jar.disabledAlgorithms", "");
    }

    public static void main(String[] args) throws Exception {
        LogService.println("Version 2025-08-08-log Started");


        String cfgFile = args.length > 0 ? args[0] : "MSSQLCollectorConfig.xml";
        AppConfig cfg = AppConfigReader.read(cfgFile);
        if (cfg == null) {
            cfg = new AppConfig();                  // дефолт
            AppConfigWriter.write(cfg, cfgFile);
            LogService.println("Default config created: " + cfgFile);
        }

        // <-- ВАЖНО: инициализация LogService из секции <LogsDestination>
        try {
            LogService.init(cfg.logsDestination);
        } catch (Exception e) {
            // если что-то пошло не так — логгер остаётся в режиме Console
            LogService.errorf("[LOG] init failed: %s%n", e.getMessage());
        }

        switch (cfg.taskName.toUpperCase()) {
            case "SAVE_CONFIGS"       -> runSaveConfigs(cfg);
            case "PROCESS_XML_RESULT" -> LogService.println("Task PROCESS_XML_RESULT not implemented yet.");
            default                   -> runFullPipeline(cfg);   // RUN (по умолчанию)
        }
    }

    /* ========== режим RUN (как раньше) ========================= */
    private static void runFullPipeline(AppConfig cfg) throws Exception {

        /* ── 1. Чтение конфигов ───────────────────────────────── */
        long t0Total = System.nanoTime();

        List<InstanceConfig> servers = InstancesConfigReader.readConfig(cfg);
        List<QueryRequest>   queries = QueryRequestsReader.read(cfg);

        /* ── 2. Подготовка (пароли + обогащение строк) ────────── */
        InstanceConfigEnreacher.enrichWithPasswords(servers);
        ResponseProcessor resp = new ResponseProcessor(cfg.resultsDestination);

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(servers.size(), cfg.threadPoolSize));

        /* ── 3. Параллельный опрос всех серверов ──────────────── */
        long t0Exec = System.nanoTime();

        CompletableFuture.allOf(
                servers.stream()
                        .map(s -> new ServerRequest(s, queries, resp).execute(pool))
                        .toArray(CompletableFuture[]::new)
        ).join();

        long execMs = (System.nanoTime() - t0Exec) / 1_000_000;
        double avgPerSrv = servers.isEmpty() ? 0.0 : (double) execMs / servers.size();
        LogService.printf("[TIME] Parallel block: %d ms (≈ %.2f ms / server)%n",
                execMs, avgPerSrv);

        pool.shutdown();

        /* ── 4. Финальная статистика ──────────────────────────── */
        long totalMs = (System.nanoTime() - t0Total) / 1_000_000;
        LogService.printf("[TIME] runFullPipeline finished in %d ms (%.2f s)%n",
                totalMs, totalMs / 1000.0);
    }

    /* ========== режим SAVE_CONFIGS ============================= */
    private static void runSaveConfigs(AppConfig cfg) throws Exception {

        List<InstanceConfig> servers = InstancesConfigReader.readConfig(cfg);
        List<QueryRequest>   queries = QueryRequestsReader.read(cfg);

        // спросим пароли (чтобы в файле они уже были заполнены)
        InstanceConfigEnreacher.enrichWithPasswords(servers);

        String instFile = cfg.getServersFileName();
        String qFile    = cfg.getJobsFileName();

        InstancesConfigWriter.write(servers, instFile);
        QueryRequestsWriter.write(queries, qFile);

        LogService.printf("Configs saved: %s (%d servers), %s (%d queries)%n",
                instFile, servers.size(), qFile, queries.size());
    }
}
