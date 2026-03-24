package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Analisis;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.entity.Pick;
import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.ResultadoPick;
import co.com.synthax.bet.repository.PickRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PickServicio {

    private final PickRepositorio pickRepositorio;

    public Pick crearPick(Pick pick) {
        return pickRepositorio.save(pick);
    }

    public List<Pick> obtenerTodos() {
        return pickRepositorio.findAll();
    }

    public List<Pick> obtenerPorCanal(CanalPick canal) {
        return pickRepositorio.findByCanal(canal);
    }

    public List<Pick> obtenerPendientes() {
        return pickRepositorio.findByResultado(ResultadoPick.PENDIENTE);
    }

    public Pick obtenerPorId(Long id) {
        return pickRepositorio.findById(id)
                .orElseThrow(() -> new RuntimeException("Pick no encontrado con id: " + id));
    }

    public Pick liquidarPick(Long id, ResultadoPick resultado) {
        Pick pick = obtenerPorId(id);
        pick.setResultado(resultado);
        pick.setLiquidadoEn(java.time.LocalDateTime.now());
        log.info(">>> Pick {} liquidado como {}", id, resultado);
        return pickRepositorio.save(pick);
    }

    public void eliminarPick(Long id) {
        if (!pickRepositorio.existsById(id)) {
            throw new RuntimeException("Pick no encontrado con id: " + id);
        }
        pickRepositorio.deleteById(id);
    }
}
