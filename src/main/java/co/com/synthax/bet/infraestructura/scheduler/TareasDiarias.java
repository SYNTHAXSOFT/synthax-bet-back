package co.com.synthax.bet.infraestructura.scheduler;

import co.com.synthax.bet.service.CuotaServicio;
import co.com.synthax.bet.service.PartidoServicio;
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
 *   06:00 → sincronizarPartidosDelDia()  — 1 request, cachea todo el día
 *   07:30 → ingestarCuotasDelDia()       — 1 request por partido analizado
 *   23:00 → precargarPartidosDemana()    — 1 request, anticipa el día siguiente
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TareasDiarias {

    private final PartidoServicio partidoServicio;
    private final CuotaServicio   cuotaServicio;

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
     * Ingestiona las cuotas del día a las 7:30 AM.
     * Se ejecuta 90 minutos después de sincronizar partidos para asegurar
     * que la tabla partidos ya tenga los datos del día.
     *
     * Consume 1 request de API-Football por partido del día.
     * Las cuotas quedan disponibles para el cálculo de edge en SugerenciaServicio.
     */
    @Scheduled(cron = "0 30 7 * * *")
    public void ingestarCuotasDelDia() {
        log.info(">>> [TAREA DIARIA] Iniciando ingesta de cuotas - {}", LocalDate.now());
        try {
            int total = cuotaServicio.ingestarCuotasDelDia();
            log.info(">>> [TAREA DIARIA] Cuotas ingresadas: {} - {}", total, LocalTime.now());
        } catch (Exception e) {
            log.error(">>> [TAREA DIARIA] Error al ingestar cuotas: {}", e.getMessage());
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
