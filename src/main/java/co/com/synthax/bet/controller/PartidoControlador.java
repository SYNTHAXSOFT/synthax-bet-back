package co.com.synthax.bet.controller;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.service.PartidoServicio;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/partidos")
@RequiredArgsConstructor
public class PartidoControlador {

    private final PartidoServicio partidoServicio;

    @GetMapping("/hoy")
    public ResponseEntity<List<Partido>> obtenerPartidosDeHoy() {
        List<Partido> partidos = partidoServicio.obtenerPartidosDeHoy();
        return ResponseEntity.ok(partidos);
    }

    @GetMapping("/fecha/{fecha}")
    public ResponseEntity<?> obtenerPartidosPorFecha(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        try {
            List<Partido> partidos = partidoServicio.obtenerPartidosPorFecha(fecha);
            return ResponseEntity.ok(partidos);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT', 'ADMINISTRADOR')")
    @PostMapping("/sincronizar")
    public ResponseEntity<?> sincronizarPartidos(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fecha) {
        try {
            LocalDate fechaSincronizar = (fecha != null) ? fecha : LocalDate.now();
            List<Partido> partidos = partidoServicio.sincronizarPartidos(fechaSincronizar);

            Map<String, Object> respuesta = new HashMap<>();
            respuesta.put("mensaje", "Sincronización completada");
            respuesta.put("fecha", fechaSincronizar);
            respuesta.put("cantidad", partidos.size());

            return ResponseEntity.ok(respuesta);
        } catch (Exception e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerPartidoPorId(@PathVariable Long id) {
        try {
            Partido partido = partidoServicio.obtenerPorId(id);
            return ResponseEntity.ok(partido);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}
