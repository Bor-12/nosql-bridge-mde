package dev.borja.nosqlbridge.runtime.plan;

public class UnsupportedPlanVersionException extends IllegalArgumentException {

    public UnsupportedPlanVersionException(int receivedVersion, int supportedVersion) {
        super("Unsupported execution plan version " + receivedVersion
                + ". Supported version is " + supportedVersion + ".");
    }
}
