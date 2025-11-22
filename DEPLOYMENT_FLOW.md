# 🚀 Panda Backend 배포 흐름 상세 분석

## 📋 목차
1. [이벤트 타입 검증](#-이벤트-타입-검증)
2. [전체 배포 흐름도](#-전체-배포-흐름도)
3. [단계별 상세 분석](#-단계별-상세-분석)
4. [SSE 이벤트 구조](#-sse-이벤트-구조)
5. [프론트엔드 처리 가이드](#-프론트엔드-처리-가이드)

---

## ✅ 이벤트 타입 검증

### 검증 결과

| 이벤트 타입 | 상태 | 사용 위치 | 개수 |
|-----------|------|---------|------|
| **stage** | ✅ | DeploymentEventPublisherImpl, DeploymentEventStore, StageEventHelper 등 | 3개 |
| **success** | ✅ | DeploymentEventPublisherImpl, DeploymentEventStore | 2개 |
| **fail** | ✅ | DeploymentEventStore | 1개 |

### 결론
✅ **모든 이벤트가 정확히 `stage`, `success`, `fail` 3개의 타입만 사용**
✅ **`stepFunctionsProgress`는 `stage`로 올바르게 변경됨**
✅ **기타 불필요한 타입은 없음**

---

## 🔄 전체 배포 흐름도

```
┌─────────────────────────────────────────────────────────────────┐
│ 1️⃣ StartDeploymentService.start()                              │
│    ↓ 배포 시작, EventBridge Rule 생성                           │
├─────────────────────────────────────────────────────────────────┤
│ EVENT: stage "[Step 1] EventBridge 규칙 생성 완료"             │
├─────────────────────────────────────────────────────────────────┤
│ 2️⃣ Event Bus 권한 설정                                         │
├─────────────────────────────────────────────────────────────────┤
│ EVENT: stage "[Step 2] Event Bus 권한 설정 완료"               │
├─────────────────────────────────────────────────────────────────┤
│ 3️⃣ DeploymentTask (Docker Build & ECR Push)                    │
│    ├─ Stage 1: Dockerfile 탐색 및 Docker Build                 │
│    └─ Stage 2: ECR에 이미지 Push                               │
├─────────────────────────────────────────────────────────────────┤
│ EVENTS: stage "[Stage 1] ..." / stage "[Stage 2] ..."         │
├─────────────────────────────────────────────────────────────────┤
│ 4️⃣ StepFunctionsPollingService.startPollingAsync()             │
│    (비동기 폴링 시작 - Step Functions 모니터링)                 │
├─────────────────────────────────────────────────────────────────┤
│ 5️⃣ Stage 3: ECS 배포 (EnsureInfra)                             │
│    ├─ TaskStateEntered: "ECS 배포 시작 중"                     │
│    └─ TaskStateExited: "ECS 배포 완료"                         │
├─────────────────────────────────────────────────────────────────┤
│ EVENTS: stage "[Stage 3] ..."                                  │
├─────────────────────────────────────────────────────────────────┤
│ 6️⃣ Stage 4: CodeDeploy Blue/Green (RegisterTaskAndDeploy)     │
│    ├─ TaskStateEntered: "CodeDeploy 배포 시작"                │
│    ├─ Blue 서비스 정보 추출                                     │
│    ├─ Green 서비스 정보 추출                                    │
│    └─ TaskStateExited: "배포 진행 중"                          │
├─────────────────────────────────────────────────────────────────┤
│ EVENTS: stage "[Stage 4] Blue 서비스 실행 중"                 │
│         stage "[Stage 4] Green 서비스 준비 완료"               │
├─────────────────────────────────────────────────────────────────┤
│ 7️⃣ CheckDeployment (최종 상태 확인)                            │
│    - status: "WAITING_APPROVAL" 감지                            │
│    - Blue/Green URL 확인                                        │
│    - 배포 준비 완료 이벤트 발행                                 │
├─────────────────────────────────────────────────────────────────┤
│ EVENT: stage "[Stage 4] Green 서비스 배포 완료 - 트래픽 전환   │
│              대기 중"                                            │
├─────────────────────────────────────────────────────────────────┤
│ 8️⃣ 배포 준비 완료 (DEPLOYMENT_READY)                          │
│    ├─ 배포 상태: "DEPLOYMENT_READY" 저장                        │
│    ├─ SSE success 이벤트 발행 (SSE 종료 신호)                  │
│    └─ 폴링 종료                                                 │
├─────────────────────────────────────────────────────────────────┤
│ EVENT: success "Deployment ready for manual traffic switch"    │
│        (5초 후 SSE 연결 자동 종료)                             │
├─────────────────────────────────────────────────────────────────┤
│ ✅ 배포 완료 (사용자가 /api/v1/deploy/{id}/switch 호출 필요)   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 📍 단계별 상세 분석

### 1️⃣ 배포 시작 (StartDeploymentService)

**파일:** `StartDeploymentService.java`

#### 1.1 EventBridge Rule 생성
```
트리거: POST /api/v1/deploy 호출
├─ 배포 ID 생성
├─ 배포 상태 저장 (RUNNING)
└─ EventBridge Rule 생성 요청

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Step 1] EventBridge 규칙 생성 완료"
├─ Details: {"stage": 1}
└─ 위치: 라인 92-93

실패 시:
└─ Type: "fail"
   Message: "EventBridge 규칙 생성 실패: ..."
```

#### 1.2 Event Bus 권한 설정
```
트리거: EventBridge Rule 생성 성공

이벤트 발행:
├─ 요청 중: Type "stage", Message "[Step 2] Event Bus 권한 설정 요청 중..."
├─ 완료: Type "stage", Message "[Step 2] Event Bus 권한 설정 완료"
├─ Details: {"stage": 2}
└─ 위치: 라인 109-110, 120-121

실패 시:
└─ Type: "fail"
   Message: "Event Bus 권한 설정 실패: ..."
```

#### 1.3 배포 작업 실행
```
트리거: Event Bus 권한 설정 성공

동작:
├─ DeploymentTask 생성
├─ deploymentTaskExecutor.executeDeployment() 호출
└─ 비동기로 배포 파이프라인 실행

반환값:
├─ 배포 ID (deploymentId)
├─ 시작 시간
└─ 초기 상태 (RUNNING)
```

---

### 2️⃣ Docker Build & ECR Push (DeploymentTask → DeploymentPipelineService)

**파일:** `DeploymentTask.java`, `DeploymentPipelineService.java`

#### Stage 1: Dockerfile 탐색 및 Docker Build
```
메서드: stage1*() 메서드들 (StageEventHelper)

진행 순서:
1. stage1Start()
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중..."
   └─ Details: {"stage": 1}

2. stage1RepositoryCloned(path)
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Repository 클론 완료"
   └─ Details: {"stage": 1, "path": "/tmp/deployment_xxx"}

3. stage1DockerfileSearching()
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Dockerfile 검색 중..."
   └─ Details: {"stage": 1}

4. stage1DockerfileFound(path)
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Dockerfile 찾음"
   └─ Details: {"stage": 1, "path": "/tmp/.../Dockerfile"}

5. stage1BuildStarting()
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Docker 이미지 빌드 시작..."
   └─ Details: {"stage": 1}

6. stage1BuildProgress(message)
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Docker 빌드 진행 중: {message}"
   └─ Details: {"stage": 1}

7. stage1BuildCompleted(imageName)
   ├─ Type: "stage"
   ├─ Message: "[Stage 1] Docker 이미지 빌드 완료"
   └─ Details: {"stage": 1, "imageName": "panda-app:v1.0"}
```

#### Stage 2: ECR에 이미지 Push
```
메서드: stage2*() 메서드들 (StageEventHelper)

진행 순서:
1. stage2Start()
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] ECR에 이미지 Push - ECR로 이미지 Push 중..."
   └─ Details: {"stage": 2}

2. stage2RepositoryEnsured(repositoryName)
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] ECR 리포지토리 확인 완료"
   └─ Details: {"stage": 2, "repository": "panda-app"}

3. stage2LoginStarting()
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] ECR 로그인 중..."
   └─ Details: {"stage": 2}

4. stage2LoginCompleted()
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] ECR 로그인 완료"
   └─ Details: {"stage": 2}

5. stage2PushStarting(uri)
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] 이미지 Push 시작"
   └─ Details: {"stage": 2, "uri": "123456789.dkr.ecr.ap-northeast-2.amazonaws.com/..."}

6. stage2PushProgress(message)
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] Push 진행 중: {message}"
   └─ Details: {"stage": 2}

7. stage2PushCompleted(uri)
   ├─ Type: "stage"
   ├─ Message: "[Stage 2] 이미지 Push 완료"
   └─ Details: {"stage": 2, "uri": "123456789.dkr.ecr.ap-northeast-2.amazonaws.com/..."}

폴링 시작:
├─ StepFunctionsPollingService.startPollingAsync() 호출
├─ 비동기로 Step Functions 모니터링 시작
└─ Secrets Manager에서 ExecutionArn 대기 (기본 10초)
```

---

### 3️⃣ Step Functions 폴링 (StepFunctionsPollingService)

**파일:** `StepFunctionsPollingService.java`

#### 폴링 설정
```
메서드: startPollingAsync()

설정값:
├─ Polling Interval: 2000ms (기본)
├─ Max Duration: 1800000ms (30분)
├─ ExecutionArn Wait: 10000ms (Secrets Manager 대기)
└─ 최대 폴링 횟수: 약 900회 (30분 / 2초)

동작:
├─ 비동기 스레드 풀에서 pollExecutionHistory() 실행
├─ Secrets Manager에서 ExecutionArn 조회
├─ 2초마다 GetExecutionHistory API 호출
├─ 마지막 처리 이벤트 ID 추적 (중복 방지)
└─ 최종 상태 도달 시 종료
```

---

### 4️⃣ Stage 3: ECS 배포 (EnsureInfra)

**감지:** `analyzeTaskStateEntered()`, `analyzeTaskStateExited()`

#### TaskStateEntered - Stage 3 시작
```
감지: Step Functions에서 "EnsureInfra" Task 시작

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Stage 3] ECS 배포 시작 중"
├─ Details: {"stage": 3}
└─ 위치: 라인 353-356

폴링 상태: "ENSURE_INFRA_IN_PROGRESS"
```

#### TaskStateExited - Stage 3 완료
```
감지: Step Functions에서 "EnsureInfra" Task 완료

Task Output 추출:
├─ clusterName: "panda-cluster"
├─ serviceName: "panda-service"
└─ taskDefinition: "panda-task:1"

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Stage 3] ECS 배포 완료"
├─ Details: {
│   "stage": 3,
│   "clusterName": "panda-cluster",
│   "serviceName": "panda-service",
│   "taskDefinition": "panda-task:1"
│ }
└─ 위치: 라인 402-405

폴링 상태: "ENSURE_INFRA_COMPLETED"
```

---

### 5️⃣ Stage 4: Blue/Green 배포 (RegisterTaskAndDeploy)

**감지:** `analyzeTaskStateEntered()`, `analyzeTaskStateExited()`

#### TaskStateEntered - Stage 4 시작
```
감지: Step Functions에서 "RegisterTaskAndDeploy" Task 시작

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Stage 4] CodeDeploy Blue/Green 배포 시작"
├─ Details: {"stage": 4}
└─ 위치: 라인 360-363

폴링 상태: "REGISTER_TASK_IN_PROGRESS"
```

#### TaskStateExited - Stage 4 진행 중
```
감지: Step Functions에서 "RegisterTaskAndDeploy" Task 완료

Task Output 추출:
├─ clusterName: "panda-cluster"
├─ serviceName: "panda-service"
├─ blueService:
│   ├─ serviceArn: "arn:aws:ecs:ap-northeast-2:123456789012:service/..."
│   └─ url: "http://blue.service.com:8080"
├─ greenService:
│   ├─ serviceArn: "arn:aws:ecs:ap-northeast-2:123456789012:service/..."
│   └─ url: "http://green.service.com:8080"
├─ codeDeployDeploymentId: "d-ABC123"
└─ codeDeployApplicationName: "panda-app"

이벤트 발행:
1️⃣ Blue 서비스 정보
   ├─ Type: "stage"
   ├─ Message: "[Stage 4] Blue 서비스 실행 중"
   └─ Details: {"stage": 4, "url": "http://blue.service.com:8080"}

2️⃣ Green 서비스 정보
   ├─ Type: "stage"
   ├─ Message: "[Stage 4] Green 서비스 준비 완료"
   └─ Details: {"stage": 4, "url": "http://green.service.com:8080"}

3️⃣ 최종 배포 상태
   ├─ Type: "stage"
   ├─ Message: "[Stage 4] CodeDeploy Blue/Green 배포 진행 중"
   └─ Details: 모든 Blue/Green 정보 포함

위치: 라인 668-674, 410-412

Health Check 트리거:
└─ Green URL이 있는 경우 triggerHealthCheck() 호출

폴링 상태: "REGISTER_TASK_COMPLETED"
폴링 계속 진행: CheckDeployment 응답 대기
```

---

### 6️⃣ CheckDeployment: 배포 준비 완료 감지

**감지:** `analyzeTaskStateExited()` 라인 415-459

#### WAITING_APPROVAL 상태 감지
```
조건:
├─ stageStatus: "CHECK_DEPLOYMENT" 포함
├─ status: "WAITING_APPROVAL"
└─ 위치: 라인 416-418

Task Output 추출:
├─ checkResult.blueTargetGroupArn: "arn:aws:elasticloadbalancing:..."
├─ checkResult.greenTargetGroupArn: "arn:aws:elasticloadbalancing:..."
├─ blueUrl: "http://blue.service.com:8080" (선택적)
├─ greenUrl: "http://green.service.com:8080" (선택적)
└─ deploymentStatus: "Ready"

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Stage 4] Green 서비스 배포 완료 - 트래픽 전환 대기 중"
├─ Details: {
│   "stage": 4,
│   "blueServiceArn": "arn:aws:elasticloadbalancing:...",
│   "greenServiceArn": "arn:aws:elasticloadbalancing:...",
│   "blueUrl": "http://blue.service.com:8080",
│   "greenUrl": "http://green.service.com:8080",
│   "message": "POST /api/v1/deploy/{deploymentId}/switch를 호출하여 트래픽 전환을 진행하세요"
│ }
└─ 위치: 라인 456-457

폴링 상태: "DEPLOYMENT_READY"
```

#### 폴링 루프에서 DEPLOYMENT_READY 처리
```
위치: pollExecutionHistory() 라인 190-200

동작 순서:
1. saveDeploymentReadyResult() 호출
   ├─ 배포 상태: "DEPLOYMENT_READY" 저장
   ├─ Blue/Green URL 저장
   └─ Service ARN 저장

2. deploymentEventStore.sendDoneEvent() 호출
   ├─ Type: "success"
   ├─ Message: "Deployment ready for manual traffic switch"
   └─ SSE 클라이언트에 전송

3. 5초 후 자동 SSE 연결 종료
   └─ closeAllEmitters(deploymentId) 호출

4. 폴링 루프 종료 (break)
```

---

### 7️⃣ Success/Fail 이벤트 시나리오

#### 배포 성공 (SUCCEEDED)
```
감지: ExecutionSucceeded 이벤트

이벤트 발행:
├─ Type: "stage"
├─ Message: "[Stage 4] 배포 완료"
├─ Details: {"stage": 4, "finalService": "green"}
└─ 위치: 라인 816

다음 동작:
├─ saveFinalDeploymentResult() 호출
├─ 배포 상태: "COMPLETED" 저장
└─ 폴링 종료

주의: 실제로는 DEPLOYMENT_READY 상태에서
success 이벤트를 받으므로 이 경로는 드물게 실행됨
```

#### 배포 준비 완료 (DEPLOYMENT_READY)
```
감지: CheckDeployment의 WAITING_APPROVAL 상태

이벤트 발행:
1️⃣ Type: "stage" (배포 준비 상태 알림)
   ├─ Message: "[Stage 4] Green 서비스 배포 완료 - 트래픽 전환 대기 중"
   └─ Details: Blue/Green URL, 지시사항 포함

2️⃣ Type: "success" (SSE 종료 신호)
   ├─ Message: "Deployment ready for manual traffic switch"
   └─ 5초 후 자동 SSE 연결 종료

배포 상태: "DEPLOYMENT_READY"
폴링: 종료

다음 단계:
└─ 사용자가 /api/v1/deploy/{id}/switch 호출 필요
```

#### 배포 실패 (FAILED)
```
발생 상황:
├─ ExecutionFailed 이벤트 감지
├─ 폴링 타임아웃
├─ ExecutionArn 미발견
├─ Step Functions 예외
├─ Health Check 실패
└─ DeploymentTask 실행 예외

이벤트 발행:
├─ Type: "fail"
├─ Message: "에러 메시지"
└─ Details: null

SSE 처리:
├─ fail 이벤트 전송
└─ 5초 후 자동 SSE 연결 종료

배포 상태: "FAILED"
폴링: 종료
```

---

## 🎯 SSE 이벤트 구조

**파일:** `DeploymentEventStore.java` 라인 46-86

### HTTP 응답 헤더
```
HTTP/1.1 200 OK
Content-Type: text/event-stream; charset=utf-8
Cache-Control: no-cache
Connection: keep-alive
Transfer-Encoding: chunked
```

### Event Stream 형식

#### 1. Stage 이벤트
```
id: {UUID}
event: stage
retry: 5000
data: {
  "type": "stage",
  "message": "[Stage 1] EventBridge 규칙 생성 완료",
  "details": {
    "stage": 1,
    "clusterName": "panda-cluster",  // 선택적
    "serviceName": "panda-service",   // 선택적
    ...
  }
}
```

**특징:**
- 전체 event 객체 전송
- 자세한 정보 포함
- 빈번하게 발행됨
- 프로그레스 바 업데이트 용도

#### 2. Success 이벤트
```
id: {UUID}
event: success
retry: 5000
data: {
  "message": "Deployment completed successfully" 또는
             "Deployment ready for manual traffic switch"
}
```

**특징:**
- message만 전송
- 배포 완료/준비 신호
- 5초 후 SSE 연결 자동 종료
- 프론트에서 SSE 수동 종료 가능

#### 3. Fail 이벤트
```
id: {UUID}
event: fail
retry: 5000
data: {
  "message": "에러 메시지"
}
```

**특징:**
- message만 전송
- 배포 실패 신호
- 5초 후 SSE 연결 자동 종료
- 프론트에서 에러 처리 후 SSE 종료

### 이벤트 히스토리
```
메커니즘:
├─ 신규 SSE 클라이언트 연결 시
├─ 배포 시작 이후 발행된 모든 이벤트 조회
├─ 각 이벤트를 순서대로 재전송
└─ 프론트가 진행 상황 즉시 파악 가능

저장소:
├─ DeploymentEventStore.eventHistoryMap
├─ 자료구조: LinkedQueue (FIFO)
└─ 메모리 저장 (휘발성)
```

---

## 💻 프론트엔드 처리 가이드

### SSE 연결 및 리스너 설정

```javascript
// 1. SSE 연결
const eventSource = new EventSource(`/api/v1/deploy/${deploymentId}/events`);

// 2. Stage 이벤트 처리
eventSource.addEventListener('stage', (event) => {
  const data = JSON.parse(event.data);
  console.log('Stage:', data.message);
  console.log('Details:', data.details);

  // 배포 준비 완료 상태 감지
  if (data.message.includes('트래픽 전환 대기')) {
    // UI: "배포 준비 완료, 사용자 확인 필요" 표시
    // 버튼: "트래픽 전환" 활성화
    showManualSwitchButton();
  }

  // 프로그레스 업데이트
  updateProgressUI(data.details.stage);
});

// 3. Success 이벤트 처리
eventSource.addEventListener('success', (event) => {
  const data = JSON.parse(event.data);
  console.log('Success:', data.message);

  // UI: "배포 완료" 메시지 표시
  showSuccessMessage(data.message);

  // SSE 연결 종료
  eventSource.close();
});

// 4. Fail 이벤트 처리
eventSource.addEventListener('fail', (event) => {
  const data = JSON.parse(event.data);
  console.error('Failed:', data.message);

  // UI: 에러 메시지 표시
  showErrorMessage(data.message);

  // SSE 연결 종료
  eventSource.close();
});

// 5. 에러 처리
eventSource.onerror = (error) => {
  console.error('SSE Error:', error);
  eventSource.close();
};
```

### 트래픽 전환 (수동)

```javascript
// 배포 준비 상태에서 사용자가 버튼을 클릭한 경우
async function manuallySwitch(deploymentId) {
  try {
    const response = await fetch(
      `/api/v1/deploy/${deploymentId}/switch`,
      { method: 'POST' }
    );

    const result = await response.json();
    console.log('Traffic switch result:', result);

    if (response.ok) {
      // 배포 상태 조회
      const finalResult = await fetch(
        `/api/v1/deploy/${deploymentId}/result`
      ).then(r => r.json());

      console.log('Final deployment result:', finalResult);
      // UI: 최종 결과 표시
    }
  } catch (error) {
    console.error('Traffic switch failed:', error);
  }
}
```

### 배포 상태 조회

```javascript
// 이전 배포 상태 확인 (재접속 시)
async function getDeploymentResult(deploymentId) {
  const result = await fetch(
    `/api/v1/deploy/${deploymentId}/result`
  ).then(r => r.json());

  // result 형식:
  // {
  //   code: 200,
  //   message: "배포 결과 조회 성공",
  //   data: {
  //     deploymentId: "...",
  //     status: "DEPLOYMENT_READY" | "COMPLETED" | "FAILED",
  //     blueUrl: "...",
  //     greenUrl: "...",
  //     errorMessage: "..." (실패 시)
  //   }
  // }

  console.log('Deployment status:', result.data.status);
}
```

---

## 📊 배포 상태 흐름

```
┌─────────────┐
│   RUNNING   │  초기 상태 (배포 시작)
└──────┬──────┘
       │
       ├─ Docker Build & ECR Push (Stage 1, 2)
       │
       ├─ ECS 배포 (Stage 3)
       │
       ├─ CodeDeploy Blue/Green (Stage 4)
       │
       │ CheckDeployment: WAITING_APPROVAL
       │
       ▼
┌─────────────────────┐
│ DEPLOYMENT_READY    │  ← SSE success 이벤트 발행
│ (수동 전환 대기)    │     (SSE 연결 종료)
└──────┬──────────────┘
       │
       │ 사용자가 /switch 호출
       │
       ▼
┌──────────────┐
│  COMPLETED   │  최종 배포 완료
└──────────────┘

에러 발생 경우:
       │
       ▼
┌──────────────┐
│    FAILED    │  ← SSE fail 이벤트 발행
│              │     (SSE 연결 종료)
└──────────────┘
```

---

## 🔍 디버깅 팁

### SSE 연결 확인
```javascript
const eventSource = new EventSource(`/api/v1/deploy/${deploymentId}/events`);

eventSource.onopen = () => {
  console.log('SSE 연결 성공');
};

eventSource.onerror = (error) => {
  console.error('SSE 연결 실패:', error);
};
```

### 배포 로그 추적
```javascript
// 모든 이벤트 로그
eventSource.addEventListener('stage', (event) => {
  console.log(`[${new Date().toISOString()}] STAGE`, JSON.parse(event.data));
});

eventSource.addEventListener('success', (event) => {
  console.log(`[${new Date().toISOString()}] SUCCESS`, JSON.parse(event.data));
});

eventSource.addEventListener('fail', (event) => {
  console.log(`[${new Date().toISOString()}] FAIL`, JSON.parse(event.data));
});
```

### 배포 상태 저장소 확인
```bash
# 배포 상태 조회 API
curl http://localhost:8080/api/v1/deploy/{deploymentId}/result

# 응답 예시:
{
  "code": 200,
  "message": "배포 결과 조회 성공",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "status": "DEPLOYMENT_READY",
    "owner": "your-org",
    "repo": "your-repo",
    "branch": "main",
    "blueUrl": "http://blue.example.com",
    "greenUrl": "http://green.example.com",
    "startedAt": "2024-01-01T12:00:00",
    "completedAt": "2024-01-01T12:08:30",
    "durationSeconds": 510,
    "eventCount": 35
  }
}
```

---

## ✅ 최종 검증 체크리스트

- [x] 이벤트 타입: `stage`, `success`, `fail` 3개만 사용
- [x] 배포 상태: `RUNNING`, `DEPLOYMENT_READY`, `COMPLETED`, `FAILED`
- [x] SSE 메시지 형식: 정확히 API 명세 준수
- [x] Stage별 메시지: 명확하고 사용자 친화적
- [x] 에러 처리: 모든 실패 경로에서 fail 이벤트 발행
- [x] 폴링 종료: `DEPLOYMENT_READY` 상태에서 success 이벤트 후 폴링 종료
- [x] SSE 연결 종료: success/fail 이벤트 후 5초 경과 시 자동 종료
- [x] 이벤트 히스토리: 신규 클라이언트가 진행 상황 즉시 파악 가능

