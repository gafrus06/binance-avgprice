package com.example.prices;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** One polling instance owns this directory. Pending sample and cooldown survive process restarts. */
@Component
public class PollStateStore {
    public record State(PriceSample pending, Instant nextAttempt, int failures) {
        static State empty() { return new State(null, Instant.EPOCH, 0); }
    }
    private final Path directory;
    private final ObjectMapper mapper;

    public PollStateStore(BinanceProperties properties, ObjectMapper mapper) {
        this.directory = properties.stateDir();
        this.mapper = mapper;
    }

    public State read() throws IOException {
        Path file = directory.resolve("poll-state.json");
        return Files.exists(file) ? mapper.readValue(file.toFile(), State.class) : State.empty();
    }

    public void write(State state) throws IOException {
        Files.createDirectories(directory);
        Path temp = directory.resolve("poll-state.tmp");
        byte[] bytes = mapper.writeValueAsBytes(state);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        // Fail closed if the filesystem cannot atomically replace a file.
        Files.move(temp, directory.resolve("poll-state.json"),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
