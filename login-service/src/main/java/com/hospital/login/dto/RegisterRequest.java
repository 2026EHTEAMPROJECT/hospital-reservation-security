package com.hospital.login.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 회원가입 요청 DTO.
 * email 을 Keycloak username 으로 사용한다(realm hospital 은 이메일 로그인 허용).
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 100) String email,
        @NotBlank @Size(min = 8, max = 200) String password,
        @NotBlank @Size(max = 50) String lastName,
        @NotBlank @Size(max = 50) String firstName,
        @NotBlank @Size(max = 20) String phoneNumber
) {
}
