package managerserver;

import io.grpc.Server;

public class ShutdownHook extends Thread {

    private final Server server;

    public ShutdownHook(Server server) {
        this.server = server;
    }

    @Override
    public void run() {
        System.err.println("* Shutting down ManagerServer (JVM is exiting)");
        try {
            server.shutdown();
            server.awaitTermination();
        } catch (InterruptedException e) {
            e.printStackTrace(System.err);
        }
        System.err.println("* ManagerServer stopped");
    }
}