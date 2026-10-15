package com.micropass.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "apps")
public class App {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String gitUrl;

    @Column(nullable = false)
    private String defaultBranch = "main";

    @Column
    private Integer allocatedPort;

    @Column
    private String activeContainerId;

    @Column
    private LocalDateTime createdAt;

    public App() {}

    public App(Long id, String name, String gitUrl, String defaultBranch, Integer allocatedPort, String activeContainerId, LocalDateTime createdAt) {
        this.id = id;
        this.name = name;
        this.gitUrl = gitUrl;
        this.defaultBranch = defaultBranch != null ? defaultBranch : "main";
        this.allocatedPort = allocatedPort;
        this.activeContainerId = activeContainerId;
        this.createdAt = createdAt;
    }

    @PrePersist // 
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static AppBuilder builder() {
        return new AppBuilder();
    }

    public static class AppBuilder {
        private Long id;
        private String name;
        private String gitUrl;
        private String defaultBranch = "main";
        private Integer allocatedPort;
        private String activeContainerId;
        private LocalDateTime createdAt;

        public AppBuilder id(Long id) { this.id = id; return this; }
        public AppBuilder name(String name) { this.name = name; return this; }
        public AppBuilder gitUrl(String gitUrl) { this.gitUrl = gitUrl; return this; }
        public AppBuilder defaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; return this; }
        public AppBuilder allocatedPort(Integer allocatedPort) { this.allocatedPort = allocatedPort; return this; }
        public AppBuilder activeContainerId(String activeContainerId) { this.activeContainerId = activeContainerId; return this; }
        public AppBuilder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }

        public App build() {
            return new App(id, name, gitUrl, defaultBranch, allocatedPort, activeContainerId, createdAt);
        }
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getGitUrl() { return gitUrl; }
    public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public Integer getAllocatedPort() { return allocatedPort; }
    public void setAllocatedPort(Integer allocatedPort) { this.allocatedPort = allocatedPort; }

    public String getActiveContainerId() { return activeContainerId; }
    public void setActiveContainerId(String activeContainerId) { this.activeContainerId = activeContainerId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

