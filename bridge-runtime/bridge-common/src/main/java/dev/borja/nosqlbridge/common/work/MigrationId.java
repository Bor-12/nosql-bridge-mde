package dev.borja.nosqlbridge.common.work;

import java.util.UUID;

public record MigrationId(UUID value) {

    public MigrationId {
        if (value == null) {
            throw new IllegalArgumentException("Migration id must not be null.");
        }
    }
}