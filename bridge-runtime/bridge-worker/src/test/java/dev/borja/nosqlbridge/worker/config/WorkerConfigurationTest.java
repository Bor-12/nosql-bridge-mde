package dev.borja.nosqlbridge.worker.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.FixedDelayTask;

import dev.borja.nosqlbridge.worker.execution.WorkUnitExecutor;
import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.ConnectorPlan;
import dev.borja.nosqlbridge.common.plan.PlanLoader;
import dev.borja.nosqlbridge.common.plan.PlanValidator;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.queue.WorkQueue.Delivery;
import dev.borja.nosqlbridge.worker.runner.PlanRunner;
import dev.borja.nosqlbridge.common.work.MigrationId;
import dev.borja.nosqlbridge.common.work.SourcePartition;
import dev.borja.nosqlbridge.common.work.WorkUnit;
import dev.borja.nosqlbridge.common.work.WorkUnitId;
import dev.borja.nosqlbridge.common.work.WorkUnitMessage;
import dev.borja.nosqlbridge.worker.work.WorkUnitResult;
import dev.borja.nosqlbridge.worker.WorkerConsumer;
import dev.borja.nosqlbridge.worker.WorkerPollingTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class WorkerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ExecutionConfiguration.class, WorkerConfiguration.class);
    private final WorkQueue queue = mock(WorkQueue.class);

    @Test
    void startsWorkerWithoutPlanAndRegistersOneSequentialScheduledTask() {
        contextRunner.withBean(WorkQueue.class, () -> queue).withPropertyValues("bridge.runtime.mode=worker")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(1, context.getBeansOfType(WorkerConsumer.class).size());
                    assertEquals(1, context.getBeansOfType(WorkUnitExecutor.class).size());
                    assertEquals(1, context.getBeansOfType(WorkerPollingTask.class).size());
                    assertTrue(context.getBeansOfType(PlanRunner.class).isEmpty());
                    ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);
                    assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());
                    ScheduledAnnotationBeanPostProcessor processor = context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertEquals(1, processor.getScheduledTasks().size());
                    FixedDelayTask task = assertInstanceOf(FixedDelayTask.class,
                            processor.getScheduledTasks().iterator().next().getTask());
                    assertEquals(Duration.ofSeconds(1), task.getIntervalDuration());
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"unused.json", ""})
    void workerNeverRegistersLocalPlanComponentsEvenWhenPlanPropertyExists(String plan) {
        contextRunner.withBean(WorkQueue.class, () -> queue)
                .withPropertyValues("bridge.runtime.mode=worker", "bridge.execution.plan=" + plan).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(PlanRunner.class).isEmpty());
                    assertTrue(context.getBeansOfType(PlanLoader.class).isEmpty());
                    assertTrue(context.getBeansOfType(PlanValidator.class).isEmpty());
                });
    }

    @Test
    void doesNotStartWorkerInLocalMode() {
        contextRunner.withPropertyValues("bridge.execution.plan=unused.json").run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(1, context.getBeansOfType(PlanRunner.class).size());
            assertTrue(context.getBeansOfType(WorkerConsumer.class).isEmpty());
            assertTrue(context.getBeansOfType(ThreadPoolTaskScheduler.class).isEmpty());
        });
    }

    @Test
    void failsClearlyWhenWorkerHasNoQueueInfrastructure() {
        contextRunner.withPropertyValues("bridge.runtime.mode=worker").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertEquals("Worker mode requires bridge.sqs.queue-url or a WorkQueue bean.",
                    NestedExceptionUtils.getMostSpecificCause(context.getStartupFailure()).getMessage());
        });
    }

    @Test
    void leavesALateDeliveryUnexecutedWhenContextClosesDuringLongPolling() {
        CountDownLatch receiving = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        WorkUnitExecutor executor = mock(WorkUnitExecutor.class);
        Delivery delivery = delivery();
        when(queue.receive()).thenAnswer(invocation -> {
            receiving.countDown();
            assertTrue(closing.await(5, TimeUnit.SECONDS));
            return Optional.of(delivery);
        });
        lifecycleContext(executor, closing).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(receiving.await(5, TimeUnit.SECONDS));
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

            context.getSourceApplicationContext().close();

            assertTrue(scheduler.getScheduledThreadPoolExecutor().isTerminated());
            verify(queue).receive();
            verifyNoMoreInteractions(queue);
            verifyNoInteractions(executor);
        });
    }

    @Test
    void letsActiveExecutionFinishAndAcknowledgeWithoutInterruptionBeforeClosingResources() {
        CountDownLatch executing = new CountDownLatch(1);
        CountDownLatch closing = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkUnitExecutor executor = mock(WorkUnitExecutor.class);
        Delivery delivery = delivery();
        when(queue.receive()).thenReturn(Optional.of(delivery));
        when(executor.execute(any(WorkUnit.class), any(BridgeExecutionPlan.class))).thenAnswer(invocation -> {
            executing.countDown();
            try {
                assertTrue(closing.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Execution interrupted", failure);
            }
            return new WorkUnitResult(delivery.message().workUnit().migrationId(),
                    delivery.message().workUnit().workUnitId(), 1, 1);
        });
        lifecycleContext(executor, closing).run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(executing.await(5, TimeUnit.SECONDS));
            ThreadPoolTaskScheduler scheduler = context.getBean(ThreadPoolTaskScheduler.class);

            context.getSourceApplicationContext().close();

            assertFalse(interrupted.get());
            assertTrue(scheduler.getScheduledThreadPoolExecutor().isTerminated());
            verify(queue).receive();
            verify(queue).acknowledge(delivery);
            verifyNoMoreInteractions(queue);
            verify(executor).execute(delivery.message().workUnit(), delivery.message().plan());
            verifyNoMoreInteractions(executor);
        });
    }

    private ApplicationContextRunner lifecycleContext(WorkUnitExecutor executor, CountDownLatch closing) {
        return new ApplicationContextRunner().withUserConfiguration(WorkerConfiguration.class)
                .withBean(WorkQueue.class, () -> queue).withBean(WorkUnitExecutor.class, () -> executor)
                .withPropertyValues("bridge.runtime.mode=worker").withInitializer(context ->
                        context.addApplicationListener(event -> {
                            if (event instanceof ContextClosedEvent) {
                                closing.countDown();
                            }
                        }));
    }

    private Delivery delivery() {
        WorkUnit unit = new WorkUnit(new MigrationId(UUID.fromString("550e8400-e29b-41d4-a716-446655440000")),
                new WorkUnitId(0), new SourcePartition("test", Map.of()));
        BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test", new ConnectorPlan("source", Map.of()),
                new ConnectorPlan("target", Map.of()), "drop", "omit", List.of());
        return new Delivery(new WorkUnitMessage(unit, plan), "receipt");
    }
}
