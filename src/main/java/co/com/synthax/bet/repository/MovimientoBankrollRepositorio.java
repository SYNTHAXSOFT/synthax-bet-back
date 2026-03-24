package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.MovimientoBankroll;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MovimientoBankrollRepositorio extends JpaRepository<MovimientoBankroll, Long> {

    List<MovimientoBankroll> findBySuscriptorIdOrderByFechaDesc(Long idSuscriptor);

    List<MovimientoBankroll> findBySuscriptorIdOrderByFechaAsc(Long idSuscriptor);
}
