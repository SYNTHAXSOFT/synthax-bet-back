package co.com.synthax.pos.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.synthax.pos.entity.Departamento;

@Repository
public interface DepartamentoRepository extends JpaRepository<Departamento, String> {
}
