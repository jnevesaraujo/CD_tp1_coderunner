package managerserver;

import coderunner.manager.RedisAddress;
import io.grpc.Server;
import io.grpc.ServerBuilder;

public class ManagerServer {

    private static int port = 7000;
    private static String redisHost = "localhost";
    private static int redisPort = 6000;

    public static void main(String[] args) {
        try {
            if (args.length > 0) port = Integer.parseInt(args[0]);
            if (args.length > 1) redisHost = args[1];
            if (args.length > 2) redisPort = Integer.parseInt(args[2]);

            ServerRegistry registry = new ServerRegistry();
            RedisAddress redisAddress = RedisAddress.newBuilder()
                    .setHost(redisHost)
                    .setPort(redisPort)
                    .build();

            Server server = ServerBuilder.forPort(port)
                    .addService(new RegistrationServiceImpl(registry, redisAddress))
                    .addService(new LocatorServiceImpl(registry))
                    .build();
            server.start();

            System.out.printf("ManagerServer on port %d, Redis at %s:%d%n", port, redisHost, redisPort);
            Runtime.getRuntime().addShutdownHook(new ShutdownHook(server));
            server.awaitTermination();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}