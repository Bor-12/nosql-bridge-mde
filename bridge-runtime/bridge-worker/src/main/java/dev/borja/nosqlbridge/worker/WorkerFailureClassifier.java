package dev.borja.nosqlbridge.worker;

import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;

import com.mongodb.MongoCommandException;
import com.mongodb.MongoConfigurationException;
import com.mongodb.MongoExecutionTimeoutException;
import com.mongodb.MongoNodeIsRecoveringException;
import com.mongodb.MongoNotPrimaryException;
import com.mongodb.MongoSecurityException;
import com.mongodb.MongoSocketException;
import com.mongodb.MongoTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkServiceException;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import software.amazon.awssdk.services.sqs.model.SqsException;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

/** Classifies final call failures; it never retries an SDK request or inspects messages. */
final class WorkerFailureClassifier {
    enum Kind { TRANSIENT, PERMANENT, INTERRUPTED, UNKNOWN }
    enum Action { FATAL, RETRYABLE, WORKUNIT_FAILED, INTERRUPTED }
    record Decision(Action action, String reason) {}

    static Decision decide(Throwable failure, String phase, boolean hasWorkUnit) {
        Kind kind = classify(failure);
        if (kind == Kind.INTERRUPTED) return new Decision(Action.INTERRUPTED, "interrupted");
        if (kind == Kind.TRANSIENT) return new Decision(Action.RETRYABLE, "transient_infrastructure_failure");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof MongoConfigurationException || cause instanceof MongoSecurityException
                    || cause instanceof SSLHandshakeException) {
                return new Decision(Action.FATAL, "global_client_configuration_or_security_failure");
            }
            if (cause instanceof BeanCreationException bean && Set.of("mongoClient", "dynamoDbClient", "sqsClient", "workQueue")
                    .contains(bean.getBeanName() == null ? "" : bean.getBeanName())) {
                return new Decision(Action.FATAL, "global_infrastructure_bean_creation_failed");
            }
            // Lazy infrastructure properties can first bind in target_setup; these are not document data.
            if (cause instanceof ConfigurationPropertiesBindException) {
                return new Decision(Action.FATAL, "global_configuration_properties_invalid");
            }
            if (cause instanceof SdkServiceException service && service.statusCode() == 401) {
                return new Decision(Action.FATAL, "global_authentication_failure");
            }
            if (cause instanceof SqsException service && "acknowledge".equals(phase)
                    && (service.statusCode() == 403 || cause instanceof QueueDoesNotExistException
                    || cause instanceof software.amazon.awssdk.services.sqs.model.InvalidAddressException
                    || cause instanceof software.amazon.awssdk.services.sqs.model.InvalidSecurityException
                    || (service.awsErrorDetails() != null && Set.of("QueueDoesNotExist", "AWS.SimpleQueueService.NonExistentQueue",
                            "InvalidAddress", "InvalidSecurity", "UnsupportedOperation")
                            .contains(service.awsErrorDetails().errorCode() == null ? "" : service.awsErrorDetails().errorCode())))) {
                return new Decision(Action.FATAL, "configured_queue_ack_access_or_request_rejected");
            }
            if (cause instanceof AwsServiceException service && service.awsErrorDetails() != null
                    && Set.of("InvalidClientTokenId", "UnrecognizedClientException", "InvalidSignatureException",
                            "SignatureDoesNotMatch", "ExpiredToken", "ExpiredTokenException", "InvalidAccessKeyId")
                    .contains(service.awsErrorDetails().errorCode() == null ? "" : service.awsErrorDetails().errorCode())) {
                return new Decision(Action.FATAL, "global_aws_credentials_or_signing_failure");
            }
            if (cause instanceof SdkServiceException service && !hasWorkUnit && "receive".equals(phase)
                    && service.statusCode() >= 400 && service.statusCode() < 500) {
                return new Decision(Action.FATAL, "configured_queue_access_or_request_rejected");
            }
        }
        if ("acknowledge".equals(phase) && hasWorkUnit) {
            // An invalid/stale receipt (or an unrecognized ACK failure) is not invalid document data.
            // Leave it unacknowledged for SQS redelivery; never infer a global outage from HTTP 400 alone.
            return new Decision(Action.WORKUNIT_FAILED, "sqs_acknowledgement_failed_review");
        }
        // A plan can select different databases/tables: a bad unit or resource-specific 403 is not a global outage.
        if (hasWorkUnit) return new Decision(Action.WORKUNIT_FAILED,
                kind == Kind.PERMANENT ? "permanent_workunit_failure_review" : "unclassified_workunit_failure_review");
        // No correlation does not prove a global defect (e.g. a malformed SQS payload).
        // Rate-limit future receives; do not replay a delivery, ACK it, or stop the instance on an inference.
        return new Decision(Action.RETRYABLE, "uncorrelated_failure_backoff_review");
    }

    static Kind classify(Throwable failure) {
        Kind result = Kind.UNKNOWN;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.size() < 32 && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof InterruptedException
                    || (cause instanceof InterruptedIOException && !(cause instanceof SocketTimeoutException))) return Kind.INTERRUPTED;
            if (cause instanceof IllegalArgumentException || cause instanceof ArithmeticException
                    || cause instanceof SSLHandshakeException || cause instanceof MongoConfigurationException
                    || cause instanceof MongoSecurityException) {
                result = Kind.PERMANENT;
                continue;
            }
            Kind current = Kind.UNKNOWN;
            if (cause instanceof SdkServiceException service) {
                if (service.isThrottlingException() || service.isRetryableException() || service.isClockSkewException()
                        || service.statusCode() == 408 || service.statusCode() == 429 || service.statusCode() >= 500) {
                    current = Kind.TRANSIENT;
                } else if (service.statusCode() >= 400 && service.statusCode() < 500) {
                    current = Kind.PERMANENT;
                }
            } else if (cause instanceof SocketTimeoutException || cause instanceof SocketException
                    || cause instanceof HttpTimeoutException || cause instanceof ApiCallTimeoutException
                    || cause instanceof ApiCallAttemptTimeoutException || cause instanceof MongoSocketException
                    || cause instanceof MongoTimeoutException || cause instanceof MongoExecutionTimeoutException
                    || cause instanceof MongoNotPrimaryException || cause instanceof MongoNodeIsRecoveringException) {
                current = Kind.TRANSIENT;
            } else if (cause instanceof MongoCommandException command
                    && (command.getErrorCode() == 13 || command.getErrorCode() == 18 || command.getErrorCode() == 2)) {
                current = Kind.PERMANENT;
            }
            if (current == Kind.PERMANENT || result == Kind.UNKNOWN) result = current;
        }
        return result;
    }
}
