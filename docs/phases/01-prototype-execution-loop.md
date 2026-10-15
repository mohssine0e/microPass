# Phase 1: Prototype & Core Execution Loop

## 1. Goal of this Phase
Build the core engine that can take an application definition, clone its Git repository into an isolated workspace on the host machine, build the project and execute its tests, create a versioned Docker image, and run it as an isolated container on an allocated local port.

---

## 2. Theoretical Concepts & Architecture

### What is a PaaS Execution Engine?
At its heart, any PaaS (like Heroku, Fly.io, Coolify, or Render) is an **orchestrated state machine around developer tools**:
1. **Workspace Isolation:** Every build gets a clean, sandboxed directory on disk (`/tmp/micropass/workspaces/{appId}/{deploymentId}`).
2. **Process Execution & Stream Redirection:** The engine spawns OS processes (`git clone`, `mvn test`, `docker build`) and continuously captures standard output (`stdout`) and standard error (`stderr`) so the developer can see real-time progress.
3. **Containerization:** The application is packaged into an Open Container Initiative (OCI) / Docker image to ensure reproducibility across different execution environments.
4. **Lifecycle Management:** The engine instructs the Docker daemon via the Docker API / CLI to start, monitor, and stop containers, while tracking port assignments to prevent port collisions on the host machine.

### Why Java 21 & Spring Boot 3 for this?
- **Virtual Threads (Project Loom):** Process execution and log streaming involve long-blocking I/O (reading from child process streams). Virtual threads handle thousands of blocking read loops with virtually zero memory overhead.
- **ProcessHandle & ProcessBuilder:** Java's standard library provides robust cross-platform APIs to spawn sub-processes, capture stdout/stderr asynchronously, and handle process timeouts and exit codes cleanly.

---

## 3. Modular Monolith Architecture for microPass

```
com.micropass
 ├── domain/             -> Core entities (App, Deployment, DeploymentStatus)
 ├── workspace/          -> Disk isolation & file management for Git checkouts
 ├── git/                -> Git clone, branch checkout, commit hash extraction
 ├── docker/             -> Docker image build, container run/stop/status
 ├── pipeline/           -> Orchestrates Git -> Build -> Docker -> Deploy
 └── web/                -> REST APIs & HTMX UI (Phase 3)
```

---

## 4. Work Done in Milestone 1

1. **Spring Boot 3 Project Setup:**
   - Initialized with Java 21, Spring Boot 3.3.x, Spring Data JPA, H2 Database (for fast, zero-dependency development), and Lombok.
2. **Core Domain Model:**
   - `App`: Repositories tracked by microPass.
   - `Deployment`: Individual runs with timestamps, status, commit hash, allocated port, and container ID.
3. **Git Service:**
   - Clones Git repositories into a clean local directory (`~/.micropass/workspaces/...`).
4. **Docker Service:**
   - Builds Docker images and runs/stops containers via local Docker daemon.
5. **Pipeline Orchestrator:**
   - Glues steps together and updates deployment status.

