package co.com.synthax.bet.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rastrea el estado de ejecución del motor de análisis y la ingesta de cuotas.
 * Permite que el frontend haga polling a GET /api/analisis/progreso para mostrar
 * una barra de progreso en tiempo real sin necesidad de WebSockets.
 *
 * Thread-safe: usa volatile + AtomicInteger para acceso concurrente seguro.
 * Singleton: Spring gestiona una sola instancia compartida entre todos los servicios.
 */
@Slf4j
@Service
public class EstadoEjecucionServicio {

    private volatile boolean ejecutando = false;
    private volatile String  fase       = "IDLE";   // "ANALISIS" | "CUOTAS" | "IDLE"
    private final AtomicInteger progreso = new AtomicInteger(0);
    private volatile int    total       = 0;
    private volatile String detalle     = "";

    // ──────────────────────────────────────────────────────────────────────────
    // Ciclo de vida de un proceso
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Marca el inicio de un nuevo proceso.
     * Si ya hay un proceso en curso, lo sobreescribe (solo un proceso a la vez).
     */
    public synchronized void iniciar(String fase, int total) {
        this.ejecutando = true;
        this.fase       = fase;
        this.total      = total;
        this.progreso.set(0);
        this.detalle    = "Iniciando...";
        log.info(">>> [PROGRESO] Iniciando: fase={}, total={} partidos", fase, total);
    }

    /**
     * Actualiza el avance dentro del proceso activo.
     * @param actual índice del ítem actual (0-based)
     * @param detalle texto descriptivo del ítem actual para mostrar en UI
     */
    public void actualizarProgreso(int actual, String detalle) {
        this.progreso.set(actual);
        this.detalle = detalle;
    }

    /**
     * Marca el proceso como completado.
     * Siempre llamar en un bloque finally para garantizar que el estado queda limpio.
     */
    public synchronized void completar() {
        if (total > 0) progreso.set(total);
        this.ejecutando = false;
        this.detalle    = "Completado";
        log.info(">>> [PROGRESO] Completado: fase={}", fase);
    }

    /** ¿Hay un proceso en ejecución ahora mismo? */
    public boolean estaEjecutando() {
        return ejecutando;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Estado para exponer al frontend
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Devuelve el estado actual serializable a JSON para el endpoint de progreso.
     * El frontend hace polling a este método cada 2 segundos.
     */
    public Map<String, Object> obtenerEstado() {
        int prog = progreso.get();
        int tot  = total;
        int pct  = (tot > 0) ? Math.min(100, prog * 100 / tot) : (ejecutando ? 5 : 0);

        Map<String, Object> estado = new HashMap<>();
        estado.put("ejecutando",  ejecutando);
        estado.put("fase",        fase);
        estado.put("progreso",    prog);
        estado.put("total",       tot);
        estado.put("porcentaje",  pct);
        estado.put("detalle",     detalle);
        return estado;
    }
}
