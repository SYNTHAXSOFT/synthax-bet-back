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
