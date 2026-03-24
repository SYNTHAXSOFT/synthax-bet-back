package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.EstadisticaEquipo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface EstadisticaEquipoRepositorio extends JpaRepository<EstadisticaEquipo, Long> {

    Optional<EstadisticaEquipo> findByIdEquipoAndTemporada(String idEquipo, String temporada);

    boolean existsByIdEquipoAndTemporada(String idEquipo, String temporada);
}
