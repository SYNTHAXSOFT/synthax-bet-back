package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Cuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CuotaRepositorio extends JpaRepository<Cuota, Long> {

    List<Cuota> findByPartidoId(Long idPartido);

    /** Carga cuotas de múltiples partidos en una sola query — evita N+1 */
    List<Cuota> findByPartidoIdIn(List<Long> idsPartidos);

    List<Cuota> findByPartidoIdAndCasaApuestas(Long idPartido, String casaApuestas);

    List<Cuota> findByPartidoIdAndNombreMercadoContaining(Long idPartido, String mercado);
}
