package co.com.synthax.bet.dto;

import co.com.synthax.bet.entity.Usuario;
import lombok.Data;

@Data
public class LoginResponse {
    private Usuario usuario;
    private String token;
    private String mensaje;
}
