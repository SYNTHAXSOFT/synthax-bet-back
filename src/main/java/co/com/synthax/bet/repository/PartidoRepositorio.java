package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.EstadoPartido;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
