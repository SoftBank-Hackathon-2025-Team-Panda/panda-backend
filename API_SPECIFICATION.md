# Panda Backend - 완전한 API 명세서

## 📋 목차
1. [API 개요](#api-개요)
2. [인증 및 보안](#인증-및-보안)
3. [응답 형식](#응답-형식)
4. [에러 처리](#에러-처리)
5. [Connection API](#connection-api)
   - [1️⃣ GitHub 레포 연결](#1️⃣-github-레포-연결)
   - [2️⃣ AWS 계정 연결](#2️⃣-aws-계정-연결)
   - [3️⃣ 저장된 연결 조회](#3️⃣-저장된-연결-조회)
6. [Deployment API](#deployment-api)
7. [SSE 스트리밍 상세](#sse-스트리밍-상세)
8. [FAQ](#faq)

---

## API 개요

### 기본 정보
- **Base URL**: `http://localhost:8080`
- **API Version**: `v1`
- **Content-Type**: `application/json`
- **Response Format**: JSON

### API 분류
| 그룹 | 엔드포인트 | 목적 |
|------|---------|------|
| **Connection** | `POST /api/v1/connect/github` | GitHub 레포 연결 |
| **Connection** | `POST /api/v1/connect/aws` | AWS 계정 연결 |
| **Connection** | `GET /api/v1/connections` | 저장된 모든 연결 조회 |
| **Deployment** | `POST /api/v1/deploy` | 배포 시작 |
| **Deployment** | `GET /api/v1/deploy/{id}/events` | 실시간 이벤트 스트리밍 (SSE) |
| **Deployment** | `POST /api/v1/deploy/{id}/switch` | 배포 전환 실행 (수동 확인) |
| **Deployment** | `GET /api/v1/deploy/{id}/result` | 배포 결과 조회 |

### 포트 및 엔드포인트
```
HTTP API: http://localhost:8080
API Docs: http://localhost:8080/api-docs
Swagger UI: http://localhost:8080/swagger-ui.html
```

---

## 인증 및 보안

### 인증 방식
**현재 버전**: 비인증 (개발 모드)

**프로덕션 배포 시 권장 사항**:
- OAuth 2.0 또는 JWT 토큰 기반 인증 추가
- API Key 기반 인증
- mTLS (Mutual TLS) 사용

### 민감한 정보 보안
```
⚠️ 주의:
- GitHub Personal Access Token은 암호화되지 않은 상태로 메모리에 저장됨
  → 프로덕션: AWS Secrets Manager 또는 HashiCorp Vault 사용 권장
- AWS 자격증명도 메모리에 저장됨
  → 프로덕션: AWS IAM Role, STS Assume Role 사용 권장
```

---

## 응답 형식

### 성공 응답 (200, 201)
```json
{
  "code": 200,
  "message": "요청 성공 메시지",
  "data": {
    // 응답 데이터
  }
}
```

### 생성 응답 (201)
```json
{
  "code": 201,
  "message": "리소스 생성 성공 메시지",
  "data": {
    // 생성된 데이터
  }
}
```

### 에러 응답 (4xx, 5xx)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "에러 타입",
  "message": "상세 에러 메시지",
  // 추가 필드 (선택)
  "deploymentId": "dep_xxxxx",
  "stage": 2,
  "errorCode": "ERROR_CODE"
}
```

---

## 에러 처리

### HTTP 상태 코드

| 코드 | 의미 | 발생 상황 |
|------|------|---------|
| **200** | OK | 성공적인 조회/업데이트 |
| **201** | Created | 리소스 생성 성공 |
| **400** | Bad Request | 배포 실패, 유효하지 않은 요청 |
| **408** | Request Timeout | 배포 타임아웃 (단계별 또는 전체) |
| **500** | Internal Server Error | 예상 외의 서버 에러 |

### 에러 코드 목록

| 에러 코드 | HTTP | 설명 | 원인 |
|----------|------|------|------|
| **DEPLOYMENT_TIMEOUT** | 408 | 배포 타임아웃 | 단계 또는 전체 배포가 제한 시간 초과 |
| **DOCKER_BUILD_FAILED** | 400 | Docker 빌드 실패 | Stage 1에서 docker build 명령 실패 |
| **ECS_DEPLOYMENT_FAILED** | 400 | ECS 배포 실패 | Stage 3에서 ECS 서비스 생성/업데이트 실패 |
| **HEALTH_CHECK_FAILED** | 400 | 헬스체크 실패 | Stage 5에서 Green 서비스 헬스체크 미통과 |
| **UNEXPECTED_ERROR** | 500 | 예상 외 에러 | 분류되지 않는 예외 |

### 에러 응답 예제

#### Timeout Exception
```json
{
  "timestamp": "2024-01-01T12:10:45",
  "status": 408,
  "error": "Deployment Timeout",
  "message": "Deployment timed out at Stage 3 after 605 seconds (timeout: 600 seconds)",
  "deploymentId": "dep_k1l2m3n4o5",
  "stage": 3,
  "errorCode": "DEPLOYMENT_TIMEOUT",
  "durationSeconds": 605,
  "timeoutSeconds": 600
}
```

#### Deployment Exception
```json
{
  "timestamp": "2024-01-01T12:05:20",
  "status": 400,
  "error": "Deployment Error",
  "message": "Dockerfile not found in repository",
  "deploymentId": "dep_k1l2m3n4o5",
  "stage": 1,
  "errorCode": "DOCKER_BUILD_FAILED"
}
```

#### Server Error
```json
{
  "timestamp": "2024-01-01T12:15:00",
  "status": 500,
  "error": "Internal Server Error",
  "message": "Unexpected error: Connection refused",
  "exceptionClass": "IOException"
}
```

---

# Connection API

## 1️⃣ GitHub 레포 연결

### 엔드포인트
```
POST /api/v1/connect/github
```

### 설명
GitHub Personal Access Token을 사용하여 GitHub 레포지토리를 검증하고 연결을 생성합니다.
이 연결 ID는 나중에 배포 요청 시 사용됩니다.

### 요청

#### Headers
```
Content-Type: application/json
```

#### Body
```json
{
  "owner": "your-org",
  "repo": "your-repo",
  "branch": "main",
  "token": "ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
}
```

#### 필드 설명
| 필드 | 타입 | 필수 | 설명 | 예제 |
|------|------|------|------|------|
| **owner** | String | ✅ | GitHub 조직명 또는 사용자명 | `"your-org"`, `"john-doe"` |
| **repo** | String | ✅ | GitHub 레포지토리명 | `"your-repo"` |
| **branch** | String | ✅ | 사용할 브랜치 | `"main"`, `"develop"` |
| **token** | String | ✅ | GitHub Personal Access Token | `"ghp_xxxx..."` |

### 응답

#### 성공 (200)
```json
{
  "code": 200,
  "message": "GitHub 연결에 성공했습니다.",
  "data": {
    "githubConnectionId": "gh_a1b2c3d4e5"
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **code** | Integer | HTTP 상태 코드 |
| **message** | String | 성공 메시지 |
| **data.githubConnectionId** | String | GitHub 연결 ID (후속 API에서 사용) |

#### 실패 (400)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "GitHub connection failed: Repository not found: your-org/your-repo"
}
```

### 가능한 에러
| 에러 메시지 | 원인 | 해결 방법 |
|-----------|------|---------|
| `Repository not found` | 레포 이름 오류 또는 토큰 권한 부족 | owner/repo 확인, token 권한 확인 |
| `Bad credentials` | 토큰 유효하지 않음 | 새 토큰 생성 |
| `Connection timeout` | GitHub API 접근 실패 | 네트워크 확인, GitHub 상태 확인 |



---

## 2️⃣ AWS 계정 연결

### 엔드포인트
```
POST /api/v1/connect/aws
```

### 설명
AWS 자격증명을 사용하여 AWS 계정을 검증하고 연결을 생성합니다.
이 연결 ID는 나중에 배포 요청 시 사용됩니다.

### 요청

#### Headers
```
Content-Type: application/json
```

#### Body
```json
{
  "region": "ap-northeast-2",
  "accessKeyId": "AKIAIOSFODNN7EXAMPLE",
  "secretAccessKey": "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
  "sessionToken": ""
}
```

#### 필드 설명
| 필드 | 타입 | 필수 | 설명 | 예제 |
|------|------|------|------|------|
| **region** | String | ✅ | AWS 리전 | `"ap-northeast-2"`, `"us-east-1"` |
| **accessKeyId** | String | ✅ | AWS Access Key ID | `"AKIAIOSFODNN7EXAMPLE"` |
| **secretAccessKey** | String | ✅ | AWS Secret Access Key | `"wJalrXUtnFEMI/K7MDENG/..."` |
| **sessionToken** | String | ❌ | AWS Session Token (STS 사용 시) | `"FwoGZXIvYXdzEF..."` |


### 응답

#### 성공 (200)
```json
{
  "code": 200,
  "message": "AWS 연결에 성공했습니다.",
  "data": {
    "awsConnectionId": "aws_f6g7h8i9j0"
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **code** | Integer | HTTP 상태 코드 |
| **message** | String | 성공 메시지 |
| **data.awsConnectionId** | String | AWS 연결 ID (후속 API에서 사용) |

#### 실패 (400)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "AWS credentials validation failed: User: arn:aws:iam::123456789012:user/test is not authorized to perform: sts:GetCallerIdentity"
}
```

### 가능한 에러
| 에러 메시지 | 원인 | 해결 방법 |
|-----------|------|---------|
| `Invalid access key ID` | AccessKeyId 잘못됨 | IAM에서 정확한 AccessKeyId 확인 |
| `Invalid secret access key` | SecretAccessKey 잘못됨 | IAM에서 정확한 SecretAccessKey 확인 |
| `not authorized to perform` | 권한 부족 | IAM 정책에 필요 권한 추가 |
| `Connection timeout` | AWS API 접근 실패 | 리전 확인, 네트워크 확인 |


---

## 3️⃣ 저장된 연결 조회

### 엔드포인트
```
GET /api/v1/connections
```

### 설명
이전에 저장한 GitHub 및 AWS 연결 정보를 모두 조회합니다.
배포 요청 시 connectionId를 선택하거나, 클라이언트가 이전 연결 정보를 재사용할 때 사용됩니다.

### 요청

#### Headers
```
Content-Type: application/json
```

### 응답

#### 성공 (200)
```json
{
  "code": 200,
  "message": "연결 정보를 조회했습니다.",
  "data": {
    "github": [
      {
        "connectionId": "gh_a1b2c3d4e5",
        "owner": "your-org",
        "repo": "your-repo",
        "branch": "main"
      }
    ],
    "aws": [
      {
        "connectionId": "aws_f6g7h8i9j0",
        "region": "ap-northeast-2"
      }
    ]
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **code** | Integer | HTTP 상태 코드 |
| **message** | String | 성공 메시지 |
| **data.github** | Array | GitHub 연결 목록 |
| **data.github[].connectionId** | String | GitHub 연결 ID |
| **data.github[].owner** | String | GitHub 조직명 또는 사용자명 |
| **data.github[].repo** | String | GitHub 레포지토리명 |
| **data.github[].branch** | String | 배포할 브랜치 |
| **data.aws** | Array | AWS 연결 목록 |
| **data.aws[].connectionId** | String | AWS 연결 ID |
| **data.aws[].region** | String | AWS 리전 |

#### 저장된 연결이 없는 경우 (200)
```json
{
  "code": 200,
  "message": "연결 정보를 조회했습니다.",
  "data": {
    "github": [],
    "aws": []
  }
}
```

---

# Deployment API

## 4️⃣ 배포 시작

### 엔드포인트
```
POST /api/v1/deploy
```

### 설명
GitHub 레포지토리에서 Docker 이미지를 빌드하고, AWS ECR로 푸시한 후, ECS를 통해 Blue/Green 배포를 시작합니다.
이 API는 즉시 `deploymentId`를 반환하고, 배포는 백그라운드에서 비동기로 진행됩니다.

### 요청

#### Headers
```
Content-Type: application/json
```

#### Body
```json
{
  "githubConnectionId": "gh_a1b2c3d4e5",
  "awsConnectionId": "aws_f6g7h8i9j0",
  "owner": "your-org",
  "repo": "your-repo",
  "branch": "main"
}
```

#### 필드 설명
| 필드 | 타입 | 필수 | 설명 | 예제 |
|------|------|------|------|------|
| **githubConnectionId** | String | ✅ | 사전에 연결한 GitHub 연결 ID | `"gh_a1b2c3d4e5"` |
| **awsConnectionId** | String | ✅ | 사전에 연결한 AWS 연결 ID | `"aws_f6g7h8i9j0"` |
| **owner** | String | ✅ | GitHub 조직명 또는 사용자명 | `"your-org"` |
| **repo** | String | ✅ | GitHub 레포지토리명 | `"your-repo"` |
| **branch** | String | ✅ | 배포할 브랜치 | `"main"` |

### 응답

#### 성공 (200)
```json
{
  "code": 200,
  "message": "배포가 시작되었습니다.",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "message": "Deployment started. Listen to /api/v1/deploy/{id}/events"
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **code** | Integer | HTTP 상태 코드 |
| **message** | String | 성공 메시지 |
| **data.deploymentId** | String | 배포 ID (SSE 및 결과 조회에 사용) |
| **data.message** | String | 다음 단계 안내 메시지 |

#### 실패 (400)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "GitHub connection not found"
}
```

### 배포 단계 (내부)
1. **Stage 1**: Dockerfile 탐색 및 Docker Build
2. **Stage 2**: ECR로 이미지 Push
3. **Stage 3**: ECS 배포 시작
4. **Stage 4**: CodeDeploy Blue/Green Lifecycle (배포 자동 완료, 수동 전환 대기)

### 타임아웃
- 전체 배포: 30분
- 단계별: 10분

### 가능한 에러
| 에러 메시지 | 원인 | 해결 방법 |
|-----------|------|---------|
| `GitHub connection not found` | githubConnectionId 유효하지 않음 | GitHub 연결 다시 생성 |
| `AWS connection not found` | awsConnectionId 유효하지 않음 | AWS 연결 다시 생성 |
| `Deployment queue is full` | ThreadPool 작업 큐 가득 찬 상태 | 대기 후 재시도 |



---

## 5️⃣ 배포 실시간 이벤트 스트리밍 (SSE)

### 엔드포인트
```
GET /api/v1/deploy/{deploymentId}/events
```

### 설명
Server-Sent Events (SSE)를 사용하여 배포 진행 상황을 실시간으로 스트리밍합니다.
클라이언트가 배포 시작 후 언제든 연결할 수 있으며, 기존 진행 상황 히스토리를 자동으로 받습니다.

### 요청

#### Path Parameters
| 파라미터 | 타입 | 필수 | 설명 | 예제 |
|---------|------|------|------|------|
| **deploymentId** | String | ✅ | 배포 ID (배포 시작 API 응답) | `"dep_k1l2m3n4o5"` |

#### Headers
```
Accept: text/event-stream
```

### 응답

#### Headers
```
HTTP/1.1 200 OK
Content-Type: application/text/event-stream
Cache-Control: no-cache
Connection: keep-alive
```

#### 이벤트 형식 (EventSource)
```
:comment
id: {UUID}
event: {eventType}
data: {JSON}
reconnect: 5000
```

#### 이벤트 타입

##### 1. Connected 이벤트 (SSE 연결 확립)
```
event: connected
data: {
  "message": "SSE connection established"
}
```

**설명**: SSE 연결이 성공적으로 확립되었음을 알립니다.
- 클라이언트가 연결 요청 직후 가장 먼저 받는 이벤트
- 이 이벤트를 받으면 SSE 연결이 안정적으로 이루어졌음을 확인
- 이후 배포 관련 이벤트들이 스트리밍됨

**예제**:
```
id: 550e8400-e29b-41d4-a716-446655440001
event: connected
data: {"message":"SSE connection established"}
reconnect: 5000
```

##### 2. Stage 이벤트 (배포 진행)
```
event: stage
data: {
  "type": "stage",
  "message": "[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...",
  "details": {
    "stage": 1,
    ...
  }
}
```

**설명**: 배포의 각 Stage에서 진행 상황을 전송합니다.
- 각 Stage 시작, 진행, 완료 시마다 이벤트 발생
- 세부 정보는 `details` 필드에 포함

**예제**:
```
id: 550e8400-e29b-41d4-a716-446655440000
event: stage
data: {"type":"stage","message":"[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...","details":{"stage":1}}
reconnect: 5000
```

##### 3. Success 이벤트 (배포 완료)
```
event: success
data: {
  "message": "Deployment completed successfully"
}
```

**설명**: 배포가 성공적으로 완료됨을 알립니다.
- 이 이벤트 이후 5초 뒤 SSE 연결 자동 종료
- 클라이언트는 `eventSource.close()` 호출 필요

**예제**:
```
id: 550e8400-e29b-41d4-a716-446655440050
event: success
data: {"message":"Deployment completed successfully"}
reconnect: 5000
```

##### 4. Fail 이벤트 (배포 실패)
```
event: fail
data: {
  "message": "Deployment failed: Docker build failed: ..."
}
```

**설명**: 배포 중 예외 발생을 알립니다.
- 이 이벤트 이후 5초 뒤 SSE 연결 자동 종료
- 클라이언트는 `eventSource.close()` 호출 필요

**예제**:
```
id: 550e8400-e29b-41d4-a716-446655440051
event: fail
data: {"message":"Deployment timed out at Stage 1 after 605 seconds"}
reconnect: 5000
```

### 연결 특성
- **연결 유지**: 배포 완료 또는 실패 후 5초
- **자동 재연결**: 크롬은 자동으로 재연결 시도 (3초 간격)
- **타임아웃**: 5분 (서버 측)



### 주의사항
- **연결 확인**: Connected 이벤트를 받으면 SSE 연결이 안정적으로 이루어짐을 확인
- **이벤트 순서**: Connected → (Stage 이벤트들...) → Success/Fail
- **연결 유지**: SSE 연결은 Success/Fail 이벤트 발행 후 5초 경과 시 자동 종료
- **히스토리**: 신규 클라이언트가 연결하면 Connected 이후 과거 배포 이벤트 자동 전송
- **순서 보장**: 이벤트는 발생 순서대로 전송됨

---

## 6️⃣ 배포 전환 실행 (수동 확인)

### 엔드포인트
```
POST /api/v1/deploy/{deploymentId}/switch
```

### 설명
Stage 4에서 Green 서비스 배포가 완료되면, 사용자가 준비 상태를 확인한 후 이 API를 호출하여 트래픽을 Blue에서 Green으로 전환합니다.
이를 통해 Blue/Green 배포의 마지막 단계를 수동으로 제어할 수 있습니다.

### 요청

#### Path Parameters
| 파라미터 | 타입 | 필수 | 설명 | 예제 |
|---------|------|------|------|------|
| **deploymentId** | String | ✅ | 배포 ID | `"dep_k1l2m3n4o5"` |

#### Headers
```
Content-Type: application/json
```

### 응답

#### 성공 (200)
```json
{
  "code": 200,
  "message": "배포 전환이 시작되었습니다.",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "message": "Traffic switching from blue to green in progress",
    "activeService": "green"
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **code** | Integer | HTTP 상태 코드 |
| **message** | String | 성공 메시지 |
| **data.deploymentId** | String | 배포 ID |
| **data.message** | String | 전환 상태 메시지 |
| **data.activeService** | String | 활성 서비스 (`green`) |

#### 실패 - 이미 전환됨 (400)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "Traffic has already been switched to green"
}
```

#### 실패 - 배포 미완료 (400)
```json
{
  "timestamp": "2024-01-01T12:00:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "Deployment is not ready for traffic switch. Current stage: 3"
}
```

### 가능한 에러
| 에러 메시지 | 원인 | 해결 방법 |
|-----------|------|---------|
| `Deployment not found` | deploymentId 유효하지 않음 | deploymentId 확인 |
| `Traffic has already been switched` | 이미 전환됨 | 배포 완료, 재전환 불가 |
| `Deployment is not ready` | Stage 4 미완료 | Stage 4 완료 후 재시도 |
| `Traffic switch failed` | 트래픽 전환 중 오류 | AWS IAM 권한 확인, 재시도 |

### 전환 프로세스
1. Green 서비스 HealthCheck 실행 (자동)
2. HealthCheck 성공 확인
3. Application Load Balancer (ALB) Target Group 수정
4. Blue → Green 트래픽 전환 완료
5. 배포 상태 업데이트

---

## 7️⃣ 배포 최종 결과 조회

### 엔드포인트
```
GET /api/v1/deploy/{deploymentId}/result
```

### 설명
배포의 최종 결과를 조회합니다.
- Stage 4 완료 후: 배포 준비 완료, 수동 전환 대기 상태
- 수동 전환 완료 후: 배포 완료 상태

### 요청

#### Path Parameters
| 파라미터 | 타입 | 필수 | 설명 | 예제 |
|---------|------|------|------|------|
| **deploymentId** | String | ✅ | 배포 ID | `"dep_k1l2m3n4o5"` |

### 응답

#### 성공 - Stage 4 완료 (배포 준비 완료, 전환 대기)
```json
{
  "code": 200,
  "message": "배포 결과 조회 성공",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "status": "DEPLOYMENT_READY",
    "owner": "your-org",
    "repo": "your-repo",
    "branch": "main",
    "startedAt": "2024-01-01T12:00:00",
    "completedAt": "2024-01-01T12:08:30",
    "durationSeconds": 510,
    "finalService": null,
    "blueUrl": "http://blue.example.com",
    "greenUrl": "http://green.example.com",
    "errorMessage": null,
    "blueLatencyMs": null,
    "greenLatencyMs": null,
    "blueErrorRate": null,
    "greenErrorRate": null,
    "eventCount": 35
  }
}
```

#### 성공 - 전환 완료 (배포 완료)
```json
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
    "completedAt": "2024-01-01T12:09:00",
    "durationSeconds": 540,
    "finalService": "green",
    "blueUrl": "http://blue.example.com",
    "greenUrl": "http://green.example.com",
    "errorMessage": null,
    "blueLatencyMs": 250,
    "greenLatencyMs": 180,
    "blueErrorRate": 0.01,
    "greenErrorRate": 0.005,
    "eventCount": 45
  }
}
```

#### 응답 필드
| 필드 | 타입 | 설명 |
|------|------|------|
| **deploymentId** | String | 배포 ID |
| **status** | String | 배포 상태 (`DEPLOYMENT_READY`: 전환 대기, `COMPLETED`: 완료, `FAILED`: 실패) |
| **owner** | String | GitHub 조직명 |
| **repo** | String | GitHub 레포 명 |
| **branch** | String | 배포한 브랜치 |
| **startedAt** | DateTime | 배포 시작 시간 |
| **completedAt** | DateTime | Stage 4 또는 전환 완료 시간 |
| **durationSeconds** | Long | 배포 소요 시간 (초) |
| **finalService** | String | 활성 서비스 (`blue`, `green`, null: 전환 대기 중) |
| **blueUrl** | String | Blue 서비스 URL |
| **greenUrl** | String | Green 서비스 URL |
| **errorMessage** | String | 배포 실패 시 에러 메시지 (null이면 성공) |
| **blueLatencyMs** | Long | Blue 서비스 응답 시간 (ms, 전환 완료 후만) |
| **greenLatencyMs** | Long | Green 서비스 응답 시간 (ms, 전환 완료 후만) |
| **blueErrorRate** | Double | Blue 서비스 에러율 (0.0 ~ 1.0, 전환 완료 후만) |
| **greenErrorRate** | Double | Green 서비스 에러율 (0.0 ~ 1.0, 전환 완료 후만) |
| **eventCount** | Integer | 발행된 이벤트 개수 |

#### 배포 실패 (200 with FAILED status)
```json
{
  "code": 200,
  "message": "배포 결과 조회 성공",
  "data": {
    "deploymentId": "dep_k1l2m3n4o5",
    "status": "FAILED",
    "owner": "your-org",
    "repo": "your-repo",
    "branch": "main",
    "startedAt": "2024-01-01T12:00:00",
    "completedAt": "2024-01-01T12:05:20",
    "durationSeconds": 320,
    "finalService": null,
    "blueUrl": "http://blue.example.com",
    "greenUrl": null,
    "errorMessage": "Dockerfile not found in repository",
    "blueLatencyMs": null,
    "greenLatencyMs": null,
    "blueErrorRate": null,
    "greenErrorRate": null,
    "eventCount": 5
  }
}
```

#### 결과 미발견 (400)
```json
{
  "timestamp": "2024-01-01T12:20:00",
  "status": 400,
  "error": "Deployment Error",
  "message": "Deployment result not found: dep_invalid"
}
```

---

# SSE 스트리밍 상세

## 이벤트 타입별 페이로드

### Connected 이벤트 (SSE 연결 확립)

#### 연결 확립
```json
{
  "message": "SSE connection established"
}
```

**발생 시점**: 클라이언트가 SSE 연결 요청 직후 (가장 먼저 수신)

**클라이언트 처리**:
```javascript
eventSource.addEventListener('connected', (event) => {
  console.log('SSE 연결 성공:', JSON.parse(event.data).message);
  // UI: 연결 표시
});
```

---

### Stage 1: Dockerfile 탐색 및 Docker Build

#### Stage 시작
```json
{
  "type": "stage",
  "message": "[Stage 1] Dockerfile 탐색 및 Docker Build - Repository 클론 중...",
  "details": {
    "stage": 1
  }
}
```

#### Repository 클론 완료
```json
{
  "type": "stage",
  "message": "[Stage 1] Repository 클론 완료",
  "details": {
    "stage": 1,
    "path": "/tmp/deployment_1704067200"
  }
}
```

#### Dockerfile 찾음
```json
{
  "type": "stage",
  "message": "[Stage 1] Dockerfile 찾음",
  "details": {
    "stage": 1,
    "path": "/tmp/deployment_1704067200/Dockerfile"
  }
}
```

#### Docker 빌드 완료
```json
{
  "type": "stage",
  "message": "[Stage 1] Docker 이미지 빌드 완료",
  "details": {
    "stage": 1,
    "imageName": "your-org-your-repo-main-1704067200"
  }
}
```

### Stage 2: ECR Push

#### Stage 시작
```json
{
  "type": "stage",
  "message": "[Stage 2] ECR에 이미지 Push 중 - ECR로 이미지 Push 중...",
  "details": {
    "stage": 2
  }
}
```

#### ECR 리포지토리 확인
```json
{
  "type": "stage",
  "message": "[Stage 2] ECR 리포지토리 확인 완료",
  "details": {
    "stage": 2,
    "repository": "your-org-your-repo"
  }
}
```

#### ECR 로그인 완료
```json
{
  "type": "stage",
  "message": "[Stage 2] ECR 로그인 완료",
  "details": {
    "stage": 2
  }
}
```

#### 이미지 Push 완료
```json
{
  "type": "stage",
  "message": "[Stage 2] 이미지 Push 완료",
  "details": {
    "stage": 2,
    "uri": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:your-org-your-repo-main-1704067200"
  }
}
```

### Stage 3: ECS 배포

#### Stage 시작
```json
{
  "type": "stage",
  "message": "[Stage 3] ECS 배포 시작",
  "details": {
    "stage": 3,
    "image": "123456789012.dkr.ecr.ap-northeast-2.amazonaws.com/your-org-your-repo:..."
  }
}
```

#### 서비스 생성 완료
```json
{
  "type": "stage",
  "message": "[Stage 3] ECS 서비스 생성 완료",
  "details": {
    "stage": 3,
    "serviceName": "panda-service",
    "clusterName": "panda-cluster"
  }
}
```

#### 서비스 업데이트 완료
```json
{
  "type": "stage",
  "message": "[Stage 3] ECS 서비스 업데이트 완료",
  "details": {
    "stage": 3,
    "serviceName": "panda-service"
  }
}
```

### Stage 4: Blue/Green 배포

#### Stage 시작
```json
{
  "type": "stage",
  "message": "[Stage 4] CodeDeploy Blue/Green 배포 시작",
  "details": {
    "stage": 4,
    "image": "..."
  }
}
```

#### Blue 서비스 실행 중
```json
{
  "type": "stage",
  "message": "[Stage 4] Blue 서비스 실행 중",
  "details": {
    "stage": 4,
    "url": "http://blue.example.com"
  }
}
```

#### Green 서비스 시작
```json
{
  "type": "stage",
  "message": "[Stage 4] Green 서비스 시작 중",
  "details": {
    "stage": 4,
    "url": "http://green.example.com"
  }
}
```

#### Green 서비스 준비 완료
```json
{
  "type": "stage",
  "message": "[Stage 4] Green 서비스 준비 완료",
  "details": {
    "stage": 4,
    "url": "http://green.example.com"
  }
}
```

#### Lifecycle Hook
```json
{
  "type": "stage",
  "message": "[Stage 4] CodeDeploy Lifecycle Hook: BeforeAllowTraffic",
  "details": {
    "stage": 4
  }
}
```

```json
{
  "type": "stage",
  "message": "[Stage 4] CodeDeploy Lifecycle Hook: AfterAllowTraffic",
  "details": {
    "stage": 4
  }
}
```

#### Stage 4 완료 - 배포 준비 완료 (수동 전환 대기)
```json
{
  "type": "stage",
  "message": "[Stage 4] Green 서비스 배포 완료 - 트래픽 전환 대기 중",
  "details": {
    "stage": 4,
    "blueServiceArn": "arn:aws:ecs:ap-northeast-2:123456789012:service/panda-cluster/panda-blue",
    "greenServiceArn": "arn:aws:ecs:ap-northeast-2:123456789012:service/panda-cluster/panda-green",
    "blueUrl": "http://blue.example.com",
    "greenUrl": "http://green.example.com",
    "message": "POST /api/v1/deploy/{deploymentId}/switch를 호출하여 트래픽 전환을 진행하세요"
  }
}
```
