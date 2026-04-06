package co.com.synthax.bet.controller;

import co.com.synthax.bet.dto.LigaDisponibleDTO;
import co.com.synthax.bet.service.CuotaServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Endpoints REST para gestión de cuotas de casas de apuestas.
 */
@RestController
@RequestMapping("/api/cuotas")
@RequiredArgsConstructor
public class CuotaControlador {

    private final CuotaServicio cuotaServicio;

    /**
     * POST /api/cuotas/ingestar
     * Ingestiona las cuotas del día desde la API externa hacia la tabla cuotas en BD.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping("/ingestar")
    public ResponseEntity<Map<String, Object>> ingestar(
            @RequestBody(required = false) Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<String> ligaIds = (body != null && body.containsKey("ligaIds"))
                ? (List<String>) body.get("ligaIds")
                : null;

        int total = cuotaServicio.ingestarCuotasDelDia(ligaIds);
        return ResponseEntity.ok(Map.of(
                "mensaje",           "Ingesta de cuotas completada",
                "cuotasPersistidas", total
        ));
    }

    /**
     * GET /api/cuotas/ligas-disponibles-hoy
     * Devuelve las ligas con partidos para hoy, marcando las favoritas.
     * Usado por el front-end para poblar el modal de selección de ligas.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @GetMapping("/ligas-disponibles-hoy")
    public ResponseEntity<List<LigaDisponibleDTO>> ligasDisponiblesHoy() {
        return ResponseEntity.ok(cuotaServicio.ligasDisponiblesHoy());
    }

    /**
     * GET /api/cuotas/diagnostico
     *
     * Revisa paso a paso por qué puede fallar la ingesta de cuotas sin necesidad
     * de revisar logs del servidor.
     *
     * Pasos que verifica:
     *   1. ¿Está disponible el proveedor de cuotas (ProveedorCuotas bean)?
     *   2. ¿Hay partidos en BD para hoy?
     *   3. ¿Esos partidos tienen idPartidoApi no nulo?
     *   4. ¿La API devuelve cuotas para el primer partido válido? (consume 1 request)
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @GetMapping("/diagnostico")
    public ResponseEntity<Map<String, Object>> diagnostico() {
        return ResponseEntity.ok(cuotaServicio.diagnosticar());
    }
}
