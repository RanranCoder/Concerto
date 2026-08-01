package com.pams.dto;

import lombok.Data;

import java.util.Map;

@Data
public class LoginResponse {
    private String token;
    private Map<String, Object> user;
}
