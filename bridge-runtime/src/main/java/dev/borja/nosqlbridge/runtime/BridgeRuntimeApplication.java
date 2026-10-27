package dev.borja.nosqlbridge.runtime;

import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

@SpringBootApplication
public class BridgeRuntimeApplication {

	public static void main(String[] args) {
		int exitCode = run(new SpringApplication(BridgeRuntimeApplication.class), System.err, args);
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	static int run(SpringApplication application, PrintStream error, String... args) {
		Path planPath;
		Path configPath;
		try {
			Map<String, String> options = new HashMap<>();
			for (int i = 0; i < args.length; i += 2) {
				String option = args[i];
				if ((!option.equals("--plan") && !option.equals("--config")) || i + 1 >= args.length
						|| args[i + 1].isBlank() || args[i + 1].startsWith("--")
						|| options.putIfAbsent(option, args[i + 1]) != null) {
					throw new IllegalArgumentException("Unknown, duplicate or invalid option: " + option);
				}
			}
			planPath = requiredFile(options.get("--plan"), "plan");
			configPath = requiredFile(options.get("--config"), "config");
		} catch (IllegalArgumentException exception) {
			error.println("Invalid arguments: " + exception.getMessage());
			error.println("Usage: java -jar bridge-runtime.jar --plan <path> --config <path>");
			return 1;
		}

		try (ConfigurableApplicationContext context = application.run("--bridge.execution.plan=" + planPath,
				"--spring.config.location=optional:classpath:/application.yaml," + configPath.toUri())) {
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
			error.println("Failed to execute plan '" + planPath + "': "
					+ (cause instanceof NoSuchFileException ? "Plan file does not exist." : cause.getMessage()));
			return 1;
		}
	}

	private static Path requiredFile(String value, String name) {
		if (value == null) throw new IllegalArgumentException("Required option: --" + name + " <path>");
		Path file = Path.of(value).toAbsolutePath().normalize();
		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			throw new IllegalArgumentException((name.equals("plan") ? "Plan" : "Config")
					+ " file does not exist or is not a readable file: " + file);
		}
		return file;
	}

}
