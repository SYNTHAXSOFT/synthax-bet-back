package co.com.synthax.bet.service;

import co.com.synthax.bet.entity.Partido;
import co.com.synthax.bet.enums.EstadoPartido;
import co.com.synthax.bet.proveedor.ProveedorFutbol;
import co.com.synthax.bet.proveedor.modelo.PartidoExterno;
import co.com.synthax.bet.repository.PartidoRepositorio;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartidoServicio {

    private final PartidoRepositorio partidoRepositorio;
    private final ProveedorFutbol proveedorFutbol;

    /**
     * Obtiene los partidos del día de hoy.
     * Primero busca en BD; si no hay datos frescos, consulta la API y sincroniza.
     */
    public List<Partido> obtenerPartidosDeHoy() {
        LocalDate hoy = LocalDate.now();
        return obtenerPartidosPorFecha(hoy);
    }

    /**
     * Obtiene partidos de una fecha específica.
     * Sincroniza desde la API si la BD no tiene datos del día.
     */
    public List<Partido> obtenerPartidosPorFecha(LocalDate fecha) {
        LocalDateTime inicioDia = fecha.atStartOfDay();
        LocalDateTime finDia = fecha.atTime(23, 59, 59);

        List<Partido> enBd = partidoRepositorio.findByFechaPartidoBetween(inicioDia, finDia);

        if (!enBd.isEmpty()) {
            log.info(">>> {} partidos encontrados en BD para {}", enBd.size(), fecha);
            return enBd;
        }

        log.info(">>> Sin partidos en BD para {}. Sincronizando desde API...", fecha);
        return sincronizarPartidos(fecha);
    }

    /**
     * Fuerza sincronización con la API y persiste los partidos en BD.
     */
    public List<Partido> sincronizarPartidos(LocalDate fecha) {
        List<PartidoExterno> externos = proveedorFutbol.obtenerPartidosDelDia(fecha);

        if (externos.isEmpty()) {
            log.warn(">>> La API no devolvió partidos para {}", fecha);
            return List.of();
        }

        List<Partido> guardados = externos.stream()
                .map(this::upsertarPartido)
                .toList();

        log.info(">>> {} partidos sincronizados para {}", guardados.size(), fecha);
        return guardados;
    }

    /**
     * Inserta o actualiza un partido según su idPartidoApi.
     */
    private Partido upsertarPartido(PartidoExterno externo) {
        Optional<Partido> existente = partidoRepositorio.findByIdPartidoApi(externo.getIdExterno());

        Partido partido = existente.orElse(new Partido());

        partido.setIdPartidoApi(externo.getIdExterno());
        partido.setEquipoLocal(externo.getEquipoLocal());
        partido.setEquipoVisitante(externo.getEquipoVisitante());
        partido.setIdEquipoLocalApi(externo.getIdEquipoLocal());
        partido.setIdEquipoVisitanteApi(externo.getIdEquipoVisitante());
        partido.setLiga(externo.getLiga());
        partido.setIdLigaApi(externo.getIdLiga());
        partido.setPais(externo.getPais());
        partido.setTemporada(externo.getTemporada());
        partido.setRonda(externo.getRonda());
        partido.setFechaPartido(externo.getFechaPartido());
        partido.setArbitro(externo.getArbitro());
        partido.setLogoLocal(externo.getLogoLocal());
        partido.setLogoVisitante(externo.getLogoVisitante());
        partido.setEstado(mapearEstado(externo.getEstado()));

        return partidoRepositorio.save(partido);
    }

    public Partido obtenerPorId(Long id) {
        return partidoRepositorio.findById(id)
                .orElseThrow(() -> new RuntimeException("Partido no encontrado con id: " + id));
    }

    private EstadoPartido mapearEstado(String estado) {
        if (estado == null) return EstadoPartido.PROGRAMADO;
        return switch (estado.toLowerCase()) {
            case "en_vivo"    -> EstadoPartido.EN_VIVO;
            case "finalizado" -> EstadoPartido.FINALIZADO;
            case "cancelado"  -> EstadoPartido.CANCELADO;
            case "aplazado"   -> EstadoPartido.APLAZADO;
            default           -> EstadoPartido.PROGRAMADO;
        };
    }
}
