import db.ServerRequest;
import model.*;

import java.util.*;
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {

        // читаем конфиги серверов и запросов
        List<InstanceConfig> servers =
                InstancesConfigReader.readConfig("InstancesConfig.xml");
        List<QueryRequest> queries =
                QueryRequestsReader.read("QueryRequests.xml");

        // запрашиваем недостающие пароли
        InstanceConfigEnreacher.enrichWithPasswords(servers);

        ExecutorService pool = Executors.newFixedThreadPool(
                Math.min(servers.size(), 16));

        CompletableFuture.allOf(
                servers.stream()
                        .map(cfg -> new ServerRequest(cfg, queries).execute(pool))
                        .toArray(CompletableFuture[]::new)
        ).join();

        pool.shutdown();
    }
}
