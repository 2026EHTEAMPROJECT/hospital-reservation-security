# 배포 & 브라우저 연동 확인 가이드

hospital-reservation 마이크로서비스(Keycloak + Istio 보안 적용)를 Kubernetes에 배포하고,
각 서비스가 잘 연동되는지 **브라우저로** 확인하는 방법 + 자주 쓰는 명령어 모음.

---

## 0. 구성 요약

| 구성요소 | 네임스페이스 | 포트 | 게이트웨이 경로 | 비고 |
|---|---|---|---|---|
| login-service | hospital-dev | 8085 | `/api/login` | Keycloak 프론팅 로그인 BFF |
| user-service | hospital-dev | 8081 | `/api/users`, `/api/doctors` | 프로필/의사, 프론트(index.html) |
| booking-service | hospital-dev | 8082 | `/api/reservations` | 예약 + 이벤트 발행 |
| payment-service | hospital-dev | 8083 | `/payments` | 결제(모의) |
| notification-service | hospital-dev | 8084 | (게이트웨이 라우트 없음 → port-forward) | SSE 알림 |
| Keycloak | hospital-dev | 8080 | host `keycloak.local` | realm `hospital` |
| RabbitMQ | hospital-dev | 5672 / 15672 | - | 메시지 브로커 |
| MySQL ×3 | hospital-dev | 3306 | - | user/booking/payment |

- realm: `hospital` / client: `hospital-frontend`
- 데모 계정: `patient1` / `doctor1` / `admin1` (pw `pass1234`)
- Keycloak admin: `admin` / `admin123`

---

# Part 1. 🌐 브라우저로 서비스 연동 확인하기

전체 흐름: **로그인(login-service→Keycloak) → 토큰 → 예약(booking) → 결제(payment) → 알림(notification)**.
각 단계에서 브라우저 주소창 + 개발자도구(F12) Network/Console로 연동을 확인한다.

> 사전: 아래 Part 2로 배포 완료 + Istio Gateway 외부 IP(또는 `kubectl port-forward`)를 확보했다고 가정.
> 게이트웨이 진입점을 `$GW`라 한다. (예: `http://localhost:8080` 으로 port-forward 한 경우)

### 1-1. Keycloak이 떠 있는지 (인증 서버)
```bash
kubectl -n hospital-dev port-forward svc/keycloak 8080:8080
```
- 브라우저 `http://localhost:8080/realms/hospital/.well-known/openid-configuration`
- → JSON(issuer, jwks_uri, token_endpoint 등)이 보이면 ✅ Keycloak realm 정상.
- 관리 콘솔 `http://localhost:8080/` (admin/admin123) → realm `hospital` → Users에 patient1/doctor1/admin1 확인.

### 1-2. 로그인 → 토큰 발급 (login-service ↔ Keycloak)
방법 A) 브라우저 로그인 페이지:
- login-service 화면: `kubectl -n hospital-dev port-forward svc/login-service 8085:8085` → `http://localhost:8085/login.html`
- patient1 / pass1234 로그인 → 화면에 `access_token`이 표시되면 ✅ (login-service가 Keycloak password grant 호출 성공).

방법 B) 개발자도구로 확인(권장):
- 프론트(`http://localhost:8081/`)에서 로그인 시도 → F12 → Network → `/api/login` 요청 확인
- 응답 200 + `access_token` 필드 → ✅ login-service 연동 정상
- 401 `invalid_credentials` → 비밀번호 오류(정상 동작), 502 `auth_server_error` → Keycloak 미연결

### 1-3. user 프론트 → 대시보드 (user-service)
- `http://localhost:8081/` (또는 `$GW/`) 접속 → 로그인 후 대시보드로 이동
- 상단 인사말에 사용자명(JWT의 name/preferred_username)이 보이면 ✅ 토큰 디코드/검증 연동 정상
- F12 → Application → Local Storage에 `hospital_token` 저장 확인
- 의사 목록이 뜨면 `/api/doctors`(user-service) 연동 ✅

### 1-4. 예약 생성 (booking-service, 토큰 검증 + 서비스간 호출)
- 의사 선택 → 날짜/시간 → 예약 → F12 Network에서 `POST /api/reservations` 요청 헤더에 `Authorization: Bearer ...` 포함 확인
- 응답 200/201 → ✅ booking이 Keycloak 토큰 검증 통과 + 예약 생성
- 만약 401/403 → Istio AuthorizationPolicy 또는 토큰 누락. (토큰 없이 호출 시 차단되는 게 정상)
- booking 로그에서 환자/의사 이름이 "환자/담당의" 기본값이 아니라 실제 이름이면 → Feign 토큰 릴레이로 user-service 조회까지 ✅

### 1-5. 결제 (payment-service)
- 예약 시 결제 모달 → 결제 → booking이 RabbitMQ로 `payment.request` 발행 → payment가 소비
- 확인: `kubectl -n hospital-dev logs deploy/payment-service | grep -i payment` 에 결제 처리 로그
- 브라우저: `GET /payments?patientId=...` (port-forward 8083) 응답에 결제 레코드 → ✅

### 1-6. 실시간 알림 (notification-service, SSE)
- notification은 게이트웨이 외부 라우트가 없으므로 port-forward:
```bash
kubectl -n hospital-dev port-forward svc/notification-service 8084:8084
```
- 브라우저 콘솔에서:
```js
const es = new EventSource('http://localhost:8084/notifications/stream?patientId=1');
es.onmessage = e => console.log('알림:', e.data);
```
- 예약/결제를 일으키면 콘솔에 알림 이벤트가 찍히면 ✅ (booking/payment → RabbitMQ → notification → SSE 연동)
- ⚠️ 현재 SSE는 브라우저 EventSource가 Authorization 헤더를 못 실어 인증 보호가 제한적(P1 후속). 데모는 port-forward로 확인.

### 1-7. 메시지 흐름 확인 (RabbitMQ)
```bash
kubectl -n hospital-dev port-forward svc/rabbitmq-service 15672:15672
```
- `http://localhost:15672` (guest/guest) → Queues 탭 → `booking.notification.queue`, `payment.notification.queue`, `booking.payment.queue`의 메시지 추이로 이벤트 흐름 확인.

### 1-8. 서비스 메시 토폴로지 (Kiali) — 연동 한눈에 보기
```bash
istioctl dashboard kiali
```
- Graph → namespace `hospital-dev` → login/user/booking/payment/notification 간 트래픽 화살표, mTLS 자물쇠, AuthorizationPolicy 적용 여부를 시각적으로 확인 → ✅ 전체 연동 검증.

---

# Part 2. ⌨️ Kubernetes 배포 & 기술스택 주소 명령어 모음

### 2-1. 클러스터 & Istio 준비
```bash
# (로컬) 클러스터 기동 — 예: minikube
minikube start --cpus 4 --memory 8192
minikube addons enable ingress   # 필요 시

# Istio 설치
istioctl install --set profile=demo -y
kubectl label namespace hospital-dev istio-injection=enabled --overwrite

# Istio 애드온(관측성: kiali/grafana/prometheus/jaeger)
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.29/samples/addons/kiali.yaml
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.29/samples/addons/prometheus.yaml
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.29/samples/addons/grafana.yaml
kubectl apply -f https://raw.githubusercontent.com/istio/istio/release-1.29/samples/addons/jaeger.yaml

# ArgoCD 설치
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

### 2-2. 이미지 빌드 & 푸시 (각 서비스)
```bash
# 예: login-service (다른 서비스도 동일 패턴, 태그는 커밋 SHA 권장)
cd hospital-reservation-security/login-service
docker build -t edenriel/hospital-login:latest .
docker push edenriel/hospital-login:latest
# user/booking/payment/notification 도 각 디렉토리에서 동일하게 build & push
```

### 2-3. 배포 (kustomize / ArgoCD)
```bash
# 네임스페이스
kubectl create namespace hospital-dev
kubectl label namespace hospital-dev istio-injection=enabled --overwrite

# 직접 배포 (kustomize)
kubectl apply -k hospital-reservation-ops/user/overlays/dev
kubectl apply -k hospital-reservation-ops/booking/overlays/dev
kubectl apply -k hospital-reservation-ops/payment/overlays/dev
kubectl apply -k hospital-reservation-ops/notification/overlays/dev
kubectl apply -k hospital-reservation-ops/infra            # gateway, rabbitmq
kubectl apply -k hospital-reservation-security             # keycloak + login-service + istio 보안정책

# 또는 ArgoCD로 GitOps 동기화
kubectl apply -f hospital-reservation-ops/argocd/           # 서비스 Application들
kubectl apply -f hospital-reservation-security/argocd/security-app.yaml
```

### 2-4. 배포 상태 확인
```bash
kubectl -n hospital-dev get pods -o wide
kubectl -n hospital-dev get svc
kubectl -n hospital-dev get deploy
kubectl -n hospital-dev rollout status deploy/login-service
kubectl -n hospital-dev get virtualservice,gateway,authorizationpolicy,requestauthentication,peerauthentication
istioctl analyze -n hospital-dev          # 메시 설정 정합성 점검
```

### 2-5. 기술스택 콘솔 주소 (port-forward 모음)
```bash
# ArgoCD (GitOps)       → https://localhost:8080  (id: admin)
kubectl -n argocd port-forward svc/argocd-server 8080:443
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d; echo

# Keycloak (인증)        → http://localhost:8080  (admin/admin123) realm: hospital
kubectl -n hospital-dev port-forward svc/keycloak 8080:8080

# Kiali (메시 토폴로지)   → http://localhost:20001
istioctl dashboard kiali

# Grafana (메트릭)        → http://localhost:3000
istioctl dashboard grafana

# Prometheus             → http://localhost:9090
istioctl dashboard prometheus

# Jaeger (트레이싱)       → http://localhost:16686
istioctl dashboard jaeger

# RabbitMQ (관리콘솔)     → http://localhost:15672 (guest/guest)
kubectl -n hospital-dev port-forward svc/rabbitmq-service 15672:15672

# Istio Ingress Gateway 진입점 (앱 외부 접속)
kubectl -n istio-system get svc istio-ingressgateway
kubectl -n istio-system port-forward svc/istio-ingressgateway 8080:80   # → http://localhost:8080

# 개별 서비스 직접 접근 (디버깅용)
kubectl -n hospital-dev port-forward svc/login-service 8085:8085
kubectl -n hospital-dev port-forward svc/user-service 8081:8081
kubectl -n hospital-dev port-forward svc/booking-service 8082:8082
kubectl -n hospital-dev port-forward svc/payment-service 8083:8083
kubectl -n hospital-dev port-forward svc/notification-service 8084:8084
```

### 2-6. 토큰 발급 & API 테스트 (CLI)
```bash
# Keycloak에서 액세스 토큰 발급 (login-service와 동일 흐름)
TOKEN=$(curl -s -X POST \
  http://localhost:8080/realms/hospital/protocol/openid-connect/token \
  -d "client_id=hospital-frontend" -d "grant_type=password" \
  -d "username=patient1" -d "password=pass1234" | jq -r .access_token)
echo "$TOKEN"

# login-service 경유 발급
curl -s -X POST http://localhost:8085/api/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"patient1","password":"pass1234"}' | jq

# 보호 API 호출 (게이트웨이 경유). 토큰 없으면 403, 있으면 통과.
curl -i http://localhost:8080/api/reservations                       # → 403 (토큰 없음)
curl -i -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/reservations   # → 200
```

### 2-7. 디버깅 / 로그
```bash
kubectl -n hospital-dev logs deploy/login-service -f
kubectl -n hospital-dev logs deploy/booking-service -f
kubectl -n hospital-dev logs deploy/keycloak -f
kubectl -n hospital-dev describe pod -l app=login-service

# Istio 사이드카 주입 확인 (각 pod에 2/2 READY 여야 함)
kubectl -n hospital-dev get pods
# 특정 워크로드의 적용된 authz 정책 확인
istioctl x authz check <pod-name> -n hospital-dev
```

---

## 빠른 연동 확인 체크리스트
- [ ] `/.well-known/openid-configuration` 200 → Keycloak ✅
- [ ] `POST /api/login` 200 + access_token → login-service↔Keycloak ✅
- [ ] 프론트 로그인 후 인사말에 이름 표시 → user 토큰 검증 ✅
- [ ] `POST /api/reservations` (Bearer) 200 → booking 검증 ✅
- [ ] 토큰 없이 호출 시 403 → Istio AuthorizationPolicy ✅
- [ ] RabbitMQ 큐 메시지 증가 → 이벤트 흐름 ✅
- [ ] SSE 콘솔에 알림 수신 → notification ✅
- [ ] Kiali 그래프에 mTLS 자물쇠 + 트래픽 → 메시 보안 ✅
