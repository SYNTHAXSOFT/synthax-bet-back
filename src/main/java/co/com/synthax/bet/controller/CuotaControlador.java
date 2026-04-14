package co.com.synthax.bet.controller;

import co.com.synthax.bet.dto.IngestaCuotasResultadoDTO;
import co.com.synthax.bet.dto.LigaDisponibleDTO;
import co.com.synthax.bet.service.CuotaServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
     *
     * Ingestiona las cuotas del día desde API-Football hacia la tabla cuotas en BD.
     * Devuelve un DTO detallado con el resultado de la ingesta:
     *   - estado: "ok" | "ok_sin_cuotas" | "abortado"
     *   - motivo (si abortado): SIN_PROVEEDOR | SIN_PARTIDOS | BUDGET_AGOTADO | SIN_ID_API
     *   - cuotasPersistidas, requestsConsumidosEnIngesta, partidosSinCuotasEnApi, etc.
     *
     * Body opcional (JSON): { "ligaIds": ["239", "2", ...] }
     * Si no se envía body, procesa todos los partidos con análisis de hoy.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping("/ingestar")
    public ResponseEntity<IngestaCuotasResultadoDTO> ingestar(
            @RequestBody(required = false) Map<String, Object> body) {

        @SuppressWarnings("unchecked")
        List<String> ligaIds = (body != null && body.containsKey("ligaIds"))
                ? (List<String>) body.get("ligaIds")
                : null;

        IngestaCuotasResultadoDTO resultado = cuotaServicio.ingestarCuotasDelDiaDetallado(ligaIds);
        return ResponseEntity.ok(resultado);
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
     * GET /api/cuotas/estado-budget
     *
     * Devuelve el estado actual del presupuesto diario de requests de API-Football.
     * Útil para que el front-end muestre una advertencia cuando quedan pocos requests
     * antes de ejecutar el análisis (operación que los consume masivamente).
     *
     * Respuesta: { requestsUsadosHoy, requestsMaxDiarios, requestsDisponiblesUsoGeneral,
     *              requestsDisponiblesParaCuotas, reservaCuotas, advertencia? }
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @GetMapping("/estado-budget")
    public ResponseEntity<Map<String, Object>> estadoBudget() {
        return ResponseEntity.ok(cuotaServicio.estadoBudget());
    }

    /**
     * GET /api/cuotas/diagnostico
     *
     * Revisa paso a paso por qué puede fallar la ingesta de cuotas sin necesidad
     * de revisar logs del servidor.
     *
     * Pasos que verifica:
     *   1. ¿Está disponible el proveedor de cuotas (ProveedorCuotas bean)?
     *   2. ¿Hay partidos con análisis para hoy?
     *   3. ¿Esos partidos tienen idPartidoApi no nulo?
     *   4. ¿La API devuelve cuotas para el primer partido válido? (consume 1 request)
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @GetMapping("/diagnostico")
    public ResponseEntity<Map<String, Object>> diagnostico() {
        return ResponseEntity.ok(cuotaServicio.diagnosticar());
    }

    /**
     * GET /api/cuotas/diagnostico-raw/{idApi}
     *
     * Consulta directamente la API-Football para un fixture ID específico y devuelve
     * las cuotas que retorna (como las parsea el adaptador). Consume 1 request del cupo.
     * Útil para depurar por qué un partido específico no tiene cuotas después de ingestar.
     *
     * Ejemplo: GET /api/cuotas/diagnostico-raw/1035047
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @GetMapping("/diagnostico-raw/{idApi}")
    public ResponseEntity<Map<String, Object>> diagnosticoRaw(@PathVariable String idApi) {
        return ResponseEntity.ok(cuotaServicio.diagnosticarCuotasRaw(idApi));
    }
}
