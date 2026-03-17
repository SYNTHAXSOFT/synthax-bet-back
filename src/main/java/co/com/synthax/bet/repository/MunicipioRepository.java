package co.com.synthax.bet.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.synthax.bet.entity.Municipio;

@Repository
public interface MunicipioRepository extends JpaRepository<Municipio, String> {
    List<Municipio> findByDepartamentoId(String departamentoId);
}
