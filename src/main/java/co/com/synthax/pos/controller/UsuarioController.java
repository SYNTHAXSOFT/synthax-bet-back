package co.com.synthax.pos.controller;

import co.com.synthax.pos.entity.Usuario;
import co.com.synthax.pos.service.UsuarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/usuarios")
@RequiredArgsConstructor
public class UsuarioController {

    private final UsuarioService usuarioService;

    // 👇 MODIFICADO: Ahora recibe el ID del usuario logueado en el header
    @PostMapping
    public ResponseEntity<?> crearUsuario(
            @Valid @RequestBody Usuario usuario,
            @RequestHeader("Usuario-Id") Long usuarioLogueadoId) {
        try {
            Usuario nuevoUsuario = usuarioService.crearUsuario(usuario, usuarioLogueadoId);
            return ResponseEntity.status(HttpStatus.CREATED).body(nuevoUsuario);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    // 👇 NUEVO ENDPOINT: Listar usuarios filtrados por rol del usuario logueado
    @GetMapping("/filtrado")
    public ResponseEntity<List<Usuario>> obtenerUsuariosFiltrados(
            @RequestHeader("Usuario-Id") Long usuarioLogueadoId) {
        List<Usuario> usuarios = usuarioService.obtenerUsuariosPorRolUsuario(usuarioLogueadoId);
        return ResponseEntity.ok(usuarios);
    }

    @GetMapping
    public ResponseEntity<List<Usuario>> obtenerTodosLosUsuarios() {
        List<Usuario> usuarios = usuarioService.obtenerTodosLosUsuarios();
        return ResponseEntity.ok(usuarios);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerUsuarioPorId(@PathVariable("id") Long id) {
        try {
            Usuario usuario = usuarioService.obtenerUsuarioPorId(id);
            return ResponseEntity.ok(usuario);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarUsuario(@PathVariable("id") Long id, @Valid @RequestBody Usuario usuario) {
        try {
            Usuario usuarioActualizado = usuarioService.actualizarUsuario(id, usuario);
            return ResponseEntity.ok(usuarioActualizado);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarUsuario(@PathVariable Long id) {
        try {
            usuarioService.eliminarUsuario(id);
            Map<String, String> respuesta = new HashMap<>();
            respuesta.put("mensaje", "Usuario eliminado correctamente");
            return ResponseEntity.ok(respuesta);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PatchMapping("/{id}/desactivar")
    public ResponseEntity<?> desactivarUsuario(@PathVariable Long id) {
        try {
            Usuario usuario = usuarioService.desactivarUsuario(id);
            return ResponseEntity.ok(usuario);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @GetMapping("/rolActivo/{rol}")
    public ResponseEntity<List<Usuario>> obtenerUsuariosPorRolActivos(@PathVariable String rol) {
        List<Usuario> usuarios = usuarioService.obtenerUsuariosPorRolActivos(rol);
        return ResponseEntity.ok(usuarios);
    }

}