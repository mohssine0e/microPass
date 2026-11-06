(function () {
  'use strict';

  var STORAGE_KEY = 'micropass-academy-v1';
  var state = loadState();

  var journey = [
    {
      icon: 'UI',
      title: 'React request',
      label: 'Stage 1 · Intent',
      description: 'The user asks microPass to deploy an application. The browser sends an HTTP POST request; it does not perform the deployment itself.',
      points: [
        'The UI gathers intent and shows feedback.',
        'The API remains the authority for validation and state.',
        'A fast HTTP response must not mean the deployment already succeeded.'
      ]
    },
    {
      icon: 'API',
      title: 'Spring API',
      label: 'Stage 2 · Validation',
      description: 'Spring validates the application identifier, checks that no deployment is already active, and creates a durable deployment record.',
      points: [
        'Validation protects the system even when the UI is bypassed.',
        'The new deployment begins in the QUEUED state.',
        'HTTP 202 Accepted means work was accepted, not completed.'
      ]
    },
    {
      icon: 'DB',
      title: 'PostgreSQL state',
      label: 'Stage 3 · Source of truth',
      description: 'PostgreSQL stores what microPass believes about applications and deployments. It lets the UI and recovery logic observe long-running work.',
      points: [
        'State survives a JVM restart.',
        'A state machine limits ambiguous transitions.',
        'The database cannot automatically undo external Docker operations.'
      ]
    },
    {
      icon: 'MQ',
      title: 'RabbitMQ queue',
      label: 'Stage 4 · Buffer and hand-off',
      description: 'The API publishes a small deployment event. RabbitMQ buffers it until a worker is ready and tracks whether it was acknowledged.',
      points: [
        'The queue decouples accepting work from executing work.',
        'Delivery is at least once, so duplicates are possible.',
        'Idempotency makes duplicate delivery harmless.'
      ]
    },
    {
      icon: 'GIT',
      title: 'Source sync',
      label: 'Stage 5 · Input material',
      description: 'The worker synchronizes the selected Git branch into an application workspace and records the exact commit being deployed.',
      points: [
        'A commit hash identifies the source revision.',
        'A persistent cache speeds later fetches.',
        'Cancellation must terminate the whole process tree.'
      ]
    },
    {
      icon: 'IMG',
      title: 'Docker build',
      label: 'Stage 6 · Package',
      description: 'Docker turns source plus a Dockerfile into an immutable image. Build output is captured and streamed as deployment logs.',
      points: [
        'An image is a template; a container is a running instance.',
        'The build context determines which files Docker can see.',
        'A successful build does not prove the application is ready.'
      ]
    },
    {
      icon: 'RUN',
      title: 'Candidate container',
      label: 'Stage 7 · Verify before promote',
      description: 'microPass starts a uniquely named candidate container on a free host port while the previous healthy container remains available.',
      points: [
        'Resource limits protect the host.',
        'A readiness probe requires an acceptable HTTP response.',
        'A failed candidate is removed without replacing the old app.'
      ]
    },
    {
      icon: 'SSE',
      title: 'State and live logs',
      label: 'Stage 8 · Observe',
      description: 'The worker records the final state and sends log events to browsers over Server-Sent Events. The queue message is acknowledged after processing returns.',
      points: [
        'SSE is a one-way server-to-browser stream.',
        'Historical logs come from the database after completion.',
        'An explicit terminal event tells the browser to stop reconnecting.'
      ]
    }
  ];

  var lessons = [
    {
      id: 'mental-model',
      title: 'System mental model',
      subtitle: 'Start here',
      level: 'Foundation',
      summary: 'See microPass as a control plane coordinating stateful, failure-prone external systems.',
      objectives: [
        ['Name the product', 'Explain what problem microPass solves.'],
        ['Trace the flow', 'Follow a request from browser to container.'],
        ['Find boundaries', 'Separate database state from real-world state.']
      ],
      idea: 'A platform is not one command. It is a coordinator around Git, Docker, networking, storage, and user feedback. microPass is the control plane: it decides what should happen, records progress, and calls tools that do the physical work.',
      mechanism: 'The main flow is request → durable deployment record → queued event → worker → Git → image → candidate container → readiness → promotion. Explicit states make this long operation visible.',
      failure: 'A database row may say DEPLOYING while the process has crashed, or Docker may start a container just before a database write fails. Engineers design reconciliation and idempotency because external side effects are not one atomic transaction.',
      terms: ['control plane', 'workload', 'state machine', 'side effect', 'reconciliation'],
      file: 'docs/PROJECT_SPEC.md and DeploymentPipelineService.java',
      challenge: 'Explain microPass in 60 seconds without listing technologies first. Start with the user problem, then describe the flow.'
    },
    {
      id: 'java-spring',
      title: 'Java 21 and Spring Boot',
      subtitle: 'Application structure',
      level: 'Foundation → Intermediate',
      summary: 'Understand dependency injection, controllers, services, repositories, blocking I/O, and virtual threads.',
      objectives: [
        ['Follow a request', 'Know which Spring layer owns which responsibility.'],
        ['Explain DI', 'Describe why dependencies are supplied through constructors.'],
        ['Place concurrency', 'Separate HTTP threads, queue consumers, and subprocesses.']
      ],
      idea: 'Spring Boot assembles objects and infrastructure so business code can focus on behavior. A controller translates HTTP, a service coordinates rules, and a repository persists domain objects. Java 21 virtual threads make blocking operations cheaper, but they do not make unreliable work durable.',
      mechanism: 'Constructor injection exposes dependencies and makes tests replace them with mocks. Spring Data generates repository implementations. ProcessBuilder starts operating-system programs. Virtual threads are useful for blocking I/O, while RabbitMQ controls when durable jobs are delivered.',
      failure: 'Common mistakes include putting business rules in controllers, confusing asynchronous execution with durability, calling an annotated method inside the same Spring bean and bypassing its proxy, or creating unbounded work just because virtual threads are cheap.',
      terms: ['dependency injection', 'bean', 'controller', 'service', 'repository', 'virtual thread'],
      file: 'AppController.java, DeploymentPipelineService.java, DeploymentRepository.java',
      challenge: 'Explain why a virtual thread cannot replace RabbitMQ, and why RabbitMQ does not replace a thread that executes the job.'
    },
    {
      id: 'rabbitmq',
      title: 'RabbitMQ and job delivery',
      subtitle: 'Durable asynchronous work',
      level: 'Intermediate',
      summary: 'Learn queue topology, acknowledgements, at-least-once delivery, idempotency, prefetch, and dead letters.',
      objectives: [
        ['Draw the topology', 'Producer → exchange → routing key → queue → consumer.'],
        ['Explain ACK', 'Know exactly when a message becomes removable.'],
        ['Handle duplicates', 'Design a consumer safe under redelivery.']
      ],
      idea: 'RabbitMQ is a message broker. The API publishes a small description of work, and a worker consumes it later. The broker is valuable because it buffers work and tracks delivery independently of the HTTP request.',
      mechanism: 'microPass uses a direct exchange and routing key to reach a durable deployment queue. Prefetch one prevents a consumer from reserving many builds. The listener returns only after pipeline processing, so acknowledgement represents completed handling. An atomic QUEUED-to-CLONING update claims the job exactly once at the application level.',
      failure: 'RabbitMQ promises at-least-once delivery, not exactly once. A consumer may finish work and crash before its ACK reaches the broker, causing redelivery. A dead-letter queue stores rejected messages for inspection; it is not a magical automatic fix.',
      terms: ['producer', 'exchange', 'routing key', 'queue', 'consumer', 'ACK', 'prefetch', 'DLQ', 'idempotency'],
      file: 'RabbitConfig.java, DeploymentWorker.java, DeploymentRepository.transitionStatus',
      challenge: 'Answer: What happens if the worker crashes after Docker starts a container but before RabbitMQ receives the ACK?'
    },
    {
      id: 'postgres',
      title: 'PostgreSQL and transactions',
      subtitle: 'Durable state',
      level: 'Intermediate',
      summary: 'Learn relational state, transactions, migrations, constraints, locking, and the limits of database atomicity.',
      objectives: [
        ['Model entities', 'Understand App and Deployment relationships.'],
        ['Protect transitions', 'Use constraints and atomic updates.'],
        ['Evolve schemas', 'Use Flyway instead of accidental production DDL.']
      ],
      idea: 'PostgreSQL is the durable source of application state. A transaction groups database changes so they either commit together or roll back together. It cannot roll back a Git process, network call, or Docker container because those systems are outside PostgreSQL.',
      mechanism: 'Flyway applies ordered SQL migrations and records which versions ran. Hibernate validates that entity mappings match the schema. Atomic update conditions, such as changing only QUEUED to CLONING, prevent two consumers from claiming the same deployment.',
      failure: 'H2 can hide PostgreSQL differences. ddl-auto=update is convenient during prototyping but does not document a production schema safely. Publishing a RabbitMQ event and committing a database row is a dual-write problem; a future transactional outbox can close that gap.',
      terms: ['transaction', 'constraint', 'migration', 'Flyway', 'isolation', 'locking', 'dual write', 'outbox'],
      file: 'V1__create_micropass_schema.sql and application-postgres.properties',
      challenge: 'Explain why @Transactional cannot make a Docker run command roll back when a database insert fails.'
    },
    {
      id: 'docker',
      title: 'Docker and deployment safety',
      subtitle: 'Execution plane',
      level: 'Foundation → Intermediate',
      summary: 'Understand images, containers, ports, build contexts, health, resources, and Docker socket risk.',
      objectives: [
        ['Separate image/container', 'Know package versus running process.'],
        ['Trace networking', 'Explain host-port to container-port mapping.'],
        ['Deploy safely', 'Health-check a candidate before promotion.']
      ],
      idea: 'A Docker image is an immutable filesystem and configuration template. A container is a process created from that image. microPass uses the Docker daemon as its execution plane.',
      mechanism: 'docker build reads a Dockerfile and build context. docker run creates a named candidate, maps a host port to the application port, and applies memory, CPU, and process limits. A readiness probe confirms that the application can serve traffic before the active record changes.',
      failure: 'Starting a container only proves its process was created. The application may still be booting or may return errors. Mounting the Docker socket gives root-equivalent control over the host, so microPass currently assumes trusted repositories and a trusted operator.',
      terms: ['image', 'container', 'Dockerfile', 'build context', 'port mapping', 'daemon', 'readiness'],
      file: 'DockerService.java, PortAllocationService.java, HealthCheckService.java',
      challenge: 'Explain why removing the old container before checking the new one creates avoidable downtime and rollback risk.'
    },
    {
      id: 'sse-react',
      title: 'SSE, HTTP, and React',
      subtitle: 'Real-time user experience',
      level: 'Foundation → Intermediate',
      summary: 'Understand request/response, one-way event streams, browser reconnect behavior, CORS, and UI state.',
      objectives: [
        ['Choose a protocol', 'Know why logs fit SSE.'],
        ['Handle lifecycle', 'Open, replay, complete, and close streams.'],
        ['Deploy the UI', 'Separate Vite development from production runtime.']
      ],
      idea: 'Normal HTTP returns one response. Server-Sent Events keep an HTTP response open so the server can send many text events. React renders application state and user actions; it should not invent backend truth.',
      mechanism: 'The browser opens EventSource for a deployment. The server replays buffered or historical lines, then broadcasts new lines. A named complete event closes the connection. During development, Vite proxies relative /api calls to Spring Boot; production can serve both from one origin.',
      failure: 'EventSource reconnects automatically after disconnection. If a completed stream closes without a terminal signal, the browser can reconnect forever and duplicate logs. Hardcoding localhost also breaks access from another machine.',
      terms: ['HTTP', 'SSE', 'EventSource', 'CORS', 'same origin', 'SPA', 'Vite proxy'],
      file: 'LogStreamService.java, LogConsoleModal.tsx, api.ts',
      challenge: 'Compare SSE and WebSockets for build logs, including one reason WebSockets could still be chosen.'
    },
    {
      id: 'testing-ci',
      title: 'Testing and continuous integration',
      subtitle: 'Evidence, not confidence',
      level: 'Foundation → Intermediate',
      summary: 'Learn the test pyramid, Testcontainers, deterministic fixtures, and CI quality gates.',
      objectives: [
        ['Choose a test', 'Match test type to the risk.'],
        ['Use real dependencies', 'Know when mocks are insufficient.'],
        ['Automate proof', 'Run verification on every change.']
      ],
      idea: 'Tests are executable evidence. Unit tests isolate logic, integration tests verify boundaries such as PostgreSQL, and end-to-end tests exercise the full user journey. More realism usually costs more time and complexity.',
      mechanism: 'JUnit and AssertJ test Java behavior. Mockito isolates collaborators. Testcontainers starts a real PostgreSQL instance for repository and migration tests. GitHub Actions repeats Maven and frontend builds in a clean environment.',
      failure: 'A test that depends on a developer machine is not reproducible. Mocking PostgreSQL cannot detect SQL or migration problems. An end-to-end test for every edge case becomes slow and fragile; most edge cases belong lower in the test pyramid.',
      terms: ['unit test', 'integration test', 'end-to-end test', 'mock', 'fixture', 'Testcontainers', 'CI'],
      file: 'backend/src/test, .github/workflows/ci.yml',
      challenge: 'Design one unit, one integration, and one end-to-end test for deployment cancellation.'
    },
    {
      id: 'cloud-devops',
      title: 'Cloud and DevOps foundations',
      subtitle: 'Think beyond one machine',
      level: 'Intermediate → Advanced',
      summary: 'Place Compose, Kubernetes, Terraform, metrics, logs, and traces in the correct problem space.',
      objectives: [
        ['Map responsibilities', 'Know provisioning versus orchestration versus delivery.'],
        ['Avoid tool collecting', 'Add technology for a real constraint.'],
        ['Design evolution', 'See how a single-node control plane could grow.']
      ],
      idea: 'DevOps is a way of reducing the distance between creating software and operating it safely. Cloud engineering adds programmable infrastructure, elastic resources, identity, networking, and managed services. Tools are implementations of responsibilities, not the responsibilities themselves.',
      mechanism: 'Docker Compose describes local multi-container dependencies. Terraform provisions infrastructure through provider APIs. Kubernetes continuously reconciles declared workloads across nodes. Prometheus stores numeric time-series metrics; centralized logs explain events; traces follow a request across components.',
      failure: 'Adding Kubernetes YAML to a single-host Docker tool does not automatically improve it. Every platform adds operational cost. Strong engineers can say why a technology is deferred and identify the condition that would justify it.',
      terms: ['IaC', 'orchestration', 'reconciliation', 'metric', 'log', 'trace', 'SLO', 'horizontal scaling'],
      file: 'docker-compose.yml and docs/IMPLEMENTATION_AND_LEARNING_PLAN.md',
      challenge: 'Answer: At what point would microPass need Kubernetes, and what part of the current Docker architecture would change?'
    }
  ];

  var comparisons = [
    {
      title: 'Virtual threads vs RabbitMQ',
      question: 'Both help with asynchronous work, but at different layers.',
      options: [
        ['Virtual threads', ['Run blocking Java tasks cheaply', 'Live inside one JVM', 'Disappear if the process crashes', 'Do not buffer or redeliver jobs']],
        ['RabbitMQ', ['Stores and routes messages', 'Lives outside the application', 'Can redeliver unacknowledged work', 'Does not execute Git or Docker itself']],
        ['Together in microPass', ['RabbitMQ decides when work is delivered', 'A Java thread executes the blocking pipeline', 'Database state makes handling idempotent', 'Each layer has one clear responsibility']]
      ],
      decision: 'Use RabbitMQ for durable hand-off and controlled consumption; use threads for the actual code execution.'
    },
    {
      title: 'RabbitMQ vs Kafka',
      question: 'A job queue and an event log optimize for different workloads.',
      options: [
        ['RabbitMQ', ['Excellent task queues and routing', 'Messages commonly disappear after ACK', 'Per-message delivery controls', 'Simple fit for deployment jobs']],
        ['Kafka', ['Durable ordered append-only log', 'Consumers track offsets', 'High-throughput event streaming', 'Useful when events must be replayed by many consumers']],
        ['microPass choice', ['Deployment is a command-like job', 'One worker group should handle it', 'ACK and DLQ semantics are useful', 'Kafka would add unjustified operational cost']]
      ],
      decision: 'Choose RabbitMQ now. Reconsider Kafka only if deployment events become a replayable analytics or integration stream.'
    },
    {
      title: 'SSE vs WebSocket vs polling',
      question: 'The direction and frequency of communication determine the protocol.',
      options: [
        ['SSE', ['Server → browser only', 'Plain HTTP text events', 'Built-in browser reconnect', 'Ideal for logs and progress']],
        ['WebSocket', ['Two-way full-duplex channel', 'Custom message protocol', 'Useful for terminals or collaborative editing', 'More lifecycle complexity']],
        ['Polling', ['Repeated normal requests', 'Simple and universally supported', 'Delayed updates and repeated overhead', 'Fine for low-frequency status']]
      ],
      decision: 'Use SSE for logs, occasional polling for container truth, and WebSockets only if microPass adds interactive terminal input.'
    },
    {
      title: 'H2 vs PostgreSQL',
      question: 'Developer convenience and production fidelity are different goals.',
      options: [
        ['H2', ['Embedded and zero setup', 'Fast for simple development', 'Different SQL and concurrency behavior', 'Can hide production bugs']],
        ['PostgreSQL', ['Real network database', 'Strong concurrency and SQL features', 'Requires setup and migrations', 'Appropriate durable platform state']],
        ['microPass strategy', ['H2 can remain an optional quick profile', 'PostgreSQL is the production-like profile', 'Testcontainers verifies real behavior', 'Flyway owns schema evolution']]
      ],
      decision: 'Use PostgreSQL for credible deployment state and integration testing; keep H2 only as an explicitly limited convenience.'
    },
    {
      title: 'Image vs container vs virtual machine',
      question: 'All package or isolate software, but at different levels.',
      options: [
        ['Docker image', ['Immutable package template', 'Contains filesystem and metadata', 'Does not run by itself', 'Can create many containers']],
        ['Container', ['Running processes from an image', 'Shares the host kernel', 'Lightweight and fast to start', 'Needs resource and security controls']],
        ['Virtual machine', ['Virtualizes a whole machine', 'Runs its own guest kernel', 'Stronger boundary but heavier', 'Useful for different kernels or stronger isolation']]
      ],
      decision: 'microPass uses containers for trusted workloads; stronger untrusted-code isolation would require technology such as microVMs.'
    },
    {
      title: 'Readiness vs liveness',
      question: 'Being alive is not the same as being ready for traffic.',
      options: [
        ['Readiness', ['Can this instance serve requests now?', 'Controls promotion or traffic', 'May fail during startup', 'microPass checks this before switching']],
        ['Liveness', ['Is the process stuck or unrecoverable?', 'Can trigger restart', 'Should not fail for temporary dependency issues', 'Not fully implemented yet']],
        ['Startup check', ['Allows long initialization', 'Prevents premature liveness failure', 'Common in orchestrators', 'Could become per-app configuration']]
      ],
      decision: 'v0.1 focuses on readiness. Add distinct liveness monitoring when microPass begins supervising long-running containers.'
    },
    {
      title: 'Unit vs integration vs end-to-end',
      question: 'Choose the smallest test that can expose the risk.',
      options: [
        ['Unit', ['One class or function', 'Fast and isolated', 'Mocks external dependencies', 'Great for timeout and state logic']],
        ['Integration', ['Multiple real components', 'Tests boundaries and configuration', 'Testcontainers PostgreSQL fits here', 'Slower than unit tests']],
        ['End-to-end', ['Full user journey', 'Highest confidence in wiring', 'Slowest and most fragile', 'Use for a few critical paths']]
      ],
      decision: 'Keep many focused unit tests, fewer integration tests, and a small number of deployment journeys.'
    },
    {
      title: 'Docker Compose vs Terraform vs Kubernetes',
      question: 'These tools answer different operational questions.',
      options: [
        ['Compose', ['How do local containers run together?', 'Developer and single-host environments', 'Networks, volumes, environment', 'Not a cloud provisioner']],
        ['Terraform', ['How is infrastructure created?', 'Calls cloud/provider APIs', 'Versioned desired infrastructure', 'Does not supervise application processes']],
        ['Kubernetes', ['How are workloads reconciled across nodes?', 'Scheduling, rollout, service discovery', 'Large operational surface', 'Would replace direct Docker execution']]
      ],
      decision: 'Compose is enough for v0.1 dependencies. Add Terraform with a real remote host, and Kubernetes with a real multi-node execution requirement.'
    }
  ];

  var flashcards = [
    ['Architecture', 'What is a control plane?', 'The component that decides and records what should run, then instructs an execution system to make it true.', 'In microPass, Spring/PostgreSQL form the control plane and Docker runs workloads.'],
    ['HTTP', 'What does HTTP 202 Accepted mean?', 'The server accepted the request for asynchronous processing; it does not mean the deployment succeeded.', 'Return a deployment ID so the client can observe progress.'],
    ['RabbitMQ', 'Why must a RabbitMQ consumer be idempotent?', 'Because delivery is at least once, so the same message can be processed again after a crash or lost acknowledgement.', 'Use a conditional state transition to claim work once.'],
    ['RabbitMQ', 'What is an acknowledgement?', 'A signal from the consumer telling the broker that a delivered message was handled and can be removed.', 'Do not ACK merely because another background thread was started.'],
    ['RabbitMQ', 'What does prefetch control?', 'How many unacknowledged messages RabbitMQ may deliver to a consumer in advance.', 'Prefetch one is sensible for heavy deployment jobs.'],
    ['Database', 'Why use Flyway instead of ddl-auto=update in production?', 'Flyway makes schema changes explicit, ordered, reviewable, and repeatable across environments.', 'Hibernate validation can then detect mapping drift.'],
    ['Database', 'What is the dual-write problem?', 'The risk of updating two independent systems, such as PostgreSQL and RabbitMQ, when only one update succeeds.', 'A transactional outbox is a common future solution.'],
    ['Docker', 'Image versus container?', 'An image is an immutable package template; a container is a running process instance created from it.', 'One image can create many containers.'],
    ['Docker', 'Why health-check a candidate before replacing the old container?', 'A successful start does not prove the application is ready; checking first preserves the working version on failure.', 'This is safer replacement, even without a reverse proxy.'],
    ['Networking', 'What does 9100:8080 mean in Docker port mapping?', 'Connections to host port 9100 are forwarded to port 8080 inside the container.', 'The application must listen on 0.0.0.0 inside the container.'],
    ['SSE', 'Why is SSE suitable for deployment logs?', 'Logs flow mainly from server to browser, and SSE provides a simple text stream over normal HTTP with reconnect support.', 'WebSockets are useful if the browser must also send interactive terminal input.'],
    ['Testing', 'Why use Testcontainers for PostgreSQL?', 'It runs tests against the real database engine, exposing SQL, type, migration, and concurrency differences that H2 or mocks can hide.', 'The test remains isolated and disposable.'],
    ['CI/CD', 'Difference between CI and CD?', 'CI automatically integrates and verifies changes. Delivery/deployment automates making verified artifacts releasable or running them.', 'A green build is a gate, not proof of production health.'],
    ['Reliability', 'Readiness versus liveness?', 'Readiness asks whether an instance can receive traffic; liveness asks whether it is irrecoverably stuck and should restart.', 'Temporary dependency failure should not always trigger liveness restart.'],
    ['Security', 'Why is Docker socket access dangerous?', 'The Docker API can mount host files, start privileged containers, and effectively obtain root-level host control.', 'microPass must assume trusted operators and repositories for now.'],
    ['Interview', 'What is the strongest structure for explaining a design choice?', 'Problem and constraints → decision → mechanism → trade-off → evidence and next improvement.', 'This shows engineering reasoning instead of tool memorization.']
  ];

  var quiz = [
    {
      question: 'The API saved a QUEUED deployment, but the RabbitMQ publish failed. What is the most honest immediate behavior?',
      options: ['Start an invisible local thread and pretend the queue worked', 'Leave the deployment QUEUED forever', 'Mark it failed and return a service-unavailable error', 'Delete the application'],
      answer: 2,
      explanation: 'A visible failure preserves a truthful state model. A production fallback with different semantics makes incidents and interview explanations harder.'
    },
    {
      question: 'Why was starting a virtual thread inside the Rabbit listener unsafe?',
      options: ['Virtual threads cannot run Docker', 'The listener returned and RabbitMQ could ACK before the pipeline finished', 'RabbitMQ supports only platform threads', 'It made JSON conversion impossible'],
      answer: 1,
      explanation: 'Acknowledgement follows listener completion. Returning after launching detached work can lose the job if the JVM crashes.'
    },
    {
      question: 'RabbitMQ redelivers deployment 42. Its database state is already CLONING. What should the duplicate consumer do?',
      options: ['Start another clone', 'Delete the first worker', 'Skip it because the atomic QUEUED claim fails', 'Reset it to QUEUED'],
      answer: 2,
      explanation: 'The state transition is the idempotency guard. Only one consumer can successfully claim QUEUED.'
    },
    {
      question: 'A readiness endpoint returns HTTP 500. Is the candidate ready?',
      options: ['Yes, any response proves readiness', 'Yes, because Docker is running', 'No, a server error is not an acceptable ready response', 'Only if RabbitMQ is empty'],
      answer: 2,
      explanation: 'Network reachability alone is weaker than application readiness. v0.1 accepts 2xx and 3xx responses.'
    },
    {
      question: 'Where should a Dockerfile path such as ../../etc/passwd be rejected?',
      options: ['Only in React', 'At the backend workspace boundary', 'RabbitMQ management UI', 'PostgreSQL automatically'],
      answer: 1,
      explanation: 'Client validation improves UX, but backend normalization and starts-with checks enforce the security boundary.'
    },
    {
      question: 'Which failure can a PostgreSQL transaction automatically roll back?',
      options: ['A row insert in the same transaction', 'A completed docker run command', 'A GitHub network request', 'A RabbitMQ message already consumed by another process'],
      answer: 0,
      explanation: 'A database transaction controls database work on its connection. External side effects need compensation or reconciliation.'
    },
    {
      question: 'Why can EventSource duplicate completed logs if the protocol has no terminal event?',
      options: ['React always renders twice in production', 'EventSource automatically reconnects after the server closes', 'SSE stores messages in PostgreSQL', 'Docker repeats stdout'],
      answer: 1,
      explanation: 'Automatic reconnect is useful during transient failure, but the client must distinguish terminal completion from interruption.'
    },
    {
      question: 'Which test best detects that a Flyway migration uses invalid PostgreSQL SQL?',
      options: ['A Mockito repository test', 'A TypeScript build', 'A PostgreSQL Testcontainers integration test', 'A CSS snapshot'],
      answer: 2,
      explanation: 'Run the migration against the same engine family used in production.'
    },
    {
      question: 'When is Kubernetes justified for microPass?',
      options: ['Immediately because it looks good on a CV', 'When multi-node scheduling and workload reconciliation become real requirements', 'To replace React', 'To store build logs'],
      answer: 1,
      explanation: 'A tool is justified by a constraint. Kubernetes would become a new execution backend, not a decorative folder of YAML.'
    },
    {
      question: 'What does limiting RabbitMQ prefetch to one protect?',
      options: ['It encrypts messages', 'It prevents one worker from reserving many expensive deployments', 'It creates database migrations', 'It removes duplicate Git commits'],
      answer: 1,
      explanation: 'Heavy jobs need backpressure. Prefetch controls outstanding unacknowledged deliveries.'
    },
    {
      question: 'A new candidate fails health checks while an old container is running. What should happen?',
      options: ['Remove both containers', 'Promote the failed candidate', 'Remove the candidate and keep the old container active', 'Mark the old deployment QUEUED'],
      answer: 2,
      explanation: 'Safe replacement protects the last known good version.'
    },
    {
      question: 'Which answer sounds strongest in an interview?',
      options: ['I used RabbitMQ because companies use it', 'RabbitMQ is scalable', 'I needed durable queued work; I used ACK-after-processing and an idempotent claim, accepting at-least-once delivery', 'RabbitMQ is better than every alternative'],
      answer: 2,
      explanation: 'The strongest answer names the requirement, mechanism, delivery guarantee, and trade-off.'
    }
  ];

  var interviews = [
    {
      question: 'Explain microPass architecture.',
      context: 'Developers need a simple way to turn a trusted Git repository into a running container on one machine.',
      decision: 'I built a modular Spring Boot control plane with PostgreSQL state, RabbitMQ job delivery, Docker execution, SSE logs, and a React dashboard.',
      mechanism: 'The API stores a QUEUED deployment and publishes its ID. A worker atomically claims it, syncs Git, builds an image, starts a candidate, checks readiness, promotes it, persists logs, and then returns so RabbitMQ can ACK.',
      tradeoff: 'It is deliberately single-node and trusts submitted code. This avoids Kubernetes complexity but does not provide strong multi-tenant isolation.',
      evidence: 'The repository includes focused unit tests, PostgreSQL Testcontainers coverage, CI, explicit states, cancellation, timeouts, and documented failure paths.',
      followups: ['What happens during a crash?', 'Why RabbitMQ instead of @Async?', 'How would you scale workers?']
    },
    {
      question: 'Why did you choose RabbitMQ?',
      context: 'Docker builds are slow and should not hold an HTTP request open.',
      decision: 'I used RabbitMQ as a durable job hand-off between request acceptance and pipeline execution.',
      mechanism: 'A direct exchange routes a persistent event to a durable queue. Prefetch is one, the listener processes synchronously, and an atomic state transition makes redelivery idempotent.',
      tradeoff: 'RabbitMQ adds an operational dependency and creates a database/message dual-write problem. For a tiny prototype, an in-process executor would be simpler.',
      evidence: 'The implementation includes ACK-after-processing semantics, a dead-letter queue, duplicate protection, and explicit broker-unavailable behavior.',
      followups: ['RabbitMQ versus Kafka?', 'What is at-least-once delivery?', 'Would you use an outbox?']
    },
    {
      question: 'How do you prevent concurrent deployment corruption?',
      context: 'Two jobs for one app would share its source cache and compete over container and state updates.',
      decision: 'I reject a second active deployment, serialize work per application, and atomically claim each queued deployment once.',
      mechanism: 'The API checks active states, the worker uses a per-app lock, and the repository updates QUEUED to CLONING only if the expected state still exists.',
      tradeoff: 'The current API-level check is designed for one control-plane instance. Multiple API replicas would need stronger database locking or a unique active-job constraint.',
      evidence: 'There is a test for active-deployment rejection and the state transition returns a row count that proves ownership.',
      followups: ['Why is an in-memory lock insufficient across replicas?', 'Could RabbitMQ routing serialize by app?', 'What is optimistic locking?']
    },
    {
      question: 'How does safe replacement work?',
      context: 'Removing the working container before proving the new version creates downtime and rollback risk.',
      decision: 'Each deployment starts a uniquely named candidate on a free port while the old container continues running.',
      mechanism: 'microPass waits for an acceptable readiness response, updates the active container record, then removes the previous container. A failed candidate is removed instead.',
      tradeoff: 'Without a reverse proxy, the public port can change, so this is safe replacement but not stable-URL zero downtime.',
      evidence: 'The lifecycle code keeps the old activeContainerId until the candidate passes and cleans failed candidates.',
      followups: ['How would Caddy or Traefik improve this?', 'What if the database update succeeds but old-container removal fails?', 'How would rollback work?']
    },
    {
      question: 'How do cancellation and timeout work?',
      context: 'Git and Docker can hang or spawn child processes, and users need to stop expensive work.',
      decision: 'I track the active Process, use a cancellation flag, enforce a real deadline while output is drained concurrently, and kill descendants before the parent.',
      mechanism: 'A virtual reader thread consumes stdout so pipes cannot block. The waiting loop checks cancellation and deadline every 100 ms. ProcessHandle terminates the process tree.',
      tradeoff: 'Forceful termination can leave external partial artifacts, so workspace and candidate cleanup remain necessary.',
      evidence: 'Unit tests execute sleeping shell processes and verify both timeout and cancellation complete quickly.',
      followups: ['Why was waitFor after stream reading ineffective?', 'What about Windows process trees?', 'Why merge stdout and stderr?']
    },
    {
      question: 'Why PostgreSQL and Flyway?',
      context: 'Deployment state must survive restarts and behave correctly under concurrent updates.',
      decision: 'PostgreSQL is the production-like database, and Flyway owns explicit versioned schema changes.',
      mechanism: 'Flyway applies SQL migrations before Hibernate validates mappings. Testcontainers runs those migrations against a disposable real PostgreSQL instance.',
      tradeoff: 'This adds local infrastructure compared with H2, but eliminates an important class of false confidence.',
      evidence: 'The Compose stack includes PostgreSQL health checks and CI runs the repository integration test with Testcontainers.',
      followups: ['What migration strategy would you use with existing data?', 'Why not ddl-auto=update?', 'What isolation level is used?']
    },
    {
      question: 'Why SSE rather than WebSockets?',
      context: 'Deployment output primarily flows from the server to the browser.',
      decision: 'I selected Server-Sent Events because they model a one-way text event stream over normal HTTP.',
      mechanism: 'The browser opens EventSource, receives replayed and live lines, and closes on a named completion event.',
      tradeoff: 'SSE is not appropriate for a fully interactive terminal where the browser must continuously send input. WebSockets would fit that requirement.',
      evidence: 'The implementation handles late subscribers, bounded replay, emitter cleanup, and terminal reconnect behavior.',
      followups: ['How does reconnect work?', 'How would multiple instances share log streams?', 'Why not polling?']
    },
    {
      question: 'What is the largest security risk?',
      context: 'microPass builds user-controlled Dockerfiles through a daemon with strong host privileges.',
      decision: 'v0.1 explicitly supports trusted operators and trusted repositories only, validates filesystem paths, and applies runtime resource limits.',
      mechanism: 'Commands use argument arrays rather than shell concatenation, Dockerfile paths remain inside the workspace, and containers receive memory, CPU, and PID caps.',
      tradeoff: 'These controls do not make hostile code safe. Strong isolation would require rootless execution, separate workers, hardened policies, or microVMs.',
      evidence: 'The threat is documented instead of hidden behind a false production-ready claim.',
      followups: ['Why is docker.sock root-equivalent?', 'Would read-only filesystems help?', 'How would secrets be protected?']
    }
  ];

  var glossary = [
    ['ACK', 'A consumer signal confirming that a broker-delivered message was handled and may be removed.', 'RabbitMQ'],
    ['At-least-once delivery', 'A guarantee that a message is not silently lost, while accepting that it may be delivered more than once.', 'Messaging'],
    ['Backpressure', 'A mechanism that prevents producers or brokers from overwhelming slower consumers.', 'Reliability'],
    ['Bean', 'An object whose creation and lifecycle are managed by the Spring container.', 'Spring'],
    ['Build context', 'The directory tree Docker can access during docker build.', 'Docker'],
    ['CI', 'Automated integration and verification of changes, usually on every push or pull request.', 'Delivery'],
    ['Container', 'A running isolated process instance created from an image while sharing the host kernel.', 'Docker'],
    ['Control plane', 'The part of a platform that decides, records, and coordinates desired behavior.', 'Architecture'],
    ['CORS', 'Browser rules and server headers controlling cross-origin requests.', 'Web'],
    ['Dead-letter queue', 'A queue that receives messages rejected or expired from their original queue.', 'RabbitMQ'],
    ['Dependency injection', 'Supplying an object with its collaborators instead of letting it construct them internally.', 'Design'],
    ['Docker daemon', 'The privileged service that builds images and manages containers.', 'Docker'],
    ['Dual write', 'One operation updating two independent systems with no shared atomic transaction.', 'Distributed systems'],
    ['EventSource', 'The browser API used to receive Server-Sent Events.', 'Web'],
    ['Exchange', 'A RabbitMQ routing component that sends published messages to bound queues.', 'RabbitMQ'],
    ['Flyway', 'A database migration tool that applies ordered, versioned scripts.', 'Database'],
    ['Health check', 'A probe used to decide whether a service is ready or alive.', 'Operations'],
    ['Idempotency', 'The property that repeating an operation does not create an incorrect additional effect.', 'Reliability'],
    ['Image', 'An immutable packaged filesystem and configuration used to create containers.', 'Docker'],
    ['Integration test', 'A test verifying multiple real components or an important external boundary together.', 'Testing'],
    ['Liveness', 'Whether a process is functioning enough to continue or should be restarted.', 'Operations'],
    ['Migration', 'A versioned change to database structure or data.', 'Database'],
    ['Observability', 'The ability to understand internal system behavior through outputs such as metrics, logs, and traces.', 'Operations'],
    ['Prefetch', 'The maximum number of unacknowledged messages delivered ahead to a RabbitMQ consumer.', 'RabbitMQ'],
    ['Readiness', 'Whether an instance can safely receive traffic now.', 'Operations'],
    ['Reconciliation', 'Comparing desired and actual state and acting to remove differences.', 'Platforms'],
    ['Repository', 'A data-access abstraction; in Spring Data it provides generated persistence operations.', 'Spring'],
    ['Routing key', 'A value an exchange uses to decide which queue bindings match a message.', 'RabbitMQ'],
    ['Side effect', 'A change outside a pure computation, such as writing a row or starting a container.', 'Design'],
    ['SSE', 'A one-way stream of text events from an HTTP server to a browser.', 'Web'],
    ['State machine', 'A model with defined states and allowed transitions between them.', 'Architecture'],
    ['Testcontainers', 'A library that starts disposable real dependencies such as PostgreSQL for integration tests.', 'Testing'],
    ['Transaction', 'A group of database operations committed or rolled back as one unit.', 'Database'],
    ['Virtual thread', 'A lightweight JVM-managed thread designed to make blocking concurrency inexpensive.', 'Java'],
    ['Workload', 'The application process or job that a platform runs and supervises.', 'Platforms']
  ];

  var currentJourney = 0;
  var currentLesson = 0;
  var currentComparison = 0;
  var currentFlash = 0;
  var quizIndex = 0;
  var quizScore = 0;
  var quizAnswered = false;

  function loadState() {
    try {
      var saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}');
      return {
        completed: Array.isArray(saved.completed) ? saved.completed : [],
        confidence: saved.confidence || {},
        quizCompleted: Boolean(saved.quizCompleted),
        quizBest: Number(saved.quizBest || 0),
        notes: saved.notes || {},
        theme: saved.theme || 'dark'
      };
    } catch (error) {
      return { completed: [], confidence: {}, quizCompleted: false, quizBest: 0, notes: {}, theme: 'dark' };
    }
  }

  function saveState() {
    var save = document.getElementById('saveState');
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (error) {
      save.textContent = 'Progress is available for this session only';
      updateProgress();
      return;
    }
    save.classList.add('saved');
    window.setTimeout(function () { save.classList.remove('saved'); }, 600);
    updateProgress();
  }

  function htmlEscape(value) {
    return String(value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function renderJourney(index) {
    currentJourney = index;
    var steps = document.querySelectorAll('.journey-step');
    steps.forEach(function (step, stepIndex) {
      step.classList.toggle('active', stepIndex === index);
    });
    document.getElementById('journeyProgress').style.width = ((index / (journey.length - 1)) * 100) + '%';
    var stage = journey[index];
    document.getElementById('journeyDetail').innerHTML =
      '<div><span class="stage-label">' + stage.label + '</span><h3>' + stage.title + '</h3><p>' + stage.description + '</p></div>' +
      '<ul>' + stage.points.map(function (point) { return '<li>' + point + '</li>'; }).join('') + '</ul>';
  }

  function initJourney() {
    document.getElementById('journeySteps').innerHTML = journey.map(function (stage, index) {
      return '<button class="journey-step' + (index === 0 ? ' active' : '') + '" type="button" data-journey="' + index + '">' +
        '<b>' + stage.icon + '</b><span>' + stage.title + '</span></button>';
    }).join('');
    document.querySelectorAll('[data-journey]').forEach(function (button) {
      button.addEventListener('click', function () { renderJourney(Number(button.dataset.journey)); });
    });
    renderJourney(0);
  }

  function renderLessonList() {
    document.getElementById('lessonList').innerHTML = lessons.map(function (lesson, index) {
      var done = state.completed.indexOf(lesson.id) !== -1;
      return '<button class="lesson-button' + (index === currentLesson ? ' active' : '') + (done ? ' done' : '') +
        '" type="button" role="tab" data-lesson="' + index + '">' +
        '<span class="lesson-index">' + String(index + 1).padStart(2, '0') + '</span>' +
        '<span><strong>' + lesson.title + '</strong><small>' + lesson.subtitle + '</small></span>' +
        '<span class="lesson-check">' + (done ? '✓' : '○') + '</span></button>';
    }).join('');
    document.querySelectorAll('[data-lesson]').forEach(function (button) {
      button.addEventListener('click', function () {
        currentLesson = Number(button.dataset.lesson);
        renderLessonList();
        renderLesson();
      });
    });
  }

  function renderLesson() {
    var lesson = lessons[currentLesson];
    var done = state.completed.indexOf(lesson.id) !== -1;
    document.getElementById('lessonContent').innerHTML =
      '<header class="lesson-header"><div><span class="eyebrow">Module ' + String(currentLesson + 1).padStart(2, '0') +
      '</span><h3>' + lesson.title + '</h3><p>' + lesson.summary + '</p></div><span class="level-pill">' + lesson.level + '</span></header>' +
      '<div class="objective-list">' + lesson.objectives.map(function (objective) {
        return '<div><b>' + objective[0] + '</b>' + objective[1] + '</div>';
      }).join('') + '</div>' +
      '<div class="lesson-block"><h4>First principle</h4><p>' + lesson.idea + '</p></div>' +
      '<div class="lesson-block"><h4>How it works in microPass</h4><p>' + lesson.mechanism + '</p></div>' +
      '<div class="lesson-block"><h4>Failure mode and engineering caution</h4><p>' + lesson.failure + '</p></div>' +
      '<div class="lesson-block"><h4>Vocabulary to own</h4><div class="term-row">' +
      lesson.terms.map(function (term) { return '<span class="term-chip">' + term + '</span>'; }).join('') + '</div></div>' +
      '<div class="code-link">Trace this in <code>' + htmlEscape(lesson.file) + '</code></div>' +
      '<div class="explain-challenge"><span>Explain it aloud</span><p>' + lesson.challenge + '</p></div>' +
      '<div class="lesson-actions"><button class="secondary-button compact" type="button" id="lessonPrevious"' +
      (currentLesson === 0 ? ' disabled' : '') + '>← Previous</button>' +
      '<button class="primary-button compact" type="button" id="lessonComplete">' +
      (done ? 'Completed ✓' : 'Mark understood') + '</button></div>';

    document.getElementById('lessonPrevious').addEventListener('click', function () {
      if (currentLesson > 0) {
        currentLesson -= 1;
        renderLessonList();
        renderLesson();
      }
    });
    document.getElementById('lessonComplete').addEventListener('click', function () {
      var position = state.completed.indexOf(lesson.id);
      if (position === -1) {
        state.completed.push(lesson.id);
        if (currentLesson < lessons.length - 1) currentLesson += 1;
      } else {
        state.completed.splice(position, 1);
      }
      saveState();
      renderLessonList();
      renderLesson();
    });
  }

  function initLessons() {
    renderLessonList();
    renderLesson();
  }

  function renderComparison() {
    var comparison = comparisons[currentComparison];
    document.querySelectorAll('.compare-tab').forEach(function (tab, index) {
      tab.classList.toggle('active', index === currentComparison);
    });
    document.getElementById('compareContent').innerHTML =
      '<div class="compare-title"><span class="eyebrow">Boundary thinking</span><h3>' + comparison.title +
      '</h3><p>' + comparison.question + '</p></div><div class="comparison-grid">' +
      comparison.options.map(function (option, index) {
        return '<article class="comparison-card' + (index === comparison.options.length - 1 ? ' featured' : '') +
          '"><h4>' + option[0] + '</h4><ul>' +
          option[1].map(function (point) { return '<li>' + point + '</li>'; }).join('') +
          '</ul></article>';
      }).join('') + '</div><div class="decision-line"><b>Engineering decision:</b> ' + comparison.decision + '</div>';
  }

  function initComparisons() {
    document.getElementById('compareTabs').innerHTML = comparisons.map(function (comparison, index) {
      return '<button class="compare-tab' + (index === 0 ? ' active' : '') + '" type="button" data-comparison="' +
        index + '">' + comparison.title + '</button>';
    }).join('');
    document.querySelectorAll('[data-comparison]').forEach(function (tab) {
      tab.addEventListener('click', function () {
        currentComparison = Number(tab.dataset.comparison);
        renderComparison();
      });
    });
    renderComparison();
  }

  function renderFlashcard() {
    var card = flashcards[currentFlash];
    var element = document.getElementById('flashcard');
    element.classList.remove('flipped');
    document.getElementById('flashCategory').textContent = card[0];
    document.getElementById('flashCount').textContent = (currentFlash + 1) + ' / ' + flashcards.length;
    document.getElementById('flashQuestion').textContent = card[1];
    document.getElementById('flashAnswer').textContent = card[2];
    document.getElementById('flashTip').textContent = 'microPass connection: ' + card[3];
  }

  function moveFlash(amount) {
    currentFlash = (currentFlash + amount + flashcards.length) % flashcards.length;
    renderFlashcard();
  }

  function initFlashcards() {
    renderFlashcard();
    document.getElementById('flashcard').addEventListener('click', function () {
      this.classList.toggle('flipped');
    });
    document.getElementById('flashPrev').addEventListener('click', function () { moveFlash(-1); });
    document.getElementById('flashNext').addEventListener('click', function () { moveFlash(1); });
    document.querySelectorAll('[data-confidence]').forEach(function (button) {
      button.addEventListener('click', function () {
        state.confidence[currentFlash] = button.dataset.confidence;
        saveState();
        moveFlash(1);
      });
    });
    document.addEventListener('keydown', function (event) {
      var tag = document.activeElement && document.activeElement.tagName;
      if (tag === 'TEXTAREA' || tag === 'INPUT' || tag === 'SELECT') return;
      if (event.code === 'Space') {
        event.preventDefault();
        document.getElementById('flashcard').classList.toggle('flipped');
      } else if (event.key === 'ArrowRight') {
        moveFlash(1);
      } else if (event.key === 'ArrowLeft') {
        moveFlash(-1);
      }
    });
  }

  function renderQuiz() {
    quizAnswered = false;
    document.getElementById('quizNext').disabled = true;
    document.getElementById('quizProgressText').textContent = 'Question ' + (quizIndex + 1) + ' of ' + quiz.length;
    document.getElementById('quizScore').textContent = quizScore;
    document.getElementById('quizBar').style.width = ((quizIndex / quiz.length) * 100) + '%';
    var item = quiz[quizIndex];
    document.getElementById('quizCard').innerHTML =
      '<div class="quiz-question"><h3>' + item.question + '</h3><div class="quiz-options">' +
      item.options.map(function (option, index) {
        return '<button class="quiz-option" type="button" data-option="' + index + '"><b>' +
          String.fromCharCode(65 + index) + '</b><span>' + option + '</span></button>';
      }).join('') + '</div><div id="quizExplanation"></div></div>';
    document.querySelectorAll('[data-option]').forEach(function (button) {
      button.addEventListener('click', function () { answerQuiz(Number(button.dataset.option)); });
    });
  }

  function answerQuiz(selected) {
    if (quizAnswered) return;
    quizAnswered = true;
    var item = quiz[quizIndex];
    if (selected === item.answer) quizScore += 1;
    document.getElementById('quizScore').textContent = quizScore;
    document.querySelectorAll('[data-option]').forEach(function (button) {
      var index = Number(button.dataset.option);
      button.disabled = true;
      if (index === item.answer) button.classList.add('correct');
      if (index === selected && index !== item.answer) button.classList.add('wrong');
    });
    document.getElementById('quizExplanation').innerHTML =
      '<div class="quiz-explanation"><b>' + (selected === item.answer ? 'Correct reasoning' : 'Study the boundary') +
      ':</b> ' + item.explanation + '</div>';
    document.getElementById('quizNext').disabled = false;
  }

  function nextQuiz() {
    if (!quizAnswered) return;
    if (quizIndex < quiz.length - 1) {
      quizIndex += 1;
      renderQuiz();
      return;
    }
    state.quizCompleted = true;
    state.quizBest = Math.max(state.quizBest, quizScore);
    saveState();
    document.getElementById('quizBar').style.width = '100%';
    document.getElementById('quizProgressText').textContent = 'Complete';
    document.getElementById('quizCard').innerHTML =
      '<div class="quiz-result"><strong>' + quizScore + '/' + quiz.length + '</strong><h3>' +
      (quizScore >= 10 ? 'Strong engineering reasoning' : quizScore >= 7 ? 'Good foundation—review the explanations' : 'Use the result as your next study map') +
      '</h3><p>Your best score is ' + state.quizBest + '. Retake the quiz after explaining weak topics aloud.</p></div>';
    document.getElementById('quizNext').disabled = true;
  }

  function resetQuiz() {
    quizIndex = 0;
    quizScore = 0;
    renderQuiz();
  }

  function initQuiz() {
    renderQuiz();
    document.getElementById('quizNext').addEventListener('click', nextQuiz);
    document.getElementById('quizReset').addEventListener('click', resetQuiz);
  }

  function renderInterviewFrame() {
    document.getElementById('answerFrame').innerHTML = [
      ['1', 'Context', 'What problem and constraint existed?'],
      ['2', 'Decision', 'What did you choose and why?'],
      ['3', 'Mechanism', 'How does it actually work?'],
      ['4', 'Trade-off', 'What cost or limitation did you accept?'],
      ['5', 'Evidence', 'What test, metric, or failure proves it?']
    ].map(function (step) {
      return '<div class="frame-step"><b>' + step[0] + '</b><div><strong>' + step[1] +
        '</strong><span>' + step[2] + '</span></div></div>';
    }).join('');
  }

  function revealInterview() {
    var item = interviews[Number(document.getElementById('interviewSelect').value)];
    document.getElementById('modelAnswer').innerHTML =
      '<div class="model-answer-content"><h3>' + item.question + '</h3>' +
      [['Context', item.context], ['Decision', item.decision], ['Mechanism', item.mechanism], ['Trade-off', item.tradeoff], ['Evidence', item.evidence]]
        .map(function (part) { return '<div class="answer-part"><b>' + part[0] + '</b><p>' + part[1] + '</p></div>'; }).join('') +
      '<div class="follow-ups"><b>Likely follow-ups</b><ul>' +
      item.followups.map(function (question) { return '<li>' + question + '</li>'; }).join('') + '</ul></div></div>';
  }

  function initInterview() {
    var select = document.getElementById('interviewSelect');
    select.innerHTML = interviews.map(function (item, index) {
      return '<option value="' + index + '">' + item.question + '</option>';
    }).join('');
    renderInterviewFrame();
    document.getElementById('revealAnswer').addEventListener('click', revealInterview);
    select.addEventListener('change', function () {
      document.getElementById('modelAnswer').innerHTML =
        '<div class="answer-placeholder"><span>✦</span><h3>Build your answer first</h3><p>Speak for 60–90 seconds using the frame, then compare with the model response.</p></div>';
    });
  }

  function renderGlossary(filter) {
    var query = (filter || '').trim().toLowerCase();
    var matches = glossary.filter(function (item) {
      return item.join(' ').toLowerCase().indexOf(query) !== -1;
    });
    document.getElementById('glossaryCount').textContent = matches.length + ' terms';
    document.getElementById('glossaryGrid').innerHTML = matches.map(function (item) {
      return '<article class="glossary-card reveal visible"><h3>' + item[0] + '</h3><p>' + item[1] +
        '</p><small>' + item[2] + '</small></article>';
    }).join('');
  }

  function initGlossary() {
    renderGlossary('');
    document.getElementById('glossarySearch').addEventListener('input', function () {
      renderGlossary(this.value);
    });
  }

  function initNotes() {
    var fields = {
      noteLearned: 'learned',
      noteUnclear: 'unclear',
      noteExplanation: 'explanation'
    };
    Object.keys(fields).forEach(function (elementId) {
      var element = document.getElementById(elementId);
      element.value = state.notes[fields[elementId]] || '';
      element.addEventListener('input', function () {
        state.notes[fields[elementId]] = element.value;
        document.getElementById('noteStatus').textContent = 'Saving...';
        window.clearTimeout(element.saveTimer);
        element.saveTimer = window.setTimeout(function () {
          saveState();
          document.getElementById('noteStatus').textContent = 'Notes saved in this browser.';
        }, 350);
      });
    });
    document.getElementById('resetProgress').addEventListener('click', function () {
      if (!window.confirm('Reset completed lessons, quiz results, confidence, and all notes?')) return;
      localStorage.removeItem(STORAGE_KEY);
      window.location.reload();
    });
  }

  function calculateProgress() {
    var lessonPoints = (state.completed.length / lessons.length) * 65;
    var knownCards = Object.keys(state.confidence).filter(function (key) {
      return state.confidence[key] === 'know';
    }).length;
    var flashPoints = (knownCards / flashcards.length) * 15;
    var quizPoints = state.quizCompleted ? Math.max(5, (state.quizBest / quiz.length) * 20) : 0;
    return Math.min(100, Math.round(lessonPoints + flashPoints + quizPoints));
  }

  function updateProgress() {
    var progress = calculateProgress();
    document.getElementById('heroPercent').textContent = progress + '%';
    document.getElementById('sidebarPercent').textContent = progress + '%';
    document.getElementById('sidebarBar').style.width = progress + '%';
    document.getElementById('progressCircle').style.strokeDashoffset = String(364.42 * (1 - progress / 100));
    var next = lessons.find(function (lesson) { return state.completed.indexOf(lesson.id) === -1; });
    if (next) {
      document.getElementById('nextLessonTitle').textContent = next.title;
      document.getElementById('nextLessonText').textContent = next.summary;
      document.getElementById('sidebarHint').textContent = 'Next: ' + next.title;
    } else {
      document.getElementById('nextLessonTitle').textContent = 'Practice explanation';
      document.getElementById('nextLessonText').textContent = 'All lessons complete. Strengthen recall with interview scenarios.';
      document.getElementById('sidebarHint').textContent = 'Move from knowledge to explanation.';
    }
  }

  function initNavigation() {
    document.querySelectorAll('[data-scroll]').forEach(function (button) {
      button.addEventListener('click', function () {
        document.querySelector(button.dataset.scroll).scrollIntoView({ behavior: 'smooth' });
      });
    });
    document.getElementById('continueButton').addEventListener('click', function () {
      var nextIndex = lessons.findIndex(function (lesson) { return state.completed.indexOf(lesson.id) === -1; });
      currentLesson = nextIndex === -1 ? 0 : nextIndex;
      renderLessonList();
      renderLesson();
      document.getElementById('lessons').scrollIntoView({ behavior: 'smooth' });
    });
    var links = Array.from(document.querySelectorAll('.nav-link'));
    var sections = links.map(function (link) { return document.querySelector(link.getAttribute('href')); });
    var sectionObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting) return;
        links.forEach(function (link) {
          link.classList.toggle('active', link.getAttribute('href') === '#' + entry.target.id);
        });
      });
    }, { rootMargin: '-25% 0px -65% 0px' });
    sections.forEach(function (section) { if (section) sectionObserver.observe(section); });
    document.getElementById('mobileMenu').addEventListener('click', function () {
      document.getElementById('sidebar').classList.toggle('open');
    });
    links.forEach(function (link) {
      link.addEventListener('click', function () { document.getElementById('sidebar').classList.remove('open'); });
    });
  }

  function initTheme() {
    document.body.classList.toggle('light', state.theme === 'light');
    document.getElementById('themeButton').textContent = state.theme === 'light' ? '☾' : '☼';
    document.getElementById('themeButton').addEventListener('click', function () {
      state.theme = document.body.classList.toggle('light') ? 'light' : 'dark';
      this.textContent = state.theme === 'light' ? '☾' : '☼';
      saveState();
    });
  }

  function initReveal() {
    if (!('IntersectionObserver' in window)) {
      document.querySelectorAll('.reveal').forEach(function (item) { item.classList.add('visible'); });
      return;
    }
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    }, { threshold: 0.12 });
    document.querySelectorAll('.reveal').forEach(function (item) { observer.observe(item); });
  }

  initTheme();
  initNavigation();
  initJourney();
  initLessons();
  initComparisons();
  initFlashcards();
  initQuiz();
  initInterview();
  initGlossary();
  initNotes();
  initReveal();
  updateProgress();
}());
