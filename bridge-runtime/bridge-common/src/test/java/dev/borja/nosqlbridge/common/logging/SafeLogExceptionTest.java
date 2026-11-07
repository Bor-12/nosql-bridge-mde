package dev.borja.nosqlbridge.common.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeLogExceptionTest {
    @Test
    void retainsTypesFramesCausesAndSuppressedFailuresWithoutMessagesOrMutatingTheOriginal() {
        IllegalArgumentException cause = new IllegalArgumentException("secret-document-value");
        IllegalStateException original = new IllegalStateException("mongodb://user:password@host", cause);
        original.addSuppressed(new RuntimeException("secret-sqs-payload"));
        Throwable safe = SafeLogException.snapshot(original);
        StringWriter text = new StringWriter();
        safe.printStackTrace(new PrintWriter(text));
        assertTrue(text.toString().contains(IllegalStateException.class.getName()));
        assertTrue(text.toString().contains(IllegalArgumentException.class.getName()));
        assertTrue(text.toString().contains("SafeLogExceptionTest.java"));
        assertFalse(text.toString().contains("secret"));
        assertFalse(text.toString().contains("password"));
        assertArrayEquals(original.getStackTrace(), safe.getStackTrace());
        assertSame(cause, original.getCause());
        assertEquals("mongodb://user:password@host", original.getMessage());
        assertEquals(1, safe.getSuppressed().length);
    }

    @Test
    void handlesNullAndCircularCauseGraphs() {
        assertNull(SafeLogException.snapshot(null));
        RuntimeException first = new RuntimeException("one");
        RuntimeException second = new RuntimeException("two");
        first.initCause(second);
        second.initCause(first);
        Throwable safe = SafeLogException.snapshot(first);
        assertNotNull(safe.getCause());
        assertNull(safe.getCause().getCause());
    }
}
