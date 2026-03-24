package co.com.synthax.bet.infraestructura.scheduler;

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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TareasDiarias {

    private final PartidoServicio partidoServicio;

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
