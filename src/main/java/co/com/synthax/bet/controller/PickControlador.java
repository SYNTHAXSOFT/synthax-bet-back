package co.com.synthax.bet.controller;

import co.com.synthax.bet.entity.Pick;
import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.ResultadoPick;
import co.com.synthax.bet.service.PickServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/picks")
@RequiredArgsConstructor
public class PickControlador {

    private final PickServicio pickServicio;

    @GetMapping
    public ResponseEntity<List<Pick>> obtenerTodos() {
        return ResponseEntity.ok(pickServicio.obtenerTodos());
    }

    @GetMapping("/canal/{canal}")
    public ResponseEntity<List<Pick>> obtenerPorCanal(@PathVariable CanalPick canal) {
        return ResponseEntity.ok(pickServicio.obtenerPorCanal(canal));
    }

    @GetMapping("/pendientes")
    public ResponseEntity<List<Pick>> obtenerPendientes() {
        return ResponseEntity.ok(pickServicio.obtenerPendientes());
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerPorId(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(pickServicio.obtenerPorId(id));
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<?> crearPick(@RequestBody Pick pick) {
        try {
            return ResponseEntity.status(HttpStatus.CREATED).body(pickServicio.crearPick(pick));
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PatchMapping("/{id}/liquidar")
    public ResponseEntity<?> liquidarPick(@PathVariable Long id,
                                          @RequestParam ResultadoPick resultado) {
        try {
            return ResponseEntity.ok(pickServicio.liquidarPick(id, resultado));
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}
