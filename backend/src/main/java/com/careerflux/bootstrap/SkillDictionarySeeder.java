package com.careerflux.bootstrap;

import java.util.List;
import java.util.Map;

import com.careerflux.common.TextUtils;
import com.careerflux.common.taxonomy.SkillCategory;
import com.careerflux.skill.Skill;
import com.careerflux.skill.SkillRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the canonical skill dictionary and its aliases.
 *
 * <p>This is reference data, not demo data: it is what makes "SpringBoot" on a
 * resume comparable with "Spring Boot" in a posting, and it is required in every
 * environment. It is seeded once and thereafter grows as
 * {@link com.careerflux.skill.SkillResolver} meets new terms.
 */
@Component
@Order(1)
public class SkillDictionarySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SkillDictionarySeeder.class);

    /** canonical name -> aliases that should resolve to it. */
    private static final Map<SkillCategory, Map<String, List<String>>> DICTIONARY = Map.of(
            SkillCategory.LANGUAGE, Map.ofEntries(
                    Map.entry("Java", List.of("java8", "java11", "java17", "core-java")),
                    Map.entry("Python", List.of("python3", "py")),
                    Map.entry("JavaScript", List.of("js", "ecmascript", "es6")),
                    Map.entry("TypeScript", List.of("ts")),
                    Map.entry("Go", List.of("golang")),
                    Map.entry("Kotlin", List.of()),
                    Map.entry("C#", List.of("csharp", "c-sharp")),
                    Map.entry("C++", List.of("cpp", "cplusplus")),
                    Map.entry("SQL", List.of("t-sql", "pl-sql")),
                    Map.entry("Rust", List.of()),
                    Map.entry("Ruby", List.of()),
                    Map.entry("PHP", List.of()),
                    Map.entry("Scala", List.of()),
                    Map.entry("Swift", List.of())),
            SkillCategory.FRAMEWORK, Map.ofEntries(
                    Map.entry("Spring Boot", List.of("springboot", "spring-boot-3")),
                    Map.entry("Spring", List.of("spring-framework", "spring-mvc")),
                    Map.entry("Spring Security", List.of("springsecurity")),
                    Map.entry("Hibernate", List.of("jpa", "spring-data-jpa")),
                    Map.entry("React", List.of("reactjs", "react-js")),
                    Map.entry("Angular", List.of("angularjs")),
                    Map.entry("Vue", List.of("vuejs", "vue-js")),
                    Map.entry("Node.js", List.of("nodejs", "node")),
                    Map.entry("Express", List.of("expressjs")),
                    Map.entry("Django", List.of()),
                    Map.entry("Flask", List.of()),
                    Map.entry("FastAPI", List.of()),
                    Map.entry(".NET", List.of("dotnet", "dot-net", "asp-net")),
                    Map.entry("Next.js", List.of("nextjs"))),
            SkillCategory.DATABASE, Map.ofEntries(
                    Map.entry("PostgreSQL", List.of("postgres", "psql")),
                    Map.entry("MySQL", List.of()),
                    Map.entry("MongoDB", List.of("mongo")),
                    Map.entry("Redis", List.of()),
                    Map.entry("Elasticsearch", List.of("elastic-search", "opensearch")),
                    Map.entry("Cassandra", List.of()),
                    Map.entry("Oracle", List.of("oracle-db")),
                    Map.entry("DynamoDB", List.of())),
            SkillCategory.CLOUD, Map.ofEntries(
                    Map.entry("AWS", List.of("amazon-web-services")),
                    Map.entry("Azure", List.of("microsoft-azure")),
                    Map.entry("GCP", List.of("google-cloud", "google-cloud-platform")),
                    Map.entry("Kubernetes", List.of("k8s")),
                    Map.entry("Docker", List.of("containers")),
                    Map.entry("Terraform", List.of())),
            SkillCategory.TOOL, Map.ofEntries(
                    Map.entry("Git", List.of("github", "gitlab", "version-control")),
                    Map.entry("Maven", List.of()),
                    Map.entry("Gradle", List.of()),
                    Map.entry("Jenkins", List.of()),
                    Map.entry("Kafka", List.of("apache-kafka")),
                    Map.entry("RabbitMQ", List.of()),
                    Map.entry("Jira", List.of()),
                    Map.entry("Linux", List.of("unix"))),
            SkillCategory.PRACTICE, Map.ofEntries(
                    Map.entry("REST APIs", List.of("rest", "restful-apis", "rest-api")),
                    Map.entry("GraphQL", List.of()),
                    Map.entry("Microservices", List.of("micro-services")),
                    Map.entry("CI/CD", List.of("cicd", "continuous-integration", "continuous-delivery")),
                    Map.entry("Agile", List.of("scrum", "kanban")),
                    Map.entry("TDD", List.of("test-driven-development", "unit-testing")),
                    Map.entry("System Design", List.of("architecture", "software-architecture")),
                    Map.entry("Code Review", List.of())),
            SkillCategory.DOMAIN, Map.ofEntries(
                    Map.entry("Backend Development", List.of("backend", "back-end", "server-side")),
                    Map.entry("Frontend Development", List.of("frontend", "front-end", "ui-development")),
                    Map.entry("Full Stack Development", List.of("fullstack", "full-stack")),
                    Map.entry("Data Engineering", List.of("etl", "data-pipelines")),
                    Map.entry("Machine Learning", List.of("ml", "deep-learning")),
                    Map.entry("DevOps", List.of("sre", "site-reliability")),
                    Map.entry("Mobile Development", List.of("android", "ios"))),
            SkillCategory.SOFT, Map.ofEntries(
                    Map.entry("Communication", List.of()),
                    Map.entry("Mentoring", List.of("coaching")),
                    Map.entry("Collaboration", List.of("teamwork")),
                    Map.entry("Leadership", List.of())));

    private final SkillRepository skillRepository;

    public SkillDictionarySeeder(SkillRepository skillRepository) {
        this.skillRepository = skillRepository;
    }

    @Override
    @Transactional
    public void run(org.springframework.boot.ApplicationArguments args) {
        int created = 0;
        for (var categoryEntry : DICTIONARY.entrySet()) {
            for (var skillEntry : categoryEntry.getValue().entrySet()) {
                String canonical = skillEntry.getKey();
                // The skill slug, not slugify: "C#" and "C++" used to share `c`,
                // and this map's order decided which of them was ever created.
                String slug = TextUtils.skillSlug(canonical);
                if (skillRepository.findBySlug(slug).isPresent()) {
                    continue;
                }
                Skill skill = new Skill();
                skill.setCanonicalName(canonical);
                skill.setSlug(slug);
                skill.setCategory(categoryEntry.getKey());
                skillEntry.getValue().forEach(alias -> skill.addAlias(TextUtils.skillSlug(alias)));
                skillRepository.save(skill);
                created++;
            }
        }
        if (created > 0) {
            log.info("Seeded {} canonical skills into the dictionary", created);
        }
        warnIfCFamilyStillShared();
    }

    /**
     * Says so when V21 could not give C# or C++ its own slug.
     *
     * <p>V21 moves the one row that held {@code c} only when the slug it needs is
     * free, rather than merging two rows on a guess. If another row already owned
     * it, the old row stays on {@code c} and this is the only place that notices.
     */
    private void warnIfCFamilyStillShared() {
        skillRepository.findBySlug("c")
                .filter(skill -> "C#".equals(skill.getCanonicalName()) || "C++".equals(skill.getCanonicalName()))
                .ifPresent(skill -> log.warn("The skill '{}' still holds the slug 'c' because the slug V21 would "
                        + "have moved it to was already taken. Review the skills table by hand.",
                        skill.getCanonicalName()));
    }
}
