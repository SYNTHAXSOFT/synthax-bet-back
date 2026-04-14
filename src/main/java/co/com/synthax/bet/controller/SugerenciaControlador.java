package co.com.synthax.bet.controller;

import co.com.synthax.bet.dto.FiltroSugerenciaDTO;
import co.com.synthax.bet.dto.SugerenciaDTO;
import co.com.synthax.bet.service.SugerenciaServicio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Endpoints para las sugerencias de apuestas del día.
 */
@Slf4j
@RestController
@RequestMapping("/api/sugerencias")
@RequiredArgsConstructor
public class SugerenciaControlador {

    private final SugerenciaServicio sugerenciaServicio;

    /**
     * GET /sugerencias/hoy
     * Devuelve las combinadas sugeridas del día (1, 2 o 3 selecciones, cuota >= 1.80).
     */
    @GetMapping("/hoy")
    public ResponseEntity<List<SugerenciaDTO>> sugerenciasDelDia() {
        log.info(">>> GET /sugerencias/hoy");
        return ResponseEntity.ok(sugerenciaServicio.generarSugerenciasDelDia());
    }

    /**
     * POST /sugerencias/personalizada
     * Genera combinadas con los filtros elegidos por el usuario:
     * rango de probabilidad, cuota mínima, equipo, liga, tipo de apuesta y categorías.
     */
    @PostMapping("/personalizada")
    public ResponseEntity<List<SugerenciaDTO>> sugerenciasPersonalizadas(
            @RequestBody FiltroSugerenciaDTO filtro) {
        log.info(">>> POST /sugerencias/personalizada — filtro: {}", filtro);
        return ResponseEntity.ok(sugerenciaServicio.generarPersonalizada(filtro));
    }

    /**
     * GET /sugerencias/ligas
     * Devuelve las ligas únicas presentes en el análisis más reciente.
     * Se usa para poblar el select buscable en "Personalizar Sugerencias".
     */
    @GetMapping("/ligas")
    public ResponseEntity<List<String>> ligasDisponibles() {
        log.info(">>> GET /sugerencias/ligas");
        return ResponseEntity.ok(sugerenciaServicio.obtenerLigasDisponibles());
    }

    /**
     * GET /sugerencias/equipos
     * Devuelve los equipos únicos (locales y visitantes) del análisis más reciente.
     * Se usa para poblar el select buscable de "Equipo" en "Personalizar Sugerencias".
     */
    @GetMapping("/equipos")
    public ResponseEntity<List<String>> equiposDisponibles() {
        log.info(">>> GET /sugerencias/equipos");
        return ResponseEntity.ok(sugerenciaServicio.obtenerEquiposDisponibles());
    }
}
