package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Departamento;
import co.com.synthax.bet.entity.Municipio;
import co.com.synthax.bet.repository.MunicipioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MunicipioService {

    private final MunicipioRepository municipioRepository;
    private final DepartamentoService departamentoService;

    public Municipio crearMunicipio(Municipio municipio) {
        if (municipioRepository.existsById(municipio.getId()+"")) {
            throw new RuntimeException("El código ya está registrado");
        }

        // Buscar departamento por ID
        Departamento departamento = departamentoService.obtenerDepartamentoPorId(municipio.getDepartamento().getId());
        municipio.setDepartamento(departamento);

        return municipioRepository.save(municipio);
    }

    public Municipio actualizarMunicipio(String id, Municipio municipio) {
        Municipio municipioExistente = obtenerMunicipioPorId(id);

        if (!municipioExistente.getId().equals(municipio.getId())
                && municipioRepository.existsById(municipio.getId()+"")) {
            throw new RuntimeException("El código ya está registrado");
        }

        // Buscar departamento por ID
        Departamento departamento = departamentoService.obtenerDepartamentoPorId(municipio.getDepartamento().getId()
        );

        municipioExistente.setId(municipio.getId());
        municipioExistente.setNombre(municipio.getNombre());
        municipioExistente.setDepartamento(departamento);
        municipioExistente.setActivo(municipio.getActivo());

        return municipioRepository.save(municipioExistente);
    }

    public List<Municipio> obtenerTodosMunicipios() {
        return municipioRepository.findAll();
    }

    public Municipio obtenerMunicipioPorId(String id) {
        return municipioRepository.findById(id+"")
                .orElseThrow(() -> new RuntimeException("Municipio no encontrado con id: " + id));
    }

    public List<Municipio> obtenerMunicipiosPorDepartamento(String departamentoId) {
        return municipioRepository.findByDepartamentoId(departamentoId);
    }

    public void eliminarMunicipio(Long id) {
        if (!municipioRepository.existsById(id+"")) {
            throw new RuntimeException("Municipio no encontrado con id: " + id);
        }
        municipioRepository.deleteById(id+"");
    }

    public Municipio desactivarMunicipio(String id) {
        Municipio municipio = obtenerMunicipioPorId(id);
        municipio.setActivo(false);
        return municipioRepository.save(municipio);
    }

    public Municipio obtenerMunicipioPorCodigo(String codigo) {
        return municipioRepository.findById(codigo+"")
                .orElseThrow(() -> new RuntimeException("Municipio no encontrado con código: " + codigo));
    }
    
    public Municipio activarMunicipio(String id) {
    	Municipio municipio = obtenerMunicipioPorId(id);
    	municipio.setActivo(true);
        return municipioRepository.save(municipio);
    }
}
