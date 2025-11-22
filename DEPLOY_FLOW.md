# Deploy API 완전 가이드: API 호출부터 배포 완료까지

> 이 문서는 `/api/v1/deploy` 엔드포인트를 호출한 이후의 **전체 흐름**을 상세히 설명합니다.

## 📋 목차
1. [전체 아키텍처](#전체-아키텍처)
2. [Step-by-Step 배포 프로세스](#step-by-step-배포-프로세스)
3. [각 단계별 상세 설명](#각-단계별-상세-설명)
4. [외부 서비스 연동](#외부-서비스-연동)
5. [에러 처리 및 타임아웃](#에러-처리-및-타임아웃)
6. [SSE를 통한 실시간 모니터링](#sse를-통한-실시간-모니터링)
7. [주요 파일 구조](#주요-파일-구조)

---

## 전체 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                       클라이언트 (사용자)                              │
└────────┬─────────────────────────────────────────────────────────────┘
         │
         │ POST /api/v1/deploy
         │ {githubConnectionId, awsConnectionId, owner, repo, branch}
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      DeployController                                │
│                   (요청 검증 & 응답 반환)                              │
└────────┬────────────────────────────────────────────────────────────┘
         │
         │ StartDeploymentService
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│               배포 초기화 & 비동기 작업 시작                           │
│  ├─ deploymentId 생성                                               │
│  ├─ DeploymentEventStore 초기화 (SSE 준비)                           │
│  ├─ EventBridge 규칙 생성                                            │
│  ├─ Lambda 호출 (Event Bus 권한 설정)                                 │
│  └─ DeploymentTask를 ThreadPool에 submit                             │
└────────┬────────────────────────────────────────────────────────────┘
         │
         │ ThreadPool에서 비동기 실행 시작
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  배포 파이프라인 (DeploymentPipelineService)                         │
│  ├─ Stage 1: GitHub Clone + Dockerfile 검색 + Docker Build         │
│  └─ Stage 2: ECR 저장소 생성/확인 + ECR 로그인 + Docker Push         │
└────────┬────────────────────────────────────────────────────────────┘
         │
         │ (ECR Push 이벤트 자동 감지)
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  AWS 자동 연동 (EventBridge → Step Functions)                        │
│  ├─ EventBridge 규칙 자동 트리거                                      │
│  ├─ Softbank Event Bus로 이벤트 전달                                  │
│  └─ Step Functions 자동 실행 (ECS 배포 & Blue/Green)                  │
└────────┬────────────────────────────────────────────────────────────┘
         │
         │ StepFunctionsPollingService
         │ (ExecutionArn 조회 → 2초 주기 폴링)
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  배포 상태 모니터링 & SSE 이벤트 발행                                  │
│  ├─ GetExecutionHistory API 호출                                     │
│  ├─ 상태 변화 감지 시 "stepFunctionsProgress" 이벤트 발행             │
│  └─ SUCCEEDED/FAILED 상태 도달 시 폴링 종료                           │
└────────┬────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     배포 완료 또는 실패                               │
│  ├─ DeploymentMetadata 업데이트                                      │
│  ├─ "done" 또는 "error" 이벤트 발행                                   │
│  └─ Secrets Manager에서 ExecutionArn 정리                             │
└─────────────────────────────────────────────────────────────────────┘
         │
         │ GET /api/v1/deploy/{deploymentId}/result
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│  최종 배포 결과 반환                                                  │
│  {status, url, executionTime, performanceMetrics...}               │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Step-by-Step 배포 프로세스

### 📌 Phase 1: API 요청 (0초 ~ 1초)

```
클라이언트
  │
  └─→ POST /api/v1/deploy
      {
        "githubConnectionId": "gh_xxxxx",
        "awsConnectionId": "aws_xxxxx",
        "owner": "your-org",
        "repo": "your-repo",
        "branch": "main"
      }
```

**DeployController에서 처리:**
1. `DeployRequest` 유효성 검증
2. GitHub Connection 조회 (소유권 확인)
3. AWS Connection 조회 (소유권 확인)
4. 유효하면 `StartDeploymentService.startDeployment()` 호출

**응답:**
```json
{
  "code": 200,
  "message": "Success",
  "data": {
    "deploymentId": "dep_1234567890abc",
    "message": "Deployment started. Listen to /api/v1/deploy/dep_1234567890abc/events"
  }
}
```

---

### 📌 Phase 2: 배포 초기화 (1초 ~ 2초)

**StartDeploymentService:**

1. **deploymentId 생성**
   ```
   deploymentId = "dep_" + UUID (예: dep_1234567890abc)
   ```

2. **DeploymentEventStore 초기화**
   - SSE Emitter를 저장할 준비
   - 초기 상태를 이벤트 히스토리에 기록
   ```
   Event: {
     "type": "info",
     "message": "Deployment initialized",
     "stage": "INITIALIZATION"
   }
   ```

3. **EventBridge 규칙 생성**
   - IAM 역할 생성: `softbank-eventbridge-role`
   - EventBridge 규칙 생성 (ECR PUSH 이벤트 감지)
   ```
   Rule Name: panda-ecr-push-rule-{deploymentId}
   Event Pattern: {
     "source": ["aws.ecr"],
     "detail-type": ["ECR Image Action"],
     "detail": {
       "action": ["PUSH"]
     }
   }
   Target: Softbank Event Bus
   ```

4. **Lambda 호출**
   - 함수명: `lambda_0_register_to_eventbus`
   - 목적: Event Bus 권한 설정
   - 사용자 계정이 Softbank Event Bus로 이벤트를 보낼 수 있도록 권한 부여

5. **비동기 작업 시작**
   - `DeploymentTask`를 ThreadPool에 submit
   - 메인 스레드는 즉시 응답 반환

---

### 📌 Phase 3: GitHub Clone & Docker Build (2초 ~ 15초)

**DeploymentPipelineService (Stage 1):**

#### 3-1. GitHub 저장소 클론
```bash
git clone --branch {branch} --depth 1 \
  https://{github_token}@github.com/{owner}/{repo}.git \
  /tmp/deployment_{deploymentId}
```

- **깊이**: `--depth 1` (최신 커밋만 받음, 빠른 클론)
- **인증**: GitHub Personal Access Token 사용
- **목적**: 배포할 코드 다운로드

#### 3-2. Dockerfile 탐색
```bash
find /tmp/deployment_{deploymentId} -name "Dockerfile" -type f
```

- 저장소 전체를 재귀적으로 검색
- 찾지 못하면 `DeploymentException` 발생
- 찾으면 경로 저장

#### 3-3. Docker 이미지 빌드
```bash
docker build -t {owner}-{repo}-{branch}-{timestamp} /tmp/deployment_{deploymentId}
```

- **이미지명**: `{owner}-{repo}-{branch}-{timestamp}`
  - 예: `panda-api-main-1700000000`
- **Dockerfile**: Stage 3-2에서 찾은 경로 사용
- **실패 시**: `DockerBuildException` 발생 후 즉시 롤백

**SSE 이벤트 발행:**
```json
{
  "type": "stage",
  "message": "Docker image built successfully",
  "stage": "BUILD",
  "imageId": "{image_id}",
  "timestamp": "2024-11-22T10:30:00Z"
}
```

---

### 📌 Phase 4: ECR Push (15초 ~ 25초)

**DeploymentPipelineService (Stage 2):**

#### 4-1. ECR 저장소 확인/생성
```java
// ECR 저장소 조회
DescribeRepositoriesRequest request = new DescribeRepositoriesRequest()
  .withRepositoryNames("{owner}-{repo}");

// 없으면 생성
CreateRepositoryRequest request = new CreateRepositoryRequest()
  .withRepositoryName("{owner}-{repo}");
```

#### 4-2. ECR 로그인 및 인증
```bash
# 1. ECR 인증 토큰 획득
aws ecr get-authorization-token --region us-east-1

# 2. Docker 로그인
docker login -u AWS -p {auth_token} {account_id}.dkr.ecr.us-east-1.amazonaws.com

# 3. 이미지 태깅
docker tag {owner}-{repo}-{branch}-{timestamp} \
  {account_id}.dkr.ecr.us-east-1.amazonaws.com/{owner}-{repo}:{timestamp}

# 4. ECR 푸시
docker push {account_id}.dkr.ecr.us-east-1.amazonaws.com/{owner}-{repo}:{timestamp}
```

#### 4-3. EventBridge 자동 트리거
```
ECR PUSH 이벤트 감지 (AWS가 자동으로 감지)
  │
  └─→ EventBridge 규칙 자동 트리거
      └─→ Softbank Event Bus로 이벤트 전달
          └─→ Step Functions 자동 시작
```

**SSE 이벤트 발행:**
```json
{
  "type": "stage",
  "message": "Docker image pushed to ECR",
  "stage": "PUSH",
  "ecr_uri": "{account_id}.dkr.ecr.us-east-1.amazonaws.com/{owner}-{repo}:{timestamp}",
  "timestamp": "2024-11-22T10:35:00Z"
}
```

---

### 📌 Phase 5: AWS Step Functions 자동 실행 (25초 ~ 60초)

**AWS 자동 처리 (EventBridge 관리):**

이 단계는 AWS Step Functions에서 자동으로 처리됩니다.

#### 5-1. Step 1: EnsureInfra
```
목적: ECS 클러스터 및 기본 인프라 확인/생성

처리:
├─ ECS 클러스터 확인
│  └─ 없으면 생성: {owner}-{repo}-{branch}-cluster
├─ IAM 역할 확인
│  └─ 없으면 생성: ECS Task Execution Role
├─ CloudWatch 로그 그룹 확인
│  └─ 없으면 생성: /ecs/{owner}-{repo}-{branch}
└─ VPC & Security Group 확인
   └─ 없으면 기본값 사용
```

#### 5-2. Step 2: RegisterTaskAndDeploy
```
목적: Task Definition 재정의 및 CodeDeploy 시작

처리:
├─ 이전 Task Definition 조회
├─ 이미지 URI 업데이트
│  └─ {account_id}.dkr.ecr.us-east-1.amazonaws.com/{owner}-{repo}:{timestamp}
├─ 새 Task Definition 등록
├─ ECS Service 생성 또는 업데이트
└─ CodeDeploy를 통한 Blue/Green 배포 시작
   ├─ Blue Service (기존): 점진적으로 트래픽 감소
   └─ Green Service (신규): 새 Task로 트래픽 증가
```

#### 5-3. Step 3: CheckDeployment
```
목적: 배포 상태 확인 및 헬스 체크

처리:
├─ ECS Service 상태 확인
│  └─ DesiredCount vs RunningCount 비교
├─ Task 헬스 체크
│  └─ ELB/ALB Target Health 확인
└─ 헬스 체크 성공 여부에 따라 분기
```

#### 5-4. Step 4: DeploymentStatusRouter
```
목적: 배포 결과에 따라 최종 처리

처리:
├─ 성공 (SUCCESS)
│  ├─ Blue Service 정리 (선택사항)
│  └─ 배포 메타데이터 저장
│
└─ 실패 (FAILURE)
   ├─ 자동 롤백 (이전 Task Definition 사용)
   ├─ Green Service 중지
   └─ 배포 실패 로그 저장
```

---

### 📌 Phase 6: 배포 상태 폴링 (25초 ~ 300초)

**StepFunctionsPollingService (비동기 폴링):**

#### 6-1. ExecutionArn 조회 (3초 대기)
```java
// Step Functions 실행이 시작된 후 약 3초 대기
// (Step Functions이 결과를 Secrets Manager에 저장할 때까지 대기)

// Secrets Manager에서 ExecutionArn 조회
GetSecretValueRequest request = new GetSecretValueRequest()
  .withSecretId("panda/deployment/{deploymentId}");

// 결과:
{
  "ExecutionArn": "arn:aws:states:us-east-1:123456789:execution:panda-step-function:dep_xxxxx"
}
```

#### 6-2. 2초 주기 폴링 시작
```java
// 최대 30분 동안 폴링
while (System.currentTimeMillis() - startTime < 30 * 60 * 1000) {
  // 2초 대기
  Thread.sleep(2000);

  // ExecutionHistory 조회
  GetExecutionHistoryRequest request = new GetExecutionHistoryRequest()
    .withExecutionArn(executionArn);

  GetExecutionHistoryResult result = stepFunctionsClient.getExecutionHistory(request);

  // 상태 변화 감지
  for (HistoryEvent event : result.getEvents()) {
    if (isStateChangeEvent(event)) {
      // SSE 이벤트 발행
      publishEvent(event);

      // 최종 상태 확인
      if (event.getType().equals("ExecutionSucceeded")
          || event.getType().equals("ExecutionFailed")) {
        // 폴링 종료
        return;
      }
    }
  }
}
```

#### 6-3. 상태 변화 감지 및 SSE 이벤트 발행

**상태 변화 예시:**

```json
이벤트 1 (Task 시작):
{
  "type": "stepFunctionsProgress",
  "message": "EnsureInfra task started",
  "taskName": "EnsureInfra",
  "state": "RUNNING",
  "timestamp": "2024-11-22T10:35:05Z"
}

이벤트 2 (Task 완료):
{
  "type": "stepFunctionsProgress",
  "message": "EnsureInfra task completed",
  "taskName": "EnsureInfra",
  "state": "SUCCEEDED",
  "timestamp": "2024-11-22T10:35:15Z"
}

이벤트 3 (배포 시작):
{
  "type": "stepFunctionsProgress",
  "message": "RegisterTaskAndDeploy started",
  "taskName": "RegisterTaskAndDeploy",
  "state": "RUNNING",
  "timestamp": "2024-11-22T10:35:20Z"
}

...

최종 이벤트 (배포 완료):
{
  "type": "done",
  "message": "Deployment completed successfully",
  "status": "SUCCESS",
  "elb_url": "panda-api-main-lb-1234567890.us-east-1.elb.amazonaws.com",
  "execution_time": "45 seconds",
  "timestamp": "2024-11-22T10:36:05Z"
}
```

---

### 📌 Phase 7: 배포 완료 (300초 이후)

**배포 완료 처리:**

#### 7-1. DeploymentMetadata 업데이트
```java
DeploymentMetadata metadata = new DeploymentMetadata();
metadata.setDeploymentId(deploymentId);
metadata.setStatus("SUCCESS");  // 또는 "FAILED"
metadata.setStartTime(startTime);
metadata.setEndTime(System.currentTimeMillis());
metadata.setExecutionTime(endTime - startTime);
metadata.setElbUrl(elbUrl);
metadata.setImageUri(imageUri);
metadata.setCommitHash(commitHash);
metadata.setPerformanceMetrics(metrics);

// 저장
deploymentEventStore.updateMetadata(deploymentId, metadata);
```

#### 7-2. "done" 또는 "error" 이벤트 발행
```json
성공 시:
{
  "type": "done",
  "message": "Deployment completed successfully",
  "status": "SUCCESS",
  "elb_url": "panda-api-main-lb-1234567890.us-east-1.elb.amazonaws.com",
  "image_uri": "123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605",
  "execution_time": "45 seconds",
  "timestamp": "2024-11-22T10:36:05Z"
}

실패 시:
{
  "type": "error",
  "message": "Deployment failed during CheckDeployment step",
  "status": "FAILED",
  "error_code": "HEALTH_CHECK_FAILED",
  "error_details": "ALB Target Health: UNHEALTHY",
  "execution_time": "120 seconds",
  "timestamp": "2024-11-22T10:37:05Z"
}
```

#### 7-3. Secrets Manager 정리
```java
// ExecutionArn 삭제
DeleteSecretRequest request = new DeleteSecretRequest()
  .withSecretId("panda/deployment/{deploymentId}");

secretsManagerClient.deleteSecret(request);
```

---

## 각 단계별 상세 설명

### GitHub Clone 상세 분석

**클론 명령어:**
```bash
git clone --branch main --depth 1 \
  https://ghp_xxxxxxxxxxxx@github.com/panda-team/panda-api.git \
  /tmp/deployment_dep_1234567890
```

**각 옵션 설명:**
| 옵션 | 설명 | 용도 |
|------|------|------|
| `--branch main` | 특정 브랜치만 클론 | 빨른 클론 |
| `--depth 1` | 최신 커밋만 받음 | 네트워크 대역폭 절감 |
| `https://` | HTTPS 프로토콜 사용 | GitHub Token 인증 용이 |
| `/tmp/deployment_*` | 임시 디렉토리 | 배포 후 자동 정리 |

**인증 방식:**
- **현재**: GitHub Personal Access Token (URL에 포함)
  - 장점: 구현 간단
  - 단점: 로그에 토큰이 노출될 수 있음

- **권장**: SSH 키 기반 인증
  - `git clone git@github.com:panda-team/panda-api.git`
  - 더 안전함

---

### Docker Build 상세 분석

**빌드 명령어:**
```bash
docker build -t panda-api-main-1700000000 /tmp/deployment_dep_1234567890
```

**Docker Context 구조:**
```
/tmp/deployment_dep_1234567890/
├── Dockerfile                 ← 발견 필수
├── .dockerignore               (옵션)
├── src/                        (Java 소스)
├── build.gradle.kts           (Gradle 설정)
├── gradle/                     (Gradle Wrapper)
└── ... (기타 파일)
```

**Dockerfile 예시:**
```dockerfile
# Multi-stage build (권장)
FROM gradle:7.6-jdk17 AS builder
WORKDIR /build
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
RUN gradle build --no-daemon || true

COPY . .
RUN gradle build -x test --no-daemon

FROM openjdk:17-slim
WORKDIR /app
COPY --from=builder /build/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**빌드 최적화:**
- `.dockerignore` 파일로 불필요한 파일 제외
- Multi-stage build로 최종 이미지 크기 최소화
- Gradle 캐시 활용

**실패 시나리오:**
```
1. Dockerfile 찾지 못함
   └─ DeploymentException: "Dockerfile not found in repository"

2. Gradle 빌드 실패
   └─ DockerBuildException: "Build failed: ..."

3. 디스크 공간 부족
   └─ DockerBuildException: "No space left on device"

4. Docker daemon 연결 실패
   └─ DockerBuildException: "Cannot connect to Docker daemon"
```

---

### ECR Push 상세 분석

**ECR 저장소명:** `{owner}-{repo}`
- 예: `panda-api` (owner: panda, repo: api)

**이미지 태그:** `{timestamp}`
- 형식: `YYYYMMdd_HHmmss`
- 예: `20241122_103605`

**ECR 전체 URI:**
```
{account_id}.dkr.ecr.{region}.amazonaws.com/{owner}-{repo}:{timestamp}
```
- 예: `123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605`

**Push 단계:**

```
1️⃣ 인증 토큰 획득
   aws ecr get-authorization-token
   │
   └─ 반환값: {username: "AWS", password: "..."}

2️⃣ Docker 로그인
   docker login -u AWS -p {password} {ecr_endpoint}

3️⃣ 로컬 이미지 태깅
   docker tag panda-api-main-1700000000 \
     123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605

4️⃣ ECR 푸시
   docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605
   │
   └─ AWS가 PUSH 이벤트 자동 감지
      └─ EventBridge 규칙 자동 트리거
         └─ Step Functions 자동 시작
```

---

### EventBridge 규칙 상세 분석

**생성되는 규칙:**

```json
{
  "Name": "panda-ecr-push-rule-dep_1234567890",
  "Description": "Trigger Step Functions on ECR image push",
  "EventPattern": {
    "source": ["aws.ecr"],
    "detail-type": ["ECR Image Action"],
    "detail": {
      "action": ["PUSH"],
      "result": ["SUCCESS"]
    }
  },
  "State": "ENABLED",
  "Targets": [
    {
      "Arn": "arn:aws:events:us-east-1:123456789:event-bus/softbank-event-bus",
      "RoleArn": "arn:aws:iam::123456789:role/softbank-eventbridge-role"
    }
  ]
}
```

**이벤트 흐름:**

```
ECR Push 완료
  │
  └─→ AWS ECR이 PUSH 이벤트 생성
      {
        "source": "aws.ecr",
        "detail-type": "ECR Image Action",
        "detail": {
          "action": "PUSH",
          "result": "SUCCESS",
          "image-url": "123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605"
        }
      }
      │
      └─→ EventBridge 규칙 자동 매칭
          └─→ Softbank Event Bus로 이벤트 전달
              └─→ Step Functions 자동 트리거
                  └─→ StepFunctionsPollingService가 폴링 시작
```

---

### Step Functions 상세 분석

**Step Functions 구조 (Softbank 관리):**

```
입력: ECR PUSH 이벤트
{
  "image_uri": "123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605",
  "deployment_id": "dep_1234567890",
  "owner": "panda",
  "repo": "api",
  "branch": "main"
}
  │
  ▼
┌─────────────────────────────────┐
│ Step 1: EnsureInfra             │
│ (인프라 생성/확인)               │
│ 소요시간: 5~10초                 │
└─────────────────────────────────┘
  │
  ├─ ECS Cluster 확인/생성
  │  └─ {owner}-{repo}-{branch}-cluster
  │
  ├─ IAM Role 확인/생성
  │  └─ ecsTaskExecutionRole
  │
  ├─ CloudWatch Logs 확인/생성
  │  └─ /ecs/{owner}-{repo}-{branch}
  │
  └─ VPC & Security Group 확인
     └─ 기본 VPC 또는 지정된 VPC
  │
  ▼
┌─────────────────────────────────┐
│ Step 2: RegisterTaskAndDeploy   │
│ (Task Definition + CodeDeploy)  │
│ 소요시간: 10~15초               │
└─────────────────────────────────┘
  │
  ├─ Task Definition 조회 (이전 버전)
  │
  ├─ 이미지 URI 업데이트
  │  └─ image_uri로 변경
  │
  ├─ 새 Task Definition 등록
  │  └─ Revision 증가 (예: 1 → 2)
  │
  ├─ ECS Service 생성 또는 업데이트
  │  ├─ Service Name: {owner}-{repo}-{branch}-service
  │  ├─ Desired Count: 2
  │  └─ Load Balancer: ALB 또는 NLB
  │
  └─ CodeDeploy Blue/Green 시작
     ├─ Blue: 기존 Task (트래픽 점진적 감소)
     └─ Green: 새 Task (트래픽 점진적 증가)
  │
  ▼
┌─────────────────────────────────┐
│ Step 3: CheckDeployment         │
│ (헬스 체크 & 배포 검증)         │
│ 소요시간: 30~60초               │
└─────────────────────────────────┘
  │
  ├─ ECS Service 상태 확인
  │  └─ DesiredCount == RunningCount?
  │
  ├─ Task 헬스 체크
  │  └─ ELB/ALB Target Health: HEALTHY?
  │
  ├─ 애플리케이션 헬스 체크
  │  └─ GET /health → HTTP 200?
  │
  └─ 성공 여부에 따라 분기
  │
  ▼
┌─────────────────────────────────┐
│ Step 4: DeploymentStatusRouter  │
│ (최종 결과 처리)                 │
│ 소요시간: 5초                    │
└─────────────────────────────────┘
  │
  ├─ SUCCESS: 배포 완료
  │  ├─ Blue Service 정리 (선택사항)
  │  └─ 메타데이터 저장 (ELB URL, 이미지 URI 등)
  │
  └─ FAILURE: 자동 롤백
     ├─ 이전 Task Definition으로 복구
     ├─ Green Service 중지
     └─ 실패 로그 저장

출력: ExecutionArn
{
  "ExecutionArn": "arn:aws:states:us-east-1:123456789:execution:panda-step-function:dep_1234567890",
  "status": "SUCCEEDED",
  "output": {
    "elb_url": "panda-api-main-lb-1234567890.us-east-1.elb.amazonaws.com",
    "deployment_status": "SUCCESS"
  }
}
```

---

### Polling 상세 분석

**ExecutionArn은 어디에 저장되나?**

```
1️⃣ Step Functions 실행 시작
   EventBridge → Step Functions (Softbank 관리)

2️⃣ Step Functions가 실행 중
   ExecitionArn이 생성됨

3️⃣ Lambda 함수가 ExecutionArn을 Secrets Manager에 저장
   (Softbank Lambda에서 처리)

4️⃣ Secrets Manager
   Secret Name: panda/deployment/{deploymentId}
   Secret Value: {
     "ExecutionArn": "arn:aws:states:us-east-1:123456789:execution:..."
   }

5️⃣ StepFunctionsPollingService가 조회
   GetSecretValueRequest → ExecutionArn 획득

6️⃣ 폴링 시작
   2초 주기로 GetExecutionHistory API 호출
```

**폴링 타임아웃:**

```
최대 폴링 시간: 30분

예외 케이스:
├─ ExecutionArn을 찾지 못함 (3초 대기 후)
│  └─ DeploymentException: "ExecutionArn not found"
│
├─ Step Functions가 실패함
│  └─ StepFunctionsPollingService가 failure 상태 감지
│     └─ SSE "error" 이벤트 발행
│
└─ 30분 초과
   └─ DeploymentException: "Polling timeout (30 minutes exceeded)"
```

**GetExecutionHistory 응답 예시:**

```json
{
  "events": [
    {
      "timestamp": "2024-11-22T10:35:00Z",
      "type": "ExecutionStarted",
      "id": 1,
      "executionStartedEventDetails": {
        "input": "{\"image_uri\": \"...\"}",
        "inputDetails": {
          "truncated": false
        },
        "roleArn": "arn:aws:iam::123456789:role/..."
      }
    },
    {
      "timestamp": "2024-11-22T10:35:05Z",
      "type": "TaskStateEntered",
      "id": 2,
      "stateEnteredEventDetails": {
        "name": "EnsureInfra",
        "input": "{...}",
        "inputDetails": {
          "truncated": false
        }
      }
    },
    {
      "timestamp": "2024-11-22T10:35:15Z",
      "type": "TaskStateExited",
      "id": 3,
      "stateExitedEventDetails": {
        "name": "EnsureInfra",
        "output": "{\"cluster\": \"panda-api-main-cluster\"}",
        "outputDetails": {
          "truncated": false
        }
      }
    },
    ...
    {
      "timestamp": "2024-11-22T10:37:00Z",
      "type": "ExecutionSucceeded",
      "id": 15,
      "executionSucceededEventDetails": {
        "output": "{\"status\": \"SUCCESS\", \"elb_url\": \"...\"}"
      }
    }
  ]
}
```

---

## 외부 서비스 연동

### AWS 서비스 연동 요약

| 서비스 | 용도 | API | 언제 호출 |
|--------|------|-----|---------|
| **ECR** | 이미지 저장소 | CreateRepository, GetAuthorizationToken | Phase 4 |
| **EventBridge** | 이벤트 라우팅 | PutRule, PutTargets | Phase 2 |
| **Lambda** | Event Bus 권한 설정 | Invoke (lambda_0_register_to_eventbus) | Phase 2 |
| **Step Functions** | 배포 오케스트레이션 | GetExecutionHistory | Phase 5~6 |
| **ECS** | 컨테이너 배포 | (Step Functions 관리) | Phase 5 |
| **Secrets Manager** | ExecutionArn 저장 | GetSecretValue, DeleteSecret | Phase 6 |
| **IAM** | 권한 관리 | CreateRole, PutRolePolicy | Phase 2 |
| **Docker Hub/Registry** | 이미지 빌드 & 푸시 | docker build, docker push | Phase 3~4 |
| **GitHub** | 코드 저장소 | git clone | Phase 3 |

---

## 에러 처리 및 타임아웃

### 타임아웃 정책

```
┌─────────────────────────────────────────────────────────┐
│                   전체 배포: 30분                        │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Phase 1: API 요청                    0초 ~ 1초       │
│  Phase 2: 배포 초기화                1초 ~ 2초       │
│  Phase 3: GitHub Clone + Build     2초 ~ 15초      │
│  Phase 4: ECR Push               15초 ~ 25초     │
│  Phase 5: Step Functions (자동)   25초 ~ 60초     │
│  Phase 6: 배포 상태 폴링           60초 ~ 300초   │
│           (최대 30분)                             │
│                                                  │
│  ├─ GitHub Clone: 10분 타임아웃                   │
│  ├─ Docker Build: 10분 타임아웃                   │
│  ├─ ECR Push: 10분 타임아웃                       │
│  ├─ Step Functions 폴링: 30분 타임아웃            │
│  └─ ECS Service 활성화: 5분 타임아웃             │
│                                                  │
└─────────────────────────────────────────────────────────┘
```

### 주요 예외 상황

**1. GitHub Clone 실패**

```
시나리오:
├─ Repository not found (권한 부족 또는 비공개)
├─ Network timeout
├─ Branch not found
└─ Authentication failed (잘못된 토큰)

발생: Phase 3-1
처리:
  └─ DeploymentException 발생
      └─ 즉시 배포 중단
      └─ "error" 이벤트 발행
      └─ 정리 작업 (임시 디렉토리 삭제)
```

**2. Dockerfile 찾지 못함**

```
시나리오:
├─ Repository 루트에 Dockerfile 없음
├─ 잘못된 Dockerfile 경로
└─ Dockerfile이 빈 파일

발생: Phase 3-2
처리:
  └─ DeploymentException: "Dockerfile not found"
      └─ 즉시 배포 중단
      └─ "error" 이벤트 발행
```

**3. Docker Build 실패**

```
시나리오:
├─ Gradle 빌드 실패
├─ 컴파일 에러
├─ 의존성 다운로드 실패
├─ 디스크 공간 부족
└─ Docker daemon 연결 실패

발생: Phase 3-3
처리:
  └─ DockerBuildException 발생
      └─ 빌드 로그 수집
      └─ 즉시 배포 중단
      └─ "error" 이벤트 발행 (오류 메시지 포함)
      └─ 로컬 이미지 삭제 (정리)
```

**4. ECR Push 실패**

```
시나리오:
├─ ECR 저장소 생성 실패
├─ 인증 실패 (토큰 만료)
├─ 네트워크 연결 끊김
└─ 이미지 크기 초과

발생: Phase 4
처리:
  └─ DeploymentException 발생
      └─ 즉시 배포 중단
      └─ "error" 이벤트 발행
```

**5. ExecutionArn 찾지 못함**

```
시나리오:
├─ Step Functions이 실행되지 않음
├─ Secrets Manager에 저장되지 않음
├─ 타임아웃 (3초 초과)
└─ Lambda 함수가 호출되지 않음

발생: Phase 6-1 (폴링 시작 전)
처리:
  └─ DeploymentException 발생
      └─ 재시도 (3회)
      └─ 모두 실패 시 "error" 이벤트 발행
```

**6. Step Functions 실패**

```
시나리오:
├─ ECS Cluster 생성 실패
├─ Task Definition 등록 실패
├─ ECS Service 생성 실패
├─ CodeDeploy 실패
├─ 헬스 체크 실패
└─ 타임아웃 (30분 초과)

발생: Phase 5 또는 Phase 6 (폴링 중)
처리:
  └─ StepFunctionsPollingService가 FAILED 상태 감지
      └─ 자동 롤백 (이전 Task Definition 사용)
      └─ "error" 이벤트 발행
      └─ 메타데이터 업데이트 (실패 상태)
      └─ 정리 작업 (Secrets Manager에서 ExecutionArn 삭제)
```

---

## SSE를 통한 실시간 모니터링

### SSE 클라이언트 연결

**엔드포인트:**
```
GET /api/v1/deploy/{deploymentId}/events
Accept: text/event-stream
```

**HTTP 응답:**
```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

### SSE 이벤트 형식

**기본 형식:**
```
event: {eventType}
id: {sequenceNumber}
data: {jsonData}

```

**예시:**

```
event: stage
id: 1
data: {"type": "stage", "message": "GitHub repository cloned", "stage": "CLONE", "timestamp": "2024-11-22T10:30:05Z"}

event: stage
id: 2
data: {"type": "stage", "message": "Dockerfile found at /Dockerfile", "stage": "DOCKERFILE_SEARCH", "timestamp": "2024-11-22T10:30:10Z"}

event: stage
id: 3
data: {"type": "stage", "message": "Docker image built successfully", "stage": "BUILD", "imageId": "sha256:abc123...", "timestamp": "2024-11-22T10:30:45Z"}

event: stage
id: 4
data: {"type": "stage", "message": "Docker image pushed to ECR", "stage": "PUSH", "ecr_uri": "123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605", "timestamp": "2024-11-22T10:35:00Z"}

event: stepFunctionsProgress
id: 5
data: {"type": "stepFunctionsProgress", "message": "EnsureInfra task started", "taskName": "EnsureInfra", "state": "RUNNING", "timestamp": "2024-11-22T10:35:05Z"}

event: stepFunctionsProgress
id: 6
data: {"type": "stepFunctionsProgress", "message": "EnsureInfra task completed", "taskName": "EnsureInfra", "state": "SUCCEEDED", "timestamp": "2024-11-22T10:35:15Z"}

event: stepFunctionsProgress
id: 7
data: {"type": "stepFunctionsProgress", "message": "RegisterTaskAndDeploy started", "taskName": "RegisterTaskAndDeploy", "state": "RUNNING", "timestamp": "2024-11-22T10:35:20Z"}

event: stepFunctionsProgress
id: 8
data: {"type": "stepFunctionsProgress", "message": "RegisterTaskAndDeploy completed", "taskName": "RegisterTaskAndDeploy", "state": "SUCCEEDED", "timestamp": "2024-11-22T10:35:45Z"}

event: stepFunctionsProgress
id: 9
data: {"type": "stepFunctionsProgress", "message": "CheckDeployment started", "taskName": "CheckDeployment", "state": "RUNNING", "timestamp": "2024-11-22T10:35:50Z"}

event: stepFunctionsProgress
id: 10
data: {"type": "stepFunctionsProgress", "message": "CheckDeployment completed", "taskName": "CheckDeployment", "state": "SUCCEEDED", "timestamp": "2024-11-22T10:37:00Z"}

event: done
id: 11
data: {"type": "done", "message": "Deployment completed successfully", "status": "SUCCESS", "elb_url": "panda-api-main-lb-1234567890.us-east-1.elb.amazonaws.com", "image_uri": "123456789.dkr.ecr.us-east-1.amazonaws.com/panda-api:20241122_103605", "commit_hash": "abc1234def567", "branch": "main", "execution_time": "67 seconds", "timestamp": "2024-11-22T10:37:07Z"}
```

### 에러 이벤트 예시

```
event: error
id: 12
data: {"type": "error", "message": "Docker build failed", "stage": "BUILD", "error_code": "BUILD_FAILED", "error_details": "Go to Docker logs for more details", "timestamp": "2024-11-22T10:33:50Z"}

```

### 클라이언트 구현 예시 (JavaScript)

```javascript
const deploymentId = "dep_1234567890abc";
const eventSource = new EventSource(
  `/api/v1/deploy/${deploymentId}/events`
);

// 히스토리 이벤트 수신 (신규 연결 시 과거 이벤트)
eventSource.addEventListener("stage", (event) => {
  const data = JSON.parse(event.data);
  console.log(`[${data.stage}] ${data.message}`);
  updateProgressUI(data);
});

eventSource.addEventListener("stepFunctionsProgress", (event) => {
  const data = JSON.parse(event.data);
  console.log(`[${data.taskName}] ${data.state}`);
  updateProgressUI(data);
});

eventSource.addEventListener("done", (event) => {
  const data = JSON.parse(event.data);
  console.log("배포 완료!");
  console.log(`URL: ${data.elb_url}`);
  console.log(`소요 시간: ${data.execution_time}`);
  eventSource.close();
});

eventSource.addEventListener("error", (event) => {
  const data = JSON.parse(event.data);
  console.error(`배포 실패: ${data.error_details}`);
  eventSource.close();
});

eventSource.onerror = () => {
  console.log("연결 끊김");
  eventSource.close();
};
```

---

## 주요 파일 구조

### API 계층
```
src/main/java/com/panda/backend/feature/deploy/
├── api/
│   ├── DeployApi.java                 # 인터페이스 정의
│   └── DeployController.java          # 구현
```

### Application 계층 (비즈니스 로직)
```
src/main/java/com/panda/backend/feature/deploy/
├── application/
│   ├── StartDeploymentService.java                # 배포 시작
│   ├── DeploymentPipelineService.java             # Git Clone, Docker Build, ECR Push
│   ├── StepFunctionsPollingService.java           # Step Functions 모니터링
│   ├── EcsDeploymentService.java                  # ECS 관리
│   ├── BlueGreenDeploymentService.java            # Blue/Green 배포
│   ├── EventBridgeRuleService.java                # EventBridge 관리
│   ├── LambdaInvocationService.java               # Lambda 호출
│   ├── GetDeploymentResultService.java            # 결과 조회
│   └── StreamDeploymentEventsService.java         # SSE 스트리밍
```

### 이벤트 & 발행
```
src/main/java/com/panda/backend/feature/deploy/
├── event/
│   ├── DeploymentEvent.java                       # 이벤트 모델
│   ├── DeploymentEventPublisher.java              # 인터페이스
│   ├── DeploymentEventPublisherImpl.java           # 구현
│   ├── DeploymentEventStore.java                  # SSE 관리
│   └── StageEventHelper.java                      # 헬퍼
```

### 인프라 & 유틸리티
```
src/main/java/com/panda/backend/feature/deploy/
├── infrastructure/
│   ├── DeploymentTask.java                        # 비동기 작업
│   ├── DeploymentTaskExecutor.java                # ThreadPool 관리
│   ├── ExecutionArnStore.java                     # Secrets Manager 연동
│   └── DeploymentErrorHandler.java                # 에러 처리
```

### DTO & 데이터
```
src/main/java/com/panda/backend/feature/deploy/
├── dto/
│   ├── DeployRequest.java
│   ├── DeployResponse.java
│   ├── DeploymentResult.java
│   ├── DeploymentMetadata.java
│   ├── RegisterEventBusRequest.java
│   └── RegisterEventBusResponse.java
```

### 예외 처리
```
src/main/java/com/panda/backend/feature/deploy/
├── exception/
│   ├── DeploymentException.java
│   ├── DeploymentTimeoutException.java
│   ├── DockerBuildException.java
│   ├── EcsDeploymentException.java
│   └── HealthCheckException.java
```

---

## 빠른 참고

### 주요 상수 및 설정값

```java
// 타임아웃
GITHUB_CLONE_TIMEOUT = 10분
DOCKER_BUILD_TIMEOUT = 10분
ECR_PUSH_TIMEOUT = 10분
STEP_FUNCTIONS_POLLING_TIMEOUT = 30분
ECS_SERVICE_ACTIVE_TIMEOUT = 5분

// 폴링
STEP_FUNCTIONS_POLLING_INTERVAL = 2초
EXECUTION_ARN_WAIT_TIME = 3초

// 이미지 태그
IMAGE_TAG_FORMAT = "YYYYMMdd_HHmmss" (예: 20241122_103605)

// 디렉토리
TEMP_CLONE_DIR = "/tmp/deployment_{deploymentId}"

// Secrets Manager
SECRET_NAME = "panda/deployment/{deploymentId}"
```

### 체크리스트

배포 전 확인사항:
- [ ] GitHub Connection이 유효한가? (Token 유효성, 저장소 접근 권한)
- [ ] AWS Connection이 유효한가? (IAM 권한, 크레덴셜 유효성)
- [ ] ECR 저장소가 생성되어 있거나 생성 권한이 있는가?
- [ ] ECS 클러스터가 생성되어 있거나 생성 권한이 있는가?
- [ ] EventBridge 규칙 생성 권한이 있는가?
- [ ] Step Functions 실행 권한이 있는가?
- [ ] Secrets Manager 접근 권한이 있는가?
- [ ] Docker daemon이 실행 중인가?
- [ ] 디스크 공간이 충분한가? (최소 10GB 권장)

---

## 결론

Deploy API는 다음의 순서로 배포를 진행합니다:

1. **API 요청 수신** → 초기화
2. **GitHub Clone** → Dockerfile 검색
3. **Docker Build** → 로컬 이미지 생성
4. **ECR Push** → 레지스트리에 업로드
5. **EventBridge 자동 트리거** → Step Functions 자동 실행
6. **Step Functions 실행** → ECS 배포 & Blue/Green 무중단 배포
7. **상태 폴링** → SSE를 통한 실시간 알림
8. **배포 완료** → 최종 URL 및 메타데이터 반환

전체 배포는 **최소 30초, 최대 30분**이 소요되며, 실패 시 자동 롤백됩니다.

---

**마지막 업데이트**: 2024-11-22
**버전**: 1.0.0
