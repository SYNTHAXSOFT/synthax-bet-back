package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface EstadisticaEquipoRepositorio extends JpaRepository<EstadisticaEquipo, Long> {

    Optional<EstadisticaEquipo> findByIdEquipoAndTemporada(String idEquipo, String temporada);

    boolean existsByIdEquipoAndTemporada(String idEquipo, String temporada);

    /**
     * Carga en lote los registros de estadísticas para una lista de IDs de equipo.
     * Usado por SugerenciaServicio para verificar la muestra de partidos disponible
     * antes de incluir un equipo en el pool de sugerencias automáticas.
     * Puede devolver múltiples registros por equipo si hay varias temporadas.
     */
    List<EstadisticaEquipo> findByIdEquipoIn(Collection<String> idEquipos);
}
