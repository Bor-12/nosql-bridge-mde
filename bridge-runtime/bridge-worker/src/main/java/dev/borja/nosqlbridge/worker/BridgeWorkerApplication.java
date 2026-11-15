package dev.borja.nosqlbridge.worker;

import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Files;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

@SpringBootApplication
public class BridgeWorkerApplication {

	public static void main(String[] args) {
		int exitCode = run(new SpringApplication(BridgeWorkerApplication.class), System.err, args);
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	static int run(SpringApplication application, PrintStream error, String... args) {
		Path configPath;
		try {
			if (args.length != 2 || !"--config".equals(args[0])
					|| args[1].isBlank() || args[1].startsWith("--")) {
				throw new IllegalArgumentException("Only --config <path> is supported.");
			}
			configPath = requiredFile(args[1]);
		} catch (IllegalArgumentException exception) {
			error.println("Invalid arguments: " + exception.getMessage());
			error.println("Usage: java -jar bridge-worker.jar --config <path>");
			return 1;
		}

		try {
			application.run("--spring.config.location=optional:classpath:/application.yaml," + configPath.toUri());
			return 0;
		} catch (Exception exception) {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
			for (Throwable failure = exception; failure != null; failure = failure.getCause()) {
				if (failure instanceof BindValidationException validation) {
					for (ObjectError issue : validation.getValidationErrors().getAllErrors()) {
						error.println("Invalid configuration: " + validation.getValidationErrors().getName()
							+ (issue instanceof FieldError field ? "." + field.getField() : "")
							+ " " + issue.getDefaultMessage());
					}
					return 1;
				}
			}
			String explanation = "Worker mode requires bridge.sqs.queue-url or a WorkQueue bean.".equals(cause.getMessage())
							? "Worker mode requires bridge.sqs.queue-url or a WorkQueue bean."
							: "Invalid infrastructure configuration (" + cause.getClass().getSimpleName() + ").";
			error.println("Failed to start worker: " + explanation);
			return 1;
		}
	}

	private static Path requiredFile(String value) {
		Path file = Path.of(value).toAbsolutePath().normalize();
		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			throw new IllegalArgumentException("Config file does not exist or is not a readable file: " + file);
		}
		return file;
	}

}
