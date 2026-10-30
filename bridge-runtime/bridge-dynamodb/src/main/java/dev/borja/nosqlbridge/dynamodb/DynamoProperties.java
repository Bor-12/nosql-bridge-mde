package dev.borja.nosqlbridge.dynamodb;

import java.net.URI;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.context.annotation.Lazy;

@Validated
@Lazy
@ConfigurationProperties("bridge.dynamodb")
public record DynamoProperties(@NotBlank(message = "bridge.dynamodb.region must not be blank.") String region, URI endpoint) {
    @AssertTrue(message = "bridge.dynamodb.endpoint must be an absolute HTTP(S) URI when provided")
    public boolean isEndpointValid() {
        return endpoint == null || (endpoint.isAbsolute() && endpoint.getHost() != null &&
                ("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme())));
    }
}
