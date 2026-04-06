package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.EstadoPartido;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PartidoRepositorio extends JpaRepository<Partido, Long> {

    Optional<Partido> findByIdPartidoApi(String idPartidoApi);

    List<Partido> findByFechaPartidoBetween(LocalDateTime desde, LocalDateTime hasta);

    List<Partido> findByFechaPartidoBetweenAndEstado(
            LocalDateTime desde, LocalDateTime hasta, EstadoPartido estado);

    List<Partido> findByLigaAndFechaPartidoBetween(
            String liga, LocalDateTime desde, LocalDateTime hasta);

    boolean existsByIdPartidoApi(String idPartidoApi);

    /**
     * Devuelve las ligas disponibles hoy agrupadas con conteo de partidos.
     * Resultado: [idLigaApi, liga, pais, count]
     */
    @Query("SELECT p.idLigaApi, p.liga, p.pais, COUNT(p) FROM Partido p " +
           "WHERE p.fechaPartido BETWEEN :desde AND :hasta AND p.idLigaApi IS NOT NULL " +
           "GROUP BY p.idLigaApi, p.liga, p.pais ORDER BY p.pais ASC, p.liga ASC")
    List<Object[]> findLigasAgrupadas(@Param("desde") LocalDateTime desde,
                                      @Param("hasta") LocalDateTime hasta);

    /**
     * Partidos de hoy filtrados por una lista de idLigaApi.
     */
    List<Partido> findByFechaPartidoBetweenAndIdLigaApiIn(
            LocalDateTime desde, LocalDateTime hasta, List<String> ligaIds);
}
