package co.com.synthax.bet.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import co.com.synthax.bet.entity.Departamento;

@Repository
public interface DepartamentoRepository extends JpaRepository<Departamento, String> {
}
