package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Pick;
import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.ResultadoPick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PickRepositorio extends JpaRepository<Pick, Long> {

    List<Pick> findByCanal(CanalPick canal);

    List<Pick> findByCanalAndResultado(CanalPick canal, ResultadoPick resultado);

    List<Pick> findByPartidoId(Long idPartido);

    List<Pick> findByResultado(ResultadoPick resultado);
}
