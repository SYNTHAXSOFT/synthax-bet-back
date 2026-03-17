package co.com.synthax.bet.controller;

import co.com.synthax.bet.entity.Departamento;
import co.com.synthax.bet.service.DepartamentoService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/departamentos")
@RequiredArgsConstructor
public class DepartamentoController {

    private final DepartamentoService departamentoService;

    @PreAuthorize("hasRole('ROOT')")
    @PostMapping
    public ResponseEntity<?> crearDepartamento(@Valid @RequestBody Departamento departamento) {
        Map<String, String> response = new HashMap<>();
        try {
            if (departamento.getId() == null) {
                response.put("error", "El campo 'id' es obligatorio y debe ser definido manualmente.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            Departamento nuevoDepartamento = departamentoService.crearDepartamento(departamento);
            return ResponseEntity.status(HttpStatus.CREATED).body(nuevoDepartamento);

        } catch (RuntimeException e) {
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT', 'CANDIDATO', 'ADMINISTRADOR')")
    @GetMapping
    public ResponseEntity<List<Departamento>> obtenerTodosDepartamentos() {
        List<Departamento> departamentos = departamentoService.obtenerTodosDepartamentos();
        return ResponseEntity.ok(departamentos);
    }

    @PreAuthorize("hasAnyRole('ROOT', 'CANDIDATO', 'ADMINISTRADOR')")
    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerDepartamentoPorId(@PathVariable String id) {
        try {
            Departamento departamento = departamentoService.obtenerDepartamentoPorId(id);
            return ResponseEntity.ok(departamento);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarDepartamento(@PathVariable String id, @Valid @RequestBody Departamento departamento) {
        try {
            Departamento departamentoActualizado = departamentoService.actualizarDepartamento(id, departamento);
            return ResponseEntity.ok(departamentoActualizado);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarDepartamento(@PathVariable String id) {
        try {
            Map<String, String> respuesta = new HashMap<>();
            respuesta.put("mensaje", "Departamento eliminado correctamente");
            return ResponseEntity.ok(respuesta);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @PatchMapping("/{id}/desactivar")
    public ResponseEntity<?> desactivarDepartamento(@PathVariable String id) {
        try {
            Departamento departamento = departamentoService.desactivarDepartamento(id);
            return ResponseEntity.ok(departamento);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @PatchMapping("/{id}/activar")
    public ResponseEntity<?> activarDepartamento(@PathVariable String id) {
        try {
            Departamento mesa = departamentoService.activarDepartamento(id);
            return ResponseEntity.ok(mesa);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}