package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Cuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CuotaRepositorio extends JpaRepository<Cuota, Long> {

    List<Cuota> findByPartidoId(Long idPartido);

    List<Cuota> findByPartidoIdAndCasaApuestas(Long idPartido, String casaApuestas);

    List<Cuota> findByPartidoIdAndNombreMercadoContaining(Long idPartido, String mercado);
}
