package co.com.synthax.bet.repository;

import co.com.synthax.bet.entity.Suscriptor;
import co.com.synthax.bet.enums.PlanSuscripcion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SuscriptorRepositorio extends JpaRepository<Suscriptor, Long> {

    Optional<Suscriptor> findByIdTelegram(String idTelegram);

    Optional<Suscriptor> findByEmail(String email);

    List<Suscriptor> findByPlanAndActivo(PlanSuscripcion plan, boolean activo);

    boolean existsByIdTelegram(String idTelegram);

    boolean existsByEmail(String email);
}
