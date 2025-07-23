import db.ServerRequest;
import logging.LogService;
import model.*;
import processor.ResponseProcessor;

import java.security.Security;          // <‑‑ NEW
import java.util.List;
import java.util.concurrent.*;

/** Точка входа приложения. */
public class Main {

    /* ------------------------------------------------------------------
       Глобально снимаем ВСЕ ограничения Java‑TLS для данного процесса.
       Делается один раз при загрузке класса Main, ещё до метода main().
       ------------------------------------------------------------------ */
    static {
        // 1. Запреты на использования «опасных» алгоритмов в канале TLS
        Security.setProperty("jdk.tls.disabledAlgorithms", "");

        // 2. Запреты на алгоритмы у сертификатов (SHA‑1, MD5 и т.д.)
        Security.setProperty("jdk.certpath.disabledAlgorithms", "");

        // 3. (опционально) блокировки в Jar‑подписях
        Security.setProperty("jdk.jar.disabledAlgorithms", "");

        LogService.println("⚠ TLS restrictions are DISABLED for this run");
    }

    public static void main(String[] args) throws Exception {

        String cfgFile = args.length > 0 ? args[0] : "MSSQLCollectorConfig.xml";
        AppConfig cfg = AppConfigReader.read(cfgFile);
        if (cfg == null) {
            cfg = new AppConfig();                  // дефолт
            AppConfigWriter.write(cfg, cfgFile);
            LogService.println("Default config created: " + cfgFile);
        }

        switch (cfg.taskName.toUpperCase()) {
            case "SAVE_CONFIGS"        -> runSaveConfigs(cfg);
            case "PROCESS_XML_RESULT"  -> LogService.println("Task PROCESS_XML_RESULT not implemented yet.");
            default                    -> runFullPipeline(cfg);   // RUN (по умолчанию)
        }
    }

    /* ========== режим RUN (как раньше) ========================= */
    private static void runFullPipeline(AppConfig cfg) throws Exception {

        List<InstanceConfig> servers = InstancesConfigReader.readConfig(cfg);
        List<QueryRequest>   queries = QueryRequestsReader.read(cfg);

        // пароли + enrich строк подключения
        InstanceConfigEnreacher.enrichWithPasswords(servers);

        ResponseProcessor resp = new ResponseProcessor(cfg.resultsDestination);

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(servers.size(), cfg.threadPoolSize));

        CompletableFuture.allOf(
                servers.stream()
                        .map(s -> new ServerRequest(s, queries, resp).execute(pool))
                        .toArray(CompletableFuture[]::new)
        ).join();

        pool.shutdown();
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
