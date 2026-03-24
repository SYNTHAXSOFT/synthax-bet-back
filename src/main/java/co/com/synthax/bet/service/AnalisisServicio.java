package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.NivelConfianza;
import co.com.synthax.bet.motor.MotorAnalisis;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalisisServicio {

    private final AnalisisRepositorio analisisRepositorio;
    private final MotorAnalisis       motorAnalisis;
    private final PartidoServicio     partidoServicio;

    /**
     * Devuelve los análisis del día de hoy.
     * Si no existen, ejecuta el motor sobre los partidos del día.
     */
    public List<Analisis> obtenerAnalisisDeHoy() {
        List<Partido> partidos = partidoServicio.obtenerPartidosDeHoy();

        if (partidos.isEmpty()) {
            log.warn(">>> Sin partidos hoy para analizar");
            return List.of();
        }

        // Obtenemos los IDs de los partidos de hoy
        List<Long> idsPartidosHoy = partidos.stream()
                .map(Partido::getId)
                .toList();

        // Buscamos análisis existentes solo para los partidos de hoy
        List<Analisis> existentes = analisisRepositorio.findByPartidoIdIn(idsPartidosHoy);

        if (!existentes.isEmpty()) {
            log.info(">>> {} análisis ya calculados para hoy, devolviendo desde BD", existentes.size());
            return existentes;
        }

        log.info(">>> Ejecutando motor de análisis para {} partidos de hoy", partidos.size());
        return motorAnalisis.analizarPartidos(partidos);
    }

    /**
     * Ejecuta el motor sobre todos los partidos del día (fuerza re-cálculo).
     * Elimina los análisis previos del día antes de insertar los nuevos.
     *
     * NOTA: NO debe ser @Transactional — si fuera una sola transacción y un save()
     * falla, Hibernate marca la sesión como corrupta (null id AssertionFailure) y
     * todos los saves posteriores fallan en cascada. Sin @Transactional cada
     * save() de MotorAnalisis corre en su propia mini-transacción.
     */
    public List<Analisis> ejecutarAnalisisDelDia() {
        List<Partido> partidos = partidoServicio.obtenerPartidosDeHoy();

        if (partidos.isEmpty()) {
            log.warn(">>> Sin partidos hoy para analizar");
            return List.of();
        }

        List<Long> idsHoy = partidos.stream().map(Partido::getId).toList();

        long eliminados = analisisRepositorio.findByPartidoIdIn(idsHoy).size();
        analisisRepositorio.deleteByPartidoIdIn(idsHoy);
        log.info(">>> {} análisis anteriores eliminados antes de re-ejecutar", eliminados);

        log.info(">>> Re-ejecutando motor para {} partidos de hoy", partidos.size());
        return motorAnalisis.analizarPartidos(partidos);
    }

    /**
     * Filtra análisis por probabilidad mínima y/o categoría.
     */
    public List<Analisis> filtrar(BigDecimal probabilidadMinima, CategoriaAnalisis categoria) {
        if (categoria != null && probabilidadMinima != null) {
            return analisisRepositorio
                    .findByPartidoIdAndCategoriaMercado(null, categoria)
                    .stream()
                    .filter(a -> a.getProbabilidad() != null
                            && a.getProbabilidad().compareTo(probabilidadMinima) >= 0)
                    .toList();
        }

        if (probabilidadMinima != null) {
            return analisisRepositorio.findByProbabilidadGreaterThanEqual(probabilidadMinima);
        }

        return analisisRepositorio.findAll();
    }

    /**
     * Devuelve análisis de un partido específico.
     */
    public List<Analisis> obtenerPorPartido(Long idPartido) {
        return analisisRepositorio.findByPartidoId(idPartido);
    }

    /**
     * Devuelve sólo análisis de alta confianza (MUY_ALTA o ALTA).
     */
    public List<Analisis> obtenerAltaConfianza() {
        return analisisRepositorio
                .findByNivelConfianzaAndProbabilidadGreaterThanEqual(
                        NivelConfianza.ALTA,
                        BigDecimal.valueOf(0.70));
    }
}
