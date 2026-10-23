package dev.borja.nosqlbridge.runtime.work;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MigrationIdTest {

    @Test
    void acceptsValidUuid() {
        UUID uuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        MigrationId id = new MigrationId(uuid);

        assertEquals(uuid, id.value());
    }

    @Test
    void rejectsNullUuid() {
        assertThrows(IllegalArgumentException.class,
                () -> new MigrationId(null));
    }
}
