import db.ServerRequest;
import model.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Главный класс приложения. Здесь происходит чтение конфигураций,
 * запуск запросов и завершение работы потоков.
 */
public class Main {

    /**
     * Точка входа в программу. Вызывается виртуальной машиной при старте
     * приложения. Ничего не возвращает.
     */
    public static void main(String[] args) throws Exception {

        String configFile = args.length > 0 ? args[0] : "MSSQLCollectorConfig.xml";
        AppConfig appConfig = AppConfigReaderWriter.readConfig(configFile);
        if (appConfig == null) {
            // Если файла нет — создаём с дефолтными значениями и читаем снова
            appConfig = new AppConfig();
            AppConfigReaderWriter.writeConfig(appConfig, configFile);
            System.out.println("Default config created: " + configFile);
        }
        // Читаем списки серверов и запросов из .....
        List<InstanceConfig> servers =InstancesConfigReader.readConfig(appConfig);
        List<QueryRequest> queries = QueryRequestsReader.read(appConfig);


        // Запрашиваем у пользователя пароли для тех конфигов,
        // в которых они не указаны.
        InstanceConfigEnreacher.enrichWithPasswords(servers);

        // Создаём пул потоков для параллельной работы с серверами.
        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(servers.size(), 16));

        // Для каждого сервера создаём асинхронный запрос и ждём окончания всех.
        CompletableFuture.allOf(
                servers.stream()
                        .map(cfg -> new ServerRequest(cfg, queries).execute(pool))
                        .toArray(CompletableFuture[]::new)
        ).join();

        // Корректно завершаем пул потоков.
        pool.shutdown();
    }
}
