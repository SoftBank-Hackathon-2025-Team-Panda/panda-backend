# Panda Backend - 아키텍처 및 시스템 흐름 상세 가이드

## 📋 목차
1. [프로젝트 개요](#프로젝트-개요)
2. [시스템 아키텍처](#시스템-아키텍처)
3. [디렉토리 구조](#디렉토리-구조)
4. [핵심 개념](#핵심-개념)
5. [파일별 상세 설명](#파일별-상세-설명)
6. [배포 파이프라인 흐름](#배포-파이프라인-흐름)
7. [SSE 실시간 스트리밍](#sse-실시간-스트리밍)
8. [에러 처리](#에러-처리)
9. [비동기 처리 및 스레드 관리](#비동기-처리-및-스레드-관리)
10. [데이터 흐름 예제](#데이터-흐름-예제)

---

## 프로젝트 개요

**프로젝트명**: ECR Deployment API (Panda Backend)
**목적**: GitHub 레포지토리에서 코드를 받아 Docker 이미지로 빌드하고, AWS ECR로 푸시한 후, ECS를 통해 Blue/Green 배포 수행
**기술 스택**: Spring Boot 3.5.7, Java 17, AWS SDK v2, Docker
**포트**: 8080

### 주요 특징
- **비동기 배포**: CompletableFuture 기반의 논블로킹 배포 처리
- **실시간 모니터링**: SSE(Server-Sent Events)를 통한 배포 진행 상황 실시간 스트리밍
- **Blue/Green 배포**: 무중단 배포로 서비스 연속성 보장
- **포괄적 에러 처리**: 예외 타입별 세분화된 에러 처리
- **타임아웃 관리**: 전체 배포 및 단계별 타임아웃 체크

---

## 시스템 아키텍처

### 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────┐
│                        Client Application                        │
│  (Webhook, Dashboard, CLI Tool 등)                              │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot REST API                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │ConnectApi    │  │DeployApi     │  │GlobalHandler │          │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘          │
│         │                 │                 │                   │
└─────────┼─────────────────┼─────────────────┼───────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Application Layer (Service)                   │
│  ┌──────────────────────┐  ┌──────────────────────┐             │
│  │Connection Services   │  │Deployment Services   │             │
│  ├──────────────────────┤  ├──────────────────────┤             │
│  │SaveGitHub...         │  │StartDeployment       │             │
│  │SaveAwsConnection     │  │GetDeploymentResult   │             │
│  │GitHubConnection      │  │StreamDeploymentEvent │             │
│  │AwsConnection         │  │DeploymentPipeline    │             │
│  └──────┬───────────────┘  └──────┬───────────────┘             │
└─────────┼─────────────────────────┼───────────────────────────────┘
          │                         │
          ▼                         ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Infrastructure & Event Layer                    │
│  ┌─────────────────────────────────────────────────────┐        │
│  │ Event Management                                    │        │
│  ├─────────────────────────────────────────────────────┤        │
│  │• DeploymentEventStore (SSE 관리 + 히스토리)          │        │
│  │• DeploymentEventPublisherImpl (이벤트 발행)          │        │
│  │• StageEventHelper (Stage별 이벤트 생성)             │        │
│  └──────────────────────┬──────────────────────────────┘        │
│  ┌─────────────────────────────────────────────────────┐        │
│  │ Task Management                                     │        │
│  ├─────────────────────────────────────────────────────┤        │
│  │• DeploymentTask (Runnable 구현)                      │        │
│  │• DeploymentTaskExecutor (ThreadPool 관리)           │        │
│  │• DeploymentHistoryManager (결과 저장소)             │        │
│  │• DeploymentErrorHandler (에러 처리)                 │        │
│  └──────────────────────┬──────────────────────────────┘        │
└─────────────────────────┼────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────────┐
│                    External Systems                              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │  GitHub      │  │  Docker      │  │  AWS         │          │
│  │  (REST API)  │  │  (CLI/API)   │  │  (SDK v2)    │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                  │
│  - GitHub API: Repository 검증                                  │
│  - Docker: Image Build & Push                                   │
│  - AWS: ECR, ECS, CodeDeploy, STS                               │
└─────────────────────────────────────────────────────────────────┘
```

### 레이어별 책임

| 레이어 | 담당 파일 | 책임 |
|--------|---------|------|
| **Presentation** | ConnectController, DeployController | HTTP 요청/응답 처리, 요청 라우팅 |
| **Application** | SaveGitHub/AwsConnectionService, StartDeploymentService 등 | 비즈니스 로직 조율, 도메인 모델 조작 |
| **Domain** | Entity (GitHubConnection, AwsConnection), DTO, Event | 도메인 모델, 데이터 구조 |
| **Infrastructure** | DeploymentTask, TaskExecutor, HistoryManager, ErrorHandler | 기술적 구현 (Thread, Storage, External API 호출) |
| **Global** | ConnectionStore, GlobalExceptionHandler, ApiResponse | 횡단 관심사 (설정, 에러 처리, 응답 형식) |

---

## 디렉토리 구조

```
src/main/java/com/panda/backend/
│
├── BackendApplication.java                     # 애플리케이션 진입점
│
├── feature/
│   ├── connect/                                # GitHub/AWS 연결 관리 (Feature 1)
│   │   ├── api/
│   │   │   ├── ConnectApi.java                # API 인터페이스
│   │   │   └── ConnectController.java         # 컨트롤러 구현
│   │   ├── application/
│   │   │   ├── GitHubConnectionService.java   # GitHub API 호출
│   │   │   ├── AwsConnectionService.java      # AWS STS 검증
│   │   │   ├── SaveGitHubConnectionService.java
│   │   │   └── SaveAwsConnectionService.java
│   │   ├── dto/
│   │   │   ├── ConnectGitHubRequest/Response
│   │   │   └── ConnectAwsRequest/Response
│   │   └── entity/
│   │       ├── GitHubConnection.java          # GitHub 연결 정보 저장
│   │       └── AwsConnection.java             # AWS 연결 정보 저장
│   │
│   └── deploy/                                 # 배포 파이프라인 (Feature 2)
│       ├── api/
│       │   ├── DeployApi.java                 # API 인터페이스 (3개 필수 API)
│       │   └── DeployController.java          # 컨트롤러 구현
│       ├── application/
│       │   ├── StartDeploymentService.java    # 배포 시작
│       │   ├── GetDeploymentResultService.java# 결과 조회
│       │   ├── StreamDeploymentEventsService.java # SSE 스트리밍
│       │   └── DeploymentPipelineService.java # 6단계 파이프라인
│       ├── event/
│       │   ├── DeploymentEvent.java           # 이벤트 데이터 모델
│       │   ├── DeploymentEventPublisher.java  # 발행자 인터페이스
│       │   ├── DeploymentEventPublisherImpl.java
│       │   ├── DeploymentEventStore.java      # SSE 관리 및 히스토리
│       │   └── StageEventHelper.java          # Stage별 이벤트 생성
│       ├── dto/
│       │   ├── DeployRequest.java
│       │   ├── DeployResponse.java
│       │   ├── DeploymentMetadata.java        # 배포 진행 중 메타데이터
│       │   └── DeploymentResult.java          # 배포 결과
│       ├── exception/
│       │   ├── DeploymentException.java       # 기본 배포 예외
│       │   ├── DeploymentTimeoutException.java
│       │   ├── DockerBuildException.java
│       │   ├── EcsDeploymentException.java
│       │   └── HealthCheckException.java
│       └── infrastructure/
│           ├── DeploymentTask.java            # Runnable 구현
│           ├── DeploymentTaskExecutor.java    # ThreadPool 관리
│           ├── DeploymentHistoryManager.java  # 결과 저장소
│           └── DeploymentErrorHandler.java    # 에러 처리 중앙화
│
└── global/
    ├── config/
    │   ├── ConnectionStore.java               # GitHub/AWS 연결 저장소
    │   └── SwaggerConfig.java                 # Swagger/OpenAPI 설정
    ├── exception/
    │   └── GlobalExceptionHandler.java        # 글로벌 예외 핸들러
    └── response/
        └── ApiResponse.java                   # 통일된 응답 형식

src/main/resources/
└── application.yaml                           # 애플리케이션 설정
```

---

## 핵심 개념

### 1. Connection Store (연결 저장소)

```
ConnectionStore (Global 싱글톤)
  ├─ Map<String, GitHubConnection>    # GitHub 연결 정보
  └─ Map<String, AwsConnection>       # AWS 연결 정보

역할:
  - GitHub 토큰, owner, repo, branch 저장
  - AWS 자격증명 (accessKeyId, secretAccessKey, sessionToken) 저장
  - 배포 시작 시 연결 정보 검증 및 조회
```

### 2. Deployment Metadata (배포 메타데이터)

진행 중인 배포의 모든 정보를 실시간으로 추적:

```
DeploymentMetadata
  ├─ deploymentId          # 배포 고유 ID (dep_xxxxxxxxxx)
  ├─ status                # IN_PROGRESS, COMPLETED, FAILED
  ├─ currentStage          # 현재 진행 중인 단계 (1-6)
  ├─ owner/repo/branch     # GitHub 정보
  ├─ awsRegion             # AWS 리전
  ├─ startedAt/completedAt # 시간 정보
  ├─ finalService          # 최종 활성 서비스 (blue/green)
  ├─ blueUrl/greenUrl      # 서비스 URL
  ├─ errorMessage          # 실패 시 에러 메시지
  └─ 성능 메트릭           # 레이턴시, 에러율 등
```

### 3. Event Store (이벤트 저장소)

SSE를 통한 실시간 스트리밍과 히스토리 관리:

```
DeploymentEventStore
  ├─ emitterMap            # deploymentId -> List<SseEmitter>
  │                        # 현재 연결된 모든 SSE 클라이언트
  │
  ├─ eventHistoryMap       # deploymentId -> Queue<DeploymentEvent>
  │                        # 배포 진행 중 발생한 모든 이벤트
  │
  └─ metadataMap           # deploymentId -> DeploymentMetadata
                           # 배포 진행 상태

주요 기능:
  - registerEmitter()      # 신규 SSE 클라이언트 등록
  - broadcastEvent()       # 모든 클라이언트에게 이벤트 전송
  - sendDoneEvent()        # 배포 완료 이벤트 + 5초 뒤 연결 종료
  - sendErrorEvent()       # 배포 실패 이벤트 + 5초 뒤 연결 종료
  - getEventHistory()      # 신규 클라이언트에게 과거 이벤트 제공
```

### 4. Deployment Task (배포 작업)

```
DeploymentTask implements Runnable
  └─ run()
     └─ deploymentPipelineService.triggerDeploymentPipeline()
        └─ 6단계 배포 파이프라인 실행

특징:
  - ThreadPool에서 비동기 실행
  - 예외 발생 시 에러 이벤트 발행
  - InterruptedException 처리
```

### 5. Task Executor (작업 실행자)

```
ThreadPool 설정:
  - Core Threads: 5
  - Max Threads: 10
  - Keep Alive: 60초
  - Queue Capacity: 50
  - Policy: CallerRunsPolicy

CompletableFuture:
  - 타임아웃: 30분 (자동)
  - whenComplete() 콜백으로 정리 작업 수행
  - Future 저장 및 중복 배포 방지
```

---

## 파일별 상세 설명

### **Connection Feature**

#### ConnectApi.java & ConnectController.java
- **역할**: GitHub/AWS 연결 API 제공
- **2개 엔드포인트**:
  - `POST /api/v1/connect/github`: GitHub 레포 연결
  - `POST /api/v1/connect/aws`: AWS 계정 연결

#### SaveGitHubConnectionService.java
```
흐름:
1. ConnectGitHubRequest 받음 (token, owner, repo, branch)
2. GitHubConnectionService.validateAndConnectGitHub() 호출
   → GitHub API로 레포 존재 여부 확인
3. 검증 성공하면 ConnectionStore에 저장
4. connectionId 반환 (gh_xxxxxxxxxx)
```

#### SaveAwsConnectionService.java
```
흐름:
1. ConnectAwsRequest 받음 (region, accessKeyId, secretAccessKey, sessionToken)
2. AwsConnectionService.validateAwsCredentials() 호출
   → STS GetCallerIdentity로 자격증명 검증
3. 검증 성공하면 ConnectionStore에 저장
4. connectionId 반환 (aws_xxxxxxxxxx)
```

### **Deployment Feature**

#### DeployApi.java & DeployController.java
- **역할**: 배포 파이프라인 관리 API
- **3개 필수 엔드포인트**:
  1. `POST /api/v1/deploy`: 배포 시작 (즉시 deploymentId 반환)
  2. `GET /api/v1/deploy/{deploymentId}/events`: SSE 스트리밍
  3. `GET /api/v1/deploy/{deploymentId}/result`: 배포 결과 조회

#### StartDeploymentService.java
```
배포 시작 흐름:
1. GitHub/AWS 연결 정보 검증 (ConnectionStore 조회)
2. deploymentId 생성 (dep_xxxxxxxxxx)
3. 배포 메타데이터 초기화 (IN_PROGRESS, stage 0)
4. DeploymentTask 생성
5. DeploymentTaskExecutor에 제출
   → ThreadPool에서 비동기 실행
6. 즉시 DeployResponse 반환
```

#### DeploymentPipelineService.java
```
배포 파이프라인 (6단계):

Stage 1: Dockerfile 탐색 및 Docker Build
  ├─ git clone --depth 1 (GitHub에서 코드 받음)
  ├─ 레포지토리 내 Dockerfile 검색
  └─ docker build (로컬 이미지 생성)

Stage 2: ECR에 이미지 Push
  ├─ AWS 계정 ID 조회 (STS GetCallerIdentity)
  ├─ ECR 리포지토리 생성/확인
  ├─ docker login (ECR 로그인)
  └─ docker push (ECR에 이미지 푸시)

Stage 3: ECS 배포 시작
  ├─ ECS 서비스 생성 (또는 기존 서비스 사용)
  └─ ECS 서비스 업데이트 (새 이미지 배포)

Stage 4: CodeDeploy Blue/Green Lifecycle
  ├─ Blue 서비스 실행 상태 확인
  ├─ Green 서비스 시작 (신규 이미지)
  ├─ CodeDeploy Lifecycle Hook (BeforeAllowTraffic)
  └─ CodeDeploy Lifecycle Hook (AfterAllowTraffic)

Stage 5: HealthCheck & 트래픽 전환
  ├─ Green 서비스 헬스체크 (5회 확인)
  ├─ 모든 체크 통과 시 Blue → Green 트래픽 전환
  └─ Blue 서비스 종료

Stage 6: 배포 완료
  └─ 최종 상태 저장

타임아웃 관리:
  - 전체 배포: 30분
  - 단계별: 10분
  - 각 단계 시작 시 checkTimeout() 호출
```

#### StreamDeploymentEventsService.java
```
SSE 스트리밍 흐름:
1. GET /api/v1/deploy/{deploymentId}/events 요청
2. registerEmitter() 호출
   → SseEmitter 생성, emitterMap에 추가
3. sendEventHistory() 호출
   → 기존 이벤트 히스토리를 신규 클라이언트에게 전송
4. SseEmitter 반환
   → 클라이언트는 SSE 연결 유지
5. 배포 진행 중 이벤트 발생
   → broadcastEvent()로 모든 연결에 전송
6. 배포 완료/실패
   → sendDoneEvent() 또는 sendErrorEvent()
   → 5초 뒤 자동 연결 종료
```

#### GetDeploymentResultService.java
```
배포 결과 조회:
1. GET /api/v1/deploy/{deploymentId}/result 요청
2. DeploymentHistoryManager에서 결과 조회
3. DeploymentResult 반환:
   - 배포 상태 (COMPLETED/FAILED)
   - 소요 시간
   - Blue/Green URL
   - 성능 메트릭 (레이턴시, 에러율)
   - 발생한 이벤트 개수
```

### **Event Management**

#### DeploymentEventStore.java
```
핵심 메서드:

registerEmitter(deploymentId)
  └─ SseEmitter 생성 (5분 타임아웃)
  └─ emitterMap에 추가
  └─ 콜백 등록 (completion, timeout, error)

broadcastEvent(deploymentId, event)
  ├─ 이벤트를 eventHistoryMap에 저장
  └─ 모든 연결된 emitter에게 전송
  └─ 실패한 emitter 자동 제거

sendDoneEvent(deploymentId, message)
  ├─ "done" 타입 이벤트 발행
  ├─ 배포 결과 저장 (HistoryManager)
  └─ 5초 뒤 closeAllEmitters() 호출

sendErrorEvent(deploymentId, message)
  ├─ "error" 타입 이벤트 발행
  ├─ 배포 결과 저장 (HistoryManager)
  └─ 5초 뒤 closeAllEmitters() 호출

getEventHistory(deploymentId)
  └─ 신규 클라이언트에게 제공할 과거 이벤트 반환
```

#### StageEventHelper.java
```
각 Stage별 이벤트 메서드:

Stage별 이벤트 생성:
  stage1Start() ~ stage1BuildCompleted()
  stage2Start() ~ stage2PushCompleted()
  stage3Start() ~ stage3ServiceUpdated()
  stage4Start() ~ stage4LifecycleHook()
  stage5Start() ~ stage5TrafficSwitched()
  stage6Complete()

각 메서드:
  ├─ updateStage() 호출
  │  └─ eventPublisher.publishStageEvent() 호출
  └─ publishProgress() 호출
     └─ 세부 진행 상황 이벤트 발행
```

#### DeploymentEventPublisherImpl.java
```
이벤트 발행 메서드:

publishStageEvent(deploymentId, stage, message, details)
  ├─ Stage 업데이트
  ├─ DeploymentEvent 생성 (type: "stage")
  └─ broadcastEvent() 호출

publishSuccessEvent(deploymentId, finalService, blueUrl, greenUrl)
  ├─ 메타데이터 완료 처리
  ├─ type: "done" 이벤트 발행
  └─ sendDoneEvent() 호출

publishErrorEvent(deploymentId, errorMessage)
  ├─ 메타데이터 실패 처리
  └─ sendErrorEvent() 호출

initializeDeployment(deploymentId, owner, repo, branch, awsRegion)
  └─ initializeMetadata() 호출
```

### **Error Handling**

#### DeploymentErrorHandler.java
```
에러 처리 흐름:

handleException(deploymentId, exception)
  ├─ exception 타입 판별
  ├─ 타입별 핸들러 호출
  └─ 에러 이벤트 발행

타입별 처리:
  - DeploymentTimeoutException → handleTimeoutException()
  - DockerBuildException → handleDockerBuildException()
  - EcsDeploymentException → handleEcsDeploymentException()
  - HealthCheckException → handleHealthCheckException()
  - DeploymentException → handleGenericDeploymentException()
  - Exception → handleUnexpectedException()

모든 경우:
  ├─ 상세 로깅
  ├─ 에러 이벤트 발행 (eventPublisher.publishErrorEvent())
  └─ 배포 상태 업데이트 (FAILED)
```

#### GlobalExceptionHandler.java
```
HTTP 레이어 예외 처리:

@ExceptionHandler(DeploymentTimeoutException)
  └─ HTTP 408 Request Timeout

@ExceptionHandler(DeploymentException)
  └─ HTTP 400 Bad Request

@ExceptionHandler(Exception)
  └─ HTTP 500 Internal Server Error

모든 응답:
  ├─ timestamp
  ├─ status (HTTP 상태 코드)
  ├─ error (에러 타입)
  ├─ message (상세 메시지)
  └─ deploymentId/stage/errorCode (배포 관련 정보)
```

### **Infrastructure**

#### DeploymentTask.java
```
Runnable 구현:

run() 메서드:
  1. deploymentPipelineService.triggerDeploymentPipeline() 호출
  2. 배포 파이프라인 실행 (Stage 1-6)
  3. 예외 발생 시:
     - InterruptedException 처리
     - eventPublisher.publishErrorEvent() 호출
     - 스택 트레이스 로깅
```

#### DeploymentTaskExecutor.java
```
ThreadPool 관리:

초기화:
  - ThreadPoolExecutor 생성
  - Core: 5, Max: 10, Queue: 50
  - 커스텀 ThreadFactory (이름: deployment-worker-N)
  - Policy: CallerRunsPolicy

executeDeployment(deploymentId, task):
  1. 이미 실행 중인 배포가 있으면 취소
  2. deploymentStartTimes에 시작 시간 기록
  3. CompletableFuture.runAsync() 제출
  4. orTimeout(30분) 설정
  5. whenComplete() 콜백에서 cleanupDeployment()
  6. Future를 deploymentFutures에 저장
  7. CompletableFuture 반환

cancelDeployment(deploymentId):
  └─ 진행 중인 배포 취소 (중복 배포 방지)

cleanupDeployment(deploymentId):
  └─ deploymentFutures/deploymentStartTimes에서 제거
```

#### DeploymentHistoryManager.java
```
배포 결과 저장소:

메모리 관리:
  - MAX_STORED_DEPLOYMENTS = 1000
  - 초과 시 가장 오래된 배포부터 삭제

saveDeploymentResult(deploymentId, metadata, events, status):
  1. DeploymentResult 빌드
  2. deploymentResults에 저장
  3. cleanupOldDeployments() 호출 (오래된 항목 정리)

getDeploymentResult(deploymentId):
  └─ 배포 결과 조회 (배포 완료 후 사용)

cleanupOldDeployments():
  └─ 저장된 배포가 1000개 초과하면 정렬 후 제거
```

---

## 배포 파이프라인 흐름

### 요청부터 완료까지의 전체 흐름

```
1️⃣ 배포 요청 (클라이언트)
   POST /api/v1/deploy
   {
     "githubConnectionId": "gh_1234567890",
     "awsConnectionId": "aws_1234567890",
     "owner": "your-org",
     "repo": "your-repo",
     "branch": "main"
   }

2️⃣ DeployController.deploy()
   ├─ StartDeploymentService.start(request) 호출
   └─ 즉시 DeployResponse 반환

3️⃣ StartDeploymentService.start()
   ├─ GitHub/AWS 연결 검증 (ConnectionStore 조회)
   ├─ deploymentId 생성: "dep_abc1234567"
   ├─ eventPublisher.initializeDeployment() 호출
   │  └─ DeploymentEventStore.initializeMetadata()
   ├─ DeploymentTask 생성
   └─ deploymentTaskExecutor.executeDeployment() 호출

4️⃣ DeploymentTaskExecutor.executeDeployment()
   ├─ deploymentStartTimes에 시작 시간 기록
   ├─ CompletableFuture.runAsync(task) 제출
   │  └─ ThreadPool의 deployment-worker 스레드에서 실행
   ├─ orTimeout(30분) 설정
   ├─ whenComplete() 콜백 등록
   └─ 즉시 CompletableFuture 반환

5️⃣ 클라이언트가 병렬로 SSE 구독
   GET /api/v1/deploy/dep_abc1234567/events
   ├─ StreamDeploymentEventsService.stream(deploymentId)
   ├─ registerEmitter(deploymentId) 호출
   ├─ sendEventHistory(deploymentId, emitter) 호출
   │  └─ 기존 이벤트 히스토리 전송
   └─ SseEmitter 반환 (SSE 연결 유지)

6️⃣ ThreadPool에서 DeploymentTask.run() 실행
   ├─ deploymentPipelineService.triggerDeploymentPipeline() 호출
   └─ StageEventHelper 생성

7️⃣ Stage 1: Dockerfile 탐색 및 Docker Build (10분 타임아웃)
   ├─ stageHelper.stage1Start()
   │  └─ [Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...
   ├─ cloneRepository()
   │  └─ git clone --depth 1 https://token@github.com/owner/repo.git
   ├─ stageHelper.stage1RepositoryCloned(clonePath)
   ├─ findDockerfile()
   ├─ stageHelper.stage1DockerfileFound()
   ├─ buildDockerImage()
   │  └─ docker build -t owner-repo-main-timestamp .
   └─ stageHelper.stage1BuildCompleted(imageName)
      └─ eventPublisher.publishStageEvent()
         └─ DeploymentEventStore.broadcastEvent()
            └─ 모든 SSE 클라이언트에게 이벤트 전송

8️⃣ Stage 2: ECR Push (10분 타임아웃)
   ├─ stageHelper.stage2Start()
   ├─ getAwsAccountId() (STS GetCallerIdentity)
   ├─ ensureEcrRepository() (ECR 리포지토리 생성/확인)
   ├─ loginToEcr() (docker login)
   ├─ docker tag && docker push
   └─ stageHelper.stage2PushCompleted()

9️⃣ Stage 3: ECS 배포 (10분 타임아웃)
   ├─ stageHelper.stage3Start()
   ├─ performEcsDeployment()
   │  ├─ ECS 서비스 생성
   │  └─ ECS 서비스 업데이트
   └─ stageHelper.stage3ServiceUpdated()

🔟 Stage 4: CodeDeploy Blue/Green (10분 타임아웃)
   ├─ stageHelper.stage4Start()
   ├─ performBlueGreenDeployment()
   │  ├─ stageHelper.stage4BlueServiceRunning()
   │  ├─ stageHelper.stage4GreenServiceSpinning()
   │  ├─ stageHelper.stage4GreenServiceReady()
   │  ├─ stageHelper.stage4LifecycleHook("BeforeAllowTraffic")
   │  └─ stageHelper.stage4LifecycleHook("AfterAllowTraffic")
   └─ 이벤트 발행

1️⃣1️⃣ Stage 5: HealthCheck & 트래픽 전환 (10분 타임아웃)
   ├─ stageHelper.stage5Start()
   ├─ performHealthCheckAndTrafficSwitch()
   │  ├─ stageHelper.stage5HealthCheckRunning()
   │  ├─ 5회 헬스체크 수행
   │  ├─ stageHelper.stage5HealthCheckPassed()
   │  ├─ stageHelper.stage5TrafficSwitching()
   │  └─ stageHelper.stage5TrafficSwitched()
   └─ 이벤트 발행

1️⃣2️⃣ Stage 6: 배포 완료
   ├─ stageHelper.stage6Complete()
   └─ eventPublisher.publishSuccessEvent()
      ├─ DeploymentEventStore.completeDeployment()
      ├─ sendDoneEvent()
      │  ├─ broadcastEvent() 호출
      │  ├─ saveDeploymentResult()
      │  └─ 5초 뒤 closeAllEmitters()
      └─ SSE 연결 자동 종료

1️⃣3️⃣ 클라이언트가 배포 결과 조회
   GET /api/v1/deploy/dep_abc1234567/result
   ├─ GetDeploymentResultService.getResult()
   ├─ DeploymentHistoryManager.getDeploymentResult()
   └─ DeploymentResult 반환 (상태, URL, 메트릭 등)

❌ 예외 발생 시
   ├─ DeploymentErrorHandler.handleException()
   ├─ 예외 타입별 처리 (타임아웃, Docker 빌드 실패 등)
   ├─ eventPublisher.publishErrorEvent()
   ├─ sendErrorEvent()
   │  ├─ broadcastEvent()
   │  ├─ saveDeploymentResult()
   │  └─ 5초 뒤 closeAllEmitters()
   └─ SSE 연결 자동 종료
```

---

## SSE 실시간 스트리밍

### SSE 프로토콜

```
EventSource API (클라이언트)
  ↓
GET /api/v1/deploy/{deploymentId}/events
  (Accept: text/event-stream)
  ↓
HTTP 200 OK
Content-Type: application/text/event-stream
  ↓
영구 연결 유지 (5분 타임아웃)
  ↓
서버가 이벤트 전송 (비동기)
  ↓
:event message
id: uuid
event: stage (또는 done, error)
data: {JSON}
reconnect: 5000
```

### 이벤트 타입

| 타입 | 발생 상황 | 페이로드 | 예제 |
|------|---------|--------|------|
| **stage** | 배포 진행 (각 Stage의 모든 진행 상황) | DeploymentEvent 전체 | 다음 섹션 참고 |
| **done** | 배포 완료 (Stage 6 완료) | `{"message": "..."}` | `Deployment completed successfully` |
| **error** | 배포 실패 (예외 발생) | `{"message": "..."}` | `Docker build failed: ...` |

### Stage 이벤트 예제

#### Stage 1 시작
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "event": "stage",
  "data": {
    "type": "stage",
    "message": "[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...",
    "details": {
      "stage": 1
    }
  },
  "reconnect": 5000
}
```

#### Dockerfile 찾음
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440001",
  "event": "stage",
  "data": {
    "type": "stage",
    "message": "[Stage 1] Dockerfile 찾음",
    "details": {
      "stage": 1,
      "path": "/tmp/deployment_1234567890/Dockerfile"
    }
  },
  "reconnect": 5000
}
```

#### Docker 빌드 완료
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440002",
  "event": "stage",
  "data": {
    "type": "stage",
    "message": "[Stage 1] Docker 이미지 빌드 완료",
    "details": {
      "stage": 1,
      "imageName": "your-org-your-repo-main-1704067200"
    }
  },
  "reconnect": 5000
}
```

#### Stage 2 시작 (ECR Push)
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440010",
  "event": "stage",
  "data": {
    "type": "stage",
    "message": "[Stage 2] ECR에 이미지 Push 중 - ECR로 이미지 Push 중...",
    "details": {
      "stage": 2
    }
  },
  "reconnect": 5000
}
```

#### 배포 완료
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440050",
  "event": "done",
  "data": {
    "message": "Deployment completed successfully"
  },
  "reconnect": 5000
}
```

#### 배포 실패
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440051",
  "event": "error",
  "data": {
    "message": "Deployment timed out at Stage 1 after 605 seconds (timeout: 600 seconds)"
  },
  "reconnect": 5000
}
```

### 클라이언트 예제 (JavaScript)

```javascript
const deploymentId = 'dep_abc1234567';
const eventSource = new EventSource(`/api/v1/deploy/${deploymentId}/events`);

// 과거 이벤트 히스토리 수신
eventSource.addEventListener('stage', (event) => {
  const data = JSON.parse(event.data);
  console.log(`[Stage ${data.details.stage}] ${data.message}`);
});

// 배포 완료
eventSource.addEventListener('done', (event) => {
  const data = JSON.parse(event.data);
  console.log('배포 완료:', data.message);
  eventSource.close();
});

// 배포 실패
eventSource.addEventListener('error', (event) => {
  const data = JSON.parse(event.data);
  console.error('배포 실패:', data.message);
  eventSource.close();
});

// 연결 에러
eventSource.onerror = (error) => {
  console.error('SSE 연결 에러:', error);
  eventSource.close();
};
```

---

## 에러 처리

### 예외 계층 구조

```
Exception
  └─ DeploymentException (배포 관련 예외의 기본)
      ├─ DeploymentTimeoutException (Stage별 타임아웃)
      │  └─ durationSeconds, timeoutSeconds
      ├─ DockerBuildException (Stage 1 Docker 빌드 실패)
      │  └─ imageName, exitCode
      ├─ EcsDeploymentException (Stage 3 ECS 배포 실패)
      │  └─ clusterName, serviceName
      └─ HealthCheckException (Stage 5 헬스체크 실패)
         └─ serviceUrl, failedCheckCount, totalCheckCount
```

### 에러 처리 플로우

```
배포 중 예외 발생
  ↓
DeploymentTask.run() catch 블록
  ├─ InterruptedException 확인
  ├─ 아니면 일반 예외로 처리
  └─ eventPublisher.publishErrorEvent() 호출
    ↓
  DeploymentEventPublisherImpl.publishErrorEvent()
    ├─ deploymentEventStore.failDeployment() 호출
    │  └─ 메타데이터 status = "FAILED"
    └─ deploymentEventStore.sendErrorEvent() 호출
      ↓
    DeploymentEventStore.sendErrorEvent()
      ├─ "error" 타입 이벤트 생성
      ├─ broadcastEvent() 호출
      │  └─ 모든 SSE 클라이언트에게 에러 메시지 전송
      ├─ saveDeploymentResult("FAILED") 호출
      └─ 5초 뒤 closeAllEmitters() 호출
        └─ SSE 연결 자동 종료

또한, 배포 파이프라인 내부:
  ├─ DeploymentPipelineService.triggerDeploymentPipeline()
  │  └─ try-catch로 예외 캡처
  │  └─ errorHandler.handleException() 호출
  │
  └─ DeploymentErrorHandler.handleException()
     ├─ 예외 타입 판별
     ├─ 타입별 핸들러 호출
     │  └─ 상세 로깅
     │  └─ 에러 메시지 생성
     └─ eventPublisher.publishErrorEvent() 호출
        └─ SSE 클라이언트에게 에러 전송
```

### HTTP 응답 에러

```
타임아웃 예외
  └─ HTTP 408 Request Timeout
  └─ 응답 본문:
     {
       "timestamp": "2024-01-01T12:00:00",
       "status": 408,
       "error": "Deployment Timeout",
       "message": "...",
       "deploymentId": "dep_abc1234567",
       "stage": 3,
       "errorCode": "DEPLOYMENT_TIMEOUT",
       "durationSeconds": 605,
       "timeoutSeconds": 600
     }

배포 예외
  └─ HTTP 400 Bad Request
  └─ 응답 본문:
     {
       "timestamp": "2024-01-01T12:00:00",
       "status": 400,
       "error": "Deployment Error",
       "message": "...",
       "deploymentId": "dep_abc1234567",
       "stage": 1,
       "errorCode": "DOCKER_BUILD_FAILED"
     }

예상 외 예외
  └─ HTTP 500 Internal Server Error
  └─ 응답 본문:
     {
       "timestamp": "2024-01-01T12:00:00",
       "status": 500,
       "error": "Internal Server Error",
       "message": "...",
       "exceptionClass": "IOException"
     }
```

---

## 비동기 처리 및 스레드 관리

### ThreadPool 구조

```
DeploymentTaskExecutor
  ├─ ThreadPoolExecutor
  │  ├─ Core Threads: 5
  │  ├─ Max Threads: 10
  │  ├─ Keep Alive: 60초
  │  ├─ Queue Capacity: 50
  │  ├─ 이름: deployment-worker-1, deployment-worker-2, ...
  │  └─ Policy: CallerRunsPolicy
  │
  ├─ deploymentFutures
  │  └─ deploymentId -> CompletableFuture<Void>
  │     └─ 진행 중인 배포 추적
  │
  └─ deploymentStartTimes
     └─ deploymentId -> startTime (ms)
        └─ 배포 타임아웃 체크용
```

### CompletableFuture 활용

```
executeDeployment(deploymentId, task)
  ↓
1️⃣ CompletableFuture.runAsync(task, executorService)
   └─ ThreadPool의 deployment-worker에서 task 실행
   ↓
2️⃣ future.orTimeout(30분)
   ├─ 30분 초과 시 TimeoutException 발생
   └─ 자동으로 다음 단계 진행
   ↓
3️⃣ futureWithTimeout.whenComplete((result, exception) -> {...})
   ├─ 배포 완료 또는 예외 발생 시 실행
   ├─ TimeoutException 체크
   └─ cleanupDeployment() 호출
   ↓
4️⃣ deploymentFutures에 저장
   └─ 중복 배포 방지용 (cancelDeployment 가능)
```

### 동시성 제어

```
ConcurrentHashMap 사용:
  - emitterMap (SSE 클라이언트)
  - eventHistoryMap (이벤트 히스토리)
  - metadataMap (배포 메타데이터)
  - deploymentResults (배포 결과)
  - deploymentFutures (진행 중인 배포)
  - deploymentStartTimes (시작 시간)

Collections.synchronizedList:
  - emitterMap의 value (List<SseEmitter>)

LinkedBlockingQueue:
  - ThreadPool 작업 큐 (용량 50)

스레드 안전성:
  ✅ 동시에 여러 배포 가능
  ✅ SSE 클라이언트 병렬 처리
  ✅ 이벤트 브로드캐스트 안전
  ✅ 배포 결과 저장소 안전
```

---

## 데이터 흐름 예제

### 예제 시나리오: GitHub → Docker → ECR → ECS 배포

#### 초기 상태
```
GitHub 레포
  └─ https://github.com/your-org/your-repo
     ├─ main 브랜치
     ├─ Dockerfile
     ├─ src/
     └─ ...

AWS 계정
  └─ ap-northeast-2 리전
     ├─ ECR: your-org-your-repo (비어있음)
     ├─ ECS: panda-cluster (기존)
     └─ ...
```

#### 1단계: 연결 설정

**클라이언트 1 - GitHub 연결**
```
POST /api/v1/connect/github
{
  "owner": "your-org",
  "repo": "your-repo",
  "branch": "main",
  "token": "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}

응답:
{
  "code": 200,
  "message": "GitHub 연결에 성공했습니다.",
  "data": {
    "githubConnectionId": "gh_a1b2c3d4e5"
  }
}

저장됨:
ConnectionStore.gitHubConnections
  └─ gh_a1b2c3d4e5 -> GitHubConnection(
       owner: "your-org",
       repo: "your-repo",
       branch: "main",
       token: "ghp_xxxx..."
     )
```

**클라이언트 2 - AWS 연결**
```
POST /api/v1/connect/aws
{
  "region": "ap-northeast-2",
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/...",
  "sessionToken": "" (optional)
}

응답:
{
  "code": 200,
  "message": "AWS 연결에 성공했습니다.",
  "data": {
    "awsConnectionId": "aws_f6g7h8i9j0"
  }
}

저장됨:
ConnectionStore.awsConnections
  └─ aws_f6g7h8i9j0 -> AwsConnection(
       region: "ap-northeast-2",
       accessKeyId: "AKIAIOSFODNN7EXAMPLE",
       secretAccessKey: "wJalrXUtnFEMI/K7MDENG/...",
       sessionToken: ""
     )
```

#### 2단계: 배포 시작

**POST /api/v1/deploy**
```
요청:
{
  "githubConnectionId": "gh_a1b2c3d4e5",
  "awsConnectionId": "aws_f6g7h8i9j0",
  "owner": "your-org",
  "repo": "your-repo",
  "branch": "main"
}

응답 (즉시):
{
  "code": 200,
  "message": "배포가 시작되었습니다.",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "message": "Deployment started. Listen to /api/v1/deploy/{id}/events"
  }
}
```

#### 3단계: 메타데이터 초기화

```
DeploymentEventStore.metadataMap
  └─ dep_k1l2m3n4o5 -> DeploymentMetadata(
       deploymentId: "dep_k1l2m3n4o5",
       status: "IN_PROGRESS",
       currentStage: 0,
       owner: "your-org",
       repo: "your-repo",
       branch: "main",
       awsRegion: "ap-northeast-2",
       startedAt: 2024-01-01T12:00:00,
       completedAt: null,
       errorMessage: null
     )
```

#### 4단계: ThreadPool에서 배포 작업 실행

```
DeploymentTaskExecutor
  └─ CompletableFuture.runAsync()
     └─ deployment-worker-1 스레드에서 실행
        └─ DeploymentPipelineService.triggerDeploymentPipeline()
```

#### 5단계: SSE 클라이언트가 스트림 구독

**GET /api/v1/deploy/dep_k1l2m3n4o5/events**
```
응답 (즉시):
  HTTP 200 OK
  Content-Type: application/text/event-stream

  기존 이벤트 히스토리 전송 (현재: 없음)

  SSE 연결 유지 (streaming)
```

#### 6단계: Stage 1 - Docker Build

```
Stage 1 시작 이벤트:
  id: uuid1
  event: stage
  data: {
    type: "stage",
    message: "[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...",
    details: { stage: 1 }
  }

Repository 클론:
  cmd: git clone --depth 1 -b main \
       https://ghp_xxxx@github.com/your-org/your-repo.git \
       /tmp/deployment_1704067200

클론 완료 이벤트:
  id: uuid2
  event: stage
  data: {
    type: "stage",
    message: "[Stage 1] Repository 클론 완료",
    details: {
      stage: 1,
      path: "/tmp/deployment_1704067200"
    }
  }

Dockerfile 검색:
  └─ /tmp/deployment_1704067200/Dockerfile 발견

Dockerfile 찾음 이벤트:
  id: uuid3
  event: stage
  data: {
    type: "stage",
    message: "[Stage 1] Dockerfile 찾음",
    details: {
      stage: 1,
      path: "/tmp/deployment_1704067200/Dockerfile"
    }
  }

Docker 빌드 시작:
  cmd: docker build -t your-org-your-repo-main-1704067200 .

Docker 빌드 완료 이벤트:
  id: uuid4
  event: stage
  data: {
    type: "stage",
    message: "[Stage 1] Docker 이미지 빌드 완료",
    details: {
      stage: 1,
      imageName: "your-org-your-repo-main-1704067200"
    }
  }

메타데이터 업데이트:
  currentStage: 1
```

#### 7단계: Stage 2 - ECR Push

```
Stage 2 시작 이벤트:
  message: "[Stage 2] ECR에 이미지 Push 중 - ECR로 이미지 Push 중..."

AWS 계정 ID 조회:
  STS GetCallerIdentity
  └─ Account ID: 123456789012

ECR 리포지토리 생성:
  create-repository --repository-name your-org-your-repo
  └─ URI: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo

ECR 로그인:
  docker login -u AWS -p <TOKEN> \
  123456789012.dkr.ecr.ap-northeast-2.amazonaws.com

Docker Tag:
  docker tag your-org-your-repo-main-1704067200 \
  123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:your-org-your-repo-main-1704067200

Docker Push:
  docker push 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:...

푸시 완료 이벤트:
  message: "[Stage 2] 이미지 Push 완료"
  data: {
    uri: "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:your-org-your-repo-main-1704067200"
  }

메타데이터 업데이트:
  currentStage: 2
```

#### 8단계: Stage 3 - ECS 배포

```
Stage 3 시작 이벤트:
  message: "[Stage 3] ECS 배포 시작"

ECS 서비스 생성/업데이트:
  - 클러스터: panda-cluster
  - 서비스: panda-service
  - 이미지: 123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:...

서비스 생성 완료 이벤트:
  message: "[Stage 3] ECS 서비스 생성 완료"
  data: {
    serviceName: "panda-service",
    clusterName: "panda-cluster"
  }

서비스 업데이트 완료 이벤트:
  message: "[Stage 3] ECS 서비스 업데이트 완료"

메타데이터 업데이트:
  currentStage: 3
```

#### 9단계: Stage 4 - Blue/Green 배포

```
Stage 4 시작 이벤트:
  message: "[Stage 4] CodeDeploy Blue/Green 배포 시작"

Blue 서비스 실행 중:
  message: "[Stage 4] Blue 서비스 실행 중"
  data: { url: "http://blue.example.com" }

Green 서비스 시작:
  message: "[Stage 4] Green 서비스 시작 중"
  data: { url: "http://green.example.com" }

Green 서비스 준비 완료:
  message: "[Stage 4] Green 서비스 준비 완료"

CodeDeploy Lifecycle Hook - BeforeAllowTraffic:
  message: "[Stage 4] CodeDeploy Lifecycle Hook: BeforeAllowTraffic"

CodeDeploy Lifecycle Hook - AfterAllowTraffic:
  message: "[Stage 4] CodeDeploy Lifecycle Hook: AfterAllowTraffic"

메타데이터 업데이트:
  currentStage: 4
```

#### 10단계: Stage 5 - HealthCheck & 트래픽 전환

```
Stage 5 시작 이벤트:
  message: "[Stage 5] Green 서비스 HealthCheck 및 트래픽 전환"

HealthCheck 시작:
  message: "[Stage 5] HealthCheck 시작"
  data: { url: "http://green.example.com" }

HealthCheck 진행 중 (5회):
  message: "[Stage 5] Green 서비스 HealthCheck 진행 중 - Check 1/5"
  message: "[Stage 5] Green 서비스 HealthCheck 진행 중 - Check 2/5"
  message: "[Stage 5] Green 서비스 HealthCheck 진행 중 - Check 3/5"
  message: "[Stage 5] Green 서비스 HealthCheck 진행 중 - Check 4/5"
  message: "[Stage 5] Green 서비스 HealthCheck 진행 중 - Check 5/5"

HealthCheck 성공:
  message: "[Stage 5] HealthCheck 성공"
  data: {
    url: "http://green.example.com",
    passedChecks: 5
  }

트래픽 전환 중:
  message: "[Stage 5] 트래픽 전환 중"
  data: {
    from: "blue",
    to: "green"
  }

트래픽 전환 완료:
  message: "[Stage 5] 트래픽 전환 완료"
  data: { activeService: "green" }

메타데이터 업데이트:
  currentStage: 5
  finalService: "green"
```

#### 11단계: Stage 6 - 배포 완료

```
Stage 6 완료 이벤트:
  message: "[Stage 6] 배포 완료"
  data: {
    finalService: "green",
    blueUrl: "http://blue.example.com",
    greenUrl: "http://green.example.com"
  }

성공 이벤트 발행:
  eventPublisher.publishSuccessEvent()
  └─ completeDeployment() 호출

Done 이벤트 전송:
  id: uuid100
  event: done
  data: { message: "Deployment completed successfully" }

배포 결과 저장:
  DeploymentHistoryManager.saveDeploymentResult()
  └─ deploymentResults[dep_k1l2m3n4o5] = DeploymentResult(
       deploymentId: "dep_k1l2m3n4o5",
       status: "COMPLETED",
       owner: "your-org",
       repo: "your-repo",
       branch: "main",
       startedAt: 2024-01-01T12:00:00,
       completedAt: 2024-01-01T12:10:30,
       durationSeconds: 630,
       finalService: "green",
       blueUrl: "http://blue.example.com",
       greenUrl: "http://green.example.com",
       eventCount: 47
     )

메타데이터 업데이트:
  status: "COMPLETED"
  currentStage: 6
  completedAt: 2024-01-01T12:10:30

5초 뒤 SSE 연결 자동 종료:
  closeAllEmitters(dep_k1l2m3n4o5)
  └─ 모든 SSE 클라이언트 연결 종료
  └─ eventSource.close() (클라이언트 측)
```

#### 12단계: 배포 결과 조회

**GET /api/v1/deploy/dep_k1l2m3n4o5/result**
```
응답:
{
  "code": 200,
  "message": "배포 결과 조회 성공",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "status": "COMPLETED",
    "owner": "your-org",
    "repo": "your-repo",
    "branch": "main",
    "startedAt": "2024-01-01T12:00:00",
    "completedAt": "2024-01-01T12:10:30",
    "durationSeconds": 630,
    "finalService": "green",
    "blueUrl": "http://blue.example.com",
    "greenUrl": "http://green.example.com",
    "errorMessage": null,
    "eventCount": 47
  }
}
```

### 예제 시나리오: 배포 실패

#### Stage 1에서 Dockerfile 미발견

```
cloneRepository() 완료
findDockerfile() 실행
  └─ Dockerfile 검색 실패 (null 반환)

예외 발생:
  throw new DeploymentException(
    "Dockerfile not found in repository",
    deploymentId: "dep_k1l2m3n4o5",
    stage: 1
  )

예외 캡처:
  DeploymentPipelineService.triggerDeploymentPipeline() catch 블록
  └─ errorHandler.handleException(deploymentId, exception)

에러 처리:
  DeploymentErrorHandler.handleException()
  └─ handleGenericDeploymentException()
  │  ├─ 에러 로깅
  │  └─ 에러 메시지 생성
  └─ eventPublisher.publishErrorEvent()
     └─ deploymentEventStore.failDeployment()
        └─ 메타데이터 status = "FAILED"
     └─ deploymentEventStore.sendErrorEvent()
        └─ 에러 이벤트 발행

에러 이벤트 (SSE):
  id: uuid50
  event: error
  data: {
    message: "Deployment failed at Stage 1: Dockerfile not found in repository"
  }

배포 결과 저장:
  DeploymentHistoryManager.saveDeploymentResult(
    deploymentId: "dep_k1l2m3n4o5",
    status: "FAILED"
  )

5초 뒤 SSE 연결 종료
```

---

## 요약

이 프로젝트는 **GitHub → Docker → AWS ECR → ECS 배포 자동화 파이프라인**을 구현한 엔터프라이즈급 Spring Boot 애플리케이션입니다.

### 핵심 특징
1. **비동기 배포**: CompletableFuture와 ThreadPool 기반
2. **실시간 모니터링**: SSE를 통한 배포 진행 상황 스트리밍
3. **Blue/Green 배포**: 무중단 서비스 갱신
4. **포괄적 에러 처리**: 예외 타입별 세분화된 에러 관리
5. **타임아웃 관리**: 전체 및 단계별 타임아웃 체크
6. **메모리 효율**: ConcurrentHashMap과 자동 정리 메커니즘

### 아키텍처의 강점
- **DDD 패턴**: 도메인 중심의 계층 분리
- **스레드 안전성**: ConcurrentHashMap 사용으로 동시성 보장
- **확장성**: 새로운 배포 단계 추가 용이
- **모니터링**: 상세한 로깅과 실시간 이벤트 스트리밍
- **프로덕션 준비**: 에러 처리, 타임아웃, 리소스 정리 완벽

프로젝트는 안정적이고 확장 가능한 구조로 설계되었으며, 배포 자동화에 필요한 모든 기능을 포함하고 있습니다.
