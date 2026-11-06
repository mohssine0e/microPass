# PFE Backend & DevOps Skill Mastery Roadmap

This document outlines the high-value backend engineering, system architecture, and DevOps skills needed to optimize your candidate profile for Final Year Project (PFE) software engineering roles in European and North American markets. It provides targeted integration plans for your existing projects (**MicroPaaS** and **Compass**).

---

## Technical Skills Matrix

| Skill Category | Technology / Concept | PFE Market Relevance | Target Integration Project |
| :--- | :--- | :--- | :--- |
| **Messaging & Async** | RabbitMQ / Apache Kafka | High — Essential for decoupled microservices & background tasks | **MicroPaaS** (Async Docker Builds) |
| **Integrations Testing** | Testcontainers | High — Standard for production integration tests without H2 | **Compass** & **MicroPaaS** |
| **CI/CD & GitOps** | GitHub Actions / GitLab CI | High — Automated build, test, and container packaging | **MicroPaaS** |
| **Observability** | Prometheus, Grafana, OpenTelemetry | Medium-High — System health, telemetry, and live metrics | **MicroPaaS** & **Compass** |
| **Infrastructure as Code** | Terraform / OpenTofu | Medium-High — Automated infrastructure provisioning | **MicroPaaS** |
| **Container Orchestration** | Kubernetes (k8s / k3s), Helm | Medium — Multi-node deployment and cluster management | **MicroPaaS** |
| **Identity & Security** | OAuth2, OIDC, Keycloak | Medium — Centralized identity management | **Compass** |

---

## 1. Backend & Systems Architecture Skills

### Skill 1: Asynchronous Event-Driven Processing (RabbitMQ / Apache Kafka)
* **Industry Focus:** Pure REST HTTP request-response patterns create heavy coupling and latency issues in enterprise platforms. Enterprise teams require decoupled background processing.
* **Project Integration Plan (MicroPaaS):**
  * **Use Case:** Decouple the image build and container deployment pipeline.
  * **Implementation:** When a user requests a new container build via the REST API, publish a `BuildRequestedEvent` to a RabbitMQ exchange. A separate background worker consumes the event, triggers the Docker Engine API to build the image, and updates the database status asynchronously.

### Skill 2: Production Integration Testing with Testcontainers
* **Industry Focus:** Testing against in-memory databases (like H2) often masks SQL syntax differences and dialect bugs. Modern teams rely on `Testcontainers` to spin up real instances in Docker during test execution.
* **Project Integration Plan (Compass & MicroPaaS):**
  * **Use Case:** Execute true database integration tests for Flyway migrations and complex JPA queries.
  * **Implementation:** Replace mock/H2 setups in JUnit 5 tests with `@Testcontainers`. Spin up real PostgreSQL and Redis containers dynamically during the Maven `test` phase to execute repository integration tests.

### Skill 3: Enterprise Identity & OAuth2 (Keycloak)
* **Industry Focus:** Modern microservices use dedicated identity providers (IdPs) instead of custom-coded JWT logic.
* **Project Integration Plan (Compass):**
  * **Use Case:** Centralize authentication and token management.
  * **Implementation:** Integrate Keycloak as an OAuth2 / OIDC server. Configure Spring Security on backend services as a Resource Server to validate JWTs issued by Keycloak.

---

## 2. DevOps & Platform Engineering Skills

### Skill 1: Automated CI/CD Pipelines (GitHub Actions)
* **Industry Focus:** Every production pull request must pass continuous integration quality gates (unit tests, security scans, artifact builds) before merging.
* **Project Integration Plan (MicroPaaS):**
  * **Implementation:** Create `.github/workflows/ci-cd.yml` that automatically:
    1. Triggers on pushes and pull requests to `main`.
    2. Runs `mvn clean verify` with Testcontainers.
    3. Builds the Docker image upon successful test runs.
    4. Pushes the container image to GitHub Packages (GHCR) or Docker Hub with version tags.

### Skill 2: System Observability & Telemetry (Prometheus & Grafana)
* **Industry Focus:** Systems engineers must track system health, error rates, JVM heap usage, and API latency metrics in real time.
* **Project Integration Plan (MicroPaaS):**
  * **Implementation:**
    1. Add `spring-boot-starter-actuator` and `micrometer-registry-prometheus` to the Spring Boot service.
    2. Expose `/actuator/prometheus` metrics endpoints.
    3. Run local Prometheus and Grafana containers via `docker-compose.yml` to scrape metrics and visualize container deployment rates, HTTP response status codes, and heap memory usage.

### Skill 3: Infrastructure as Code (Terraform)
* **Industry Focus:** Provisioning resources manually via UI dashboards is prone to error. Infrastructure must be defined, versioned, and applied as code.
* **Project Integration Plan (MicroPaaS):**
  * **Implementation:** Add a `terraform/` directory to the repository with declarative configuration files (`main.tf`, `variables.tf`) that provision the required local Docker networks, persistent volumes, and PostgreSQL container instances automatically.

### Skill 4: Kubernetes & Manifest Orchestration (k3s / Helm)
* **Industry Focus:** Moving beyond single-host `docker-compose` to multi-container Kubernetes deployments.
* **Project Integration Plan (MicroPaaS):**
  * **Implementation:** Add a `k8s/` folder containing Kubernetes manifests (`deployment.yaml`, `service.yaml`, `configmap.yaml`, `ingress.yaml`) or package them into a Helm chart to demonstrate familiarity with cloud-native application deployment patterns.

---

## Recommended Step-by-Step Execution Plan

```
 Phase 1: Quality & Pipeline Automation
 ├── Step 1: Add Testcontainers to Compass & MicroPaaS
 └── Step 2: Build GitHub Actions CI/CD Pipeline for MicroPaaS

 Phase 2: Asynchronous & Infrastructure Architecture
 ├── Step 3: Implement RabbitMQ event queues in MicroPaaS for background builds
 └── Step 4: Write Terraform scripts for local infrastructure provisioning

 Phase 3: Observability & Production Readiness
 ├── Step 5: Configure Spring Actuator + Prometheus + Grafana dashboards
 └── Step 6: Add Kubernetes deployment manifests (k8s/Helm)
```
```

---

<FollowUp label="Would you like to start by setting up Testcontainers or a GitHub Actions pipeline?" query="Show me how to set up Testcontainers in my Spring Boot project first."/>
