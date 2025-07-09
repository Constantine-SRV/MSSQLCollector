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

        // Читаем списки серверов и запросов из XML-файлов.
        List<InstanceConfig> servers =
                InstancesConfigReader.readConfig("InstancesConfig.xml");
        List<QueryRequest> queries =
                QueryRequestsReader.read("QueryRequests.xml");

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
