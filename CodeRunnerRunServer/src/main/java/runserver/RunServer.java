package runserver;

import coderunner.manager.RedisAddress;
import coderunner.manager.RegistrationServiceGrpc;
import coderunner.manager.ServerAddress;
import container.ContainerExecutor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RunServer {

    private static String managerHost = "localhost";
    private static int managerPort = 7000;
    private static String myHost = "localhost";   // on GCP: this VM's EXTERNAL IP
    private static int myPort = 8500;
    private static int maxContainers = 4;

    public static void main(String[] args) {
        try {
            if (args.length > 0) managerHost = args[0];
            if (args.length > 1) managerPort = Integer.parseInt(args[1]);
            if (args.length > 2) myHost = args[2];
            if (args.length > 3) myPort = Integer.parseInt(args[3]);
            if (args.length > 4) maxContainers = Integer.parseInt(args[4]);

            // 1. Make sure the port is free BEFORE registering
            try (ServerSocket probe = new ServerSocket(myPort)) {
                // free: closes immediately
            } catch (IOException e) {
                System.err.println("Port " + myPort + " is already in use. Choose another port.");
                return;
            }

            // 2. Register with the ManagerServer and receive the Redis address
            ServerAddress me = ServerAddress.newBuilder().setHost(myHost).setPort(myPort).build();
            RedisAddress redisAddress = registerWithManager(me);
            System.out.printf("Registered with ManagerServer %s:%d, Redis at %s:%d%n",
                    managerHost, managerPort, redisAddress.getHost(), redisAddress.getPort());

            // 3. Connect to Redis
            RequestStore store = new RequestStore(
                    redisAddress.getHost(), redisAddress.getPort(), myHost + ":" + myPort);

            // 4. Folder for the requests' files (Docker needs an absolute path)
            Path workDir = Path.of("work").toAbsolutePath();
            Files.createDirectories(workDir);

            // 5. At most maxContainers executions at the same time; the rest wait as PENDENTE
            ExecutorService executionPool = Executors.newFixedThreadPool(maxContainers);
            ProgramRunner runner = new ContainerExecutor();

            // 6. Start the gRPC server
            Server server = ServerBuilder.forPort(myPort)
                    .addService(new ExecutionServiceImpl(store, runner, executionPool, workDir))
                    .build();
            server.start();
            System.out.printf("RunServer %s on port %d, work dir %s, max %d containers%n",
                    myHost, myPort, workDir, maxContainers);

            Runtime.getRuntime().addShutdownHook(new ShutdownHook(server, executionPool, store));
            server.awaitTermination();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static RedisAddress registerWithManager(ServerAddress me) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(managerHost, managerPort)
                .usePlaintext()
                .build();
        try {
            return RegistrationServiceGrpc.newBlockingStub(channel)
                    .withDeadlineAfter(5, TimeUnit.SECONDS)
                    .register(me);
        } finally {
            channel.shutdownNow();
        }
    }
}