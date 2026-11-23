package com.tixrush;

import org.testcontainers.DockerClientFactory;

/** "Is a Docker engine running here?" Asked once per test run, then remembered. */
public final class DockerCheck {

    private static Boolean available;

    private DockerCheck() {
    }

    public static synchronized boolean available() {
        if (available == null) {
            try {
                available = DockerClientFactory.instance().isDockerAvailable();
            } catch (RuntimeException | LinkageError e) {
                available = false;
            }
        }
        return available;
    }
}
