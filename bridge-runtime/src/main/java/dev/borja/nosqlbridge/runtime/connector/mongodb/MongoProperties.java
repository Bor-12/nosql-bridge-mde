package dev.borja.nosqlbridge.runtime.connector.mongodb;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("bridge.mongodb")
public record MongoProperties(@NotBlank String uri) {}
