package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.ResolucionHistorial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ResolucionHistorialRepositorio extends JpaRepository<ResolucionHistorial, Long> {

    List<ResolucionHistorial> findByFechaLoteOrderByPartidoAscCategoriaAsc(LocalDate fechaLote);

    Optional<ResolucionHistorial> findByFechaLoteAndIdPartidoAndMercado(
            LocalDate fechaLote, Long idPartido, String mercado);

    @Query("SELECT DISTINCT r.fechaLote FROM ResolucionHistorial r ORDER BY r.fechaLote DESC")
    List<LocalDate> findFechasDisponibles();
}
