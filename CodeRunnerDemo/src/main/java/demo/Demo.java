package demo;

import client.Client;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs several Clients concurrently. */
public final class Demo {

    private static final List<String> PROGRAM_NAMES = List.of(
            "prog.py", "memory.py", "loop.py");
    private static final Object OUTPUT_LOCK = new Object();

    private Demo() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 4 || (args.length > 0 && "--help".equals(args[0]))) {
            usage();
            return;
        }

        String managerHost = args.length >= 1 ? args[0] : Client.DEFAULT_MANAGER_HOST;
        int managerPort = args.length >= 2
                ? Integer.parseInt(args[1])
                : Client.DEFAULT_MANAGER_PORT;
        Path programDirectory = args.length >= 3
                ? Path.of(args[2])
                : Path.of("demo-programs");
        int clients = args.length >= 4 ? Integer.parseInt(args[3]) : 8;

        if (clients <= 0) {
            throw new IllegalArgumentException("number of clients must be positive");
        }

        // Load the three demo programs and start the client tasks.
        List<Path> programs = loadPrograms(programDirectory);
        System.out.printf("A iniciar %d clientes concorrentes através de %s:%d%n",
                clients, managerHost, managerPort);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(clients, 16));
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < clients; i++) {
                int clientNumber = i + 1;
                Path program = programs.get(i % programs.size());
                futures.add(executor.submit(() -> runOne(
                        clientNumber, managerHost, managerPort, programDirectory, program)));
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (ExecutionException exception) {
                    synchronized (OUTPUT_LOCK) {
                        System.err.println("Cliente Demo terminou com erro: "
                                + exception.getCause().getMessage());
                    }
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static void runOne(int clientNumber,
                               String managerHost,
                               int managerPort,
                               Path programDirectory,
                               Path program) {
        // Submit and wait for one program.
        Path input = inputFor(programDirectory, program);

        try (Client client = new Client(managerHost, managerPort)) {
            Client.Submission submission = client.submit(program, Optional.ofNullable(input));
            Client.Result result = client.awaitResult(submission);

            synchronized (OUTPUT_LOCK) {
                System.out.printf("[%d] %s -> %s, exit=%d, servidor=%s:%d%n",
                        clientNumber,
                        program.getFileName(),
                        Client.describe(result.status()),
                        result.exitCode(),
                        submission.submittedTo().getHost(),
                        submission.submittedTo().getPort());
                if (result.stdout().length > 0) {
                    System.out.printf("[%d] stdout:%n%s%n",
                            clientNumber, new String(result.stdout(), StandardCharsets.UTF_8));
                }
                if (result.stderr().length > 0) {
                    System.err.printf("[%d] stderr:%n%s%n",
                            clientNumber, new String(result.stderr(), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException("cliente " + clientNumber + " ("
                    + program.getFileName() + "): " + exception.getMessage(), exception);
        }
    }

    // Require the three demo programs.
    private static List<Path> loadPrograms(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("program directory does not exist: " + directory);
        }

        List<Path> programs = PROGRAM_NAMES.stream()
                .map(directory::resolve)
                .peek(path -> {
                    if (!Files.isRegularFile(path)) {
                        throw new IllegalArgumentException("missing demo program: " + path);
                    }
                })
                .toList();
        return programs;
    }

    // Use input only for prog.py.
    private static Path inputFor(Path directory, Path program) {
        if (!"prog.py".equals(program.getFileName().toString())) {
            return null;
        }

        Path input = directory.resolve("input.txt");
        return Files.isRegularFile(input) ? input : null;
    }

    private static void usage() {
        System.err.println("Usage:");
        System.err.println("  Demo [manager-host] [manager-port] [program-directory] [number-of-clients]");
        System.err.println();
        System.err.println("The directory must contain prog.py, memory.py and loop.py.");
    }
}
