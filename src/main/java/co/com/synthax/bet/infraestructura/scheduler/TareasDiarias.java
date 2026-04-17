package co.com.synthax.bet.infraestructura.scheduler;

import co.com.synthax.bet.service.CuotaServicio;
import co.com.synthax.bet.service.PartidoServicio;
import co.com.synthax.bet.service.PickServicio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Tareas automáticas programadas que se ejecutan diariamente.
 * Consume los requests de API con criterio para no superar el límite gratuito.
 *
 * Flujo diario:
 *   06:00 → sincronizarPartidosDelDia()   — 1 request, cachea todo el día
 *   07:30 → ingestarCuotasDelDia()        — 1 request por partido analizado
 *   22:00 → resolverPicksPendientes()     — 2 requests por pick (resultado + stats)
 *   23:00 → precargarPartidosDemana()     — 1 request, anticipa el día siguiente
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TareasDiarias {

    private final PartidoServicio partidoServicio;
    private final CuotaServicio   cuotaServicio;
    private final PickServicio    pickServicio;

    /**
     * Sincroniza los partidos del día todos los días a las 6:00 AM.
     * Usa 1 request del límite diario y cachea el resultado todo el día.
     */
    @Scheduled(cron = "0 0 6 * * *")
    public void sincronizarPartidosDelDia() {
        log.info(">>> [TAREA DIARIA] Iniciando sincronización de partidos - {}",
                LocalDate.now());
        try {
            partidoServicio.sincronizarPartidos(LocalDate.now());
            log.info(">>> [TAREA DIARIA] Partidos sincronizados correctamente - {}",
                    LocalTime.now());
        } catch (Exception e) {
            log.error(">>> [TAREA DIARIA] Error al sincronizar partidos: {}", e.getMessage());
        }
    }

    /**
     * Intenta ingestar cuotas a las 7:30 AM como conveniencia.
     *
     * COMPORTAMIENTO: si el admin NO ha ejecutado el análisis antes de las 7:30 AM,
     * esta tarea aborta automáticamente sin consumir ningún request (motivo: SIN_PARTIDOS).
     * Solo consume requests si ya hay análisis generados hoy (caso de análisis nocturno).
     *
     * En uso normal el admin ejecuta el análisis manualmente durante el día y luego
     * ingestiona cuotas desde la UI. Esta tarea es un complemento, no el flujo principal.
     */
    @Scheduled(cron = "0 30 7 * * *")
    public void ingestarCuotasDelDia() {
        log.info(">>> [TAREA DIARIA] Verificando ingesta de cuotas - {}", LocalDate.now());
        try {
            int total = cuotaServicio.ingestarCuotasDelDia();
            if (total > 0) {
                log.info(">>> [TAREA DIARIA] Cuotas ingresadas: {} - {}", total, LocalTime.now());
            } else {
                log.info(">>> [TAREA DIARIA] Ingesta abortada (sin análisis previo) — 0 requests consumidos");
            }
        } catch (Exception e) {
            log.error(">>> [TAREA DIARIA] Error al ingestar cuotas: {}", e.getMessage());
        }
    }

    /**
     * Resuelve automáticamente todos los picks pendientes a las 10:00 PM.
     *
     * ¿Por qué 10 PM Colombia (UTC-5)?
     *   - Liga colombiana, Libertadores, Liga MX: terminan antes de las 10 PM Colombia.
     *   - Ligas europeas (Premier, La Liga, Champions): finalizan entre 9 AM y 5 PM Colombia.
     *   - Eso garantiza que la gran mayoría de partidos ya tienen estado FT en la API.
     *
     * Consumo de API:
     *   - 2 requests por pick pendiente: /fixtures?id=X (resultado) + /fixtures/statistics (stats).
     *   - Si hay 10 picks pendientes → 20 requests de los 7500 diarios del plan PRO.
     *
     * Si un partido NO está FT aún (ej. partido de tarde USA), el pick queda PENDIENTE
     * y se puede resolver manualmente desde la pantalla de Picks o al día siguiente.
     */
    @Scheduled(cron = "0 0 22 * * *")
    public void resolverPicksPendientes() {
        log.info(">>> [TAREA DIARIA] Iniciando resolución automática de picks pendientes - {}",
                LocalDate.now());
        try {
            var resultado = pickServicio.resolverPendientesAutomatico();
            log.info(">>> [TAREA DIARIA] Picks resueltos — ganados: {}, perdidos: {}, nulos: {}, aún pendientes: {} - {}",
                    resultado.getGanados(),
                    resultado.getPerdidos(),
                    resultado.getNulos(),
                    resultado.getPendientesAun(),
                    LocalTime.now());
        } catch (Exception e) {
            log.error(">>> [TAREA DIARIA] Error al resolver picks pendientes: {}", e.getMessage());
        }
    }

    /**
     * Pre-carga los partidos del día siguiente a las 11:00 PM.
     * Permite tener datos listos de madrugada para el análisis matutino.
     */
    @Scheduled(cron = "0 0 23 * * *")
    public void precargarPartidosDemana() {
        LocalDate manana = LocalDate.now().plusDays(1);
        log.info(">>> [TAREA DIARIA] Pre-cargando partidos de mañana - {}", manana);
        try {
            partidoServicio.sincronizarPartidos(manana);
            log.info(">>> [TAREA DIARIA] Partidos de mañana pre-cargados correctamente");
        } catch (Exception e) {
            log.error(">>> [TAREA DIARIA] Error al pre-cargar partidos: {}", e.getMessage());
        }
    }
}
