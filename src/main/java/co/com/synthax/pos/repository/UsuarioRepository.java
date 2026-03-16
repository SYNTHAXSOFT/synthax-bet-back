package co.com.synthax.pos.repository;

import co.com.synthax.pos.entity.Usuario;
import co.com.synthax.pos.enums.Rol;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByEmail(String email);
    boolean existsByEmail(String email);
    List<Usuario> findByRolAndActivo(Rol rol, boolean activo);
     

    Optional<Usuario> findByCedula(String cedula);

}