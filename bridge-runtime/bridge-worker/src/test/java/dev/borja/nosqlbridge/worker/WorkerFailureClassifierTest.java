package dev.borja.nosqlbridge.worker;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoTimeoutException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.services.sqs.model.SqsException;
import static dev.borja.nosqlbridge.worker.WorkerFailureClassifier.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerFailureClassifierTest {
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            dev.borja.nosqlbridge.dynamodb.DynamoBatchWriteProperties.class)
    static class LazyTargetProperties {}

    @Test void invalidGlobalTargetPropertiesAreFatalEvenWhenFirstBoundForAnIdentifiedWorkUnit() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(LazyTargetProperties.class)
                .withPropertyValues("bridge.dynamodb.batch-write.max-retries=-1").run(context -> {
                    assertNull(context.getStartupFailure());
                    var failure = assertThrows(org.springframework.boot.context.properties.ConfigurationPropertiesBindException.class,
                            () -> context.getBean(dev.borja.nosqlbridge.dynamodb.DynamoBatchWriteProperties.class));
                    var decision = WorkerFailureClassifier.decide(failure, "target_setup", true);
                    assertEquals(WorkerFailureClassifier.Action.FATAL, decision.action());
                    assertEquals("global_configuration_properties_invalid", decision.reason());
                });
    }

    @Test void targetSetupStillDistinguishesInvalidPlanFromGlobalClientConfigurationAndTimeout() {
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(new IllegalArgumentException("invalid table plan"), "target_setup", true).action());
        assertEquals(WorkerFailureClassifier.Action.FATAL,
                WorkerFailureClassifier.decide(new org.springframework.beans.factory.BeanCreationException(
                        "dynamoDbClient", "invalid endpoint", new IllegalArgumentException()), "target_setup", true).action());
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE,
                WorkerFailureClassifier.decide(ApiCallTimeoutException.create(1000), "target_setup", true).action());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"InvalidAddress", "InvalidSecurity", "QueueDoesNotExist",
            "AWS.SimpleQueueService.NonExistentQueue", "UnsupportedOperation"})
    void ackHttp400WithKnownQueueConfigurationFailureIsFatal(String errorCode) {
        var failure = SqsException.builder().statusCode(400).awsErrorDetails(
                software.amazon.awssdk.awscore.exception.AwsErrorDetails.builder().errorCode(errorCode).build()).build();
        assertEquals(WorkerFailureClassifier.Action.FATAL,
                WorkerFailureClassifier.decide(failure, "acknowledge", true).action());
    }

    @Test void invalidReceiptAndUnknownAck400RequireReviewWhileThrottlingBacksOff() {
        var invalidReceipt = software.amazon.awssdk.services.sqs.model.ReceiptHandleIsInvalidException.builder()
                .statusCode(400).build();
        var decision = WorkerFailureClassifier.decide(invalidReceipt, "acknowledge", true);
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED, decision.action());
        assertEquals("sqs_acknowledgement_failed_review", decision.reason());
        assertEquals(decision, WorkerFailureClassifier.decide(SqsException.builder().statusCode(400).build(), "acknowledge", true));
        SqsException throttled = new SqsException(SqsException.builder().statusCode(400)) {
            @Override public boolean isThrottlingException() { return true; }
        };
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE,
                WorkerFailureClassifier.decide(throttled, "acknowledge", true).action());
    }

    @Test void distinguishesUnitScopeFromFatalInfrastructureInsteadOfTreatingEveryIllegalArgumentAsFatal() {
        var failure = new IllegalArgumentException("private-invalid-data");
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(failure, "transform", true).action());
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(failure, "target_setup", true).action());
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE,
                WorkerFailureClassifier.decide(failure, "receive", false).action());
        assertEquals(WorkerFailureClassifier.Action.FATAL,
                WorkerFailureClassifier.decide(new org.springframework.beans.factory.BeanCreationException("mongoClient", "private", failure),
                        "source_setup", true).action());
    }

    @Test void considersWhichResourceAndOperationFailedForAuthorizationErrors() {
        var resourceDenied = SdkServiceException.builder().statusCode(403).build();
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(resourceDenied, "target_write", true).action());
        assertEquals(WorkerFailureClassifier.Action.FATAL,
                WorkerFailureClassifier.decide(SqsException.builder().statusCode(403).build(), "receive", false).action());
        assertEquals(WorkerFailureClassifier.Action.FATAL,
                WorkerFailureClassifier.decide(SqsException.builder().statusCode(403).build(), "acknowledge", true).action());
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(SqsException.builder().statusCode(400).build(), "acknowledge", true).action());
    }

    @Test void unknownErrorsNeverBecomeGlobalFatalByInference() {
        var failure = new IllegalStateException("private");
        assertEquals(WorkerFailureClassifier.Action.WORKUNIT_FAILED,
                WorkerFailureClassifier.decide(failure, "execute", true).action());
        var decision = WorkerFailureClassifier.decide(failure, "receive", false);
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE, decision.action());
        assertEquals("uncorrelated_failure_backoff_review", decision.reason());
    }

    @Test void recognizesTypedTransientNetworkTimeoutAndServiceErrors() {
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE,
                WorkerFailureClassifier.decide(SqsException.builder().statusCode(503).build(), "receive", false).action());
        assertEquals(WorkerFailureClassifier.Action.RETRYABLE,
                WorkerFailureClassifier.decide(ApiCallTimeoutException.create(1000), "target_write", true).action());
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(SdkClientException.create("private", new ConnectException())));
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(SdkClientException.create("private", new SocketTimeoutException())));
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(ApiCallTimeoutException.create(1000)));
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(new MongoTimeoutException("private")));
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(SqsException.builder().statusCode(503).build()));
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(SqsException.builder().statusCode(429).build()));
        SdkServiceException throttled = new SdkServiceException(SdkServiceException.builder().statusCode(400)) {
            @Override public boolean isThrottlingException() { return true; }
        };
        assertEquals(TRANSIENT, WorkerFailureClassifier.classify(throttled));
    }

    @Test void recognizesPermanentErrorsAndDoesNotInferMeaningFromMessages() {
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(new NumberFormatException("timeout throttle")));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(new ArithmeticException("private")));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(new MongoConfigurationException("timeout")));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(SqsException.builder().statusCode(403).build()));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(SqsException.builder().statusCode(400).build()));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(SdkClientException.create("private", new SSLHandshakeException("private"))));
        assertEquals(UNKNOWN, WorkerFailureClassifier.classify(new IllegalStateException("timeout throttle retryable")));
        assertEquals(UNKNOWN, WorkerFailureClassifier.classify(SdkClientException.create("Unable to load credentials")));
        assertEquals(UNKNOWN, WorkerFailureClassifier.classify(SdkClientException.create("private", new UnknownHostException())));
    }

    @Test void preservesInterruptionsAndSafelyTraversesCircularCauses() {
        assertEquals(INTERRUPTED, WorkerFailureClassifier.classify(SdkClientException.create("private", new InterruptedException())));
        assertEquals(INTERRUPTED, WorkerFailureClassifier.classify(SdkClientException.create("private", new java.io.InterruptedIOException())));
        RuntimeException one = new RuntimeException();
        RuntimeException two = new RuntimeException();
        one.initCause(two);
        two.initCause(one);
        assertEquals(UNKNOWN, WorkerFailureClassifier.classify(one));
        assertEquals(PERMANENT, WorkerFailureClassifier.classify(new IllegalArgumentException("private", new SocketTimeoutException())));
    }
}
