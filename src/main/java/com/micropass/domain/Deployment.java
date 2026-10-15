package com.micropass.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "deployments")
public class Deployment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "app_id")
    private App app;

    @Enumerated(EnumType.STRING) // this annotation specifies that the enum should be stored as a string in the
                                 // database
    @Column(nullable = false)
    private DeploymentStatus status;

    @Column
    private String commitHash;

    @Column
    private String imageTag;

    @Column
    private String containerId;

    @Column
    private Integer port;

    @Lob // this annotation specifies that the field should be stored as a large object
         // in the database
    @Column(length = 1000000)
    private String buildLogs;

    @Column
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    public Deployment() {
    }

    public Deployment(Long id, App app, DeploymentStatus status, String commitHash, String imageTag,
            String containerId, Integer port, String buildLogs, LocalDateTime startedAt, LocalDateTime finishedAt) {
        this.id = id;
        this.app = app;
        this.status = status != null ? status : DeploymentStatus.QUEUED;
        this.commitHash = commitHash;
        this.imageTag = imageTag;
        this.containerId = containerId;
        this.port = port;
        this.buildLogs = buildLogs;
        this.startedAt = startedAt != null ? startedAt : LocalDateTime.now();
        this.finishedAt = finishedAt;
    }

    @PrePersist
    public void prePersist() {
        if (startedAt == null) {
            startedAt = LocalDateTime.now();
        }
        if (status == null) {
            status = DeploymentStatus.QUEUED;
        }
    }

    public static DeploymentBuilder builder() {
        return new DeploymentBuilder();
    }

    public static class DeploymentBuilder {
        private Long id;
        private App app;
        private DeploymentStatus status = DeploymentStatus.QUEUED;
        private String commitHash;
        private String imageTag;
        private String containerId;
        private Integer port;
        private String buildLogs;
        private LocalDateTime startedAt = LocalDateTime.now();
        private LocalDateTime finishedAt;

        public DeploymentBuilder id(Long id) {
            this.id = id;
            return this;
        }

        public DeploymentBuilder app(App app) {
            this.app = app;
            return this;
        }

        public DeploymentBuilder status(DeploymentStatus status) {
            this.status = status;
            return this;
        }

        public DeploymentBuilder commitHash(String commitHash) {
            this.commitHash = commitHash;
            return this;
        }

        public DeploymentBuilder imageTag(String imageTag) {
            this.imageTag = imageTag;
            return this;
        }

        public DeploymentBuilder containerId(String containerId) {
            this.containerId = containerId;
            return this;
        }

        public DeploymentBuilder port(Integer port) {
            this.port = port;
            return this;
        }

        public DeploymentBuilder buildLogs(String buildLogs) {
            this.buildLogs = buildLogs;
            return this;
        }

        public DeploymentBuilder startedAt(LocalDateTime startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        public DeploymentBuilder finishedAt(LocalDateTime finishedAt) {
            this.finishedAt = finishedAt;
            return this;
        }

        public Deployment build() {
            return new Deployment(id, app, status, commitHash, imageTag, containerId, port, buildLogs, startedAt,
                    finishedAt);
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public App getApp() {
        return app;
    }

    public void setApp(App app) {
        this.app = app;
    }

    public DeploymentStatus getStatus() {
        return status;
    }

    public void setStatus(DeploymentStatus status) {
        this.status = status;
    }

    public String getCommitHash() {
        return commitHash;
    }

    public void setCommitHash(String commitHash) {
        this.commitHash = commitHash;
    }

    public String getImageTag() {
        return imageTag;
    }

    public void setImageTag(String imageTag) {
        this.imageTag = imageTag;
    }

    public String getContainerId() {
        return containerId;
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getBuildLogs() {
        return buildLogs;
    }

    public void setBuildLogs(String buildLogs) {
        this.buildLogs = buildLogs;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}
