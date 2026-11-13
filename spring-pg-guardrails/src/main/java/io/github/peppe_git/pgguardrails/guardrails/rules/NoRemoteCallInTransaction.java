// SPDX-License-Identifier: Apache-2.0
package io.github.peppe_git.pgguardrails.guardrails.rules;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import io.github.peppe_git.pgguardrails.guardrails.support.CallClosure;
import io.github.peppe_git.pgguardrails.guardrails.support.Guard;
import io.github.peppe_git.pgguardrails.guardrails.support.Transactions;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * No network call and no sub-process is reachable from a method whose body runs in a transaction.
 *
 * <p>A transaction held open across a remote call keeps a pooled connection (and its locks) for the whole duration
 * of that call. Nothing shows in development, where connections outnumber requests; under load the pool drains and
 * every request waits, with timeouts that name the pool rather than the cause.
 *
 * <p><b>What it sees.</b> Methods annotated {@code @Transactional} (Spring or Jakarta; on the method or its class;
 * not {@code SUPPORTS}/{@code NOT_SUPPORTED}/{@code NEVER}), and the calls reachable from them <b>inside the same
 * class</b>. Transactions opened with {@code TransactionTemplate} are deliberately out of scope: the transaction
 * begins and ends inside {@code execute(...)}, and a method that reads in a short transaction and then calls the
 * network is doing the right thing; the call graph cannot tell sequence from nesting.
 *
 * <p><b>What it cannot see.</b> A transactional method that delegates to <i>another</i> bean which then calls the
 * network.
 */
public final class NoRemoteCallInTransaction extends Guard<NoRemoteCallInTransaction> {

    /** Calls recognised by default: JDK network and process APIs, Spring's blocking HTTP clients. */
    private static final Map<String, Set<String>> DEFAULTS = defaults();

    private final JavaClasses classes;
    private final Map<String, Set<String>> remoteCalls = new LinkedHashMap<>(DEFAULTS);

    private NoRemoteCallInTransaction(JavaClasses classes) {
        this.classes = Objects.requireNonNull(classes, "classes");
    }

    public static NoRemoteCallInTransaction in(JavaClasses classes) {
        return new NoRemoteCallInTransaction(classes);
    }

    /**
     * Also treats calls to these methods of {@code ownerType} (or of its subtypes) as remote calls, e.g. your
     * generated API client.
     */
    public NoRemoteCallInTransaction remoteCall(String ownerType, String... methodNames) {
        if (methodNames.length == 0) {
            throw new IllegalArgumentException("name at least one method of " + ownerType);
        }
        remoteCalls.computeIfAbsent(ownerType, k -> new LinkedHashSet<>()).addAll(List.of(methodNames));
        return this;
    }

    /** Forgets the default list; only calls added with {@link #remoteCall} count. */
    public NoRemoteCallInTransaction withoutDefaults() {
        remoteCalls.clear();
        return this;
    }

    @Override
    protected String name() {
        return "NoRemoteCallInTransaction";
    }

    @Override
    protected String rationale() {
        return "a transaction held open across a network call or a sub-process keeps a pooled connection for the "
                + "whole call; under load the pool drains and every request waits";
    }

    @Override
    protected String remedy() {
        return "do the remote work outside the transaction and keep only the short state updates inside it "
                + "(a TransactionTemplate around the writes, or a separate bean); exempt by 'Class#method' only "
                + "with a reason";
    }

    @Override
    protected void evaluate(Collector collector) {
        if (remoteCalls.isEmpty()) {
            throw new IllegalStateException("no remote call to look for: add some with remoteCall(...)");
        }
        Map<String, Set<String>> found = new TreeMap<>();
        for (JavaClass clazz : classes) {
            for (JavaMethod method : clazz.getMethods()) {
                if (!Transactions.runsInTransaction(method)) {
                    continue;
                }
                collector.examined();
                Set<JavaMethod> reachable = CallClosure.withinSameClass(method, clazz);
                CallClosure.accessesFrom(reachable).filter(this::isRemote).forEach(a ->
                        found.computeIfAbsent(clazz.getName() + "#" + method.getName(), k -> new TreeSet<>())
                                .add(a.getTargetOwner().getSimpleName() + "." + a.getName() + "() at "
                                        + a.getSourceCodeLocation()));
            }
        }
        found.forEach((id, calls) -> collector.violation(id, "runs in a transaction and reaches " + calls));
    }

    private boolean isRemote(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        for (Map.Entry<String, Set<String>> e : remoteCalls.entrySet()) {
            if (e.getValue().contains(access.getName()) && isA(owner, e.getKey())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isA(JavaClass owner, String type) {
        if (owner.getName().equals(type)) {
            return true;
        }
        try {
            return owner.isAssignableTo(type);
        } catch (RuntimeException unresolved) {
            return false;
        }
    }

    private static Map<String, Set<String>> defaults() {
        Map<String, Set<String>> d = new LinkedHashMap<>();
        d.put("java.net.http.HttpClient", Set.of("send", "sendAsync"));
        d.put("java.lang.ProcessBuilder", Set.of("start"));
        d.put("java.lang.Runtime", Set.of("exec"));
        d.put("java.net.URL", Set.of("openConnection", "openStream"));
        d.put("java.net.Socket", Set.of("connect"));
        Set<String> restOperations = Set.of("getForObject", "getForEntity", "headForHeaders", "postForLocation",
                "postForObject", "postForEntity", "put", "patchForObject", "delete", "optionsForAllow", "exchange",
                "execute");
        d.put("org.springframework.web.client.RestOperations", restOperations);
        d.put("org.springframework.web.client.RestTemplate", restOperations);
        d.put("org.springframework.web.client.RestClient$RequestHeadersSpec", Set.of("retrieve", "exchange"));
        d.put("org.springframework.web.reactive.function.client.WebClient$RequestHeadersSpec",
                Set.of("retrieve", "exchangeToMono", "exchangeToFlux"));
        return Map.copyOf(d);
    }
}
