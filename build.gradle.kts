plugins {
	java
	id("org.springframework.boot") version "3.5.7"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.panda"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")

	// AWS SDK v2
	implementation(platform("software.amazon.awssdk:bom:2.28.0"))
	implementation("software.amazon.awssdk:ec2")
	implementation("software.amazon.awssdk:ecr")
	implementation("software.amazon.awssdk:ecs")
	implementation("software.amazon.awssdk:elasticloadbalancingv2")
	implementation("software.amazon.awssdk:codedeploy")
	implementation("software.amazon.awssdk:secretsmanager")
	implementation("software.amazon.awssdk:sts")

	// GitHub API
	implementation("org.kohsuke:github-api:1.321")

	// Docker
	implementation("com.github.docker-java:docker-java-core:3.3.6")
	implementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.6")

	// JSON processing
	implementation("com.fasterxml.jackson.core:jackson-databind")

	// Swagger
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.7.0")

	// Logging
	implementation("org.springframework.boot:spring-boot-starter-logging")

	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	// Testing
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
