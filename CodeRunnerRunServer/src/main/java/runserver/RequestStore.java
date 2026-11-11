package runserver;

import coderunner.execution.Status;
import redis.clients.jedis.RedisClient;

import java.util.Map;

public class RequestStore {

    private static final long TTL_SECONDS = 24 * 3600;

    private final RedisClient redis;
    private final String serverAddress; // "host:port" of this RunServer

    public RequestStore(String redisHost, int redisPort, String serverAddress) {
        this.redis = RedisClient.create(redisHost, redisPort);
        this.serverAddress = serverAddress;
        redis.ping(); // fails here, at startup, if Redis is unreachable
    }

    public String serverAddress() {
        return serverAddress;
    }

    private static String key(String id) {
        return "pedido:" + id;
    }

    public void createPending(String id) {
        String key = key(id);
        redis.hset(key, Map.of(
                "estado", Status.PENDENTE.name(),
                "servidor", serverAddress));
        redis.expire(key, TTL_SECONDS);
    }

    public void markRunning(String id) {
        redis.hset(key(id), "estado", Status.EM_EXECUCAO.name());
    }

    /** Stores the final state. Use exitCode -1 for internal failures (e.g. Docker unavailable). */
    public Status markFinished(String id, int exitCode) {
        Status status = statusFromExitCode(exitCode);
        redis.hset(key(id), Map.of(
                "estado", status.name(),
                "exitCode", String.valueOf(exitCode)));
        return status;
    }

    /** All fields of the request; an empty map if the id does not exist (or expired). */
    public Map<String, String> get(String id) {
        return redis.hgetAll(key(id));
    }

    public static Status statusFromExitCode(int exitCode) {
        return switch (exitCode) {
            case 0 -> Status.CONCLUIDO;
            case 124 -> Status.TIMEOUT;
            default -> Status.ERRO;
        };
    }

    public void close() {
        redis.close();
    }

    public void delete(String id) {
        redis.del(key(id));
    }
}