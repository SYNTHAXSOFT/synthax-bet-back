package co.com.synthax.bet.controller;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.service.AnalisisServicio;
import co.com.synthax.bet.service.EstadoEjecucionServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analisis")
@RequiredArgsConstructor
public class AnalisisControlador {

    private final AnalisisServicio        analisisServicio;
    private final EstadoEjecucionServicio estadoEjecucion;

    /**
     * GET /api/analisis/progreso
     * Devuelve el estado actual del motor de análisis o ingesta de cuotas.
     * El frontend hace polling a este endpoint cada 2 segundos para mostrar
     * la barra de progreso sin necesidad de WebSockets.
     *
     * Respuesta ejemplo (ejecutando):
     *   { "ejecutando": true, "fase": "ANALISIS", "progreso": 12, "total": 35,
     *     "porcentaje": 34, "detalle": "Real Madrid vs Barcelona (12/35)" }
     *
     * Respuesta cuando termina:
     *   { "ejecutando": false, "fase": "IDLE", "progreso": 35, "total": 35,
     *     "porcentaje": 100, "detalle": "Completado" }
     */
    @GetMapping("/progreso")
    public ResponseEntity<?> obtenerProgreso() {
        return ResponseEntity.ok(estadoEjecucion.obtenerEstado());
    }

    /**
     * GET /api/analisis/hoy
     * Devuelve todos los análisis del día. Si no existen, los calcula.
     */
    @GetMapping("/hoy")
    public ResponseEntity<List<Analisis>> obtenerAnalisisDeHoy() {
        List<Analisis> analisis = analisisServicio.obtenerAnalisisDeHoy();
        return ResponseEntity.ok(analisis);
    }

    /**
     * GET /api/analisis/hoy/alta-confianza
     * Solo análisis con nivel ALTA o MUY_ALTA (prob >= 70%).
     */
    @GetMapping("/hoy/alta-confianza")
    public ResponseEntity<List<Analisis>> obtenerAltaConfianza() {
        return ResponseEntity.ok(analisisServicio.obtenerAltaConfianza());
    }

    /**
     * POST /api/analisis/filtrar
     * Filtra análisis por probabilidad mínima y/o categoría de mercado.
     *
     * Body ejemplo:
     * { "probabilidadMinima": 0.65, "categoria": "GOLES" }
     */
    @PostMapping("/filtrar")
    public ResponseEntity<?> filtrar(@RequestBody Map<String, String> filtros) {
        try {
            BigDecimal probabilidadMinima = filtros.containsKey("probabilidadMinima")
                    ? new BigDecimal(filtros.get("probabilidadMinima")) : null;

            CategoriaAnalisis categoria = filtros.containsKey("categoria")
                    ? CategoriaAnalisis.valueOf(filtros.get("categoria").toUpperCase()) : null;

            List<Analisis> resultado = analisisServicio.filtrar(probabilidadMinima, categoria);
            return ResponseEntity.ok(resultado);

        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    /**
     * GET /api/analisis/partido/{id}
     * Todos los análisis de un partido específico.
     */
    @GetMapping("/partido/{id}")
    public ResponseEntity<?> obtenerPorPartido(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(analisisServicio.obtenerPorPartido(id));
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    /**
     * POST /api/analisis/ejecutar
     * Fuerza re-ejecución del motor sobre los partidos de hoy (solo admin).
     *
     * Body opcional: { "ligaIds": ["239", "39", "140"] }
     * Si no se envía ligaIds, analiza los primeros MAX_PARTIDOS_A_ANALIZAR del día.
     */
    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping("/ejecutar")
    public ResponseEntity<?> ejecutarMotor(
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            @SuppressWarnings("unchecked")
            List<String> ligaIds = (body != null && body.containsKey("ligaIds"))
                    ? (List<String>) body.get("ligaIds")
                    : null;

            List<Analisis> resultados = analisisServicio.ejecutarAnalisisDelDia(ligaIds);
            Map<String, Object> respuesta = new HashMap<>();
            respuesta.put("mensaje", "Motor ejecutado correctamente");
            respuesta.put("analisesGenerados", resultados.size());
            respuesta.put("ligasFiltradas", ligaIds != null ? ligaIds.size() : "todas (limitado a 40)");
            return ResponseEntity.ok(respuesta);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }
}
