# QuantumBPM Java SDK

Official Java SDK for the [QuantumBPM](https://quantumbpm.com) platform — DMN evaluation, BPMN process orchestration, and external job workers.

## Installation

The SDK is split into two artifacts:

```xml
<!-- Plain Java client -->
<dependency>
    <groupId>com.quantumbpm</groupId>
    <artifactId>quantum-client</artifactId>
    <version>1.0.0</version>
</dependency>

<!-- Spring Boot starter — autoconfigured client + @JobWorker registration -->
<dependency>
    <groupId>com.quantumbpm</groupId>
    <artifactId>quantum-spring</artifactId>
    <version>1.0.0</version>
</dependency>
```

Java 21+. The worker runtime uses virtual threads.

## What's in the box

| Module                    | Package                          | Purpose                                                                       |
| ------------------------- | -------------------------------- | ----------------------------------------------------------------------------- |
| `quantum-client`          | `com.quantumbpm.client`          | Plain Java SDK — `QuantumBPM`, `DmnClient`, `BpmnClient`, `Worker`            |
|                           | `com.quantumbpm.client.auth`     | `TokenProvider`, `ZitadelTokenProvider`, `StaticTokenProvider`                |
|                           | `com.quantumbpm.client.variables`| `Vars` wrapper with typed accessors and FEEL-context conversion               |
|                           | `com.quantumbpm.client.workers`  | `Worker`, `Job<T>`, `Handler<T>`, `BpmnError`                                 |
|                           | `com.quantumbpm.client.generated`| OpenAPI-generated client (use `client.raw()` for unwrapped endpoints)         |
| `quantum-spring`          | `com.quantumbpm.spring`          | Spring Boot starter, `@JobWorker` annotation, autoconfig                      |

## Quick start (plain Java)

```java
import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.auth.ZitadelTokenProvider;
import com.quantumbpm.client.variables.Vars;
import java.util.UUID;

ZitadelTokenProvider provider = new ZitadelTokenProvider(
    "./service-account.json",       // Zitadel JSON Key file
    "https://auth.quantumbpm.com",  // issuer
    "your-zitadel-project-id");     // audience scope

QuantumBPM client = QuantumBPM.builder()
    .baseUrl("https://api.quantumbpm.com")
    .projectId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
    .tokenProvider(provider)
    .build();

var result = client.dmn().evaluate(
    "loan-eligibility",
    new Vars().set("requestedAmt", 1000).set("creditScore", 720));
System.out.println(result);
```

## Quick start (Spring Boot)

`application.yml`:

```yaml
quantumbpm:
  base-url: https://api.quantumbpm.com
  project-id: 00000000-0000-0000-0000-000000000000
  auth:
    zitadel:
      key-file: /path/to/service-account.json
      issuer: https://auth.quantumbpm.com
      project-id: 123456789
  worker:
    enabled: true            # default; scans @JobWorker beans
    client-id: billing-svc
```

A handler — register a method with `@JobWorker`:

```java
import com.quantumbpm.client.variables.Vars;
import com.quantumbpm.client.workers.Job;
import com.quantumbpm.spring.JobWorker;
import org.springframework.stereotype.Component;

@Component
public class EmailHandler {

    @JobWorker(type = "send-email", maxJobs = 5, lockDuration = "1m")
    public Vars handle(Job<EmailJob> job) {
        emailService.send(job.typed().recipient(), job.typed().subject());
        return new Vars().set("messageID", "msg-123");
    }

    public record EmailJob(String recipient, String subject) {}
}
```

The autoconfig wires everything: builds the `QuantumBPM` bean from properties, scans every Spring bean for `@JobWorker` methods, and starts/stops the worker around the application lifecycle.

Inject `QuantumBPM` anywhere you need DMN/BPMN client calls:

```java
@Service
public class LoanService {
    private final QuantumBPM quantum;
    public LoanService(QuantumBPM quantum) { this.quantum = quantum; }

    public String startLoan(LoanRequest req) throws Exception {
        return quantum.bpmn().startInstance(processDefId, Vars.of()
            .set("applicantID", req.applicantId())
            .set("requestedAmt", req.amount()));
    }
}
```

## Authentication

The `TokenProvider` interface is a `@FunctionalInterface` returning a bearer token. Two implementations ship out of the box.

### Zitadel service account

```java
import com.quantumbpm.client.auth.ZitadelTokenProvider;

var provider = new ZitadelTokenProvider(
    "./service-account.json",      // path to JSON Key file
    "https://auth.quantumbpm.com", // issuer URL
    "your-zitadel-project-id");    // adds the audience scope
```

Tokens are cached in-memory until shortly before expiry. Concurrent calls share the cache via a lock.

### Static bearer token

```java
import com.quantumbpm.client.auth.StaticTokenProvider;

var provider = new StaticTokenProvider("eyJhbGciOi...");
```

### Bring your own

```java
TokenProvider provider = () -> myAuth.fetchToken();
```

## DMN evaluation

`client.dmn()` exposes four methods.

### Evaluate a stored definition

```java
var result = client.dmn().evaluate(
    "loan-eligibility",
    new Vars().set("requestedAmt", 5000).set("creditScore", 720));
```

Returns `Map<String, EvaluationResult>` keyed by decision name.

Pin a version, restrict the evaluated decisions, or attach decision services:

```java
import static com.quantumbpm.client.dmn.DmnClient.*;

var result = client.dmn().evaluate(
    "loan-eligibility", vars,
    withVersion(3),
    withDecisions("eligibility", "rate"));
```

### Evaluate by platform UUID

```java
var result = client.dmn().evaluateById(definitionUuid, vars);
```

### Ad-hoc XML evaluation

```java
import static com.quantumbpm.client.dmn.DmnClient.*;

var result = client.dmn().evaluateDesign(
    dmnXml, vars,
    withAdditionalXmls(importedXml1, importedXml2),
    withDesignDecisions("eligibility"));
```

### Batch ad-hoc evaluation

```java
var rows = List.of(
    new Vars().set("requestedAmt", 1000),
    new Vars().set("requestedAmt", 5000),
    new Vars().set("requestedAmt", 25000));
var batch = client.dmn().evaluateDesignBatch(dmnXml, rows);
```

## BPMN processes

`client.bpmn()` covers the full BPMN runtime surface.

### Deploy and start

```java
var draft = client.bpmn().createResource("loan-process", bpmnXml);
client.bpmn().deployResource(draft.getId());

// Re-fetch to get populated process-definition list.
var deployed = client.bpmn().getResource(draft.getId());
var processDef = deployed.getProcesses().get(0);

String workflowId = client.bpmn().startInstance(
    processDef.getId(),
    new Vars().set("applicantID", "u-123").set("requestedAmt", 25000));
```

### Inspect runtime state

```java
var state = client.bpmn().getInstance(workflowId);
System.out.println(state.getStatus());

Vars vars = client.bpmn().getInstanceVariables(workflowId);
var children = client.bpmn().getInstanceChildren(workflowId);
```

### Send messages and signals

```java
client.bpmn().publishMessage("loan-approved",
    new Vars().set("approvedAmt", 24000),
    null,                  // CorrelationKeys (optional)
    "PT5M");               // TTL (optional)

client.bpmn().publishSignal("system-maintenance", null);
```

### User tasks

```java
var page = client.bpmn().listUserTasks(
    null, "CREATED", "alice@example.com", null, null, null, null);

client.bpmn().completeUserTask(executionKey, new Vars().set("approved", true));

// Or fail with a BPMN error code:
client.bpmn().throwUserTaskError(executionKey, "REVIEW_REJECTED", null);
```

## External job workers

Workers handle service tasks asynchronously. Register a handler per task type, then start the worker. The runtime owns long-polling, lock heartbeats, dispatch (on virtual threads), and outcome mapping.

### Plain Java (no Spring)

```java
import com.quantumbpm.client.QuantumBPM;
import com.quantumbpm.client.variables.Vars;
import com.quantumbpm.client.workers.BpmnError;
import com.quantumbpm.client.workers.Worker;

QuantumBPM client = QuantumBPM.builder()...build();
Worker worker = client.newWorker("billing-svc");

worker.handle("send-email", job -> {
    String recipient = job.vars().get("recipient", String.class);
    String subject = job.vars().get("subject", String.class);
    emailer.send(recipient, subject);
    return new Vars().set("messageID", "msg-123");          // → Complete
}, Worker.withMaxJobs(10), Worker.withLockDuration("1m"));

worker.start();    // returns immediately; loops on virtual threads
Runtime.getRuntime().addShutdownHook(new Thread(() -> worker.stop(15_000)));
```

### Typed handlers

Pass the typed class as the second argument; the runtime decodes the job's variables into that type before invoking the handler. The decoded value lands in `job.typed()`.

```java
record EmailJob(String recipient, String subject) {}

worker.handle("send-email", EmailJob.class, job -> {
    emailer.send(job.typed().recipient(), job.typed().subject());
    return new Vars().set("messageID", "msg-123");
});
```

In Spring, the type is inferred from the method's `Job<T>` parameter — no extra argument needed.

### Throwing typed BPMN errors

Throw a `BpmnError` to fail the job with a code that boundary error events on the originating service task can catch:

```java
worker.handle("charge-card", job -> {
    try {
        return new Vars().set("transactionID", charge(job.vars().toMap()));
    } catch (InsufficientFundsException e) {
        throw new BpmnError("INSUFFICIENT_FUNDS",
            new Vars().set("availableBalance", 12.0));
    }
});
```

Any other exception is reported as `WORKER_ERROR`, which the server treats as a retryable failure that decrements the job's retry budget.

### Concurrency, polling, and locks

```java
worker.handle("send-email", handler,
    Worker.withMaxJobs(10),         // up to 10 in flight per task type
    Worker.withPollTimeout("45s"),  // long-poll wait
    Worker.withLockDuration("2m")); // exclusive lock per job
```

Concurrency is per task type. Different task types poll independently. The runtime auto-renews the lock at half the lock-duration interval while the handler runs.

## Variables

`Vars` is a thin wrapper around `Map<String, Object>` shared by DMN, BPMN, and workers.

### Construction

```java
var v = new Vars().set("amount", 100).set("name", "Alice");
var v = Vars.from(Map.of("amount", 100, "name", "Alice"));
```

### Typed access

```java
double amount = v.get("amount", Double.class);
boolean approved = v.get("approved", Boolean.class);

record Loan(double requestedAmt, boolean approved) {}
Loan loan = v.as(Loan.class);
```

`get(name, Class)` and `as(Class)` use Jackson under the hood — records, POJOs, and primitives all work.

## Escape hatch

`client.raw()` exposes the underlying generated `ApiClient` for endpoints not yet wrapped (instance migration, modification, ad-hoc triggers, batch job complete/error, etc.):

```java
import com.quantumbpm.client.generated.api.BpmnApi;

var api = new BpmnApi(client.raw());
api.migrateBpmnInstance(client.projectId(), workflowId, body);
```

## License

MIT License — see [LICENSE](LICENSE) for details.
