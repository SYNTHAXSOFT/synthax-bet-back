package co.com.synthax.bet.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String cedula;
    private String password;
}
