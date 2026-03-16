package co.com.synthax.pos.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.synthax.pos.entity.Municipio;

@Repository
public interface MunicipioRepository extends JpaRepository<Municipio, String> {
    List<Municipio> findByDepartamentoId(String departamentoId);
}
