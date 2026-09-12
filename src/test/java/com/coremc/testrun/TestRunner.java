package com.coremc.testrun;

import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

/**
 * Standalone JUnit Platform runner: discovers and runs every test under
 * {@code com.coremc} and exits non-zero on failure. The canonical build
 * uses Maven Surefire ({@code mvn verify}); this runner is a convenience
 * for running the suite with just the JUnit platform jars on a classpath
 * (e.g. a machine without Maven).
 */
public final class TestRunner {

    private TestRunner() {
    }

    public static void main(final String[] args) {
        final LauncherDiscoveryRequest discoveryRequest =
                request().selectors(selectPackage("com.coremc")).build();

        final Launcher launcher = LauncherFactory.create();
        final SummaryGeneratingListener listener = new SummaryGeneratingListener();
        launcher.execute(discoveryRequest, listener);

        final TestExecutionSummary summary = listener.getSummary();
        summary.printTo(new java.io.PrintWriter(System.out, true));

        if (summary.getTotalFailureCount() > 0) {
            for (final TestExecutionSummary.Failure failure : summary.getFailures()) {
                System.out.println("FAILED: " + failure.getTestIdentifier().getDisplayName());
                failure.getException().printStackTrace(System.out);
            }
            System.exit(1);
        }
        System.out.println("ALL " + summary.getTestsSucceededCount() + " TESTS PASSED");
        System.exit(0);
    }
}
