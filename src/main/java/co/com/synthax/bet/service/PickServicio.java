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
    private final TelegramServicio telegramServicio;

    /**
     * Crea un pick a partir de los datos de una línea de sugerencia.
     * Resuelve los IDs de partido y análisis contra la BD antes de persistir.
     */
    @Transactional
    public PickResponseDTO crearDesdeDTO(PublicarPickDTO dto) {
        Partido partido = partidoRepositorio.findById(dto.getPartidoId())
                .orElseThrow(() -> new RuntimeException("Partido no encontrado: " + dto.getPartidoId()));

        // Validar duplicado: mismo partido + mismo mercado ya registrado
        if (pickRepositorio.existsByPartidoIdAndNombreMercado(dto.getPartidoId(), dto.getNombreMercado())) {
            throw new RuntimeException(
                    "Ya existe un pick registrado para este partido con el mercado '"
                    + dto.getNombreMercado() + "'. No se permiten picks duplicados.");
        }

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
        PickResponseDTO respuesta = PickResponseDTO.desde(guardado);

        // Notificar al canal de Telegram correspondiente
        telegramServicio.notificarNuevoPick(respuesta);

        return respuesta;
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
        PickResponseDTO respuesta = PickResponseDTO.desde(guardado);

        // Notificar resultado al canal de Telegram
        telegramServicio.notificarResultado(respuesta);

        return respuesta;
    }

    public void eliminarPick(Long id) {
        if (!pickRepositorio.existsById(id)) {
            throw new RuntimeException("Pick no encontrado con id: " + id);
        }
        pickRepositorio.deleteById(id);
    }

    /**
     * Reactiva un pick (lo vuelve PENDIENTE) para que pueda ser
     * re-evaluado automáticamente en la próxima resolución.
     * Útil cuando un pick quedó como NULO por falta de datos de la API
     * (corners, tarjetas) pero el partido ya finalizó correctamente.
     */
    @Transactional
    public PickResponseDTO reactivarPick(Long id) {
        Pick pick = pickRepositorio.findByIdConPartido(id)
                .orElseThrow(() -> new RuntimeException("Pick no encontrado con id: " + id));
        pick.setResultado(ResultadoPick.PENDIENTE);
        pick.setLiquidadoEn(null);
        log.info(">>> Pick {} reactivado a PENDIENTE para re-evaluación", id);
        return PickResponseDTO.desde(pickRepositorio.save(pick));
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
     *
     * SIN @Transactional deliberadamente: el método hace llamadas HTTP a la API
     * y mantener una transacción abierta durante esas llamadas monopolizaría la
     * conexión DB. Cada save() corre en su propia mini-transacción del repositorio.
     */
    public RendimientoResolucionDTO resolverPendientesAutomatico() {
        List<Pick> pendientesList = pickRepositorio.findPendientesConPartido();
        log.info(">>> [RESOLUCIÓN] {} picks pendientes encontrados", pendientesList.size());

        int resueltos = 0, ganados = 0, perdidos = 0, nulos = 0, pendientesAun = 0;

        for (Pick pick : pendientesList) {
            Partido partido = pick.getPartido();
            if (partido == null) {
                log.warn(">>> [RESOLUCIÓN] Pick {} sin partido asociado — saltando", pick.getId());
                pendientesAun++; continue;
            }

            log.info(">>> [RESOLUCIÓN] Evaluando pick {} | {} | partido: {} vs {} | idApi={}",
                    pick.getId(), pick.getNombreMercado(),
                    partido.getEquipoLocal(), partido.getEquipoVisitante(),
                    partido.getIdPartidoApi());

            int golesLocal     = -1;
            int golesVisitante = -1;
            int corners        = -1;
            int tarjetas       = -1;

            // Picks de corners o tarjetas necesitan datos que no están en BD → siempre API
            boolean necesitaEstadisticasEspeciales = requiereEstadisticas(pick.getNombreMercado());

            // 1. Intentar resolver desde datos ya guardados en BD
            //    (solo para picks de goles/resultado donde los goles son suficientes)
            if (!necesitaEstadisticasEspeciales
                    && partido.getEstado() == EstadoPartido.FINALIZADO
                    && partido.getGolesLocal() != null
                    && partido.getGolesVisitante() != null) {
                golesLocal     = partido.getGolesLocal();
                golesVisitante = partido.getGolesVisitante();
                log.info(">>> [RESOLUCIÓN] Pick {} — resultado en BD: {} {}-{} {}",
                        pick.getId(),
                        partido.getEquipoLocal(), golesLocal, golesVisitante,
                        partido.getEquipoVisitante());
            } else {
                // 2. Consultar API (obligatorio para corners/tarjetas o cuando no está en BD)
                log.info(">>> [RESOLUCIÓN] Pick {} — consultando API (necesitaEspeciales={}, estado={})...",
                        pick.getId(), necesitaEstadisticasEspeciales, partido.getEstado());

                if (partido.getIdPartidoApi() == null) {
                    log.warn(">>> [RESOLUCIÓN] Pick {} — idPartidoApi es null, no se puede consultar API", pick.getId());
                    pendientesAun++; continue;
                }

                Optional<ResultadoFixtureDTO> resultadoOpt =
                        apiFootball.obtenerResultadoFixture(partido.getIdPartidoApi());

                if (resultadoOpt.isEmpty()) {
                    log.warn(">>> [RESOLUCIÓN] Pick {} — API no devolvió resultado para fixture {}",
                            pick.getId(), partido.getIdPartidoApi());
                    pendientesAun++;
                    continue;
                }

                ResultadoFixtureDTO res = resultadoOpt.get();
                if (!res.estaFinalizado()) {
                    log.warn(">>> [RESOLUCIÓN] Pick {} — fixture {} aún no finalizado según API",
                            pick.getId(), partido.getIdPartidoApi());
                    pendientesAun++; continue;
                }

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

            // Notificar resultado automático al canal de Telegram
            telegramServicio.notificarResultado(PickResponseDTO.desde(pick));

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

    /**
     * Retorna true si el mercado necesita datos de corners o tarjetas
     * que NO están almacenados en la entidad Partido (solo hay goles).
     * Estos picks deben consultar siempre la API para resolverse correctamente.
     */
    private boolean requiereEstadisticas(String mercado) {
        if (mercado == null) return false;
        String m = mercado.toLowerCase();
        return m.contains("corner") || m.contains("tarjeta");
    }
}
