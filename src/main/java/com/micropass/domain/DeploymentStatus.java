package com.micropass.domain;

public enum DeploymentStatus {
    QUEUED,
    CLONING,
    BUILDING,
    DEPLOYING,
    HEALTHY,
    FAILED,
    STOPPED
}
