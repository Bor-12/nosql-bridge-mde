package dev.borja.nosqlbridge.runtime;

import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.NestedExceptionUtils;

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
		try {
			if (args.length != 2 || !"--plan".equals(args[0])
					|| args[1].isBlank() || args[1].startsWith("--")) {
				throw new IllegalArgumentException("Required option: --plan <path>");
			}
			planPath = Path.of(args[1]);
		} catch (IllegalArgumentException exception) {
			error.println("Invalid arguments: " + exception.getMessage());
			error.println("Usage: java -jar bridge-runtime.jar --plan <path>");
			return 1;
		}

		try (var context = application.run("--bridge.execution.plan=" + planPath)) {
			return 0;
		} catch (Exception exception) {
			Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
			error.println("Failed to execute plan '" + planPath + "': "
					+ (cause instanceof NoSuchFileException ? "Plan file does not exist." : cause.getMessage()));
			return 1;
		}
	}

}
