package com.micropass.web;

import com.micropass.domain.DeploymentStatus;

import java.time.LocalDateTime;

public record DeploymentResponse(
                Long id,
                Long appId,
                String appName,
                DeploymentStatus status,
                String commitHash,
                String imageTag,
                String containerId,
                Integer port,
                LocalDateTime startedAt,
                LocalDateTime finishedAt) {
}
