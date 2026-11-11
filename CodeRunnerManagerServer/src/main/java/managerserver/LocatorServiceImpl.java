package managerserver;

import coderunner.manager.Empty;
import coderunner.manager.LocatorServiceGrpc;
import coderunner.manager.ServerAddress;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

public class LocatorServiceImpl extends LocatorServiceGrpc.LocatorServiceImplBase {

    private final ServerRegistry registry;

    public LocatorServiceImpl(ServerRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void getServer(Empty request, StreamObserver<ServerAddress> responseObserver) {
        ServerAddress server = registry.nextServer();
        if (server == null) {
            responseObserver.onError(new StatusException(
                    Status.UNAVAILABLE.withDescription("No RunServer registered yet")));
            return;
        }

        System.out.printf("Client sent to %s:%d%n", server.getHost(), server.getPort());
        responseObserver.onNext(server);
        responseObserver.onCompleted();
    }
}