package runserver;

import coderunner.execution.ExecutionServiceGrpc;
import coderunner.execution.RequestId;
import coderunner.execution.Status;
import coderunner.execution.SubmitChunk;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import coderunner.execution.ResultChunk;
import coderunner.execution.StatusInfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class ExecutionServiceImpl extends ExecutionServiceGrpc.ExecutionServiceImplBase {

    private static final int CHUNK_SIZE = 32 * 1024;

    /** Ids whose result is being sent right now, so two clients can't deliver (and delete) the same one. */
    private final Set<String> delivering = ConcurrentHashMap.newKeySet();
    private final RequestStore store;
    private final ProgramRunner runner;
    private final ExecutorService executionPool;
    private final Path workDir;


    public ExecutionServiceImpl(RequestStore store, ProgramRunner runner,
                                ExecutorService executionPool, Path workDir) {
        this.store = store;
        this.runner = runner;
        this.executionPool = executionPool;
        this.workDir = workDir;
    }

    // ---------------------------------------------------------------- submit

    @Override
    public StreamObserver<SubmitChunk> submit(StreamObserver<RequestId> responseObserver) {
        return new StreamObserver<>() {
            private ByteString program = ByteString.EMPTY;
            private ByteString input = ByteString.EMPTY;

            @Override
            public void onNext(SubmitChunk chunk) {
                switch (chunk.getContentCase()) {
                    case PROGRAM -> program = program.concat(chunk.getProgram());
                    case INPUT -> input = input.concat(chunk.getInput());
                    default -> { } // empty chunk: ignore
                }
            }

            @Override
            public void onError(Throwable t) {
                // Client cancelled or the connection dropped: nothing was written yet, nothing to clean.
                System.err.println("submit cancelled by client: " + t.getMessage());
            }

            @Override
            public void onCompleted() {
                if (program.isEmpty()) {
                    responseObserver.onError(io.grpc.Status.INVALID_ARGUMENT
                            .withDescription("No program received").asRuntimeException());
                    return;
                }

                String id = UUID.randomUUID().toString();
                Path requestDir = workDir.resolve(id);
                try {
                    Files.createDirectories(requestDir);
                    Files.write(requestDir.resolve("prog.py"), program.toByteArray());
                    Files.write(requestDir.resolve("input.txt"), input.toByteArray()); // empty if no input
                    store.createPending(id);
                    executionPool.execute(() -> execute(id, requestDir));
                } catch (RejectedExecutionException e) {
                    store.markFinished(id, -1);
                    responseObserver.onError(io.grpc.Status.UNAVAILABLE
                            .withDescription("RunServer is shutting down").asRuntimeException());
                    return;
                } catch (Exception e) {
                    responseObserver.onError(io.grpc.Status.INTERNAL
                            .withDescription("Could not accept request: " + e.getMessage()).asRuntimeException());
                    return;
                }

                System.out.printf("[%s] accepted (%d bytes, %d bytes input)%n", id, program.size(), input.size());
                responseObserver.onNext(RequestId.newBuilder().setId(id).build());
                responseObserver.onCompleted();
            }
        };
    }

    /** Runs on a thread of executionPool, never on a gRPC thread. */
    private void execute(String id, Path requestDir) {
        try {
            store.markRunning(id);
            System.out.printf("[%s] running%n", id);
            int exitCode = runner.run(id, requestDir);
            Status status = store.markFinished(id, exitCode);
            System.out.printf("[%s] %s (exit code %d)%n", id, status, exitCode);
        } catch (Exception e) {
            System.err.printf("[%s] internal error: %s%n", id, e);
            try {
                Files.writeString(requestDir.resolve("err.txt"), "Internal error in RunServer: " + e.getMessage());
                store.markFinished(id, -1);
            } catch (Exception ignored) {
                // nothing more we can do; the Redis entry still expires in 24 h
            }
        }
    }

    // ------------------------------------------------------------- getResult

    @Override
    public void getResult(RequestId request, StreamObserver<ResultChunk> responseObserver) {
        String id = request.getId();
        try {
            Map<String, String> fields = store.get(id);

            // 1. Unknown, expired or already delivered
            if (fields.isEmpty()) {
                sendStatusAndComplete(StatusInfo.newBuilder().setStatus(Status.DESCONHECIDO), responseObserver);
                return;
            }

            Status status = Status.valueOf(fields.get("estado"));
            StatusInfo.Builder info = StatusInfo.newBuilder().setStatus(status);
            if (fields.containsKey("exitCode")) {
                info.setExitCode(Integer.parseInt(fields.get("exitCode")));
            }

            // 2. Executed on another RunServer: tell the client where to go
            String owner = fields.getOrDefault("servidor", "");
            if (!owner.equals(store.serverAddress())) {
                int colon = owner.lastIndexOf(':');
                info.setOwnerHost(owner.substring(0, colon))
                        .setOwnerPort(Integer.parseInt(owner.substring(colon + 1)));
                sendStatusAndComplete(info, responseObserver);
                return;
            }

            // 3. Mine but not finished yet: state only, keep everything
            boolean finished = status == Status.CONCLUIDO || status == Status.ERRO || status == Status.TIMEOUT;
            if (!finished) {
                sendStatusAndComplete(info, responseObserver);
                return;
            }

            // 4. Mine and finished: send the output, then clean up
            if (!delivering.add(id)) {
                responseObserver.onError(io.grpc.Status.FAILED_PRECONDITION
                        .withDescription("This result is already being delivered to another client").asRuntimeException());
                return;
            }
            try {
                Path requestDir = workDir.resolve(id);
                responseObserver.onNext(ResultChunk.newBuilder().setStatus(info).build());
                sendFile(requestDir.resolve("out.txt"), true, responseObserver);
                sendFile(requestDir.resolve("err.txt"), false, responseObserver);
                responseObserver.onCompleted();

                // Only reached if everything was sent: now the result is no longer needed
                store.delete(id);
                deleteDirectory(requestDir);
                System.out.printf("[%s] result delivered and removed%n", id);
            } finally {
                delivering.remove(id);
            }
        } catch (Exception e) {
            System.err.printf("[%s] getResult failed: %s%n", id, e);
            try {
                responseObserver.onError(io.grpc.Status.INTERNAL
                        .withDescription("Could not get result: " + e.getMessage()).asRuntimeException());
            } catch (Exception ignored) {
                // the call was already closed (e.g. client disconnected)
            }
        }
    }

    private static void sendStatusAndComplete(StatusInfo.Builder info, StreamObserver<ResultChunk> out) {
        out.onNext(ResultChunk.newBuilder().setStatus(info).build());
        out.onCompleted();
    }

    /** Streams a file in CHUNK_SIZE pieces as stdout or stderr. A missing file sends nothing. */
    private static void sendFile(Path file, boolean isStdout, StreamObserver<ResultChunk> out) throws IOException {
        if (!Files.exists(file)) return;
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                ByteString part = ByteString.copyFrom(buffer, 0, n);
                ResultChunk.Builder chunk = ResultChunk.newBuilder();
                if (isStdout) chunk.setStdout(part); else chunk.setStderr(part);
                out.onNext(chunk.build());
            }
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}