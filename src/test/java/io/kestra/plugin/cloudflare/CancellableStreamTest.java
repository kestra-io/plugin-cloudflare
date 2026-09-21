package io.kestra.plugin.cloudflare;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.models.property.Property;
import io.kestra.plugin.cloudflare.d1.Import;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A kill during a multi-minute dump or upload is only honoured because every read consults the latch.
 */
class CancellableStreamTest {

    private static Import task() {
        return Import.builder()
            .apiToken(Property.ofValue("test-token"))
            .accountId(Property.ofValue("test-account"))
            .databaseId(Property.ofValue("test-db"))
            .build();
    }

    private static ByteArrayInputStream source() {
        return new ByteArrayInputStream("CREATE TABLE t (id INTEGER);".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldReadThroughWhenNotCancelled() throws Exception {
        try (var in = task().cancellable(source(), "D1 import")) {
            assertArrayEquals("CREATE TABLE t (id INTEGER);".getBytes(StandardCharsets.UTF_8), in.readAllBytes());
        }
    }

    @Test
    void shouldFailTheReadOnceKilled() throws Exception {
        var task = task();

        try (var in = task.cancellable(source(), "D1 import")) {
            assertArrayEquals("CREATE".getBytes(StandardCharsets.UTF_8), in.readNBytes(6));

            task.kill();

            assertThrows(KilledException.class, in::readAllBytes);
        }
    }
}
