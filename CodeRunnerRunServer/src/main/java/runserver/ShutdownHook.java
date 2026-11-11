package runserver;

import io.grpc.Server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ShutdownHook extends Thread {

    private final Server server;
    private final ExecutorService executionPool;
    private final RequestStore store;

    public ShutdownHook(Server server, ExecutorService executionPool, RequestStore store) {
        this.server = server;
        this.executionPool = executionPool;
        this.store = store;
    }

    @Override
    public void run() {
        System.err.println("* Shutting down RunServer");
        try {
            server.shutdown();                                 // stop accepting new requests
            executionPool.shutdown();                          // no new executions...
            if (!executionPool.awaitTermination(30, TimeUnit.SECONDS)) {  // ...but finish the running ones
                executionPool.shutdownNow();
            }
            server.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        } finally {
            store.close();
        }
        System.err.println("* RunServer stopped");
    }
}