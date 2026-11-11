package client;

import coderunner.execution.ExecutionServiceGrpc;
import coderunner.execution.RequestId;
import coderunner.execution.ResultChunk;
import coderunner.execution.Status;
import coderunner.execution.SubmitChunk;
import coderunner.manager.Empty;
import coderunner.manager.LocatorServiceGrpc;
import coderunner.manager.ServerAddress;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Client for the CodeRunner system. */
public final class Client implements AutoCloseable {

    public static final String DEFAULT_MANAGER_HOST = "localhost";
    public static final int DEFAULT_MANAGER_PORT = 7000;

    private static final int CHUNK_SIZE = 64 * 1024; // 65 536 bytes
    private static final long RESULT_POLL_INTERVAL_MS = 200;
    private static final int MAX_REDIRECTS = 8;
    private static final int MAX_ATTEMPTS = 3;
    private static final long RPC_DEADLINE_SECONDS = 5;
    private static final long SUBMIT_TIMEOUT_SECONDS = 30;

    private final ManagedChannel managerChannel;
    private final LocatorServiceGrpc.LocatorServiceBlockingStub locator;

    public Client(String managerHost, int managerPort) {
        if (managerHost == null || managerHost.isBlank()) {
            throw new IllegalArgumentException("managerHost must not be blank");
        }
        if (managerPort <= 0 || managerPort > 65535) {
            throw new IllegalArgumentException("managerPort must be between 1 and 65535");
        }

        managerChannel = channel(managerHost, managerPort);
        locator = LocatorServiceGrpc.newBlockingStub(managerChannel);
    }

    // Ask the ManagerServer for a RunServer (contract b).
    public ServerAddress locateRunServer() {
        return locator.withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                .getServer(Empty.newBuilder().build());
    }

    // ---------------------------------------------------------------- submit

    // Stream a program and optional input to a RunServer (contract c).
    public Submission submit(Path programFile, Optional<Path> inputFile) throws Exception {
        requireRegularFile(programFile, "program");
        inputFile.ifPresent(path -> requireRegularFile(path, "input"));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ServerAddress runServer = locateRunServer();
                return submitToServer(programFile, inputFile, runServer);
            } catch (StatusRuntimeException exception) {
                if (!isUnavailable(exception) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                logRetry("submit", attempt);
            }
        }

        throw new IllegalStateException("submit attempts exhausted");
    }

    private Submission submitToServer(Path programFile,
                                      Optional<Path> inputFile,
                                      ServerAddress runServer) throws Exception {
        ManagedChannel runServerChannel = channel(runServer.getHost(), runServer.getPort());

        try {
            ExecutionServiceGrpc.ExecutionServiceStub execution =
                    ExecutionServiceGrpc.newStub(runServerChannel)
                            .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS);
            CompletableFuture<RequestId> requestId = new CompletableFuture<>();

            StreamObserver<RequestId> responseObserver = new StreamObserver<>() {
                @Override
                public void onNext(RequestId value) {
                    requestId.complete(value);
                }

                @Override
                public void onError(Throwable throwable) {
                    requestId.completeExceptionally(throwable);
                }

                @Override
                public void onCompleted() {
                    // The request id is delivered by onNext.
                }
            };

            StreamObserver<SubmitChunk> requestObserver = execution.submit(responseObserver);
            try {
                streamFile(requestObserver, programFile, true);
                if (inputFile.isPresent()) {
                    streamFile(requestObserver, inputFile.get(), false);
                }
                requestObserver.onCompleted();
            } catch (Throwable throwable) {
                requestObserver.onError(throwable);
                throw throwable;
            }

            RequestId id = awaitRequestId(requestId);

            return new Submission(id, runServer);

        } finally {
            shutdown(runServerChannel);
        }
    }

    // Poll until the request reaches a terminal state, following the owner if needed.
    public Result awaitResult(Submission submission) throws Exception {
        Objects.requireNonNull(submission, "submission");

        ServerAddress currentServer = submission.submittedTo();
        int redirects = 0;

        while (true) {
            Result result = fetchResult(currentServer, submission.requestId());

            if (result.owner() != null && !sameAddress(currentServer, result.owner())) {
                if (++redirects > MAX_REDIRECTS) {
                    throw new IOException("Too many RunServer redirects for request "
                            + submission.requestId().getId());
                }
                currentServer = result.owner();
                continue;
            }

            if (isTerminal(result.status())) {
                return result;
            }

            Thread.sleep(RESULT_POLL_INTERVAL_MS);
        }
    }

    // Locate a RunServer and obtain a result from a new Client instance.
    public Result awaitResult(RequestId requestId) throws Exception {
        Objects.requireNonNull(requestId, "requestId");

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return awaitResult(new Submission(requestId, locateRunServer()));
            } catch (StatusRuntimeException exception) {
                if (!isUnavailable(exception) || attempt == MAX_ATTEMPTS) {
                    throw exception;
                }
                logRetry("result", attempt);
            }
        }

        throw new IllegalStateException("result attempts exhausted");
    }

    // Fetch one result stream from a RunServer.
    public Result fetchResult(ServerAddress runServer, RequestId requestId) {
        Objects.requireNonNull(runServer, "runServer");
        Objects.requireNonNull(requestId, "requestId");

        ManagedChannel runServerChannel = channel(runServer.getHost(), runServer.getPort());
        try {
            Iterator<ResultChunk> chunks = ExecutionServiceGrpc
                    .newBlockingStub(runServerChannel)
                    .withDeadlineAfter(RPC_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .getResult(requestId);

            Status status = null;
            int exitCode = 0;
            ServerAddress owner = null;
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();

            while (chunks.hasNext()) {
                ResultChunk chunk = chunks.next();
                switch (chunk.getContentCase()) {
                    case STATUS -> {
                        if (status != null) {
                            throw new IllegalStateException("getResult returned more than one status");
                        }
                        status = chunk.getStatus().getStatus();
                        exitCode = chunk.getStatus().getExitCode();
                        owner = ownerAddress(chunk);
                    }
                    case STDOUT -> write(stdout, chunk.getStdout());
                    case STDERR -> write(stderr, chunk.getStderr());
                    case CONTENT_NOT_SET -> {
                        // Ignore empty protocol messages for forward compatibility.
                    }
                }
            }

            if (status == null) {
                throw new IllegalStateException("getResult returned no status");
            }

            return new Result(status, exitCode, stdout.toByteArray(), stderr.toByteArray(), owner);
        } finally {
            shutdown(runServerChannel);
        }
    }

    public static boolean isTerminal(Status status) {
        return status == Status.CONCLUIDO
                || status == Status.ERRO
                || status == Status.TIMEOUT
                || status == Status.DESCONHECIDO;
    }

    public static String describe(Status status) {
        return switch (status) {
            case PENDENTE -> "PENDENTE";
            case EM_EXECUCAO -> "EM_EXECUCAO";
            case CONCLUIDO -> "CONCLUIDO";
            case ERRO -> "ERRO";
            case TIMEOUT -> "TIMEOUT";
            case DESCONHECIDO -> "DESCONHECIDO";
            case UNRECOGNIZED -> "UNRECOGNIZED";
        };
    }

    public static void printResult(Result result, OutputStream stdout, OutputStream stderr)
            throws IOException {
        if (result.stdout().length > 0) {
            stdout.write(result.stdout());
            stdout.flush();
        }
        if (result.stderr().length > 0) {
            stderr.write(result.stderr());
            stderr.flush();
        }
    }

    @Override
    public void close() {
        shutdown(managerChannel);
    }

    private static RequestId awaitRequestId(CompletableFuture<RequestId> future) throws Exception {
        try {
            return future.get(SUBMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        } catch (TimeoutException exception) {
            throw new IOException("Timed out waiting for the RunServer request id", exception);
        }
    }

    // Send a file in chunks.
    private static void streamFile(StreamObserver<SubmitChunk> requestObserver,
                                   Path path,
                                   boolean program) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[CHUNK_SIZE];
            int count;
            while ((count = input.read(buffer)) != -1) {
                ByteString bytes = ByteString.copyFrom(buffer, 0, count);
                SubmitChunk.Builder chunk = SubmitChunk.newBuilder();
                if (program) {
                    chunk.setProgram(bytes);
                } else {
                    chunk.setInput(bytes);
                }
                requestObserver.onNext(chunk.build());
            }
        }
    }

    private static ServerAddress ownerAddress(ResultChunk chunk) {
        String host = chunk.getStatus().getOwnerHost();
        int port = chunk.getStatus().getOwnerPort();
        if (host == null || host.isBlank() || port <= 0) {
            return null;
        }
        return ServerAddress.newBuilder().setHost(host).setPort(port).build();
    }

    private static boolean sameAddress(ServerAddress first, ServerAddress second) {
        return first.getPort() == second.getPort()
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private static boolean isUnavailable(StatusRuntimeException exception) {
        return exception.getStatus().getCode() == io.grpc.Status.Code.UNAVAILABLE;
    }

    private static void logRetry(String operation, int attempt) {
        System.err.printf("%s failed with UNAVAILABLE (attempt %d/%d); retrying%n",
                operation, attempt, MAX_ATTEMPTS);
    }

    private static void write(ByteArrayOutputStream target, ByteString bytes) {
        try {
            bytes.writeTo(target);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to collect streamed output", exception);
        }
    }

    private static ManagedChannel channel(String host, int port) {
        return ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
    }

    // Close a gRPC channel gracefully.
    private static void shutdown(ManagedChannel channel) {
        channel.shutdown();
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow();
            }
        } catch (InterruptedException exception) {
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static void requireRegularFile(Path path, String description) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(description + " file does not exist: " + path);
        }
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  Client <program.py> [input.txt]");
        System.err.println("  Client <manager-host> <manager-port> <program.py> [input.txt]");
        System.err.println("  Client --no-wait <program.py> [input.txt]");
        System.err.println("  Client <manager-host> <manager-port> --no-wait <program.py> [input.txt]");
        System.err.println("  Client --result <id>");
        System.err.println("  Client <manager-host> <manager-port> --result <id>");
    }

    public static void main(String[] args) {
        if (args.length == 0 || "--help".equals(args[0])) {
            usage();
            return;
        }

        try {
            CommandLine commandLine = parseArguments(args);

            try (Client client = new Client(commandLine.managerHost(), commandLine.managerPort())) {
                if (commandLine.resultId() != null) {
                    Result result = client.awaitResult(commandLine.resultId());
                    System.err.printf("Estado: %s (exit code: %d)%n",
                            describe(result.status()), result.exitCode());
                    printResult(result, System.out, System.err);
                    return;
                }

                Submission submission = client.submit(
                        commandLine.programFile(), Optional.ofNullable(commandLine.inputFile()));
                if (commandLine.noWait()) {
                    System.out.println(submission.requestId().getId());
                    return;
                }

                System.err.printf("Pedido %s submetido a %s:%d%n",
                        submission.requestId().getId(),
                        submission.submittedTo().getHost(),
                        submission.submittedTo().getPort());

                Result result = client.awaitResult(submission);
                System.err.printf("Estado: %s (exit code: %d)%n",
                        describe(result.status()), result.exitCode());
                printResult(result, System.out, System.err);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println("Argument error: " + exception.getMessage());
            usage();
        } catch (StatusRuntimeException exception) {
            System.err.println("Erro gRPC: " + exception.getStatus());
        } catch (Exception exception) {
            System.err.println("Erro no Client: " + exception.getMessage());
        }
    }

    private static CommandLine parseArguments(String[] args) {
        String managerHost = DEFAULT_MANAGER_HOST;
        int managerPort = DEFAULT_MANAGER_PORT;
        int index = 0;

        if (args.length >= 3 && !args[0].startsWith("--") && isInteger(args[1])) {
            managerHost = args[0];
            managerPort = Integer.parseInt(args[1]);
            index = 2;
        }

        if (index >= args.length) {
            throw new IllegalArgumentException("missing command");
        }

        if ("--result".equals(args[index])) {
            if (args.length != index + 2) {
                throw new IllegalArgumentException("--result requires one request id");
            }
            RequestId requestId = RequestId.newBuilder().setId(args[index + 1]).build();
            return new CommandLine(managerHost, managerPort, null, null, false, requestId);
        }

        boolean noWait = "--no-wait".equals(args[index]);
        if (noWait) {
            index++;
        }

        int remaining = args.length - index;
        if (remaining < 1 || remaining > 2) {
            throw new IllegalArgumentException("expected a program and optional input file");
        }

        Path programFile = Path.of(args[index]);
        Path inputFile = remaining == 2 ? Path.of(args[index + 1]) : null;
        return new CommandLine(managerHost, managerPort, programFile, inputFile, noWait, null);
    }

    private static boolean isInteger(String value) {
        try {
            Integer.parseInt(value);
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private record CommandLine(String managerHost,
                               int managerPort,
                               Path programFile,
                               Path inputFile,
                               boolean noWait,
                               RequestId resultId) {
    }

    public record Submission(RequestId requestId, ServerAddress submittedTo) {
        public Submission {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(submittedTo, "submittedTo");
        }
    }

    public record Result(Status status,
                         int exitCode,
                         byte[] stdout,
                         byte[] stderr,
                         ServerAddress owner) {
        public Result {
            Objects.requireNonNull(status, "status");
            stdout = stdout == null ? new byte[0] : stdout.clone();
            stderr = stderr == null ? new byte[0] : stderr.clone();
        }

        @Override
        public byte[] stdout() {
            return stdout.clone();
        }

        @Override
        public byte[] stderr() {
            return stderr.clone();
        }
    }
}
