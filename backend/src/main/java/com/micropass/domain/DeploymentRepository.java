package com.micropass.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findByAppIdOrderByIdDesc(Long appId);
    java.util.Optional<Deployment> findTopByAppIdOrderByIdDesc(Long appId);
    List<Deployment> findByAppIdOrderByStartedAtDesc(Long appId);
    java.util.Optional<Deployment> findTopByAppIdOrderByStartedAtDesc(Long appId);
    List<Deployment> findByStatusIn(List<DeploymentStatus> statuses);
}

