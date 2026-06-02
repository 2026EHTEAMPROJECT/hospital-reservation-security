# hospital-reservation-security

hospital-reservation 마이크로서비스의 **인증·인가 보안 계층**을 담는 GitOps 레포지토리.
강의자료 9강(API 게이트웨이)와 참조 프로젝트(authserver/loginserver)의 패턴을
**Keycloak(인증 서버) + Istio(게이트웨이 검증)** 로 production화한 것이다.

## 왜 이 레포가 필요한가

기존 hospital-reservation의 가장 큰 보안 구멍:
- user-service가 JWT를 **발급만 하고 시스템 어디서도 검증하지 않음** → 모든 API 무인증 개방(IDOR 포함)

이 레포는 그 구멍을 **메시 레벨에서** 메운다.

## 참조 패턴 → 현재 스택 매핑

| 예시(authserver/loginserver) | 이 레포의 대체 |
|---|---|
| authserver — 자체 JWT 발급 | **Keycloak** (realm `hospital`, OIDC 토큰 발급) |
| nginx `auth-url` forward-auth 검증 | **Istio `RequestAuthentication`** (Keycloak JWKS로 사이드카 로컬 검증) |
| (검증 후 통과) | **Istio `AuthorizationPolicy`** (유효 JWT/역할 요구) |
| nginx 평문 통과 | **Istio `PeerAuthentication`** (서비스 간 mTLS, 현재 PERMISSIVE / STRICT는 P2) |
| ingress.yaml 라우팅 | ops 레포의 `hospital-gateway` 재사용 + Keycloak용 `keycloak-vs` |

각 Spring 서비스는 `spring-boot-starter-oauth2-resource-server`로 **2차 방어**(앱 레벨)도 수행한다(서비스 레포별 변경).

## 인증 흐름

```
[브라우저] --(1) 로그인--> Keycloak (realm: hospital, client: hospital-frontend)
     |  (2) JWT(access token) 수신
     v
[Istio Ingress Gateway] --(3) RequestAuthentication: JWKS로 서명/만료/issuer 검증
     |                        AuthorizationPolicy: 유효 토큰/역할 요구
     v
[user / booking / payment / notification]  --(4) oauth2-resource-server 2차 검증
     ↕ PeerAuthentication: 서비스 간 mTLS
```

## 구성 파일

```
keycloak/keycloak.yaml            Keycloak Deployment+Service (start-dev, --import-realm)
keycloak/keycloak-gateway.yaml    Keycloak 외부 노출 VirtualService (host: keycloak.local)
keycloak/realm/hospital-realm.json realm/client/role/user 부트스트랩
istio/peer-authentication.yaml    mTLS (PERMISSIVE 시작, STRICT는 P2)
istio/request-authentication.yaml Keycloak JWKS JWT 검증
istio/authorization-policy.yaml   서비스별 인가 규칙(역할 포함)
argocd/security-app.yaml          ArgoCD Application
kustomization.yaml                전체 묶음 + realm ConfigMap 생성
```

## 핵심 설정값

| 항목 | 값 |
|---|---|
| realm | `hospital` |
| issuer-uri | `http://keycloak.hospital-dev.svc.cluster.local:8080/realms/hospital` |
| jwks-uri | `.../realms/hospital/protocol/openid-connect/certs` |
| public client | `hospital-frontend` (Standard + Direct Access Grants) |
| roles | `PATIENT`, `DOCTOR`, `ADMIN` |
| demo users | `patient1` / `doctor1` / `admin1` (pw: `pass1234`) |
| Keycloak admin | `admin` / `admin123` |

> **issuer 일관성 주의**: Keycloak `KC_HOSTNAME`을 클러스터 내부 DNS로 고정해
> 토큰의 `iss`가 항상 위 issuer-uri와 일치하도록 했다. 외부 호스트로 접속해도
> `iss`는 내부 DNS로 발급되므로 Istio/Spring 검증과 어긋나지 않는다.
> 운영에서는 공개 도메인 + TLS로 `KC_HOSTNAME`을 재설정해야 한다.

## 배포

```bash
# 사전: istio 설치 + sidecar injection 활성화
kubectl label namespace hospital-dev istio-injection=enabled

# 미리보기
kustomize build .

# 직접 적용(또는 ArgoCD로 동기화)
kubectl apply -k .
```

ArgoCD 사용 시: 이 레포를 GitHub(`2026EHTEAMPROJECT/hospital-reservation-security`)에
생성·푸시한 뒤 `kubectl apply -f argocd/security-app.yaml`.

---

## 🌐 브라우저로 기술스택 보기 (접속 가이드)

모든 콘솔은 기본적으로 ClusterIP라 `port-forward` 또는 게이트웨이로 노출한다.

### 1. ArgoCD (GitOps 배포 현황)
```bash
kubectl -n argocd port-forward svc/argocd-server 8080:443
# 초기 admin 비밀번호
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d
```
→ 브라우저 https://localhost:8080 (id: `admin`)

### 2. Keycloak (인증 서버 / realm·user 관리)
```bash
kubectl -n hospital-dev port-forward svc/keycloak 8080:8080
```
→ http://localhost:8080 (admin/admin123) → realm `hospital` 선택

### 3. Kiali (Istio 서비스 메시 토폴로지)
```bash
istioctl dashboard kiali
# 또는
kubectl -n istio-system port-forward svc/kiali 20001:20001
```
→ http://localhost:20001 — 트래픽 흐름, mTLS 잠금 아이콘, AuthorizationPolicy 적용 확인

### 4. Grafana (메트릭 대시보드)
```bash
istioctl dashboard grafana
```
→ http://localhost:3000

### 5. Prometheus (메트릭 원본)
```bash
istioctl dashboard prometheus
```
→ http://localhost:9090

### 6. Jaeger (분산 트레이싱)
```bash
istioctl dashboard jaeger
```
→ http://localhost:16686

### 7. 토큰 발급 테스트 (CLI)
```bash
# Keycloak port-forward 상태에서
curl -s -X POST \
  http://localhost:8080/realms/hospital/protocol/openid-connect/token \
  -d "client_id=hospital-frontend" \
  -d "username=patient1" -d "password=pass1234" \
  -d "grant_type=password" | jq -r .access_token
```
→ 이 토큰을 `Authorization: Bearer <token>`로 게이트웨이에 보내면 통과,
   없으면 403(RBAC 차단)인지 확인.

---

## ⚠️ 알려진 한계 / 후속 작업

아키텍트 검증으로 식별된, 운영 전 처리해야 할 항목들이다.

### P1 (기능)
- **notification SSE 인증**: 브라우저 `EventSource`는 커스텀 `Authorization` 헤더를
  보낼 수 없다. `/notifications/stream`을 인증 보호하려면 쿼리파라미터 토큰 수신
  + 게이트웨이(hospital-vs)에 `/notifications` 라우트 추가가 필요하다. 현재 외부
  라우트가 없어 SSE는 클러스터 외부에서 도달 불가.
- **booking 역할(RBAC) 세분화**: 전체 예약 조회(ADMIN 전용 등) 역할 기반 인가는
  Keycloak protocol mapper로 `realm_access.roles`를 top-level claim으로 평탄화한 뒤
  AuthorizationPolicy `when` 절에 추가한다. (nested claim 매칭은 Istio에서 불안정해 보류)
- **user-service 자체 JWT 제거**: `JwtUtil`(HS256 self-issued)이 아직 남아 있다.
  로그인은 Keycloak token 엔드포인트로 이관하고, 이 self-token 발급은 제거해야 한다
  (지금은 그 토큰으로 보호 API 통과 불가 = 데드코드).
- **notification 등 컨슈머의 inter-service 호출**: HTTP 요청 컨텍스트가 없어 토큰
  릴레이가 불가하므로 `hospital-services`(client credentials) service-account 토큰을
  발급해 사용해야 한다. (현재는 실패 시 "환자/담당의" 폴백으로 graceful degradation)

### P2 (운영/보안 위생)
- **mTLS STRICT 전환**: 현재 PERMISSIVE. 모든 워크로드(특히 MySQL×3/RabbitMQ/Keycloak)
  사이드카 주입·헬스체크를 Kiali로 확인한 뒤 `peer-authentication.yaml`을 STRICT로.
- **시크릿 외부화**: Keycloak admin(`admin123`), realm의 service secret을 K8s Secret +
  환경변수로. realm JSON의 `redirectUris`/`webOrigins` 와일드카드(`*`)를 도메인 화이트리스트로.
- **Keycloak 영속성**: `start-dev`(H2)는 재시작 시 런타임 데이터(가입 사용자/세션) 소실.
  운영은 `start` + 외부 PostgreSQL + PVC로 전환.
- **Namespace ownership**: istio-injection 라벨이 ops 레포 namespace.yaml에 있다. 이
  보안 레포의 kustomization과 ArgoCD ownership이 겹치지 않도록 단일 출처로 관리.
