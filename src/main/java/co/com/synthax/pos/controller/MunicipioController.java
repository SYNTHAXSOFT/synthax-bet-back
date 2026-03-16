package co.com.synthax.pos.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import co.com.synthax.pos.entity.Municipio;
import co.com.synthax.pos.service.MunicipioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/municipios")
@RequiredArgsConstructor
public class MunicipioController {

    private final MunicipioService municipioService;

    @PreAuthorize("hasRole('ROOT')")
    @PostMapping
    public ResponseEntity<?> crearMunicipio(@Valid @RequestBody Municipio municipio) {
        try {
            Municipio nuevoMunicipio = municipioService.crearMunicipio(municipio);
            return ResponseEntity.status(HttpStatus.CREATED).body(nuevoMunicipio);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT','CANDIDATO','ADMINISTRADOR')")
    @GetMapping
    public ResponseEntity<List<Municipio>> obtenerTodosMunicipios() {
        List<Municipio> municipios = municipioService.obtenerTodosMunicipios();
        return ResponseEntity.ok(municipios);
    }

    @PreAuthorize("hasAnyRole('ROOT','CANDIDATO','ADMINISTRADOR')")
    @GetMapping("/{id}")
    public ResponseEntity<?> obtenerMunicipioPorId(@PathVariable String id) {
        try {
            Municipio municipio = municipioService.obtenerMunicipioPorId(id);
            return ResponseEntity.ok(municipio);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasAnyRole('ROOT','CANDIDATO','ADMINISTRADOR')")
    @GetMapping("/departamento/{departamentoId}")
    public ResponseEntity<List<Municipio>> obtenerMunicipiosPorDepartamento(@PathVariable String departamentoId) {
        List<Municipio> municipios = municipioService.obtenerMunicipiosPorDepartamento(departamentoId);
        return ResponseEntity.ok(municipios);
    }

    @PreAuthorize("hasRole('ROOT')")
    @PutMapping("/{id}")
    public ResponseEntity<?> actualizarMunicipio(@PathVariable String id, @Valid @RequestBody Municipio municipio) {
        try {
            Municipio municipioActualizado = municipioService.actualizarMunicipio(id, municipio);
            return ResponseEntity.ok(municipioActualizado);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> eliminarMunicipio(@PathVariable Long id) {
        try {
            municipioService.eliminarMunicipio(id);
            Map<String, String> respuesta = new HashMap<>();
            respuesta.put("mensaje", "Municipio eliminado correctamente");
            return ResponseEntity.ok(respuesta);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @PatchMapping("/{id}/desactivar")
    public ResponseEntity<?> desactivarMunicipio(@PathVariable String id) {
        try {
            Municipio municipio = municipioService.desactivarMunicipio(id);
            return ResponseEntity.ok(municipio);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }

    @PreAuthorize("hasRole('ROOT')")
    @PatchMapping("/{id}/activar")
    public ResponseEntity<?> activarMunicipio(@PathVariable String id) {
        try {
            Municipio municipio = municipioService.activarMunicipio(id);
            return ResponseEntity.ok(municipio);
        } catch (RuntimeException e) {
            Map<String, String> error = new HashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
        }
    }
}