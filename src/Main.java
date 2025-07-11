import db.ServerRequest;
import model.*;
import processor.ResponseProcessor;

import java.util.*;
import java.util.concurrent.*;

/**
 * Главный класс приложения.
 */
public class Main {

    public static void main(String[] args) throws Exception {



        String configFile = args.length > 0 ? args[0] : "MSSQLCollectorConfig.xml";
        AppConfig appConfig = AppConfigReaderWriter.readConfig(configFile);
        if (appConfig == null) {
            // Если файла нет — создаём с дефолтными значениями и читаем снова
            appConfig = new AppConfig();
            AppConfigReaderWriter.writeConfig(appConfig, configFile);
            System.out.println("Default config created: " + configFile);
        }

        // Чтение серверов и запросов
        List<InstanceConfig> servers = InstancesConfigReader.readConfig(appConfig);
        List<QueryRequest> queries = QueryRequestsReader.read(appConfig);

        // Универсальный обработчик результатов (через конфиг)
        ResponseProcessor respProcessor = new ResponseProcessor(appConfig.resultsDestination);

        // Запрашиваем у пользователя пароли для тех конфигов, в которых они не указаны.
        InstanceConfigEnreacher.enrichWithPasswords(servers);
        long t0 = System.nanoTime(); // ← стартуем секундомер
        // Пул потоков для параллельной работы
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(servers.size(), 16));

        // Для каждого сервера создаём ServerRequest с respProcessor
        CompletableFuture.allOf(
                servers.stream()
                        .map(cfg -> new ServerRequest(cfg, queries, respProcessor).execute(pool))
                        .toArray(CompletableFuture[]::new)
        ).join();

        pool.shutdown();

        // ==== Выводим время выполнения ====
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("Done. Elapsed: %d ms (%.2f s)%n", elapsedMs, elapsedMs / 1000.0);
    }
}
