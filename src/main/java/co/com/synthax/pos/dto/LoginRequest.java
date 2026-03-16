package co.com.synthax.pos.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String cedula;
    private String password;
}
