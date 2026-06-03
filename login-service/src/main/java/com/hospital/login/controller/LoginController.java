package com.hospital.login.controller;

import com.hospital.login.dto.LoginRequest;
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
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.HashMap;
import java.util.Map;

@RestController
public class LoginController {

    private static final Logger log = LoggerFactory.getLogger(LoginController.class);

    private final RestClient restClient;

    @Value("${keycloak.token-uri}")
    private String tokenUri;

    @Value("${keycloak.client-id}")
    private String clientId;

    public LoginController(RestClient restClient) {
        this.restClient = restClient;
    }

    @SuppressWarnings("unchecked")
    @PostMapping("/api/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("grant_type", "password");
        form.add("username", request.username());
        form.add("password", request.password());

        try {
            Map<String, Object> keycloakResponse = restClient.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            Map<String, Object> filtered = new HashMap<>();
            if (keycloakResponse != null) {
                filtered.put("access_token", keycloakResponse.get("access_token"));
                filtered.put("token_type", keycloakResponse.get("token_type"));
                filtered.put("expires_in", keycloakResponse.get("expires_in"));
            }

            HttpHeaders headers = new HttpHeaders();
            headers.set("Cache-Control", "no-store");

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(filtered);

        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            log.warn("Keycloak authentication failed: status={}", status);

            Map<String, Object> error = new HashMap<>();
            if (status == 401 || status == 400) {
                error.put("error", "invalid_credentials");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
            } else {
                error.put("error", "auth_server_error");
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(error);
            }
        }
    }
}
