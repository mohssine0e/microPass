# Phase 1: Complete Files Inventory and Architecture Breakdown

This document provides an exhaustive breakdown of every file created during **Phase 1 (Core Prototype & Execution Loop)**, explaining its exact purpose, design rationale, and how it fits into the microPass modular monolith architecture.

---

## 1. Directory Structure Overview

```
microPass/
├── pom.xml                                      # Build descriptor and dependencies
├── src/
│   └── main/
│       ├── java/com/micropass/
│       │   ├── MicroPassApplication.java        # Spring Boot entry point & async enabler
│       │   ├── common/
│       │   │   └── CommandExecutor.java         # Subprocess execution & stream consumption
│       │   ├── domain/
│       │   │   ├── App.java                     # Application JPA entity (registered projects)
│       │   │   ├── AppRepository.java           # Spring Data JPA repository for App
│       │   │   ├── Deployment.java              # Deployment run JPA entity (builds/runs)
│       │   │   ├── DeploymentRepository.java    # Spring Data JPA repository for Deployment
│       │   │   └── DeploymentStatus.java        # State machine enum (CLONING, BUILDING...)
│       │   ├── service/
│       │   │   ├── WorkspaceService.java        # Sandboxed disk workspace manager
│       │   │   ├── GitService.java              # Git clone & commit hash resolution
│       │   │   ├── DockerService.java           # Docker CLI wrapper (build, run, stop)
│       │   │   ├── PortAllocationService.java   # Dynamic host TCP port allocator
│       │   │   └── DeploymentPipelineService.java # Async pipeline orchestrator
│       │   └── web/
│       │       └── AppController.java           # REST API for apps & deployment triggers
│       └── resources/
│           └── application.properties           # Server, H2 DB, paths, and port configurations
├── fixtures/
│   └── sample-app/                              # Built-in sample application for testing
│       ├── Dockerfile                           # Nginx container definition
│       └── index.html                           # Landing page verifying successful deployment
└── docs/
    ├── PROJECT_SPEC.md                          # Full MVP specification & boundaries
    ├── decisions/
    │   └── 01-process-builder-and-port-allocation.md # Architecture Decision Records (ADRs)
    ├── phases/
    │   ├── 01-prototype-execution-loop.md       # Phase 1 concept guide
    │   └── 01-files-and-purposes.md             # This document
    └── troubleshooting/
        └── docker-iptables-dnat.md              # Docker iptables missing chain analysis
```

---

## 2. Exhaustive File-by-File Purpose & Design Rationale

### A. Root & Configuration

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`pom.xml`](file:///home/mohssine/Desktop/projects/microPass/pom.xml) | Maven Project Object Model | Configures Java 21, Spring Boot 3.3.4, Spring Data JPA, embedded H2 database, and validation. Explicitly keeps dependencies minimal and zero-cost with no heavy external SDKs. |
| [`src/main/resources/application.properties`](file:///home/mohssine/Desktop/projects/microPass/src/main/resources/application.properties) | Application Runtime Configuration | Sets server port to `9090`, configures file-persisted H2 database (`./data/micropassdb`), enables Java 21 virtual threads (`spring.threads.virtual.enabled=true`), and sets default workspace paths (`~/.micropass/workspaces`) and port ranges (`9100-9200`). |

---

### B. Core Application & Subprocess Execution

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`MicroPassApplication.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/MicroPassApplication.java) | Spring Boot Application Main Class | Annotated with `@SpringBootApplication` and `@EnableAsync`. Enables asynchronous execution so build/deploy pipelines run in the background without blocking HTTP request threads. |
| [`CommandExecutor.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/common/CommandExecutor.java) | OS Process Execution Utility | Wraps Java's native `ProcessBuilder`. Executes system binaries (`git`, `docker`), redirects `stderr` into `stdout`, and uses a `Consumer<String>` callback to process log lines line-by-line as they occur, preventing OS pipe buffer saturation and process deadlocks. |

---

### C. Domain Model (Database & State)

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`DeploymentStatus.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/domain/DeploymentStatus.java) | Deployment Lifecycle State Enum | Defines explicit states: `QUEUED`, `CLONING`, `BUILDING`, `DEPLOYING`, `HEALTHY`, `FAILED`, and `STOPPED`. Powers state tracking across the platform. |
| [`App.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/domain/App.java) | Application Entity | Represents an application managed by microPass. Stores unique application name, Git repository URL, default branch, currently assigned host port, and active Docker container ID. Uses standard POJO methods and a clean Builder pattern. |
| [`AppRepository.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/domain/AppRepository.java) | App Data Access | Spring Data JPA repository providing CRUD queries and name lookups (`findByName`) for registered applications. |
| [`Deployment.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/domain/Deployment.java) | Deployment Run Entity | Represents an individual build and deployment event linked to an `App`. Records the exact commit hash, generated Docker image tag, container ID, allocated port, full build logs (`@Lob`), and start/finish timestamps. |
| [`DeploymentRepository.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/domain/DeploymentRepository.java) | Deployment Data Access | Spring Data JPA repository providing queries to retrieve all deployment history for a given app ordered by start time descending. |

---

### D. Services & Pipeline Engine

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`WorkspaceService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/WorkspaceService.java) | Filesystem Sandbox Manager | Creates and isolates build workspaces on disk at `~/.micropass/workspaces/app-{id}/deployment-{id}`. Ensures parallel or successive builds don't overwrite each other and provides recursive directory cleanup. |
| [`GitService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/GitService.java) | Git Operations Provider | Clones the target repository using `git clone --depth 1 --branch <branch>` to conserve disk and network bandwidth. Resolves and logs the precise short commit hash (`git rev-parse --short HEAD`). |
| [`DockerService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/DockerService.java) | Container Lifecycle Manager | Interacts directly with the local Docker daemon. Verifies repository `Dockerfile`, triggers `docker build -t <tag>`, cleans up any old container with the same name, and runs the new container detached with port publishing and restart policies. |
| [`PortAllocationService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/PortAllocationService.java) | Host Port Manager | Dynamically finds and reserves an open TCP port in the configured range (`9100–9200`). Combines database verification with live socket testing (`new ServerSocket(port)`) to prevent host port collisions. |
| [`DeploymentPipelineService.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/service/DeploymentPipelineService.java) | Orchestration Engine | Glues the entire workflow together. Handles database persistence, initiates the async task (`@Async`), steps through `CLONING` $\to$ `BUILDING` $\to$ `DEPLOYING` $\to$ `HEALTHY`, handles pipeline errors, updates logs in real time, and marks `FAILED` on error. |

---

### E. Web Layer (REST API)

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`AppController.java`](file:///home/mohssine/Desktop/projects/microPass/src/main/java/com/micropass/web/AppController.java) | REST Endpoints Controller | Exposes JSON endpoints: `POST /api/apps` (register app), `GET /api/apps` (list apps), `POST /api/apps/{id}/deploy` (trigger deployment), and `GET /api/deployments/{id}` (inspect status and logs). |

---

### F. Fixtures (Testing Environment)

| File | Purpose | Why It Exists & Design Decisions |
| :--- | :--- | :--- |
| [`fixtures/sample-app/Dockerfile`](file:///home/mohssine/Desktop/projects/microPass/fixtures/sample-app/Dockerfile) | Test Docker Container Image Spec | Uses `nginx:alpine` to serve a static website on port 80. Keeps verification builds extremely fast (<1 second) without needing complex compiler steps during platform validation. |
| [`fixtures/sample-app/index.html`](file:///home/mohssine/Desktop/projects/microPass/fixtures/sample-app/index.html) | Test Verification Page | Simple HTML page displaying a confirmation badge and message confirming that microPass successfully built and deployed the container. |

---

## 3. Verification & Integrity Confirmation

- All 13 Java source files compile cleanly under Java 21 (`BUILD SUCCESS`).
- `pom.xml` dependency declarations are verified and validated.
- All domain entities (`App`, `Deployment`) use clean standard Java POJOs with builder patterns, ensuring complete runtime compatibility with Java 21 without relying on external bytecode transformers.

