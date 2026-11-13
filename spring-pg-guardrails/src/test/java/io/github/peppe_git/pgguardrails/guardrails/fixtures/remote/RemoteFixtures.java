// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.fixtures.remote;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Fixtures for NoRemoteCallInTransaction: three violations, the rest clean. */
public final class RemoteFixtures {

    private RemoteFixtures() {
    }

    public static class DirectCall {
        private final HttpClient http = HttpClient.newHttpClient();

        @Transactional
        public String fetch() throws IOException, InterruptedException {
            return http.send(HttpRequest.newBuilder(URI.create("http://localhost")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
        }
    }

    public static class ThroughPrivateHelper {
        @Transactional
        public void render() throws IOException {
            spawn();
        }

        private void spawn() throws IOException {
            new ProcessBuilder("true").start();
        }
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Transactional
    public @interface WriteTransaction {
    }

    public static class ComposedAnnotation {
        @WriteTransaction
        public void run() throws IOException {
            Runtime.getRuntime().exec(new String[] {"true"});
        }
    }

    @Transactional
    public static class ClassLevel {
        @Transactional(propagation = Propagation.NOT_SUPPORTED)
        public void outsideTransaction() throws IOException, InterruptedException {
            HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost")).build(),
                    HttpResponse.BodyHandlers.discarding());
        }

        public int insideTransactionButLocal() {
            return 42;
        }
    }

    public static class NotTransactional {
        public void call() throws IOException {
            new ProcessBuilder("true").start();
        }
    }
}
