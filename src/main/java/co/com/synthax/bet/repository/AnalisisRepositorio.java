package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.NivelConfianza;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AnalisisRepositorio extends JpaRepository<Analisis, Long> {

    List<Analisis> findByPartidoId(Long idPartido);

    List<Analisis> findByPartidoIdIn(List<Long> idPartidos);

    @Transactional
    @Modifying
    void deleteByPartidoIdIn(List<Long> idPartidos);

    /** Elimina TODOS los análisis cuyo calculadoEn esté en el rango dado.
     *  Se usa para limpiar el día completo antes de re-ejecutar con ligas seleccionadas.
     *
     *  clearAutomatically = true: limpia la caché L1 del EntityManager después del DELETE masivo.
     *  Sin esto, el EntityManager retiene en caché los registros borrados ("entidades fantasma")
     *  y los posteriores saveAll() de los demás partidos pueden fallar por estado inconsistente
     *  (solo se guarda el primer partido y el resto queda sin análisis en BD). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM Analisis a WHERE a.calculadoEn BETWEEN :inicio AND :fin")
    void deleteByCalculadoEnBetween(@org.springframework.data.repository.query.Param("inicio") LocalDateTime inicio,
                                    @org.springframework.data.repository.query.Param("fin")    LocalDateTime fin);

    /** Cuenta los análisis cuyo calculadoEn esté en el rango dado (sin cargarlos en memoria). */
    @Query("SELECT COUNT(a) FROM Analisis a WHERE a.calculadoEn BETWEEN :inicio AND :fin")
    long countByCalculadoEnBetween(@org.springframework.data.repository.query.Param("inicio") LocalDateTime inicio,
                                   @org.springframework.data.repository.query.Param("fin")    LocalDateTime fin);

    List<Analisis> findByPartidoIdAndCategoriaMercado(Long idPartido, CategoriaAnalisis categoria);

    List<Analisis> findByNivelConfianzaAndProbabilidadGreaterThanEqual(
            NivelConfianza nivelConfianza, BigDecimal probabilidadMinima);

    List<Analisis> findByProbabilidadGreaterThanEqual(BigDecimal probabilidadMinima);

    /** Todos los análisis cuyo calculadoEn esté dentro del rango dado */
    List<Analisis> findByCalculadoEnBetween(LocalDateTime inicio, LocalDateTime fin);

    /** Fecha y hora del análisis más reciente en toda la tabla */
    @Query("SELECT MAX(a.calculadoEn) FROM Analisis a")
    Optional<LocalDateTime> findMaxCalculadoEn();
}
