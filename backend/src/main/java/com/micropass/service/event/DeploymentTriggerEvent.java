package com.micropass.service.event;

public record DeploymentTriggerEvent(
        Long deploymentId,
        Long appId,
        String appName
) {}
