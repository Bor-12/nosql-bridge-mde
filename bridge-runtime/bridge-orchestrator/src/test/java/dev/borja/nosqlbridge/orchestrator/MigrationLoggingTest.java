package dev.borja.nosqlbridge.orchestrator;

import java.util.List;
import java.util.Map;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import dev.borja.nosqlbridge.common.plan.*;
import dev.borja.nosqlbridge.common.queue.WorkQueue;
import dev.borja.nosqlbridge.common.work.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MigrationLoggingTest {
    @Test void logsIncrementalSummaryAndOnlyOneSafeCorrelatedErrorForFailedSend() {
        Logger logger = (Logger) LoggerFactory.getLogger(MigrationPublisher.class);
        Level previousLevel = logger.getLevel();
        Map<String, String> previousContext = MDC.getCopyOfContextMap();
        ListAppender<ILoggingEvent> events = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                event.prepareForDeferredProcessing();
                super.append(event);
            }
        };
        events.start();
        logger.addAppender(events);
        logger.setLevel(Level.INFO);
        try {
            MigrationPartitioner partitioner = mock(MigrationPartitioner.class);
            WorkUnitCursor cursor = mock(WorkUnitCursor.class);
            WorkQueue queue = mock(WorkQueue.class);
            BridgeExecutionPlan plan = new BridgeExecutionPlan(1, "test",
                    new ConnectorPlan("mongodb", Map.of("uri", "secret-uri")),
                    new ConnectorPlan("dynamodb", Map.of("table", "test")), "drop", "omit", List.of());
            when(partitioner.open(same(plan), any(), eq(1))).thenAnswer(invocation -> {
                assertNull(MDC.get("workUnitId"));
                MigrationId migration = invocation.getArgument(1);
                var units = List.of(
                        new WorkUnit(migration, new WorkUnitId(0), new SourcePartition("test", Map.of())),
                        new WorkUnit(migration, new WorkUnitId(1), new SourcePartition("test", Map.of()))).iterator();
                when(cursor.hasNext()).thenAnswer(call -> {
                    assertNull(MDC.get("workUnitId"), "Previous unit must be cleared before partitioning");
                    return units.hasNext();
                });
                when(cursor.next()).thenAnswer(call -> {
                    assertNull(MDC.get("workUnitId"));
                    return units.next();
                });
                return cursor;
            });
            IllegalStateException failure = new IllegalStateException("secret-sqs-payload");
            doAnswer(invocation -> {
                WorkUnitMessage message = invocation.getArgument(0);
                assertEquals(message.workUnit().migrationId().value().toString(), MDC.get("migrationId"));
                assertEquals(Long.toString(message.workUnit().workUnitId().value()), MDC.get("workUnitId"));
                if (message.workUnit().workUnitId().value() == 1) throw failure;
                return null;
            }).when(queue).publish(any());
            MDC.clear();
            MDC.put("caller", "retained");
            MDC.put("migrationId", "caller-migration");
            MDC.put("workUnitId", "caller-unit");
            Map<String, String> callerContext = MDC.getCopyOfContextMap();
            MigrationPublisher publisher = new MigrationPublisher(new PlanValidator(), partitioner, queue, 1);
            var exception = assertThrows(MigrationPublisher.PublicationException.class, () -> publisher.publish(plan));
            assertSame(failure, exception.getCause());
            assertEquals(1, exception.progress().publishedMessages());
            ILoggingEvent error = events.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow();
            assertEquals(1, events.list.stream().filter(e -> e.getLevel() == Level.ERROR).count());
            assertTrue(error.getFormattedMessage().contains("workUnitId=1 phase=publish publishedWorkUnits=1"));
            assertEquals(exception.progress().migrationId().value().toString(), error.getMDCPropertyMap().get("migrationId"));
            assertEquals("1", error.getMDCPropertyMap().get("workUnitId"));
            assertFalse(ThrowableProxyUtil.asString(error.getThrowableProxy()).contains("secret-sqs-payload"));
            assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("secret-uri")));
            assertEquals(callerContext, MDC.getCopyOfContextMap());
            events.list.clear();
            doReturn(cursor).when(partitioner).open(same(plan), any(), eq(1));
            doReturn(false).when(cursor).hasNext();
            publisher.publish(plan);
            assertEquals(callerContext, MDC.getCopyOfContextMap());
            assertTrue(events.list.stream().allMatch(e -> !e.getMDCPropertyMap().containsKey("workUnitId")));
            assertTrue(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKUNITS_PUBLISHED")));
            assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().contains("MIGRATION_COMPLETED")));
            assertFalse(events.list.stream().anyMatch(e -> e.getFormattedMessage().startsWith("WORKUNIT_PUBLISHED ")));
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previousLevel);
            events.stop();
            if (previousContext == null) MDC.clear(); else MDC.setContextMap(previousContext);
        }
    }
}
