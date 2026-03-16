package co.com.synthax.pos.dto;

import co.com.synthax.pos.entity.Usuario;
import lombok.Data;

@Data
public class LoginResponse {
    private Usuario usuario;
    private String token;
    private String mensaje;
}
