package managerserver;

import coderunner.manager.ServerAddress;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerRegistry {

    private final CopyOnWriteArrayList<ServerAddress> servers = new CopyOnWriteArrayList<>();
    private final AtomicInteger next = new AtomicInteger(0);

    /** Returns true if the server is new, false if it was already registered. */
    public boolean register(ServerAddress server) {
        return servers.addIfAbsent(server);
    }

    /** Next server in round-robin order, or null if none is registered yet. */
    public ServerAddress nextServer() {
        int size = servers.size();
        if (size == 0) return null;
        int index = Math.floorMod(next.getAndIncrement(), size);
        return servers.get(index);
    }

    public int count() {
        return servers.size();
    }

    public List<ServerAddress> all() {
        return List.copyOf(servers);
    }
}