package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Arbitro;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ArbitroRepositorio extends JpaRepository<Arbitro, Long> {

    Optional<Arbitro> findByNombreIgnoreCase(String nombre);

    boolean existsByNombreIgnoreCase(String nombre);
}
