package runserver;

import java.nio.file.Path;

/** Runs prog.py in requestDir, writes out.txt, err.txt and exit.txt there, and returns the exit code. */
public interface ProgramRunner {
    int run(String requestId, Path requestDir) throws Exception;
}