package runserver;

import java.nio.file.Files;
import java.nio.file.Path;

/** Temporary: pretends to run the program. Replace with ContainerExecutor when part A is ready. */
public class FakeRunner implements ProgramRunner {

    @Override
    public int run(String requestId, Path requestDir) throws Exception {
        Thread.sleep(3000); // pretend the program takes a while
        Files.writeString(requestDir.resolve("out.txt"), "fake output for " + requestId + "\n");
        Files.writeString(requestDir.resolve("err.txt"), "");
        Files.writeString(requestDir.resolve("exit.txt"), "0\n");
        return 0;
    }
}