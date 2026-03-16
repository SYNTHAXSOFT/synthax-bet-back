package co.com.synthax.pos.service;

import co.com.synthax.pos.entity.Departamento;
import co.com.synthax.pos.entity.Municipio;
import co.com.synthax.pos.entity.Usuario;
import co.com.synthax.pos.enums.Rol;
import co.com.synthax.pos.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UsuarioService {

    private final UsuarioRepository usuarioRepository;

    private final DepartamentoService departamentoService;
    private final MunicipioService municipioService;

    public Usuario crearUsuario(Usuario usuario, Long usuarioLogueadoId) {
        if (usuarioRepository.existsByEmail(usuario.getEmail())) {
            throw new RuntimeException("El email ya está registrado");
        }

        // Obtener el usuario logueado para saber su rol
        Usuario usuarioLogueado = obtenerUsuarioPorId(usuarioLogueadoId);
    
        // Validar y asignar departamento
        if (usuario.getDepartamento() != null && usuario.getDepartamento().getId() != null) {
            Departamento departamento = departamentoService.obtenerDepartamentoPorId(
                    usuario.getDepartamento().getId()
            );
            usuario.setDepartamento(departamento);
        } else {
            usuario.setDepartamento(null);
        }

        // Validar y asignar municipio
        if (usuario.getMunicipio() != null && usuario.getMunicipio().getId() != null) {
            Municipio municipio = municipioService.obtenerMunicipioPorId(
                    usuario.getMunicipio().getId()
            );
            usuario.setMunicipio(municipio);
        } else {
            usuario.setMunicipio(null);
        }

        return usuarioRepository.save(usuario);
    }

    public List<Usuario> obtenerUsuariosPorRolUsuario(Long usuarioLogueadoId) {
        Usuario usuarioLogueado = obtenerUsuarioPorId(usuarioLogueadoId);

        switch (usuarioLogueado.getRol()) {
            case ROOT:
                // ROOT ve todos los usuarios
                return usuarioRepository.findAll();


            case ADMINISTRADOR:
                // ADMINISTRADOR 
                

            default:
                return List.of();
        }
    }

    public List<Usuario> obtenerTodosLosUsuarios() {
        return usuarioRepository.findAll();
    }

    public Usuario obtenerUsuarioPorId(Long id) {
        return usuarioRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con id: " + id));
    }

    public Usuario actualizarUsuario(Long id, Usuario usuario) {
        Usuario usuarioExistente = obtenerUsuarioPorId(id);

        if (!usuarioExistente.getEmail().equals(usuario.getEmail())
                && usuarioRepository.existsByEmail(usuario.getEmail())) {
            throw new RuntimeException("El email ya está registrado");
        }

        if (usuario.getDepartamento() != null && usuario.getDepartamento().getId() != null) {
            Departamento departamento = departamentoService.obtenerDepartamentoPorId(
                    usuario.getDepartamento().getId()
            );
            usuarioExistente.setDepartamento(departamento);
        } else {
            usuarioExistente.setDepartamento(null);
        }

        // Validar y asignar municipio
        if (usuario.getMunicipio() != null && usuario.getMunicipio().getId() != null) {
            Municipio municipio = municipioService.obtenerMunicipioPorId(
                    usuario.getMunicipio().getId()
            );
            usuarioExistente.setMunicipio(municipio);
        } else {
            usuarioExistente.setMunicipio(null);
        }

        usuarioExistente.setNombre(usuario.getNombre());

        usuarioExistente.setNombre(usuario.getNombre());
        usuarioExistente.setApellido(usuario.getApellido());
        usuarioExistente.setEmail(usuario.getEmail());
        usuarioExistente.setPassword(usuario.getPassword());
        usuarioExistente.setRol(usuario.getRol());
        usuarioExistente.setActivo(usuario.getActivo());
        usuarioExistente.setCedula(usuario.getCedula());

        return usuarioRepository.save(usuarioExistente);
    }

    public void eliminarUsuario(Long id) {
        if (!usuarioRepository.existsById(id)) {
            throw new RuntimeException("Usuario no encontrado con id: " + id);
        }
        usuarioRepository.deleteById(id);
    }

    public Usuario desactivarUsuario(Long id) {
        Usuario usuario = obtenerUsuarioPorId(id);
        usuario.setActivo(false);
        return usuarioRepository.save(usuario);
    }

    public Usuario autenticar(String email, String password) {
        Usuario usuario = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado"));

        if (!usuario.getActivo()) {
            throw new RuntimeException("Usuario desactivado");
        }

        if (!usuario.getPassword().equals(password)) {
            throw new RuntimeException("Contraseña incorrecta");
        }

        return usuario;
    }

    public List<Usuario> obtenerUsuariosPorRolActivos(String rol) {
        return usuarioRepository.findByRolAndActivo(Rol.valueOf(rol), true);
    }
    
    public Usuario buscarPorEmail(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con email: " + email));
    }

    public Usuario autenticarPorCedula(String cedula, String password) {
        Usuario usuario = usuarioRepository.findByCedula(cedula)
                .orElseThrow(() -> new RuntimeException("Usuario no encontrado con cédula: " + cedula));

        if (!password.equals(usuario.getPassword())) {
            throw new RuntimeException("Credenciales incorrectas");
        }

        return usuario;
    }

}