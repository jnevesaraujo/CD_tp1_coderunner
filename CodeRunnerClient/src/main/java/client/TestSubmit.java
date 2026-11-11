package client;

import coderunner.execution.ExecutionServiceGrpc;
import coderunner.execution.RequestId;
import coderunner.execution.SubmitChunk;
import coderunner.manager.Empty;
import coderunner.manager.LocatorServiceGrpc;
import coderunner.manager.ServerAddress;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class TestSubmit {

    public static void main(String[] args) throws Exception {
        String program = "import sys\nnums = [int(x) for x in sys.stdin.read().split()]\nprint(sum(nums))\n";
        String input = "10 20 30 40\n";

        // 1. Ask the Manager for a RunServer
        ManagedChannel managerChannel = ManagedChannelBuilder.forAddress("localhost", 7000).usePlaintext().build();
        ServerAddress server = LocatorServiceGrpc.newBlockingStub(managerChannel).getServer(Empty.getDefaultInstance());
        managerChannel.shutdownNow();
        System.out.println("Using RunServer " + server.getHost() + ":" + server.getPort());

        // 2. Submit the program in small chunks
        ManagedChannel channel = ManagedChannelBuilder.forAddress(server.getHost(), server.getPort()).usePlaintext().build();
        CompletableFuture<String> idFuture = new CompletableFuture<>();

        StreamObserver<SubmitChunk> requests = ExecutionServiceGrpc.newStub(channel).submit(new StreamObserver<>() {
            @Override public void onNext(RequestId reply) { idFuture.complete(reply.getId()); }
            @Override public void onError(Throwable t) { idFuture.completeExceptionally(t); }
            @Override public void onCompleted() { }
        });
        sendInChunks(requests, program.getBytes(), true);
        sendInChunks(requests, input.getBytes(), false);
        requests.onCompleted();

        System.out.println("Request id: " + idFuture.get(10, TimeUnit.SECONDS));
        channel.shutdown();
    }

    private static void sendInChunks(StreamObserver<SubmitChunk> requests, byte[] data, boolean isProgram) {
        int chunkSize = 16; // tiny on purpose, to prove the server joins the chunks correctly
        for (int i = 0; i < data.length; i += chunkSize) {
            ByteString part = ByteString.copyFrom(data, i, Math.min(chunkSize, data.length - i));
            SubmitChunk.Builder chunk = SubmitChunk.newBuilder();
            if (isProgram) chunk.setProgram(part); else chunk.setInput(part);
            requests.onNext(chunk.build());
        }
    }
}