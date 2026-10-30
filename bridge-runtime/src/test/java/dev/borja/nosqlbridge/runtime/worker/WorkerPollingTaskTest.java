package dev.borja.nosqlbridge.runtime.worker;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkerPollingTaskTest {

    @Mock
    private WorkerConsumer consumer;

    @Test
    void continuesPollingAfterAnEmptyQueue() {
        when(consumer.processOne()).thenReturn(Optional.empty());
        WorkerPollingTask task = new WorkerPollingTask(consumer);

        task.poll();
        task.poll();

        verify(consumer, times(2)).processOne();
    }

    @Test
    void keepsRunningAfterFailuresWithoutLoggingExceptionContents(CapturedOutput output) {
        when(consumer.processOne()).thenThrow(new IllegalStateException("sensitive-plan-content"))
                .thenReturn(Optional.empty());
        WorkerPollingTask task = new WorkerPollingTask(consumer);

        task.poll();
        task.poll();

        verify(consumer, times(2)).processOne();
        assertTrue(output.getAll().contains("java.lang.IllegalStateException"));
        assertFalse(output.getAll().contains("sensitive-plan-content"));
    }

    @Test
    void signalsConsumerToStopReceivingOnContextClose() {
        new WorkerPollingTask(consumer).stop();

        verify(consumer).stopReceiving();
    }

    @Test
    void rejectsMissingConsumer() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerPollingTask(null));
    }
}
