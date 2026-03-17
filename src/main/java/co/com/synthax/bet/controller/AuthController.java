package co.com.synthax.bet.controller;

import co.com.synthax.bet.dto.LoginRequest;
import co.com.synthax.bet.dto.LoginResponse;
import co.com.synthax.bet.entity.Usuario;
import co.com.synthax.bet.service.JwtService;
import co.com.synthax.bet.service.UsuarioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UsuarioService usuarioService;
    private final JwtService jwtService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        try {
            // CAMBIO: usar cedula en lugar de email
            Usuario usuario = usuarioService.autenticarPorCedula(request.getCedula(), request.getPassword());
            String token = jwtService.generateToken(usuario);

            LoginResponse response = new LoginResponse();
            response.setUsuario(usuario);
            response.setToken(token);
            response.setMensaje("Login exitoso");

            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("mensaje", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error);
        }
    }
}