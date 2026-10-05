package db.redis;

import java.io.IOException;
import java.util.*;

/**
 * Превращает ответ Redis в таблицу.
 *
 *  INFO [section]        → section | metric_name | metric_value   (metric_name = redis_&lt;key&gt;)
 *                          строки db0:keys=1,expires=0 раскладываются в redis_db0_keys, redis_db0_expires ...
 *  CLUSTER INFO          → то же, section = cluster
 *  CLUSTER NODES         → node_id | node | flags | role | master_id | ping_sent | pong_recv | config_epoch | link_state | slots
 *  CONFIG GET / HGETALL  → name | value
 *  прочее                → value (скаляр) или idx | value (массив; вложенные массивы — через пробел)
 *
 *  TOPOLOGY (встроенное) → mode | node | role | master | link_state | slots | flags | cluster_state
 */
public final class RedisResultMapper {

    private RedisResultMapper() {}

    public static RowSetTable map(List<String> cmd, Object reply) {
        String c0 = cmd.isEmpty() ? "" : cmd.get(0).toUpperCase(Locale.ROOT);
        String c1 = cmd.size() > 1 ? cmd.get(1).toUpperCase(Locale.ROOT) : "";

        if (c0.equals("INFO") && reply instanceof String s) {
            return infoTable(s, null);
        }
        if (c0.equals("CLUSTER") && c1.equals("INFO") && reply instanceof String s) {
            return infoTable(s, "cluster");
        }
        if (c0.equals("CLUSTER") && (c1.equals("NODES") || c1.equals("REPLICAS") || c1.equals("SLAVES"))
                && reply instanceof String s) {
            return clusterNodesTable(s);
        }
        if (((c0.equals("CONFIG") && c1.equals("GET")) || c0.equals("HGETALL"))
                && reply instanceof List<?> l) {
            RowSetTable t = new RowSetTable("name", "value");
            for (int i = 0; i + 1 < l.size(); i += 2) t.add(str(l.get(i)), str(l.get(i + 1)));
            return t;
        }
        if (reply instanceof List<?> l) {
            RowSetTable t = new RowSetTable("idx", "value");
            for (int i = 0; i < l.size(); i++) t.add(String.valueOf(i), str(l.get(i)));
            return t;
        }
        return new RowSetTable("value").add(str(reply));
    }

    /* ============================ INFO ============================ */

    public static Map<String, String> parseInfo(String text) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String l : text.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && !l.startsWith("#")) m.put(l.substring(0, i), l.substring(i + 1).trim());
        }
        return m;
    }

    static RowSetTable infoTable(String text, String fixedSection) {
        RowSetTable t = new RowSetTable("section", "metric_name", "metric_value");
        String section = fixedSection == null ? "" : fixedSection;
        for (String raw : text.split("\r?\n")) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#")) {
                if (fixedSection == null) section = l.substring(1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String key = l.substring(0, i);
            String val = l.substring(i + 1).trim();

            // Keyspace: db0:keys=1,expires=0,avg_ttl=0 → redis_db0_keys ...
            if (section.equals("keyspace") && val.contains("=")) {
                for (String kv : val.split(",")) {
                    int e = kv.indexOf('=');
                    if (e > 0) t.add(section, "redis_" + key + "_" + kv.substring(0, e), kv.substring(e + 1));
                }
                continue;
            }
            t.add(section, "redis_" + key, val);
        }
        return t;
    }

    /* ============================ CLUSTER NODES ============================ */

    /** Строка CLUSTER NODES: id addr flags master ping pong epoch link [slots...] */
    record ClusterNode(String id, String addr, String flags, String masterId,
                       String ping, String pong, String epoch, String link, String slots) {
        boolean isMaster() { return flags.contains("master"); }
        boolean isMyself() { return flags.contains("myself"); }
    }

    static List<ClusterNode> parseClusterNodes(String text) {
        List<ClusterNode> list = new ArrayList<>();
        for (String nl : text.split("\n")) {
            String[] f = nl.trim().split(" ");
            if (f.length < 8) continue;
            String addr = f[1].split("@")[0];     // ip:port@cport[,hostname]
            String slots = f.length > 8 ? String.join(" ", Arrays.copyOfRange(f, 8, f.length)) : "";
            list.add(new ClusterNode(f[0], addr, f[2], f[3], f[4], f[5], f[6], f[7], slots));
        }
        return list;
    }

    static RowSetTable clusterNodesTable(String text) {
        RowSetTable t = new RowSetTable("node_id", "node", "flags", "role", "master_id",
                "ping_sent", "pong_recv", "config_epoch", "link_state", "slots");
        for (ClusterNode n : parseClusterNodes(text)) {
            t.add(n.id(), n.addr(), n.flags(), n.isMaster() ? "master" : "slave",
                    "-".equals(n.masterId()) ? "" : n.masterId(),
                    n.ping(), n.pong(), n.epoch(), n.link(), n.slots());
        }
        return t;
    }

    /* ============================ TOPOLOGY ============================ */

    /**
     * Встроенное задание TOPOLOGY: кто мастер, кто реплика, состав кластера.
     * Делает несколько запросов (INFO server, INFO replication, CLUSTER INFO/NODES, SENTINEL MASTERS).
     */
    public static RowSetTable topology(RespClient c, String self) throws IOException {
        RowSetTable t = new RowSetTable("mode", "node", "role", "master", "link_state",
                "slots", "flags", "cluster_state");

        Map<String, String> server = parseInfo(asText(c.command("INFO", "server"), "INFO server"));
        String mode = server.getOrDefault("redis_mode", "standalone");

        if (mode.equals("sentinel")) {
            Object masters = c.command("SENTINEL", "MASTERS");
            if (masters instanceof List<?> l) {
                for (Object o : l) {
                    if (!(o instanceof List<?> kvl)) continue;
                    Map<String, String> kv = new HashMap<>();
                    for (int i = 0; i + 1 < kvl.size(); i += 2) kv.put(str(kvl.get(i)), str(kvl.get(i + 1)));
                    t.add(mode, kv.get("ip") + ":" + kv.get("port"), "master", "", "",
                            "", "monitored_by=" + self + " name=" + kv.get("name")
                                    + " replicas=" + kv.get("num-slaves")
                                    + " sentinels=" + kv.get("num-other-sentinels")
                                    + " flags=" + kv.get("flags"), "");
                }
            } else {
                throw new IOException("SENTINEL MASTERS: " + masters);
            }
            return t;
        }

        if (mode.equals("cluster")) {
            Map<String, String> ci = parseInfo(asText(c.command("CLUSTER", "INFO"), "CLUSTER INFO"));
            String state = ci.getOrDefault("cluster_state", "");
            List<ClusterNode> nodes = parseClusterNodes(asText(c.command("CLUSTER", "NODES"), "CLUSTER NODES"));
            Map<String, String> addrById = new HashMap<>();
            for (ClusterNode n : nodes) addrById.put(n.id(), n.addr());
            for (ClusterNode n : nodes) {
                t.add(mode, n.addr(), n.isMaster() ? "master" : "slave",
                        n.isMaster() ? "" : addrById.getOrDefault(n.masterId(), n.masterId()),
                        n.link(), n.slots(), n.flags(), state);
            }
            return t;
        }

        // standalone (возможно с репликами)
        Map<String, String> repl = parseInfo(asText(c.command("INFO", "replication"), "INFO replication"));
        String role = repl.getOrDefault("role", "");
        if (role.equals("slave")) {
            t.add(mode, self, "slave", repl.get("master_host") + ":" + repl.get("master_port"),
                    repl.get("master_link_status"), "", "myself", "");
        } else {
            int n = parseInt(repl.get("connected_slaves"));
            t.add(mode, self, role, "", "", "", "myself replicas=" + n, "");
            for (int k = 0; k < n; k++) {
                // slave0:ip=10.0.0.2,port=6379,state=online,offset=123,lag=0
                Map<String, String> s = new HashMap<>();
                String v = repl.get("slave" + k);
                if (v == null) continue;
                for (String kv : v.split(",")) {
                    int e = kv.indexOf('=');
                    if (e > 0) s.put(kv.substring(0, e), kv.substring(e + 1));
                }
                t.add(mode, s.get("ip") + ":" + s.get("port"), "slave", self,
                        s.get("state"), "", "lag=" + s.get("lag"), "");
            }
        }
        return t;
    }

    /* ============================ helpers ============================ */

    private static String asText(Object reply, String what) throws IOException {
        if (reply instanceof String s) return s;
        throw new IOException(what + ": " + reply);
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s == null ? "0" : s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    static String str(Object o) {
        if (o == null) return null;
        if (o instanceof List<?> l) {
            StringJoiner j = new StringJoiner(" ");
            for (Object x : l) j.add(String.valueOf(str(x)));
            return j.toString();
        }
        return String.valueOf(o);
    }
}
