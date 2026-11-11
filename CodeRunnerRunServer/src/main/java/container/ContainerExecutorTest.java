package container;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Test class to validate ContainerExecutor functionality locally.
 */
public class ContainerExecutorTest {

    public static void main(String[] args) {
        try {
            // Define the local working directory for the test request
            Path requestDir = Path.of("work").toAbsolutePath().normalize();
            Files.createDirectories(requestDir);

            // Create test files simulating a client submission
            String pythonCodeSum = "import sys\n" +
                    "numeros = [int(x) for x in sys.stdin.read().split()]\n" +
                    "print(sum(numeros))";

            String pythonCodeLoop = "i = 0\n" +
                    "while True:\n" +
                    "    i += 1\n" +
                    "    if i % 10_000_000 == 0:\n" +
                    "        print(f\"iteracao {i}\", flush=True)";

            String pythonCodeMemory = "blocos = []\n" +
                    "while True:\n" +
                    "    blocos.append(bytearray(10 * 1024 * 1024))";

            String pythonCodeError = "print(1 / 0)";

            //Files.writeString(requestDir.resolve("prog.py"), pythonCodeSum);
            Files.writeString(requestDir.resolve("prog.py"), pythonCodeLoop);
            //Files.writeString(requestDir.resolve("prog.py"), pythonCodeMemory);
            //Files.writeString(requestDir.resolve("prog.py"), pythonCodeError);
            Files.writeString(requestDir.resolve("input.txt"), "10 20 30");

            // Instantiate the executor (using the empty constructor required by RunServer)
            ContainerExecutor executor = new ContainerExecutor();
            String requestId = "test-local-01";

            System.out.println("Starting container for request: " + requestId);

            // Invoke the run method
            int exitCode = executor.run(requestId, requestDir);

            System.out.println("Execution completed. Exit code: " + exitCode);

            // Verify the generated results in the directory
            System.out.println("--- out.txt ---");
            System.out.print(Files.readString(requestDir.resolve("out.txt")));

            System.out.println("--- err.txt ---");
            System.out.print(Files.readString(requestDir.resolve("err.txt")));

            System.out.println("--- exit.txt ---");
            System.out.print(Files.readString(requestDir.resolve("exit.txt")));

        } catch (Exception e) {
            System.err.println("Error during executor test:");
            e.printStackTrace();
        }
    }
}