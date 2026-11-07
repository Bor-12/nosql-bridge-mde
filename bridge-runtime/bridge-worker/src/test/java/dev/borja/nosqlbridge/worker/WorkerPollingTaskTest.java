package dev.borja.nosqlbridge.worker;

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
import static org.mockito.Mockito.mock;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class WorkerPollingTaskTest {

    @Mock
    private WorkerConsumer consumer;

    @Test
    void continuesPollingAfterAnEmptyQueue() {
        WorkQueue queue = mock(WorkQueue.class);
        when(queue.receive()).thenReturn(Optional.empty());
        WorkerPollingTask task = new WorkerPollingTask(new WorkerConsumer(queue, mock(WorkUnitExecutor.class)));

        task.poll();
        task.poll();

        verify(queue, times(2)).receive();
    }

    @Test
    void appliesBackoffToAnUnclassifiedReceiveFailureWithoutLeakingExceptionContents(CapturedOutput output) {
        WorkQueue queue = mock(WorkQueue.class);
        when(queue.receive()).thenThrow(new IllegalStateException("sensitive-plan-content"))
                .thenReturn(Optional.empty());
        WorkerPollingTask task = new WorkerPollingTask(new WorkerConsumer(queue, mock(WorkUnitExecutor.class)));

        task.poll();
        task.poll();

        verify(queue).receive();
        assertTrue(output.getAll().contains("java.lang.IllegalStateException"));
        assertFalse(output.getAll().contains("sensitive-plan-content"));
        assertTrue(output.getAll().contains("failureDisposition=RETRYABLE"));
        assertTrue(output.getAll().contains("decisionReason=uncorrelated_failure_backoff_review"));
        org.junit.jupiter.api.Assertions.assertEquals(1, output.getAll().split("BRIDGE_WORKER_ITERATION_FAILED", -1).length - 1);
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
