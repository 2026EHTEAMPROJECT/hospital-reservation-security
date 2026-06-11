package com.hospital.login.controller;

import com.hospital.login.dto.RegisterRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keycloak Admin API 를 통해 realm hospital 에 사용자를 생성하는 회원가입 엔드포인트.
 * 프론트의 register.html 폼에서 호출한다.
 */
@RestController
public class RegisterController {

    private static final Logger log = LoggerFactory.getLogger(RegisterController.class);

    private final RestClient restClient;

    @Value("${keycloak.base-url}")
    private String baseUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.admin.token-uri}")
    private String adminTokenUri;

    @Value("${keycloak.admin.client-id}")
    private String adminClientId;

    @Value("${keycloak.admin.username}")
    private String adminUsername;

    @Value("${keycloak.admin.password}")
    private String adminPassword;

    public RegisterController(RestClient restClient) {
        this.restClient = restClient;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/api/register")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        // 1) master realm 의 admin-cli 로 관리자 토큰 획득
        String adminToken;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("client_id", adminClientId);
            form.add("grant_type", "password");
            form.add("username", adminUsername);
            form.add("password", adminPassword);

            Map<String, Object> tokenResponse = restClient.post()
                    .uri(adminTokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            if (tokenResponse == null || tokenResponse.get("access_token") == null) {
                return error(HttpStatus.BAD_GATEWAY, "auth_server_error");
            }
            adminToken = (String) tokenResponse.get("access_token");
        } catch (RestClientResponseException | ResourceAccessException e) {
            log.warn("Admin token 획득 실패: {}", e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "auth_server_error");
        }

        // 2) realm hospital 에 사용자 생성
        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", request.password());
        credential.put("temporary", false);

        // realm hospital 의 User Profile 은 firstName, lastName 을 모두 필수로 둔다.
        // 회원가입 폼은 이름 1개만 받으므로 lastName 이 비면 사용자는 만들어지지만
        // 로그인 시 VERIFY_PROFILE 이 걸려 "Account is not fully set up"(invalid_grant)
        // 으로 막힌다. 그래서 단일 이름을 firstName/lastName 으로 나눠 둘 다 채운다.
        //   - 공백이 있으면 앞=firstName, 뒤=lastName ("John Doe" → John / Doe)
        //   - 공백이 없으면(한국 이름 등) 같은 값을 양쪽에 넣어 필수 검증을 통과시킨다.
        String fullName = request.name() == null ? "" : request.name().trim();
        String firstName = fullName;
        String lastName = fullName;
        int sp = fullName.indexOf(' ');
        if (sp > 0) {
            firstName = fullName.substring(0, sp).trim();
            lastName = fullName.substring(sp + 1).trim();
        }

        Map<String, Object> user = new HashMap<>();
        user.put("username", request.email());
        user.put("email", request.email());
        user.put("firstName", firstName);
        user.put("lastName", lastName);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("credentials", List.of(credential));

        try {
            restClient.post()
                    .uri(baseUrl + "/admin/realms/" + realm + "/users")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity();

            Map<String, Object> body = new HashMap<>();
            body.put("status", "registered");
            body.put("email", request.email());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 409) {
                log.info("이미 존재하는 사용자: {}", request.email());
                return error(HttpStatus.CONFLICT, "user_already_exists");
            }
            log.warn("Keycloak 사용자 생성 실패: status={}", status);
            return error(HttpStatus.BAD_GATEWAY, "auth_server_error");
        } catch (ResourceAccessException e) {
            log.warn("Keycloak 연결 오류: {}", e.getMessage());
            return error(HttpStatus.BAD_GATEWAY, "auth_server_error");
        }
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", code);
        return ResponseEntity.status(status).body(error);
    }
}
