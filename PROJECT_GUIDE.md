# Panda Backend - 완벽 가이드

## 📋 목차
1. [프로젝트 개요](#프로젝트-개요)
2. [아키텍처 & 시스템 설계](#아키텍처--시스템-설계)
3. [기술 스택](#기술-스택)
4. [프로젝트 구조](#프로젝트-구조)
5. [핵심 모듈 상세 설명](#핵심-모듈-상세-설명)
6. [API 명세](#api-명세)
7. [배포 파이프라인 동작 원리](#배포-파이프라인-동작-원리)
8. [환경 설정 및 실행](#환경-설정-및-실행)
9. [보안 고려사항](#보안-고려사항)
10. [주요 클래스 상세 분석](#주요-클래스-상세-분석)

---

## 🎯 프로젝트 개요

### 프로젝트명
**Panda Backend (ECR Deployment Automation Platform)**

### 목적
GitHub 레포지토리의 소스코드를 자동으로 Docker 이미지로 빌드하고, AWS ECR에 푸시한 후, ECS를 통해 **Blue/Green 무중단 배포**를 완전 자동화하는 엔터프라이즈급 배포 플랫폼.

### 핵심 특징
- ✅ **완전 자동화**: 버튼 하나로 Git Clone → Docker Build → ECR Push → ECS 배포까지 자동화
- ✅ **실시간 모니터링**: SSE(Server-Sent Events)를 통한 배포 진행 상황 실시간 스트리밍
- ✅ **무중단 배포**: Blue/Green 배포 패턴으로 기존 서비스 중단 없음
- ✅ **안정성**: AWS Step Functions와 EventBridge를 활용한 이벤트 기반 아키텍처
- ✅ **자동 롤백**: 배포 실패 시 이전 버전으로 즉시 롤백
- ✅ **멀티 연결**: GitHub과 AWS 계정을 여러 개 연결하여 관리

---

## 🏗️ 아키텍처 & 시스템 설계

### 전체 시스템 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                           Client (Web/CLI)                          │
└──────────────────────┬──────────────────────────────────────────────┘
                       │
                       ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Panda Backend Server                             │
│                    (Spring Boot 3.5.7, Java 17)                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌─────────────────────┐        ┌──────────────────────┐            │
│  │   Connection API    │        │   Deploy API         │            │
│  │ (GitHub/AWS Link)   │        │ (배포 시작/모니터링) │            │
│  └──────────┬──────────┘        └──────────┬───────────┘            │
│             │                              │                        │
│  ┌──────────▼──────────────┐   ┌──────────▼──────────────┐         │
│  │ Connection Services     │   │ Deployment Services    │         │
│  │ • SaveGitHubConn        │   │ • StartDeployment      │         │
│  │ • SaveAwsConn           │   │ • Pipeline Execution   │         │
│  │ • ConnectionStore       │   │ • BlueGreen Deploy     │         │
│  └───────────┬─────────────┘   │ • SSE Streaming       │         │
│              │                 │ • Result Collection    │         │
│              │                 └──────────┬──────────────┘         │
│              │                            │                        │
│  ┌───────────▼────────────────────────────▼────────────┐          │
│  │         Infrastructure & Event Management            │          │
│  │  • DeploymentEventStore (SSE Emitter & History)    │          │
│  │  • DeploymentTaskExecutor (Thread Pool Management)  │          │
│  │  • ExecutionArnStore (Secrets Manager Integration)  │          │
│  │  • ErrorHandler & Exception Management              │          │
│  └───────────────────────┬────────────────────────────┘          │
│                          │                                         │
│        ┌─────────────────▼─────────────────┐                      │
│        │  AWS Client Configuration         │                      │
│        │  • S3, EC2, ECR, ECS, Lambda      │                      │
│        │  • Secrets Manager, IAM, etc      │                      │
│        └─────────────────┬─────────────────┘                      │
└────────────────────────────┼──────────────────────────────────────┘
                            │
         ┌──────────────────┼──────────────────┬─────────────────┐
         │                  │                  │                 │
         ▼                  ▼                  ▼                 ▼
    GitHub Repo         Docker Daemon      AWS Services      EventBridge
    (Clone Source)      (Build Image)     (ECR/ECS/etc)     (Trigger Workflow)
         │                  │                  │                 │
         └──────────────────┼──────────────────┴─────────────────┘
                            │
                            ▼
                   ┌────────────────────┐
                   │  AWS Step Functions │
                   │  (Workflow Engine)  │
                   └────────────────────┘
```

### 데이터 흐름 다이어그램

```
1️⃣ 배포 요청 시작
   Client ─POST /api/v1/deploy─> Backend
                                   ├─ deploymentId 생성
                                   ├─ EventBridge 규칙 생성
                                   └─ 반환: {deploymentId, status}

2️⃣ 실시간 모니터링 (SSE)
   Client ─GET /api/v1/deploy/{id}/events─> Backend (Stream)
                                             ├─ 과거 히스토리 전송
                                             └─ 실시간 Stage 이벤트 스트리밍

3️⃣ 배포 결과 조회
   Client ─GET /api/v1/deploy/{id}/result─> Backend
                                             └─ 반환: {status, duration, urls, metrics}

백그라운드 비동기 동작 (DeploymentTask):
┌─────────────────────────────────────────┐
│ Stage 1: Git Clone & Dockerfile 검색     │
│ • GitHub URL 구성                         │
│ • git clone --branch --depth 1 실행     │
│ • Dockerfile 탐색 (최상위 또는 docker 디렉토리)
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│ Stage 2: Docker Build                    │
│ • Docker 이미지 빌드                     │
│ • 태그: {owner}-{repo}-{branch}-{timestamp}
│ • BuildKit 캐시 활용                     │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│ Stage 3: ECR Push                        │
│ • AWS 계정 ID 조회 (STS)                │
│ • ECR 리포지토리 확인/생성              │
│ • Docker 로그인 (AWS ECR)               │
│ • 이미지 Tag & Push                      │
│ • 동시에 EventBridge 자동 발동 →       │
│   Step Functions 워크플로우 시작         │
└────────────────┬────────────────────────┘
                 │
                 ▼ (EventBridge 이벤트)
┌─────────────────────────────────────────┐
│ Stage 4: Step Functions 폴링             │
│ • ExecutionArn 조회 (Secrets Manager)  │
│ • 2초 간격 GetExecutionHistory          │
│ • 실행 상태 분석:                       │
│   - TaskStarted, TaskSucceeded          │
│   - ExecutionSucceeded, ExecutionFailed │
│ • SSE 이벤트 발행                        │
└────────────────┬────────────────────────┘
                 │
                 ▼
┌─────────────────────────────────────────┐
│ Stage 5: Blue/Green 배포 (by Step Fn)  │
│ • 기존 Blue Service 상태 확인           │
│ • Green Service (새 Task) 시작          │
│ • ELB 트래픽 점진적 전환               │
│ • Lifecycle Hooks 실행                  │
│ • 배포 완료 또는 롤백                   │
└────────────────┬────────────────────────┘
                 │
                 ▼
       DeploymentEventStore
       (히스토리 저장 & SSE 발행)
```

---

## 💻 기술 스택

### 백엔드 프레임워크
| 항목 | 버전 | 용도 |
|------|------|------|
| **Spring Boot** | 3.5.7 | REST API 및 웹 애플리케이션 프레임워크 |
| **Java** | 17 (LTS) | 컴파일 언어 |
| **Gradle** | 8.6+ | 빌드 도구 (Kotlin DSL) |
| **JDK** | Eclipse Temurin 17 | Java 런타임 |

### AWS SDK & 서비스
```
AWS SDK v2 (Corretto 17 최적화):
├── EC2 (aws-ec2)
├── ECR (aws-ecr)
├── ECS (aws-ecs)
├── ElasticLoadBalancingV2 (aws-elasticloadbalancingv2)
├── CodeDeploy (aws-codedeploy)
├── Secrets Manager (aws-secretsmanager)
├── STS (aws-sts)
├── EventBridge (aws-events)
├── IAM (aws-iam)
├── Step Functions (aws-sfn)
└── Lambda (aws-lambda)
```

### 외부 라이브러리
```
GitHub API:
└── org.kohsuke:github-api:1.321
    • GitHub 토큰 검증
    • 레포지토리 정보 조회
    • 커밋 히스토리 검색

Docker Client:
├── com.github.docker-java:docker-java-core
├── com.github.docker-java:docker-java-transport-httpclient5
└── 역할: Docker 이미지 빌드, 로그인, 푸시

JSON 처리:
└── com.fasterxml.jackson.core (databind, datatype-jsr310)

유틸리티:
├── org.projectlombok:lombok (보일러플레이트 제거)
└── org.apache.commons:commons-text (문자열 처리)

API 문서:
└── org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0 (Swagger UI)
```

### Spring Boot 스타터 라이브러리
```
Core:
├── spring-boot-starter-web: REST API, servlet 지원
├── spring-boot-starter-actuator: 헬스 체크, 메트릭
└── spring-boot-starter-validation: @Valid, @NotNull 등 입력 검증

로깅:
├── spring-boot-starter-logging
└── 기본: SLF4J + Logback

테스트:
└── spring-boot-starter-test: JUnit 5, Mockito, AssertJ
```

### Docker & 컨테이너
```
Docker 버전: 최신 (API v1.48)
다중 스테이지 빌드:
├── Stage 1 (Builder): Eclipse Temurin 17 JDK
│   └── Gradle 빌드 (테스트 제외)
├── Stage 2 (Runtime): Eclipse Temurin 17 JRE (경량)
│   ├── Git 설치 (소스 클론)
│   ├── Docker CLI 설치 (배포 파이프라인)
│   ├── 비루트 사용자 실행 (보안)
│   └── Health Check (30초 간격)
└── 포트: 8080
```

---

## 📂 프로젝트 구조

### 전체 디렉토리 레이아웃

```
panda-backend/
├── src/main/java/com/panda/backend/
│   ├── feature/
│   │   ├── connect/                    # GitHub & AWS 연결 관리
│   │   │   ├── api/
│   │   │   │   ├── ConnectApi.java              # API 인터페이스
│   │   │   │   └── ConnectController.java       # API 구현
│   │   │   ├── application/
│   │   │   │   ├── SaveGitHubConnectionService.java
│   │   │   │   ├── GitHubConnectionService.java
│   │   │   │   ├── SaveAwsConnectionService.java
│   │   │   │   └── AwsConnectionService.java
│   │   │   ├── dto/
│   │   │   │   ├── ConnectGitHubRequest.java
│   │   │   │   ├── ConnectGitHubResponse.java
│   │   │   │   ├── ConnectAwsRequest.java
│   │   │   │   ├── ConnectAwsResponse.java
│   │   │   │   └── ConnectionResponse.java
│   │   │   ├── entity/
│   │   │   │   ├── GitHubConnection.java        # GitHub 연결 정보
│   │   │   │   └── AwsConnection.java           # AWS 연결 정보
│   │   │   └── infrastructure/
│   │   │       └── ConnectionStore.java         # 메모리 기반 저장소
│   │   │
│   │   └── deploy/                     # 배포 파이프라인 (핵심)
│   │       ├── api/
│   │       │   ├── DeployApi.java               # API 인터페이스 (3개 엔드포인트)
│   │       │   └── DeployController.java        # API 구현
│   │       ├── application/
│   │       │   ├── DeploymentPipelineService.java       # Stage 1-3 (Git/Docker/ECR)
│   │       │   ├── BlueGreenDeploymentService.java      # Stage 4 (ECS 배포)
│   │       │   ├── EcsDeploymentService.java            # ECS 관련 작업
│   │       │   ├── StartDeploymentService.java          # 배포 초기화
│   │       │   ├── StreamDeploymentEventsService.java   # SSE 스트리밍
│   │       │   ├── GetDeploymentResultService.java      # 결과 조회
│   │       │   ├── HealthCheckService.java              # Health Check
│   │       │   ├── StepFunctionsPollingService.java     # Step Functions 모니터링 (핵심)
│   │       │   ├── EventBridgeRuleService.java          # EventBridge 규칙 생성
│   │       │   └── LambdaInvocationService.java         # Lambda 호출
│   │       ├── dto/
│   │       │   ├── DeployRequest.java
│   │       │   ├── DeployResponse.java
│   │       │   ├── DeploymentResult.java
│   │       │   ├── RegisterEventBusRequest.java
│   │       │   ├── RegisterEventBusResponse.java
│   │       │   ├── DeploymentMetadata.java
│   │       │   └── EcsTaskDefinition.java
│   │       ├── event/
│   │       │   ├── DeploymentEvent.java              # 이벤트 모델
│   │       │   ├── DeploymentEventStore.java         # SSE 관리 & 히스토리
│   │       │   ├── DeploymentEventPublisher.java     # 발행자 인터페이스
│   │       │   ├── DeploymentEventPublisherImpl.java  # 발행자 구현
│   │       │   └── StageEventHelper.java             # 단계별 이벤트 생성
│   │       ├── exception/
│   │       │   ├── DeploymentException.java
│   │       │   ├── DeploymentTimeoutException.java
│   │       │   ├── DockerBuildException.java
│   │       │   ├── EcsDeploymentException.java
│   │       │   └── HealthCheckException.java
│   │       └── infrastructure/
│   │           ├── DeploymentTask.java             # 배포 작업 (Runnable)
│   │           ├── DeploymentTaskExecutor.java     # 스레드 풀 관리
│   │           ├── ExecutionArnStore.java          # Step Functions 실행 ARN 저장소
│   │           └── DeploymentErrorHandler.java     # 에러 처리
│   │
│   ├── config/
│   │   ├── AwsStepFunctionsConfig.java    # AWS 클라이언트 설정
│   │   └── WebConfig.java                 # Web 설정 (CORS 등)
│   │
│   └── global/
│       ├── exception/
│       │   ├── GlobalExceptionHandler.java     # 전역 예외 처리
│       │   └── ErrorResponse.java              # 에러 응답 포맷
│       ├── response/
│       │   └── ApiResponse.java                # 통일된 API 응답
│       ├── health/
│       │   └── HealthCheckIndicator.java       # 헬스 체크
│       └── BackendApplication.java             # Spring Boot 진입점
│
├── src/main/resources/
│   ├── application.yml          # 애플리케이션 설정
│   ├── application-prod.yml     # 프로덕션 설정
│   └── logback-spring.xml       # 로깅 설정
│
├── build.gradle.kts             # 프로젝트 의존성 & 빌드 설정
├── Dockerfile                   # 다중 스테이지 Docker 이미지
├── .dockerignore                # Docker 빌드 무시 파일
│
├── ARCHITECTURE.md              # 상세 아키텍처 문서
├── API_SPECIFICATION.md         # API 명세서
├── .env.example                 # 환경 변수 예제
└── .github/workflows/
    ├── ci.yml                   # CI 파이프라인 (테스트)
    └── cd.yml                   # CD 파이프라인 (배포)
```

---

## 🔧 핵심 모듈 상세 설명

### 1️⃣ Connection Module (연결 관리)

#### 역할
GitHub 레포지토리와 AWS 계정의 자격증명을 저장 및 관리.

#### API 엔드포인트

**POST /api/v1/connect/github** - GitHub 연결
```json
요청:
{
  "owner": "mycompany",
  "repo": "backend-service",
  "branch": "main",
  "token": "ghp_xxxxxxxxxxxxxxxxxxxxx"
}

응답:
{
  "connectionId": "conn_abc123def456",
  "message": "GitHub 연결 성공",
  "details": {
    "owner": "mycompany",
    "repo": "backend-service",
    "branch": "main"
  }
}
```

**POST /api/v1/connect/aws** - AWS 연결
```json
요청:
{
  "region": "ap-northeast-2",
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "sessionToken": "optional_token"
}

응답:
{
  "connectionId": "conn_xyz789uvw",
  "message": "AWS 연결 성공",
  "details": {
    "region": "ap-northeast-2",
    "accountId": "123456789012"
  }
}
```

#### 저장 구조
```java
// GitHub 연결 정보 저장
GitHubConnection {
  owner: String              // GitHub 조직 또는 사용자명
  repo: String               // 레포지토리명
  branch: String             // 브랜치명 (기본: main)
  token: String              // Personal Access Token
  connectionId: String       // 고유 ID (conn_xxxxxxxx)
}

// AWS 연결 정보 저장
AwsConnection {
  region: String             // AWS 리전 (ap-northeast-2 등)
  accessKeyId: String        // AWS Access Key ID
  secretAccessKey: String    // AWS Secret Access Key
  sessionToken: String       // STS 세션 토큰 (선택사항)
  accountId: String          // AWS 계정 ID (검증 후 저장)
  connectionId: String       // 고유 ID
}
```

#### 동작 원리

**SaveGitHubConnectionService.java**
```
1. GitHub 토큰 검증
   └─ GitHub API 호출: GET /repos/{owner}/{repo}

2. 레포지토리 정보 확인
   └─ 브랜치 존재 여부 확인

3. 연결 정보 저장
   └─ ConnectionStore (메모리) 저장

4. 응답
   └─ connectionId 반환
```

**SaveAwsConnectionService.java**
```
1. AWS 자격증명 검증
   └─ STS GetCallerIdentity API 호출

2. AWS 계정 ID 추출
   └─ 향후 ECR 리포지토리 생성 시 사용

3. IAM 권한 확인 (선택사항)
   └─ ECR, ECS, Secrets Manager 등 필수 권한 검증

4. 연결 정보 저장
   └─ ConnectionStore (메모리) 저장

5. 응답
   └─ connectionId 반환
```

---

### 2️⃣ Deploy Module - Pipeline (배포 파이프라인)

#### 역할
Git Clone → Docker Build → ECR Push → Step Functions 트리거까지 수행.

#### 핵심 클래스: DeploymentPipelineService.java

**Stage 1: Git Clone & Dockerfile 검색**
```java
동작:
1. GitHub 연결 정보 조회
2. Clone URL 구성: https://{token}@github.com/{owner}/{repo}.git
3. Git Clone 실행
   └─ git clone --branch {branch} --depth 1 {url} {tempDir}
   └─ shallow clone으로 속도 향상

4. Dockerfile 탐색
   ├─ {tempDir}/Dockerfile 확인
   ├─ {tempDir}/docker/Dockerfile 확인
   ├─ {tempDir}/deployment/Dockerfile 확인
   └─ 찾은 첫 번째 Dockerfile 사용

예외 처리:
• DockerBuildException: Dockerfile을 찾지 못한 경우
• DeploymentException: Git Clone 실패
```

**Stage 2: Docker Build**
```java
동작:
1. 이미지 태그 생성
   └─ Format: {owner}-{repo}-{branch}-{timestamp}
   └─ Example: mycompany-backend-main-20231215120530

2. Docker 이미지 빌드
   └─ BuildImages API 호출
   └─ DockerBuild 진행 상황 로깅
   └─ 빌드 컨텍스트: Dockerfile 디렉토리

3. 빌드 로그 수집
   └─ 빌드 각 단계별 로그 확인
   └─ 에러 발생 시 예외 처리

4. 이미지 검증
   └─ 빌드된 이미지가 정상 생성되었는지 확인

예외 처리:
• DockerBuildException: 빌드 실패
• BuildFailedException: 로그 기반 상세 에러 분석
```

**Stage 3: ECR Push**
```java
동작:
1. AWS 계정 ID 조회
   └─ STS GetCallerIdentity API 호출

2. ECR 리포지토리 관리
   ├─ 리포지토리 존재 확인 (DescribeRepositories)
   ├─ 없으면 생성 (CreateRepository)
   │  └─ 리포지토리명: {owner}-{repo}
   │  └─ Tag Mutability: MUTABLE (이미지 태그 변경 가능)
   │  └─ 스캔 활성화: 보안 취약점 자동 스캔
   └─ 이미지 만료 정책 설정 (선택사항)

3. AWS ECR 로그인
   ├─ ECR Authorization Token 획득
   ├─ Docker 로그인 실행
   │  └─ aws ecr get-login-password | docker login
   └─ 인증 토큰 캐싱 (12시간)

4. Docker 이미지 태깅
   ├─ Local Image: {owner}-{repo}-{branch}-{timestamp}
   ├─ ECR Image: {accountId}.dkr.ecr.{region}.amazonaws.com/{owner}-{repo}:{tag}
   └─ docker tag 명령 실행

5. ECR Push
   ├─ docker push 실행
   ├─ 각 레이어 업로드 진행 상황 로깅
   └─ 완료 후 ECR URL 반환

6. EventBridge 자동 트리거 (중요!)
   └─ ECR 푸시 이벤트 감지
   └─ EventBridge 규칙에 의해 Step Functions 자동 실행
   └─ Step Functions가 실제 ECS 배포 수행

예외 처리:
• EcrException: ECR 관련 오류
• DockerPushException: Push 실패
• AuthenticationException: AWS 인증 실패
```

---

### 3️⃣ Deploy Module - Step Functions Monitoring

#### 핵심 클래스: StepFunctionsPollingService.java

**역할**
ECR Push 후 Step Functions의 실행 상태를 주기적으로 모니터링하고, 실시간으로 이벤트를 발행.

**동작 원리**

```
Timeline:
├─ T=0s: ECR Push 완료
│  └─ EventBridge → Step Functions 자동 트리거
│  └─ ExecutionArn이 Secrets Manager에 저장됨
│
├─ T=3s: Polling 시작
│  └─ Secrets Manager에서 ExecutionArn 조회
│  └─ ExecutionArn 찾을 때까지 최대 3초 대기
│
├─ T=5s~: 2초 간격 GetExecutionHistory
│  ├─ Step Functions 실행 상태 조회
│  ├─ 이벤트 분석:
│  │  ├─ TaskStarted: 단계 시작 (예: EnsureInfra)
│  │  ├─ TaskSucceeded: 단계 완료
│  │  └─ ExecutionSucceeded/Failed: 전체 완료/실패
│  │
│  └─ SSE 이벤트 발행
│
├─ T=30분: Timeout
│  └─ 폴링 중단
│  └─ TIMEOUT 이벤트 발행
│
└─ 언제든: SUCCEEDED 또는 FAILED 상태 도달
   └─ 폴링 즉시 중단
   └─ 최종 이벤트 발행
```

**상세 동작 단계**

```java
// 1단계: ExecutionArn 획득 (최대 3초)
while (!foundExecutionArn && elapsed < 3000ms) {
  executionArn = secretsManager.getSecret(deploymentId)
  if (executionArn != null) {
    foundExecutionArn = true
  } else {
    Thread.sleep(100ms)
  }
}

// 2단계: ExecutionHistory 폴링 (2초 간격, 최대 30분)
while (executionArn != null && elapsed < 30min) {
  List<HistoryEvent> events = stepFunctions.getExecutionHistory(executionArn)

  // 이전 이벤트 제외하고 신규 이벤트만 분석
  for (HistoryEvent event : newEvents) {
    if (event is TaskStarted) {
      // Task 단계 시작
      publishEvent("stage", "EnsureInfra started", {timestamp})
    }
    else if (event is TaskSucceeded) {
      // Task 단계 완료
      publishEvent("stage", "EnsureInfra succeeded", {duration})
    }
    else if (event is ExecutionSucceeded) {
      // 전체 배포 완료
      publishEvent("done", "Deployment succeeded", {totalDuration})
      return  // 폴링 종료
    }
    else if (event is ExecutionFailed) {
      // 배포 실패
      publishEvent("error", "Deployment failed", {reason})
      return  // 폴링 종료
    }
  }

  Thread.sleep(2000ms)
}

// 3단계: 타임아웃 처리
if (elapsed >= 30min && !finished) {
  publishEvent("error", "Deployment timeout", {elapsed})
}
```

**이벤트 타입**

```json
Stage Event (단계 진행 중)
{
  "type": "stage",
  "message": "EnsureInfra started",
  "details": {
    "stage": "EnsureInfra",
    "timestamp": "2024-01-15T10:30:15Z",
    "duration": 2500
  }
}

Done Event (배포 완료)
{
  "type": "done",
  "message": "Blue/Green deployment succeeded",
  "details": {
    "totalDuration": 180000,
    "newServiceUrl": "http://green.example.com:8080",
    "previousServiceUrl": "http://blue.example.com:8080"
  }
}

Error Event (배포 실패)
{
  "type": "error",
  "message": "Deployment failed: Task definition update failed",
  "details": {
    "reason": "InsufficientCapacityException",
    "failedStage": "RegisterTaskDefinition",
    "timestamp": "2024-01-15T10:35:20Z"
  }
}
```

---

### 4️⃣ Deploy Module - Blue/Green Deployment

#### 핵심 클래스: BlueGreenDeploymentService.java

**역할**
AWS Step Functions가 실행하는 최종 배포 단계. 실제 ECS 태스크 실행 및 트래픽 전환.

**Blue/Green 배포 방식**

```
기존 상태 (Blue):
┌──────────────────────────────────────┐
│ ECS Service "myapp"                  │
├──────────────────────────────────────┤
│ Task Definition: myapp:10            │
│ Desired Count: 3                     │
│ Running Count: 3                     │
│ Load Balancer: Blue Target Group     │
│ 포트: 8080                           │
└──────────────────────────────────────┘
         │
         └─ ALB 트래픽 100% → Blue


배포 중 (Blue + Green):
┌──────────────────────────────────────┐     ┌──────────────────────────────────────┐
│ ECS Service "myapp" (Blue)           │     │ ECS Service "myapp" (Green) 준비 중  │
├──────────────────────────────────────┤     ├──────────────────────────────────────┤
│ Task Definition: myapp:10            │     │ Task Definition: myapp:11 (새 버전) │
│ Desired Count: 3                     │     │ Desired Count: 3                     │
│ Running Count: 3                     │     │ Running Count: 0→3 (시작 중)        │
│ Load Balancer: Blue Target Group     │     │ Load Balancer: Green Target Group    │
└──────────────────────────────────────┘     └──────────────────────────────────────┘
         │                                             │
         └─ ALB 트래픽 100% → Blue              Green 준비 완료 → Health Check


배포 완료 (Green):
┌──────────────────────────────────────┐
│ ECS Service "myapp" (Green)          │
├──────────────────────────────────────┤
│ Task Definition: myapp:11            │
│ Desired Count: 3                     │
│ Running Count: 3                     │
│ Load Balancer: Green Target Group    │
│ 포트: 8080                           │
└──────────────────────────────────────┘
         │
         └─ ALB 트래픽 100% → Green


실패 시 롤백 (Blue):
┌──────────────────────────────────────┐
│ ECS Service "myapp" (Blue)           │
├──────────────────────────────────────┤
│ Task Definition: myapp:10            │
│ Desired Count: 3                     │
│ Running Count: 3                     │
│ Load Balancer: Blue Target Group     │
└──────────────────────────────────────┘
         │
         └─ ALB 트래픽 100% → Blue (복구됨)
```

**상세 배포 단계**

```java
// 1단계: Blue Service 상태 확인
Service blueService = ecs.describeService(serviceName);
if (blueService == null || blueService.isInactive()) {
  throw EcsDeploymentException("Blue Service 찾을 수 없음");
}
// 확인 항목:
// • 서비스가 존재하는가?
// • 서비스가 ACTIVE 상태인가?
// • 태스크가 정상 실행 중인가? (runningCount >= desiredCount)

// 2단계: Green Service 시작
TaskDefinition taskDefinition = ecs.describeTaskDefinition(
  newImageUri  // ECR에 푸시한 새 이미지
);

List<Task> greenTasks = ecs.runTask(
  cluster: serviceName,
  taskDefinition: taskDefinition,  // 새 Task Definition
  desiredCount: blueService.getDesiredCount(),  // Blue와 동일
  networkConfiguration: blueService.getNetworkConfiguration()
);

// 3단계: Green Task 실행 대기
waitForTasksHealthy(greenTasks, timeout: 5min);

// 확인 항목:
// • 모든 Task가 RUNNING 상태인가?
// • Task 내부 헬스 체크를 통과했는가?
// • 애플리케이션이 포트 8080에서 응답하는가?

// 4단계: Load Balancer 트래픽 전환
// 이 부분은 AWS CodeDeploy 또는 Lambda 함수가 수행
// • Blue Target Group에서 Green Target Group으로 이전
// • Deregistration Delay (Connection Draining) 대기
// • 기존 연결이 모두 종료될 때까지 대기 (보통 30초)

// 5단계: Lifecycle Hooks 실행 (선택사항)
// BeforeAllowTraffic: Green 배포 후, 트래픽 전환 전
//   └─ 예: 스모크 테스트, 데이터베이스 마이그레이션 검증
// AfterAllowTraffic: 트래픽 전환 후
//   └─ 예: Blue 리소스 정리, 모니터링 시작

// 6단계: 배포 확인
// • Green 트래픽이 정상인가? (에러율, 응답 시간)
// • Blue와 Green이 모두 정상인가?

// 7단계: Blue 서비스 제거 또는 대기 (선택사항)
// Option A: 즉시 제거
//   └─ 비용 절감
// Option B: 일정 시간 대기 후 제거
//   └─ 문제 발생 시 빠른 롤백 가능
```

---

### 5️⃣ Deploy Module - SSE Streaming

#### 핵심 클래스: StreamDeploymentEventsService.java & DeploymentEventStore.java

**역할**
배포 진행 상황을 실시간으로 클라이언트에게 스트리밍.

**HTTP 프로토콜: Server-Sent Events (SSE)**

```
GET /api/v1/deploy/{deploymentId}/events

응답 헤더:
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

응답 바디 (스트림):
event: stage
data: {"type":"stage","message":"Git Clone started","details":{...}}

event: stage
data: {"type":"stage","message":"Docker Build succeeded","details":{...}}

event: done
data: {"type":"done","message":"Deployment succeeded","details":{...}}
```

**동작 원리**

```java
// 1단계: SSE Emitter 등록
@GetMapping("/{deploymentId}/events")
public SseEmitter streamEvents(@PathVariable String deploymentId) {
  SseEmitter emitter = new SseEmitter(timeout: 5min);

  // emitter 등록 (클라이언트별 관리)
  eventStore.registerEmitter(deploymentId, emitter);

  // 2단계: 이전 이벤트 히스토리 전송 (중요!)
  List<DeploymentEvent> history = eventStore.getHistory(deploymentId);
  for (DeploymentEvent event : history) {
    try {
      emitter.send(SseEmitter.event()
        .id(event.id)
        .name(event.type)
        .data(event)
        .build());
    } catch (IOException e) {
      // 클라이언트 연결 끊김
      eventStore.removeEmitter(deploymentId, emitter);
    }
  }

  return emitter;
}

// 3단계: 새로운 이벤트 발행
void publishEvent(String deploymentId, DeploymentEvent event) {
  // 히스토리에 저장 (메모리 기반, 보통 10MB 제한)
  eventStore.addToHistory(deploymentId, event);

  // 모든 등록된 Emitter에 전송
  List<SseEmitter> emitters = eventStore.getEmitters(deploymentId);
  for (SseEmitter emitter : emitters) {
    try {
      emitter.send(SseEmitter.event()
        .id(UUID.randomUUID().toString())
        .name(event.type)
        .data(event)
        .build());
    } catch (IOException e) {
      // 전송 실패 시 Emitter 제거
      eventStore.removeEmitter(deploymentId, emitter);
    }
  }
}

// 4단계: 배포 종료 시 정리
void finishDeployment(String deploymentId) {
  // 모든 Emitter에 완료 이벤트 전송
  publishEvent(deploymentId, DeploymentEvent.done(...));

  // 타임아웃 또는 오류 발생 시 Emitter 자동 정리
  eventStore.closeAllEmitters(deploymentId);
}
```

**클라이언트 구현 예시 (JavaScript)**

```javascript
// SSE 연결
const eventSource = new EventSource(`/api/v1/deploy/${deploymentId}/events`);

// 이전 히스토리 및 실시간 이벤트 수신
eventSource.addEventListener('stage', (e) => {
  const event = JSON.parse(e.data);
  console.log(`Stage: ${event.message}`);
  updateUI(event);
});

eventSource.addEventListener('done', (e) => {
  const event = JSON.parse(e.data);
  console.log(`✅ 배포 완료: ${event.message}`);
  eventSource.close();
});

eventSource.addEventListener('error', (e) => {
  const event = JSON.parse(e.data);
  console.error(`❌ 배포 실패: ${event.message}`);
  eventSource.close();
});

eventSource.onerror = () => {
  console.log('SSE 연결 종료');
  eventSource.close();
};
```

---

## 📡 API 명세

### Connection API

#### 1. GitHub 연결 저장
```
POST /api/v1/connect/github

요청 본문:
{
  "owner": "mycompany",
  "repo": "backend-api",
  "branch": "main",
  "token": "ghp_16CvMVPRD4C74RgT98FzaVJvSC..."
}

응답:
{
  "success": true,
  "message": "GitHub 연결 성공",
  "data": {
    "connectionId": "conn_abc123",
    "owner": "mycompany",
    "repo": "backend-api",
    "branch": "main"
  }
}

HTTP Status: 200 OK 또는 400 Bad Request
```

#### 2. AWS 연결 저장
```
POST /api/v1/connect/aws

요청 본문:
{
  "region": "ap-northeast-2",
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}

응답:
{
  "success": true,
  "message": "AWS 연결 성공",
  "data": {
    "connectionId": "conn_xyz789",
    "region": "ap-northeast-2",
    "accountId": "123456789012"
  }
}
```

#### 3. 저장된 연결 조회
```
GET /api/v1/connections

응답:
{
  "success": true,
  "data": {
    "github": [
      {
        "connectionId": "conn_abc123",
        "owner": "mycompany",
        "repo": "backend-api",
        "branch": "main"
      }
    ],
    "aws": [
      {
        "connectionId": "conn_xyz789",
        "region": "ap-northeast-2",
        "accountId": "123456789012"
      }
    ]
  }
}
```

---

### Deployment API

#### 1. 배포 시작
```
POST /api/v1/deploy

요청 본문:
{
  "gitHubConnectionId": "conn_abc123",
  "awsConnectionId": "conn_xyz789",
  "ecsServiceName": "myapp-service",
  "ecsClusterName": "production-cluster",
  "ecsTaskFamily": "myapp-task",
  "containerPort": 8080,
  "desiredCount": 3
}

응답 (즉시 반환):
{
  "success": true,
  "message": "배포가 시작되었습니다",
  "data": {
    "deploymentId": "dep_1234567890ab",
    "status": "RUNNING",
    "startTime": "2024-01-15T10:30:00Z"
  }
}

HTTP Status: 202 Accepted (배포는 백그라운드에서 진행)
```

**배포 ID 형식**: `dep_` + 10자리 알파벳 숫자

#### 2. 배포 진행 상황 스트리밍 (SSE)
```
GET /api/v1/deploy/{deploymentId}/events

응답 헤더:
Content-Type: text/event-stream
Connection: keep-alive

응답 (스트림, 지속적으로 전송):

// 1. 과거 히스토리 전송 (중간 접속 사용자도 상황 파악 가능)
event: stage
data: {"type":"stage","message":"Git Clone started","timestamp":"2024-01-15T10:30:05Z"}

event: stage
data: {"type":"stage","message":"Git Clone succeeded","timestamp":"2024-01-15T10:30:15Z"}

// 2. 실시간 새로운 이벤트 전송
event: stage
data: {"type":"stage","message":"Docker Build started","timestamp":"2024-01-15T10:30:20Z"}

... (계속 이벤트 수신) ...

// 3. 배포 완료
event: done
data: {"type":"done","message":"Deployment succeeded","timestamp":"2024-01-15T10:33:00Z","details":{"totalDuration":180000,"blueServiceUrl":"http://...","greenServiceUrl":"http://..."}}
```

#### 3. 배포 결과 조회
```
GET /api/v1/deploy/{deploymentId}/result

응답:
{
  "success": true,
  "data": {
    "deploymentId": "dep_1234567890ab",
    "status": "SUCCEEDED",  // RUNNING, SUCCEEDED, FAILED, TIMEOUT
    "startTime": "2024-01-15T10:30:00Z",
    "endTime": "2024-01-15T10:33:00Z",
    "duration": 180000,  // ms
    "blueServiceUrl": "http://blue.elb.amazonaws.com:8080",
    "greenServiceUrl": "http://green.elb.amazonaws.com:8080",
    "newImageUri": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/mycompany-backend-api:main-20240115103000",
    "metrics": {
      "gitCloneDuration": 5000,
      "dockerBuildDuration": 90000,
      "ecrPushDuration": 15000,
      "deploymentDuration": 70000
    }
  }
}

HTTP Status: 200 OK 또는 404 Not Found
```

---

## 🔄 배포 파이프라인 동작 원리

### 전체 흐름 (시간축)

```
T=0s: 클라이언트 배포 요청
│
├─→ POST /api/v1/deploy
│   ├─ deploymentId 생성 (dep_xxxxxxxx)
│   ├─ EventBridge 규칙 생성
│   └─ 반환: {deploymentId, status: "RUNNING"}
│
T=0.5s: 클라이언트 SSE 연결
│
├─→ GET /api/v1/deploy/{deploymentId}/events
│   └─ 히스토리 이벤트 전송 시작
│
T=1s: 백그라운드 배포 작업 시작 (스레드 풀)
│
├─→ Stage 1: Git Clone (5초~10초)
│   ├─ 이벤트: "stage", "Git Clone started"
│   ├─ git clone --branch --depth 1 ...
│   ├─ Dockerfile 탐색
│   └─ 이벤트: "stage", "Git Clone succeeded"
│
├─→ Stage 2: Docker Build (1분~5분)
│   ├─ 이벤트: "stage", "Docker Build started"
│   ├─ docker build -t owner-repo-branch-timestamp .
│   ├─ 진행 상황 로깅
│   └─ 이벤트: "stage", "Docker Build succeeded"
│
├─→ Stage 3: ECR Push (30초~2분)
│   ├─ 이벤트: "stage", "ECR Push started"
│   ├─ ECR 리포지토리 확인/생성
│   ├─ docker login (AWS ECR)
│   ├─ docker tag & docker push
│   └─ 이벤트: "stage", "ECR Push succeeded"
│
│   🎯 EventBridge 자동 트리거 → Step Functions 워크플로우 시작
│
├─→ Stage 4: Step Functions 폴링 (2분~5분)
│   ├─ ExecutionArn 조회 (최대 3초 대기)
│   ├─ 2초 간격 GetExecutionHistory
│   ├─ 각 단계별 이벤트 분석:
│   │  ├─ "stage", "EnsureInfra started"
│   │  ├─ "stage", "EnsureInfra succeeded"
│   │  ├─ "stage", "RegisterTaskDefinition started"
│   │  ├─ "stage", "RegisterTaskDefinition succeeded"
│   │  ├─ "stage", "UpdateService started"
│   │  ├─ "stage", "UpdateService succeeded"
│   │  ├─ "stage", "BlueGreenDeployment started"
│   │  └─ "stage", "BlueGreenDeployment succeeded"
│   │
│   ├─ ExecutionSucceeded 도달
│   └─ 폴링 종료
│
T=180s (3분): 배포 완료
│
├─→ 이벤트: "done", "Deployment succeeded"
│   ├─ totalDuration: 180000ms
│   ├─ blueServiceUrl
│   ├─ greenServiceUrl
│   └─ metrics
│
└─→ 배포 상태: "SUCCEEDED"
    클라이언트가 SSE 연결 종료
```

### 오류 발생 시 흐름

```
배포 중 오류 발생 (예: Docker Build 실패)
│
├─→ DockerBuildException 발생
│   │
│   └─→ DeploymentErrorHandler
│       ├─ 에러 메시지 생성
│       ├─ 이벤트: "error", "Docker Build failed: ..."
│       └─ 배포 상태: "FAILED"
│
└─→ SSE를 통해 클라이언트에 에러 전송
    클라이언트는 배포 실패 상황 실시간 인지
```

### 타임아웃 처리

```
배포가 30분 이상 지속
│
├─→ StepFunctionsPollingService 타임아웃
│   │
│   └─→ 폴링 중단
│       ├─ 이벤트: "error", "Deployment timeout"
│       └─ 배포 상태: "TIMEOUT"
│
└─→ 사용자가 수동으로 배포 상태 조회
    GET /api/v1/deploy/{deploymentId}/result
    └─ status: "TIMEOUT"
```

---

## ⚙️ 환경 설정 및 실행

### 로컬 개발 환경 설정

#### 1. 필수 설치 항목
```bash
# Java 17
java -version
# openjdk version "17.0.x" LTS

# Docker
docker --version
# Docker version 25.0+

# Git
git --version
# git version 2.40+

# Gradle
gradle --version
# Gradle 8.6+ (프로젝트에 포함됨)
```

#### 2. 환경 변수 설정 (.env)
```bash
# AWS Configuration
export AWS_REGION="ap-northeast-2"
export AWS_ACCESS_KEY_ID="AKIAIOSFODNN7EXAMPLE"
export AWS_SECRET_ACCESS_KEY="wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

# Docker Configuration (로컬 개발)
export DOCKER_HOST="unix:///var/run/docker.sock"

# Panda Configuration
export PANDA_TEMP_DIR="/tmp/panda-deployments"  # 임시 파일 디렉토리
export PANDA_THREADPOOL_CORE=5                  # 코어 스레드 수
export PANDA_THREADPOOL_MAX=10                  # 최대 스레드 수
```

#### 3. 프로젝트 빌드
```bash
cd panda-backend

# 의존성 다운로드 및 빌드
./gradlew clean build

# 또는 테스트 제외
./gradlew clean build -x test
```

#### 4. 로컬 실행
```bash
# 방법 1: Gradle 직접 실행
./gradlew bootRun

# 방법 2: JAR 파일 실행
java -jar build/libs/backend-0.0.1-SNAPSHOT.jar

# 방법 3: Docker 컨테이너 실행
docker build -t panda-backend:latest .
docker run -p 8080:8080 \
  -e AWS_REGION=ap-northeast-2 \
  -e AWS_ACCESS_KEY_ID=... \
  -e AWS_SECRET_ACCESS_KEY=... \
  -v /var/run/docker.sock:/var/run/docker.sock \
  panda-backend:latest
```

#### 5. API 테스트
```bash
# Swagger UI
http://localhost:8080/swagger-ui.html

# Health Check
curl http://localhost:8080/actuator/health

# GitHub 연결 테스트
curl -X POST http://localhost:8080/api/v1/connect/github \
  -H "Content-Type: application/json" \
  -d '{
    "owner": "mycompany",
    "repo": "backend-api",
    "branch": "main",
    "token": "ghp_..."
  }'

# AWS 연결 테스트
curl -X POST http://localhost:8080/api/v1/connect/aws \
  -H "Content-Type: application/json" \
  -d '{
    "region": "ap-northeast-2",
    "accessKeyId": "AKIA...",
    "secretAccessKey": "..."
  }'
```

---

### 프로덕션 배포

#### 1. Docker 빌드 및 푸시
```bash
# 이미지 빌드
docker build -t panda-backend:v1.0.0 .

# 레지스트리에 태그 지정
docker tag panda-backend:v1.0.0 \
  123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/panda-backend:v1.0.0

# ECR 로그인
aws ecr get-login-password --region ap-northeast-2 | \
  docker login --username AWS --password-stdin \
  123456789012.dkr.ecr.ap-northeast-2.amazonaws.com

# ECR에 푸시
docker push 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/panda-backend:v1.0.0
```

#### 2. ECS 배포 (Terraform)
```hcl
# ECS 작업 정의
resource "aws_ecs_task_definition" "panda_backend" {
  family                   = "panda-backend"
  network_mode            = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                     = "512"
  memory                  = "1024"

  container_definitions = jsonencode([{
    name  = "panda-backend"
    image = "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/panda-backend:v1.0.0"

    portMappings = [{
      containerPort = 8080
      hostPort      = 8080
      protocol      = "tcp"
    }]

    environment = [
      {
        name  = "AWS_REGION"
        value = "ap-northeast-2"
      }
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = "/ecs/panda-backend"
        "awslogs-region"        = "ap-northeast-2"
        "awslogs-stream-prefix" = "ecs"
      }
    }
  }])
}

# ECS 서비스
resource "aws_ecs_service" "panda_backend" {
  name            = "panda-backend-service"
  cluster         = aws_ecs_cluster.production.id
  task_definition = aws_ecs_task_definition.panda_backend.arn
  desired_count   = 3
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.panda_backend.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.panda_backend.arn
    container_name   = "panda-backend"
    container_port   = 8080
  }
}
```

#### 3. AWS Secrets Manager에 시크릿 저장
```bash
# GitHub Token
aws secretsmanager create-secret \
  --name panda/github/token \
  --secret-string "ghp_..."

# AWS Credentials
aws secretsmanager create-secret \
  --name panda/aws/credentials \
  --secret-string '{"accessKeyId":"AKIA...","secretAccessKey":"..."}'

# Step Functions ExecutionArn (배포마다 업데이트됨)
aws secretsmanager create-secret \
  --name panda/deployments/{deploymentId}/execution-arn \
  --secret-string "arn:aws:states:ap-northeast-2:123456789012:execution:..."
```

---

## 🔐 보안 고려사항

### 현재 상태 (개발/테스트 모드)

#### 보안 취약점
1. **평문 자격증명 저장**
   - GitHub Token: 메모리에 평문 저장
   - AWS Access Key: 메모리에 평문 저장
   - 위험: 메모리 덤프 또는 로그 노출 시 탈취 가능

2. **Git URL에 토큰 포함**
   - 형식: `https://{token}@github.com/{owner}/{repo}.git`
   - 위험: 로그에 토큰이 노출될 수 있음

3. **인증/인가 부재**
   - 모든 API가 누구나 접근 가능
   - 위험: 무단 배포 또는 정보 유출

4. **HTTPS 미사용** (로컬 개발)
   - 위험: 평문 통신으로 데이터 탈취 가능

---

### 프로덕션 보안 개선 방안

#### 1. 시크릿 관리
```java
// ❌ 현재 (평문 저장)
class GitHubConnection {
  String token;  // 평문 저장
}

// ✅ 개선 (Secrets Manager)
class GitHubConnection {
  String secretArn;  // "arn:aws:secretsmanager:..."
}

// Secrets Manager에서 동적으로 로드
String token = secretsManager.getSecretValue(secretArn);
```

#### 2. AWS IAM Role 사용
```java
// ❌ 현재 (Access Key 저장)
String accessKeyId = ...;
String secretAccessKey = ...;

// ✅ 개선 (IAM Role)
// 애플리케이션이 EC2/ECS 인스턴스에서 실행될 때
// 자동으로 임시 자격증명 제공 (15분~1시간 유효)
AwsCredentialsProvider provider = DefaultCredentialsProvider.create();
// → IAM Role이 자동으로 자격증명 관리
```

#### 3. 인증/인가 추가
```java
// ✅ API Key 기반 인증
@Configuration
@EnableWebSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .authorizeHttpRequests(authz -> authz
        .requestMatchers("/api/v1/**")
          .hasHeader("X-API-Key")
        .anyRequest().authenticated()
      )
      .addFilterBefore(new ApiKeyFilter(), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }
}

// 또는 OAuth 2.0 / OpenID Connect 사용
// 또는 JWT 토큰 기반 인증
```

#### 4. HTTPS/TLS 적용
```yaml
# application.yml
server:
  ssl:
    key-store: classpath:keystore.p12
    key-store-password: ${KEY_STORE_PASSWORD}
    key-store-type: PKCS12
    key-alias: panda-backend
  port: 8443
```

#### 5. 로그 보안
```java
// ❌ 위험: 로그에 토큰 노출
logger.info("Cloning repository with token: " + token);

// ✅ 개선: 토큰 마스킹
String maskedToken = token.substring(0, 10) + "***";
logger.info("Cloning repository with token: " + maskedToken);

// ✅ 더 나은 방법: 로그에서 토큰 제거
String cloneUrl = "https://github.com/" + owner + "/" + repo + ".git";
// Git 환경 변수로 토큰 전달
ProcessBuilder pb = new ProcessBuilder("git", "clone", cloneUrl);
pb.environment().put("GIT_ASKPASS_OVERRIDE", "echo");
pb.environment().put("GIT_ASKPASS", "echo");
```

#### 6. 네트워크 보안
```terraform
# 보안 그룹 설정
resource "aws_security_group" "panda_backend" {
  ingress {
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]  # 내부 VPC만
  }

  egress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]  # GitHub, AWS API 호출용
  }
}

# VPC Endpoint (AWS API 호출 시 인터넷 게이트웨이 우회)
resource "aws_vpc_endpoint" "ecr_api" {
  vpc_id             = aws_vpc.main.id
  service_name       = "com.amazonaws.ap-northeast-2.ecr.api"
  vpc_endpoint_type  = "Interface"
  subnet_ids         = aws_subnet.private[*].id
  security_group_ids = [aws_security_group.vpc_endpoints.id]
}
```

#### 7. 감시 및 로깅
```java
// CloudTrail: AWS API 호출 로깅
// CloudWatch Logs: 애플리케이션 로그
// CloudWatch Alarms: 비정상 활동 감지

@Aspect
@Component
public class AuditLoggingAspect {
  @Before("@annotation(Auditable)")
  public void auditLog(JoinPoint joinPoint) {
    String user = SecurityContextHolder.getContext().getAuthentication().getName();
    String action = joinPoint.getSignature().getName();

    // CloudWatch Logs에 감사 로그 저장
    logger.info("AUDIT: user={}, action={}, timestamp={}",
      user, action, Instant.now());
  }
}
```

---

## 🔍 주요 클래스 상세 분석

### 1. DeployApi.java & DeployController.java

**역할**: 배포 관련 REST API 정의 및 구현

**코드 구조**

```java
@RestController
@RequestMapping("/api/v1/deploy")
public class DeployController {

  @PostMapping
  public ResponseEntity<ApiResponse<DeployResponse>> deploy(
      @RequestBody DeployRequest request) {
    // 1. 입력 검증
    // 2. 배포 시작
    // 3. deploymentId 반환
    // 4. HTTP 202 Accepted 반환 (비동기 처리)
  }

  @GetMapping("/{deploymentId}/events")
  public SseEmitter streamEvents(@PathVariable String deploymentId) {
    // 1. SSE Emitter 생성
    // 2. 이전 이벤트 히스토리 전송
    // 3. 실시간 이벤트 스트리밍
  }

  @GetMapping("/{deploymentId}/result")
  public ResponseEntity<ApiResponse<DeploymentResult>> getResult(
      @PathVariable String deploymentId) {
    // 1. 배포 결과 조회
    // 2. 상태, 소요 시간, URL 등 반환
  }
}
```

**HTTP 상태 코드**
- `202 Accepted`: 배포 요청 수락 (비동기 처리 중)
- `200 OK`: 결과 조회 성공
- `404 Not Found`: deploymentId를 찾을 수 없음
- `400 Bad Request`: 입력 검증 실패
- `500 Internal Server Error`: 서버 오류

---

### 2. StartDeploymentService.java

**역할**: 배포를 초기화하고 백그라운드 작업 시작

**핵심 메서드**

```java
public DeployResponse startDeployment(DeployRequest request) {
  // 1단계: deploymentId 생성
  String deploymentId = generateDeploymentId();  // dep_abc123def4

  // 2단계: EventBridge 규칙 생성
  createEventBridgeRule(deploymentId);
  // 규칙 이름: panda-deployment-{deploymentId}
  // 이벤트 소스: ECR 푸시 이벤트
  // 대상: Step Functions 상태 머신

  // 3단계: 서비스 계정 Lambda 호출
  invokeLambda(deploymentId);
  // 목적: Event Bus 권한 설정
  // EventBridge → Step Functions 실행 가능하도록 IAM 정책 추가

  // 4단계: 배포 메타데이터 초기화
  initializeDeploymentMetadata(deploymentId, request);

  // 5단계: 배포 작업을 스레드 풀에서 비동기 실행
  DeploymentTask task = new DeploymentTask(deploymentId, request);
  taskExecutor.execute(task);

  // 6단계: 즉시 응답 반환 (배포는 백그라운드에서 진행)
  return new DeployResponse(deploymentId, "RUNNING");
}

private String generateDeploymentId() {
  // Format: dep_ + 10자리 랜덤 알파벳 숫자
  return "dep_" + randomAlphanumeric(10);
}

private void createEventBridgeRule(String deploymentId) {
  // AWS EventBridge에 규칙 등록
  // 규칙: ECR에 이미지가 푸시되면 → Step Functions 실행

  Rule rule = Rule.builder()
    .name("panda-deployment-" + deploymentId)
    .eventBusName("default")
    .eventPattern("""
      {
        "source": ["aws.ecr"],
        "detail-type": ["ECR Image Action"],
        "detail": {
          "action": ["PUSH"],
          "result": ["SUCCESS"],
          "image-tag": ["main-20240115*"]  // 우리가 푼 태그와 매칭
        }
      }
    """)
    .state(RuleState.ENABLED)
    .targets(asList(
      Target.builder()
        .arn(stepFunctionArn)
        .roleArn(eventBridgeRoleArn)
        .build()
    ))
    .build();

  eventBridgeClient.putRule(rule);
}
```

---

### 3. DeploymentPipelineService.java

**역할**: Stage 1~3 실행 (Git Clone, Docker Build, ECR Push)

**핵심 메서드 (Stage 별)**

```java
public class DeploymentPipelineService {

  // Stage 1: Git Clone & Dockerfile 검색
  public void cloneRepository(String deploymentId, GitHubConnection github) {
    try {
      // 1. GitHub 연결 정보에서 Clone URL 구성
      String cloneUrl = String.format(
        "https://%s@github.com/%s/%s.git",
        github.getToken(),
        github.getOwner(),
        github.getRepo()
      );

      // 2. 임시 디렉토리 생성
      Path tempDir = Files.createTempDirectory("panda_" + deploymentId);

      // 3. Git Clone 실행
      ProcessBuilder pb = new ProcessBuilder(
        "git", "clone",
        "--branch", github.getBranch(),
        "--depth", "1",  // Shallow clone (속도 향상)
        cloneUrl,
        tempDir.toString()
      );
      Process process = pb.start();

      int exitCode = process.waitFor(2, TimeUnit.MINUTES);
      if (exitCode != 0) {
        throw new DeploymentException("Git clone failed");
      }

      // 4. Dockerfile 탐색
      Path dockerfile = findDockerfile(tempDir);
      if (dockerfile == null) {
        throw new DockerBuildException("Dockerfile not found");
      }

      // 5. SSE 이벤트 발행
      publishEvent(deploymentId, "stage", "Git Clone succeeded", {
        "duration": System.currentTimeMillis() - startTime,
        "dockerfilePath": dockerfile.toString()
      });

    } catch (Exception e) {
      publishErrorEvent(deploymentId, "Git Clone failed: " + e.getMessage());
      throw e;
    }
  }

  // Dockerfile 탐색 (우선순위 순서)
  private Path findDockerfile(Path root) {
    List<String> candidates = asList(
      root.resolve("Dockerfile"),
      root.resolve("docker/Dockerfile"),
      root.resolve("deployment/Dockerfile")
    );

    for (Path candidate : candidates) {
      if (Files.exists(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  // Stage 2: Docker Build
  public void buildDockerImage(String deploymentId, Path dockerfilePath) {
    try {
      // 1. 이미지 태그 생성
      String imageTag = String.format(
        "%s-%s-%s-%d",
        github.getOwner(),
        github.getRepo(),
        github.getBranch(),
        System.currentTimeMillis()
      );

      // 2. Docker 클라이언트 생성
      DockerClient docker = DockerClientBuilder
        .getInstance(DockerConfigBuilder.getInstance().build())
        .build();

      // 3. 이미지 빌드
      List<String> buildOutput = new ArrayList<>();
      docker.buildImageCmd(dockerfilePath.getParent().toFile())
        .withDockerfile(dockerfilePath.toFile())
        .withTag(imageTag)
        .exec(new BuildImageResultCallback() {
          @Override
          public void onNext(BuildResponseItem item) {
            buildOutput.add(item.getStream());
          }

          @Override
          public void onComplete() {
            publishEvent(deploymentId, "stage", "Docker Build succeeded", {
              "imageTag": imageTag,
              "buildLog": String.join("", buildOutput)
            });
          }

          @Override
          public void onError(Throwable throwable) {
            publishErrorEvent(deploymentId, "Docker Build failed: " + throwable.getMessage());
          }
        })
        .awaitCompletion(5, TimeUnit.MINUTES);

    } catch (Exception e) {
      publishErrorEvent(deploymentId, "Docker Build error: " + e.getMessage());
      throw new DockerBuildException(e.getMessage(), e);
    }
  }

  // Stage 3: ECR Push
  public void pushToEcr(String deploymentId, String imageTag, AwsConnection aws) {
    try {
      // 1. AWS 계정 ID 조회
      String accountId = getAwsAccountId(aws);

      // 2. ECR 리포지토리 관리
      String repositoryName = github.getOwner() + "-" + github.getRepo();
      createRepositoryIfNotExists(aws, repositoryName);

      // 3. AWS ECR 로그인
      String ecrUrl = accountId + ".dkr.ecr." + aws.getRegion() + ".amazonaws.com";
      String authToken = getEcrAuthToken(aws, ecrUrl);

      DockerClient docker = ...;
      docker.authCmd()
        .withUsername("AWS")
        .withPassword(authToken)
        .withRegistryAddress("https://" + ecrUrl)
        .exec(new AuthCmd.Callback() {...})
        .awaitCompletion();

      // 4. Docker 이미지 태깅
      String ecrImageTag = ecrUrl + "/" + repositoryName + ":" + imageTag;
      docker.tagImageCmd(imageTag, ecrImageTag, imageTag).exec();

      // 5. ECR Push
      docker.pushImageCmd(ecrImageTag)
        .exec(new PushImageResultCallback() {
          @Override
          public void onNext(PushResponseItem item) {
            // 진행 상황 로깅
          }

          @Override
          public void onComplete() {
            publishEvent(deploymentId, "stage", "ECR Push succeeded", {
              "imageUri": ecrImageTag
            });
            // EventBridge 자동으로 ECR 푸시 이벤트 감지
            // → Step Functions 워크플로우 자동 시작
          }
        })
        .awaitCompletion(2, TimeUnit.MINUTES);

    } catch (Exception e) {
      publishErrorEvent(deploymentId, "ECR Push failed: " + e.getMessage());
      throw new EcrException(e.getMessage(), e);
    }
  }
}
```

---

### 4. StepFunctionsPollingService.java

**역할**: ECR Push 후 Step Functions의 실행 상태를 주기적으로 모니터링

**핵심 메서드**

```java
public class StepFunctionsPollingService {

  public void startPolling(String deploymentId) {
    executor.submit(() -> {
      try {
        long startTime = System.currentTimeMillis();
        long timeout = 30 * 60 * 1000;  // 30분

        // 1단계: ExecutionArn 조회 (최대 3초)
        String executionArn = null;
        long arnSearchStart = System.currentTimeMillis();
        while (executionArn == null &&
               System.currentTimeMillis() - arnSearchStart < 3000) {
          try {
            executionArn = secretsManager.getSecret(deploymentId);
          } catch (ResourceNotFoundException e) {
            Thread.sleep(100);  // 100ms 대기 후 재시도
          }
        }

        if (executionArn == null) {
          publishErrorEvent(deploymentId, "Failed to find Step Functions execution");
          return;
        }

        publishEvent(deploymentId, "stage", "Step Functions execution found", {
          "executionArn": executionArn
        });

        // 2단계: ExecutionHistory 폴링 (2초 간격, 최대 30분)
        List<HistoryEvent> previousEvents = new ArrayList<>();

        while (System.currentTimeMillis() - startTime < timeout) {
          try {
            // ExecutionHistory 조회
            List<HistoryEvent> allEvents = stepFunctions.getExecutionHistory(
              executionArn
            );

            // 새로운 이벤트만 필터링
            List<HistoryEvent> newEvents = allEvents.stream()
              .filter(e -> !previousEvents.contains(e))
              .collect(toList());

            // 이벤트 분석
            for (HistoryEvent event : newEvents) {
              analyzeEvent(deploymentId, event);

              // 배포 종료 조건 확인
              if (event.getType().equals("ExecutionSucceeded")) {
                publishEvent(deploymentId, "done", "Deployment succeeded", {
                  "totalDuration": System.currentTimeMillis() - startTime,
                  "executionArn": executionArn
                });
                return;  // 폴링 종료
              }

              if (event.getType().equals("ExecutionFailed")) {
                publishErrorEvent(deploymentId,
                  "Deployment failed: " + event.getStateFailedEventDetails().getError());
                return;  // 폴링 종료
              }
            }

            previousEvents.addAll(newEvents);
            Thread.sleep(2000);  // 2초 대기

          } catch (Exception e) {
            logger.error("Error polling Step Functions: " + e.getMessage(), e);
            Thread.sleep(2000);  // 오류 발생해도 계속 폴링
          }
        }

        // 3단계: 타임아웃 처리
        publishErrorEvent(deploymentId,
          "Deployment timeout after 30 minutes");

      } catch (Exception e) {
        publishErrorEvent(deploymentId, "Polling error: " + e.getMessage());
      }
    });
  }

  private void analyzeEvent(String deploymentId, HistoryEvent event) {
    String eventType = event.getType();

    switch (eventType) {
      case "TaskStarted":
        publishEvent(deploymentId, "stage",
          event.getTaskStartedEventDetails().getResourceType() + " started", {
            "timestamp": event.getTimestamp()
          });
        break;

      case "TaskSucceeded":
        publishEvent(deploymentId, "stage",
          event.getTaskSucceededEventDetails().getResourceType() + " succeeded", {
            "duration": calculateDuration(event),
            "output": event.getTaskSucceededEventDetails().getOutput()
          });
        break;

      case "TaskFailed":
        publishErrorEvent(deploymentId,
          event.getTaskFailedEventDetails().getError() + ": " +
          event.getTaskFailedEventDetails().getCause());
        break;

      case "ExecutionFailed":
        publishErrorEvent(deploymentId,
          "Execution failed: " + event.getExecutionFailedEventDetails().getError());
        break;
    }
  }
}
```

---

### 5. DeploymentEventStore.java

**역할**: SSE Emitter 관리 및 배포 이벤트 히스토리 저장

**데이터 구조**

```java
public class DeploymentEventStore {

  // Emitter 저장소 (deploymentId → List<SseEmitter>)
  private ConcurrentHashMap<String, List<SseEmitter>> emitters;

  // 이벤트 히스토리 (deploymentId → List<DeploymentEvent>)
  private ConcurrentHashMap<String, List<DeploymentEvent>> history;

  // 배포 상태 추적 (deploymentId → DeploymentStatus)
  private ConcurrentHashMap<String, DeploymentStatus> status;

  public void registerEmitter(String deploymentId, SseEmitter emitter) {
    // deploymentId에 해당하는 Emitter 리스트에 추가
    emitters.computeIfAbsent(deploymentId, k -> new CopyOnWriteArrayList<>())
      .add(emitter);

    // 타임아웃 시 자동 제거
    emitter.onTimeout(() -> removeEmitter(deploymentId, emitter));
  }

  public void publishEvent(String deploymentId, DeploymentEvent event) {
    // 1. 히스토리에 저장 (메모리 제한: 최대 100개 또는 10MB)
    List<DeploymentEvent> eventHistory =
      history.computeIfAbsent(deploymentId, k -> new CopyOnWriteArrayList<>());
    eventHistory.add(event);

    if (eventHistory.size() > 100) {
      eventHistory.remove(0);  // 가장 오래된 이벤트 제거
    }

    // 2. 모든 Emitter에 전송
    List<SseEmitter> emitterList = emitters.get(deploymentId);
    if (emitterList != null) {
      for (SseEmitter emitter : emitterList) {
        try {
          emitter.send(SseEmitter.event()
            .id(UUID.randomUUID().toString())
            .name(event.getType())
            .data(event)
            .build());
        } catch (IOException e) {
          removeEmitter(deploymentId, emitter);  // 연결 끊긴 Emitter 제거
        }
      }
    }
  }

  public List<DeploymentEvent> getHistory(String deploymentId) {
    return history.getOrDefault(deploymentId, Collections.emptyList());
  }

  public void removeEmitter(String deploymentId, SseEmitter emitter) {
    List<SseEmitter> emitterList = emitters.get(deploymentId);
    if (emitterList != null) {
      emitterList.remove(emitter);

      // 더 이상 연결된 Emitter가 없으면 정리
      if (emitterList.isEmpty()) {
        emitters.remove(deploymentId);
      }
    }
  }
}
```

---

## 📊 성능 및 최적화

### 병목 지점

1. **Docker Build**
   - 문제: 첫 빌드는 모든 레이어를 다운로드해야 함
   - 해결: Docker BuildKit 캐싱, 계층형 Dockerfile 구성

2. **ECR Push**
   - 문제: 대용량 이미지는 push에 시간 소요
   - 해결: 병렬 레이어 업로드, 압축

3. **Step Functions 폴링**
   - 문제: 2초 간격 폴링으로 불필요한 API 호출
   - 해결: AWS EventBridge와 SNS를 통한 pub/sub 방식으로 개선 가능

### 성능 메트릭

```
평균 배포 시간 (100MB 이미지 기준):
├─ Stage 1 (Git Clone): 5~10초
├─ Stage 2 (Docker Build): 60~120초
├─ Stage 3 (ECR Push): 30~60초
├─ Stage 4 (Step Functions): 60~120초
└─ 전체: 155~310초 (약 3~5분)

리소스 사용:
├─ CPU: 최대 4 코어 (Docker Build 중)
├─ 메모리: 최대 2GB (Docker Build + JVM)
└─ 디스크: 최대 10GB (임시 파일, 캐시)
```

---

## 📚 추가 리소스

### 관련 문서
- `ARCHITECTURE.md`: 상세 아키텍처
- `API_SPECIFICATION.md`: API 명세서
- `.env.example`: 환경 변수 설정

### AWS 관련 문서
- [AWS Step Functions](https://docs.aws.amazon.com/step-functions/)
- [AWS EventBridge](https://docs.aws.amazon.com/eventbridge/)
- [Amazon ECS](https://docs.aws.amazon.com/ecs/)
- [Amazon ECR](https://docs.aws.amazon.com/ecr/)

### Spring Boot 문서
- [Spring Boot 3.5 Documentation](https://spring.io/projects/spring-boot)
- [Spring Web MVC](https://spring.io/guides/gs/serving-web-content/)
- [Async Processing](https://spring.io/guides/gs/async-method/)

### Docker 문서
- [Docker Build](https://docs.docker.com/engine/reference/commandline/build/)
- [Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)

---

## 🎓 결론

**Panda Backend**는 GitHub에서 코드를 자동으로 가져와 완전히 자동화된 배포 파이프라인을 제공하는 엔터프라이즈급 솔루션입니다.

### 주요 특징 요약
- ✅ **완전 자동화**: Git → Docker → ECR → ECS까지 버튼 하나로
- ✅ **실시간 모니터링**: SSE를 통한 실시간 배포 진행 상황 스트리밍
- ✅ **안정적 배포**: Blue/Green 무중단 배포로 기존 서비스 중단 방지
- ✅ **확장 가능**: AWS 서비스와 완벽 통합, 다중 연결 지원
- ✅ **안전한 배포**: Step Functions를 통한 체계적인 배포 관리

이 프로젝트는 마이크로서비스 환경에서 안정적이고 신뢰할 수 있는 배포를 원하는 팀에게 이상적인 솔루션입니다.

---

**문서 생성일**: 2024년 1월 15일
**마지막 수정**: 2024년 1월 15일
**대상 버전**: 1.0.0+
