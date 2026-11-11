package client;

import coderunner.execution.ExecutionServiceGrpc;
import coderunner.execution.RequestId;
import coderunner.execution.ResultChunk;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.Iterator;

public class TestGetResult {

    private static final String ID = "1f4efabd-731c-4f18-a69d-8b1d3e0913e9";
    private static final String HOST = "localhost";
    private static final int PORT = 8500;

    public static void main(String[] args) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(HOST, PORT).usePlaintext().build();
        Iterator<ResultChunk> chunks = ExecutionServiceGrpc.newBlockingStub(channel)
                .getResult(RequestId.newBuilder().setId(ID).build());

        ByteString stdout = ByteString.EMPTY;
        ByteString stderr = ByteString.EMPTY;
        while (chunks.hasNext()) {
            ResultChunk chunk = chunks.next();
            switch (chunk.getContentCase()) {
                case STATUS -> System.out.println("Status: " + chunk.getStatus());
                case STDOUT -> stdout = stdout.concat(chunk.getStdout());
                case STDERR -> stderr = stderr.concat(chunk.getStderr());
                default -> { }
            }
        }
        System.out.println("--- stdout ---\n" + stdout.toStringUtf8());
        System.out.println("--- stderr ---\n" + stderr.toStringUtf8());
        channel.shutdown();
    }
}