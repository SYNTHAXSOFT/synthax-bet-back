package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Pick;
import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.ResultadoPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PickRepositorio extends JpaRepository<Pick, Long> {

    /** Todos los picks con partido cargado en una sola query (evita LazyInitializationException). */
    @Query("SELECT p FROM Pick p JOIN FETCH p.partido ORDER BY p.publicadoEn DESC")
    List<Pick> findAllConPartido();

    /** Pick por id con partido cargado (para liquidar y devolver respuesta serializable). */
    @Query("SELECT p FROM Pick p JOIN FETCH p.partido WHERE p.id = :id")
    Optional<Pick> findByIdConPartido(Long id);

    /** Por canal con partido cargado. */
    @Query("SELECT p FROM Pick p JOIN FETCH p.partido WHERE p.canal = :canal ORDER BY p.publicadoEn DESC")
    List<Pick> findByCanalConPartido(CanalPick canal);

    /** Pendientes con partido cargado. */
    @Query("SELECT p FROM Pick p JOIN FETCH p.partido WHERE p.resultado = co.com.synthax.bet.enums.ResultadoPick.PENDIENTE ORDER BY p.publicadoEn DESC")
    List<Pick> findPendientesConPartido();

    // ── Métodos para estadísticas (no necesitan serializar partido) ──────────

    List<Pick> findByCanal(CanalPick canal);

    List<Pick> findByCanalAndResultado(CanalPick canal, ResultadoPick resultado);

    List<Pick> findByPartidoId(Long idPartido);

    /** Verifica si ya existe un pick para el mismo partido y mercado (evita duplicados). */
    boolean existsByPartidoIdAndNombreMercado(Long partidoId, String nombreMercado);

    /** Elimina todos los picks de una lista de partidos. */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    void deleteByPartidoIdIn(List<Long> idsPartidos);

    List<Pick> findByResultado(ResultadoPick resultado);

    /** Picks liquidados ordenados del más reciente al más antiguo — para calcular racha. */
    @Query("SELECT p FROM Pick p WHERE p.resultado <> co.com.synthax.bet.enums.ResultadoPick.PENDIENTE " +
           "AND p.resultado <> co.com.synthax.bet.enums.ResultadoPick.NULO " +
           "ORDER BY p.liquidadoEn DESC")
    List<Pick> findLiquidadosOrdenadosPorFecha();

    /** Picks liquidados (todos, incluyendo NULO) para calcular ROI. */
    @Query("SELECT p FROM Pick p WHERE p.resultado <> co.com.synthax.bet.enums.ResultadoPick.PENDIENTE " +
           "ORDER BY p.liquidadoEn DESC")
    List<Pick> findTodosLiquidados();
}
