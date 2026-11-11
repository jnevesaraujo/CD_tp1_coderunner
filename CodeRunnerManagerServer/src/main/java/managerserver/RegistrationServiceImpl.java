package managerserver;

import coderunner.manager.RedisAddress;
import coderunner.manager.RegistrationServiceGrpc;
import coderunner.manager.ServerAddress;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.stub.StreamObserver;

public class RegistrationServiceImpl extends RegistrationServiceGrpc.RegistrationServiceImplBase {

    private final ServerRegistry registry;
    private final RedisAddress redisAddress;

    public RegistrationServiceImpl(ServerRegistry registry, RedisAddress redisAddress) {
        this.registry = registry;
        this.redisAddress = redisAddress;
    }

    @Override
    public void register(ServerAddress request, StreamObserver<RedisAddress> responseObserver) {
        if (request.getHost().isBlank() || request.getPort() <= 0) {
            responseObserver.onError(new StatusException(
                    Status.INVALID_ARGUMENT.withDescription("RunServer must send a host and a port")));
            return;
        }

        boolean isNew = registry.register(request);
        System.out.printf("RunServer %s:%d %s (total: %d)%n",
                request.getHost(), request.getPort(),
                isNew ? "registered" : "was already registered",
                registry.count());

        responseObserver.onNext(redisAddress);
        responseObserver.onCompleted();
    }
}