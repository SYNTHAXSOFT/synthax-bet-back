package co.com.synthax.bet.controller;

import co.com.synthax.bet.dto.EstadisticasPickDTO;
import co.com.synthax.bet.dto.PickResponseDTO;
import co.com.synthax.bet.dto.PublicarPickDTO;
import co.com.synthax.bet.dto.RendimientoResolucionDTO;
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
    public ResponseEntity<List<PickResponseDTO>> obtenerTodos() {
        return ResponseEntity.ok(pickServicio.obtenerTodos());
    }

    @GetMapping("/canal/{canal}")
    public ResponseEntity<List<PickResponseDTO>> obtenerPorCanal(@PathVariable CanalPick canal) {
        return ResponseEntity.ok(pickServicio.obtenerPorCanal(canal));
    }

    @GetMapping("/pendientes")
    public ResponseEntity<List<PickResponseDTO>> obtenerPendientes() {
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

    /**
     * POST /api/picks
     * Publica un nuevo pick desde una sugerencia del motor.
     * El body es PublicarPickDTO con los datos de la línea seleccionada.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping
    public ResponseEntity<?> crearPick(@RequestBody PublicarPickDTO dto) {
        try {
            PickResponseDTO pick = pickServicio.crearDesdeDTO(dto);
            return ResponseEntity.status(HttpStatus.CREATED).body(pick);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    /**
     * PATCH /api/picks/{id}/liquidar
     * Marca el resultado final de un pick: GANADO, PERDIDO o NULO.
     * Body: { "resultado": "GANADO" }
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PatchMapping("/{id}/liquidar")
    public ResponseEntity<?> liquidarPick(@PathVariable Long id,
                                           @RequestBody Map<String, String> body) {
        try {
            String resultadoStr = body.get("resultado");
            if (resultadoStr == null) {
                Map<String, String> error = new HashMap<>();
                error.put("error", "El campo 'resultado' es requerido");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
            }
            ResultadoPick resultado = ResultadoPick.valueOf(resultadoStr.toUpperCase());
            PickResponseDTO pick = pickServicio.liquidarPick(id, resultado);
            return ResponseEntity.ok(pick);
        } catch (IllegalArgumentException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", "Resultado inválido. Use: GANADO, PERDIDO o NULO");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * GET /api/picks/estadisticas
     * Devuelve métricas de rendimiento: win rate, ROI, racha actual.
     * Visible para todos los roles (los suscriptores pueden ver el historial).
     */
    @GetMapping("/estadisticas")
    public ResponseEntity<EstadisticasPickDTO> estadisticas() {
        return ResponseEntity.ok(pickServicio.estadisticas());
    }

    /**
     * PATCH /api/picks/{id}/reactivar
     * Vuelve un pick al estado PENDIENTE para que sea re-evaluado
     * automáticamente. Útil cuando quedó NULO por falta de datos de la API.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PatchMapping("/{id}/reactivar")
    public ResponseEntity<?> reactivarPick(@PathVariable Long id) {
        try {
            PickResponseDTO pick = pickServicio.reactivarPick(id);
            return ResponseEntity.ok(pick);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * POST /api/picks/resolver-pendientes
     * Evalúa automáticamente todos los picks pendientes consultando la API de resultados.
     * Se invoca al hacer clic en "Rendimiento" en el menú.
     * Solo accesible para administradores.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping("/resolver-pendientes")
    public ResponseEntity<RendimientoResolucionDTO> resolverPendientes() {
        RendimientoResolucionDTO resultado = pickServicio.resolverPendientesAutomatico();
        return ResponseEntity.ok(resultado);
    }
}
