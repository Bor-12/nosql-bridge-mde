package dev.borja.nosqlbridge.orchestrator;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import dev.borja.nosqlbridge.common.plan.BridgeExecutionPlan;
import dev.borja.nosqlbridge.common.plan.PlanLoader;

@SpringBootApplication
public class BridgeOrchestratorApplication {

    public static void main(String[] args) {
        int exitCode = run(new SpringApplication(BridgeOrchestratorApplication.class), System.out, System.err, args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(SpringApplication application, PrintStream output, PrintStream error, String... args) {
        Path planPath;
        Path configPath;
        try {
            Map<String, String> options = new HashMap<>();
            for (int index = 0; index < args.length; index += 2) {
                String option = args[index];
                if ((!option.equals("--plan") && !option.equals("--config")) || index + 1 >= args.length
                        || args[index + 1].isBlank() || args[index + 1].startsWith("--")
                        || options.putIfAbsent(option, args[index + 1]) != null) {
                    throw new IllegalArgumentException("Unknown, duplicate or invalid option: " + option);
                }
            }
            planPath = requiredFile(options.get("--plan"), "plan");
            configPath = requiredFile(options.get("--config"), "config");
        } catch (IllegalArgumentException failure) {
            error.println("Invalid arguments: " + failure.getMessage());
            error.println("Usage: java -jar bridge-orchestrator.jar --plan <path> --config <path>");
            return 1;
        }

        application.setWebApplicationType(WebApplicationType.NONE);
        MigrationPublisher.Result result;
        try (ConfigurableApplicationContext context = application.run(
                "--spring.config.location=optional:classpath:/application.yaml," + configPath.toUri())) {
            BridgeExecutionPlan plan = context.getBean(PlanLoader.class).load(planPath);
            result = context.getBean(MigrationPublisher.class).publish(plan);
        } catch (MigrationPublisher.PublicationException failure) {
            error.println(failure.getMessage());
            return 1;
        } catch (IllegalArgumentException failure) {
            error.println("Invalid execution plan: " + failure.getMessage());
            return 1;
        } catch (Exception failure) {
            error.println("Could not prepare or finish migration (" + failure.getClass().getSimpleName()
                    + "). Check the plan and orchestrator configuration.");
            return 1;
        }
        output.println("Migration " + result.migrationId().value() + ": " + result.publishedMessages()
                + " publish calls completed successfully.");
        return 0;
    }

    private static Path requiredFile(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Required option: --" + name + " <path>");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("The --" + name + " file does not exist or is not readable.");
        }
        return path;
    }
}
