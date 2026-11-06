package com.micropass.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.LocalDateTime;

@Repository
public interface DeploymentRepository extends JpaRepository<Deployment, Long> {
    List<Deployment> findByAppIdOrderByIdDesc(Long appId);
    Optional<Deployment> findTopByAppIdOrderByIdDesc(Long appId);
    List<Deployment> findByAppIdOrderByStartedAtDesc(Long appId);
    Optional<Deployment> findTopByAppIdOrderByStartedAtDesc(Long appId);
    Optional<Deployment> findTopByAppIdAndContainerIdOrderByIdDesc(Long appId, String containerId);
    Optional<Deployment> findTopByAppIdAndStatusOrderByIdDesc(Long appId, DeploymentStatus status);
    List<Deployment> findByStatusIn(List<DeploymentStatus> statuses);
    boolean existsByAppIdAndStatusIn(Long appId, Collection<DeploymentStatus> statuses);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Deployment d set d.status = :nextStatus where d.id = :deploymentId and d.status = :expectedStatus")
    int transitionStatus(
            @Param("deploymentId") Long deploymentId,
            @Param("expectedStatus") DeploymentStatus expectedStatus,
            @Param("nextStatus") DeploymentStatus nextStatus);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Deployment d set d.status = :cancelledStatus, d.finishedAt = :finishedAt " +
            "where d.id = :deploymentId and d.status in :activeStatuses")
    int cancelActive(
            @Param("deploymentId") Long deploymentId,
            @Param("activeStatuses") Collection<DeploymentStatus> activeStatuses,
            @Param("cancelledStatus") DeploymentStatus cancelledStatus,
            @Param("finishedAt") LocalDateTime finishedAt);
}
