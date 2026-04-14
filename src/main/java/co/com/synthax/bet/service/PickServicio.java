package co.com.synthax.bet.service;

import co.com.synthax.bet.dto.EstadisticasPickDTO;
import co.com.synthax.bet.dto.PickResponseDTO;
import co.com.synthax.bet.dto.PublicarPickDTO;
import co.com.synthax.bet.dto.RendimientoResolucionDTO;
import co.com.synthax.bet.dto.ResultadoFixtureDTO;
import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.entity.Pick;
import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.EstadoPartido;
import co.com.synthax.bet.enums.ResultadoPick;
import co.com.synthax.bet.motor.EvaluadorPick;
import co.com.synthax.bet.proveedor.adaptadores.apifootball.ApiFootballAdaptador;
import co.com.synthax.bet.repository.AnalisisRepositorio;
import co.com.synthax.bet.repository.PartidoRepositorio;
import co.com.synthax.bet.repository.PickRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PickServicio {

    private final PickRepositorio pickRepositorio;
    private final PartidoRepositorio  partidoRepositorio;
    private final AnalisisRepositorio analisisRepositorio;
    private final ApiFootballAdaptador apiFootball;

    /**
     * Crea un pick a partir de los datos de una línea de sugerencia.
     * Resuelve los IDs de partido y análisis contra la BD antes de persistir.
     */
    @Transactional
    public PickResponseDTO crearDesdeDTO(PublicarPickDTO dto) {
        Partido partido = partidoRepositorio.findById(dto.getPartidoId())
                .orElseThrow(() -> new RuntimeException("Partido no encontrado: " + dto.getPartidoId()));

        Pick pick = new Pick();
        pick.setPartido(partido);
        pick.setNombreMercado(dto.getNombreMercado());
        pick.setProbabilidad(dto.getProbabilidad() != null
                ? BigDecimal.valueOf(dto.getProbabilidad()) : null);
        pick.setValorCuota(dto.getValorCuota() != null
                ? BigDecimal.valueOf(dto.getValorCuota()) : null);
        pick.setCasaApuestas(dto.getCasaApuestas());
        pick.setCanal(CanalPick.valueOf(dto.getCanal().toUpperCase()));
        pick.setResultado(ResultadoPick.PENDIENTE);
        if (dto.getEdge() != null) {
            pick.setEdge(java.math.BigDecimal.valueOf(dto.getEdge()));
        }
        if (dto.getCategoriaMercado() != null && !dto.getCategoriaMercado().isBlank()) {
            try { pick.setCategoriaMercado(CategoriaAnalisis.valueOf(dto.getCategoriaMercado().toUpperCase())); }
            catch (IllegalArgumentException ignored) {}
        }

        if (dto.getAnalisisId() != null) {
            analisisRepositorio.findById(dto.getAnalisisId())
                    .ifPresent(pick::setAnalisis);
        }

        log.info(">>> Pick publicado: {} | {} | canal: {}",
                partido.getEquipoLocal() + " vs " + partido.getEquipoVisitante(),
                dto.getNombreMercado(), dto.getCanal());

        Pick guardado = pickRepositorio.save(pick);
        return PickResponseDTO.desde(guardado);
    }

    @Transactional(readOnly = true)
    public List<PickResponseDTO> obtenerTodos() {
        return pickRepositorio.findAllConPartido()
                .stream()
                .map(PickResponseDTO::desde)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PickResponseDTO> obtenerPorCanal(CanalPick canal) {
        return pickRepositorio.findByCanalConPartido(canal)
                .stream()
                .map(PickResponseDTO::desde)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PickResponseDTO> obtenerPendientes() {
        return pickRepositorio.findPendientesConPartido()
                .stream()
                .map(PickResponseDTO::desde)
                .toList();
    }

    @Transactional(readOnly = true)
    public PickResponseDTO obtenerPorId(Long id) {
        Pick pick = pickRepositorio.findByIdConPartido(id)
                .orElseThrow(() -> new RuntimeException("Pick no encontrado con id: " + id));
        return PickResponseDTO.desde(pick);
    }

    @Transactional
    public PickResponseDTO liquidarPick(Long id, ResultadoPick resultado) {
        // Carga con JOIN FETCH para que el partido esté disponible al mapear
        Pick pick = pickRepositorio.findByIdConPartido(id)
                .orElseThrow(() -> new RuntimeException("Pick no encontrado con id: " + id));
        pick.setResultado(resultado);
        pick.setLiquidadoEn(java.time.LocalDateTime.now());
        log.info(">>> Pick {} liquidado como {}", id, resultado);
        Pick guardado = pickRepositorio.save(pick);
        return PickResponseDTO.desde(guardado);
    }

    public void eliminarPick(Long id) {
        if (!pickRepositorio.existsById(id)) {
            throw new RuntimeException("Pick no encontrado con id: " + id);
        }
        pickRepositorio.deleteById(id);
    }

    /**
     * Calcula las estadísticas de rendimiento del sistema de picks.
     * winRate = ganados / (ganados + perdidos)
     * ROI = ganancia neta asumiendo 1 unidad por pick
     */
    public EstadisticasPickDTO estadisticas() {
        List<Pick> todos = pickRepositorio.findAll();

        int ganados   = (int) todos.stream().filter(p -> p.getResultado() == ResultadoPick.GANADO).count();
        int perdidos  = (int) todos.stream().filter(p -> p.getResultado() == ResultadoPick.PERDIDO).count();
        int nulos     = (int) todos.stream().filter(p -> p.getResultado() == ResultadoPick.NULO).count();
        int pendientes = (int) todos.stream().filter(p -> p.getResultado() == ResultadoPick.PENDIENTE).count();

        // Win rate sobre picks liquidados (excluye NULO y PENDIENTE)
        int liquidados = ganados + perdidos;
        double winRate = liquidados > 0 ? (double) ganados / liquidados * 100.0 : 0.0;

        // ROI: asumiendo 1 unidad por pick
        // GANADO → cuota - 1 (ganancia neta)
        // PERDIDO → -1
        // NULO → 0
        List<Pick> todosLiquidados = pickRepositorio.findTodosLiquidados();
        double gananciaNeta = todosLiquidados.stream()
                .mapToDouble(p -> {
                    if (p.getResultado() == ResultadoPick.GANADO && p.getValorCuota() != null)
                        return p.getValorCuota().doubleValue() - 1.0;
                    if (p.getResultado() == ResultadoPick.PERDIDO)
                        return -1.0;
                    return 0.0; // NULO
                })
                .sum();
        double unidadesApostadas = ganados + perdidos + nulos;
        double roi = unidadesApostadas > 0 ? (gananciaNeta / unidadesApostadas) * 100.0 : 0.0;

        // Racha actual (solo GANADO/PERDIDO, más reciente primero)
        List<Pick> conRacha = pickRepositorio.findLiquidadosOrdenadosPorFecha();
        int racha = 0;
        if (!conRacha.isEmpty()) {
            ResultadoPick primerResultado = conRacha.get(0).getResultado();
            for (Pick p : conRacha) {
                if (p.getResultado() == primerResultado) racha++;
                else break;
            }
            if (primerResultado == ResultadoPick.PERDIDO) racha = -racha;
        }

        String rachaDescripcion;
        if (racha == 0)      rachaDescripcion = "Sin racha";
        else if (racha > 0)  rachaDescripcion = racha + " ganado" + (racha > 1 ? "s" : "") + " seguido" + (racha > 1 ? "s" : "");
        else                 rachaDescripcion = Math.abs(racha) + " perdido" + (Math.abs(racha) > 1 ? "s" : "") + " seguido" + (Math.abs(racha) > 1 ? "s" : "");

        return new EstadisticasPickDTO(
                todos.size(), ganados, perdidos, nulos, pendientes,
                Math.round(winRate * 10.0) / 10.0,
                Math.round(roi * 10.0) / 10.0,
                racha, rachaDescripcion
        );
    }

    /**
     * Recorre todos los picks PENDIENTE, consulta la API para ver si el partido
     * ya terminó, evalúa el resultado y lo persiste.
     *
     * - Si el partido está en BD como FINALIZADO y tiene goles, no gasta requests.
     * - Si el partido no está finalizado en BD, consulta la API.
     * - Corners y tarjetas se obtienen de la API sólo cuando son necesarios.
     */
    @Transactional
    public RendimientoResolucionDTO resolverPendientesAutomatico() {
        List<Pick> pendientesList = pickRepositorio.findPendientesConPartido();
        log.info(">>> [RESOLUCIÓN] {} picks pendientes encontrados", pendientesList.size());

        int resueltos = 0, ganados = 0, perdidos = 0, nulos = 0, pendientesAun = 0;

        for (Pick pick : pendientesList) {
            Partido partido = pick.getPartido();
            if (partido == null) { pendientesAun++; continue; }

            int golesLocal     = -1;
            int golesVisitante = -1;
            int corners        = -1;
            int tarjetas       = -1;

            // 1. Intentar resolver desde datos ya guardados en BD
            if (partido.getEstado() == EstadoPartido.FINALIZADO
                    && partido.getGolesLocal() != null
                    && partido.getGolesVisitante() != null) {
                golesLocal     = partido.getGolesLocal();
                golesVisitante = partido.getGolesVisitante();
                log.debug(">>> Pick {} resuelto desde BD (sin API)", pick.getId());
            } else {
                // 2. Consultar API
                if (partido.getIdPartidoApi() == null) { pendientesAun++; continue; }

                Optional<ResultadoFixtureDTO> resultadoOpt =
                        apiFootball.obtenerResultadoFixture(partido.getIdPartidoApi());

                if (resultadoOpt.isEmpty()) {
                    pendientesAun++;
                    continue;
                }

                ResultadoFixtureDTO res = resultadoOpt.get();
                if (!res.estaFinalizado()) { pendientesAun++; continue; }

                golesLocal     = res.getGolesLocal();
                golesVisitante = res.getGolesVisitante();
                corners        = res.getCorners();
                tarjetas       = res.getTarjetas();

                // Actualizar partido en BD para no repetir consulta la próxima vez
                partido.setEstado(EstadoPartido.FINALIZADO);
                if (golesLocal >= 0)     partido.setGolesLocal(golesLocal);
                if (golesVisitante >= 0) partido.setGolesVisitante(golesVisitante);
                partidoRepositorio.save(partido);
            }

            if (golesLocal < 0 || golesVisitante < 0) { pendientesAun++; continue; }

            // 3. Evaluar el pick
            ResultadoPick resultado = EvaluadorPick.evaluar(
                    pick.getNombreMercado(), golesLocal, golesVisitante, corners, tarjetas);

            pick.setResultado(resultado);
            pick.setLiquidadoEn(java.time.LocalDateTime.now());
            pickRepositorio.save(pick);

            resueltos++;
            switch (resultado) {
                case GANADO  -> ganados++;
                case PERDIDO -> perdidos++;
                default      -> nulos++;
            }

            log.info(">>> Pick {} [{}] → {} ({}–{})",
                    pick.getId(), pick.getNombreMercado(), resultado,
                    golesLocal, golesVisitante);
        }

        log.info(">>> [RESOLUCIÓN] Resultado: resueltos={} (G={} P={} N={}) | pendientes={}",
                resueltos, ganados, perdidos, nulos, pendientesAun);

        return new RendimientoResolucionDTO(
                resueltos, ganados, perdidos, nulos, pendientesAun,
                estadisticas()
        );
    }
}
