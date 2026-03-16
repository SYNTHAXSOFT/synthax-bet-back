package co.com.synthax.pos.service;

import co.com.synthax.pos.entity.Departamento;
import co.com.synthax.pos.repository.DepartamentoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartamentoService {

    private final DepartamentoRepository departamentoRepository;

    public Departamento crearDepartamento(Departamento departamento) {
        if (departamentoRepository.existsById(departamento.getId())) {
            throw new RuntimeException("Ya existe un departamento con ese ID");
        }
        return departamentoRepository.save(departamento);
    }

    public List<Departamento> obtenerTodosDepartamentos() {
        return departamentoRepository.findAll();
    }

    public Departamento obtenerDepartamentoPorId(String id) {
        return departamentoRepository.findById(id+"")
                .orElseThrow(() -> new RuntimeException("Departamento no encontrado con id: " + id));
    }

    public Departamento actualizarDepartamento(String id, Departamento departamento) {
        Departamento departamentoExistente = obtenerDepartamentoPorId(id);

        departamentoExistente.setNombre(departamento.getNombre());
        departamentoExistente.setActivo(departamento.getActivo());

        return departamentoRepository.save(departamentoExistente);
    }


    public Departamento desactivarDepartamento(String id) {
        Departamento departamento = obtenerDepartamentoPorId(id);
        departamento.setActivo(false);
        return departamentoRepository.save(departamento);
    }
    
    public Departamento activarDepartamento(String id) {
    	Departamento depto = obtenerDepartamentoPorId(id);
    	depto.setActivo(true);
        return departamentoRepository.save(depto);
    }
}
