package dev.borja.nosqlbridge.common.logging;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Copies exception types and stack frames for logging, never messages or payloads. */
public final class SafeLogException {
    private SafeLogException() {}

    public static Throwable snapshot(Throwable failure) {
        return copy(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static Throwable copy(Throwable failure, Set<Throwable> seen) {
        if (failure == null || seen.size() >= 32 || !seen.add(failure)) return null;
        Throwable safe = new Throwable(failure.getClass().getName());
        safe.setStackTrace(failure.getStackTrace());
        Throwable cause = copy(failure.getCause(), seen);
        if (cause != null) safe.initCause(cause);
        for (Throwable suppressed : failure.getSuppressed()) {
            Throwable safeSuppressed = copy(suppressed, seen);
            if (safeSuppressed != null) safe.addSuppressed(safeSuppressed);
        }
        return safe;
    }
}
