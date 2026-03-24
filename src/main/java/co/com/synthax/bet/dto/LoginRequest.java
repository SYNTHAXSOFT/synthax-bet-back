package co.com.synthax.bet.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String email;
    private String password;
}
