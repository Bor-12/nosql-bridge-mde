package dev.borja.nosqlbridge.mongodb;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.context.annotation.Lazy;

@Validated
@Lazy
@ConfigurationProperties("bridge.mongodb")
public record MongoProperties(@NotBlank(message = "bridge.mongodb.uri must not be blank.") String uri) {}
