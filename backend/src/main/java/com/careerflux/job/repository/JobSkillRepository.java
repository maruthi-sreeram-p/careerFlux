package com.careerflux.job.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.careerflux.job.domain.JobSkill;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobSkillRepository extends JpaRepository<JobSkill, UUID> {

    @Query("select js from JobSkill js join fetch js.skill where js.job.id = :jobId")
    List<JobSkill> findByJobId(UUID jobId);

    @Query("select js from JobSkill js join fetch js.skill where js.job.id in :jobIds")
    List<JobSkill> findByJobIdIn(Collection<UUID> jobIds);
}
