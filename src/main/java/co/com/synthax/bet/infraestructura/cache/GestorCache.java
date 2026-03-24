package co.com.synthax.bet.infraestructura.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gestor de caché en memoria.
 * Usa ConcurrentHashMap para almacenar respuestas de la API y evitar
 * re-requests innecesarios dentro del límite diario gratuito (100 req/día).
 *
 * Nota: al reiniciar la aplicación el caché se vacía. Para persistencia
 * entre reinicios se puede incorporar Redis más adelante.
 */
@Slf4j
@Service
public class GestorCache {

    @Value("${statbet.proveedor.cache-segundos:3600}")
    private long cacheTtlSegundos;

    @Value("${statbet.apis.api-football.requests-diarios-max:100}")
    private int requestsMax;

    // Caché de objetos: clave -> valor
    private final ConcurrentHashMap<String, Object> almacen = new ConcurrentHashMap<>();

    // Caché de expiración: clave -> momento en que vence
    private final ConcurrentHashMap<String, LocalDateTime> expiraciones = new ConcurrentHashMap<>();

    // Contador de requests del día
    private final AtomicInteger contadorRequests = new AtomicInteger(0);
    private LocalDate fechaContador = LocalDate.now();

    // -------------------------------------------------------
    // Guardar y recuperar objetos
    // -------------------------------------------------------

    public void guardar(String clave, Object valor) {
        guardar(clave, valor, cacheTtlSegundos);
    }

    public void guardar(String clave, Object valor, long ttlSegundos) {
        almacen.put(clave, valor);
        expiraciones.put(clave, LocalDateTime.now().plusSeconds(ttlSegundos));
        log.debug(">>> Cache GUARDADO: {} (TTL: {}s)", clave, ttlSegundos);
    }

    @SuppressWarnings("unchecked")
    public <T> T obtener(String clave, Class<T> tipo) {
        LocalDateTime vencimiento = expiraciones.get(clave);

        if (vencimiento == null || LocalDateTime.now().isAfter(vencimiento)) {
            almacen.remove(clave);
            expiraciones.remove(clave);
            log.debug(">>> Cache MISS (vencido o inexistente): {}", clave);
            return null;
        }

        Object valor = almacen.get(clave);
        if (valor == null) return null;

        log.debug(">>> Cache HIT: {}", clave);
        return (T) valor;
    }

    public void eliminar(String clave) {
        almacen.remove(clave);
        expiraciones.remove(clave);
    }

    public boolean existe(String clave) {
        LocalDateTime vencimiento = expiraciones.get(clave);
        return vencimiento != null && LocalDateTime.now().isBefore(vencimiento);
    }

    // -------------------------------------------------------
    // Control de requests diarios
    // -------------------------------------------------------

    /**
     * Registra un request consumido hoy.
     * Si cambia el día, reinicia el contador automáticamente.
     */
    public void registrarRequest() {
        reiniciarContadorSiEsNuevoDia();
        int total = contadorRequests.incrementAndGet();
        log.info(">>> Requests API hoy: {}/{}", total, requestsMax);
    }

    /**
     * Devuelve el total de requests usados hoy.
     */
    public int contarRequestsHoy() {
        reiniciarContadorSiEsNuevoDia();
        return contadorRequests.get();
    }

    /**
     * Verifica si todavía hay requests disponibles.
     * Reserva 5 requests como margen de seguridad.
     */
    public boolean puedeHacerRequest() {
        reiniciarContadorSiEsNuevoDia();
        boolean puede = contadorRequests.get() < (requestsMax - 5);
        if (!puede) {
            log.warn(">>> Límite de requests alcanzado: {}/{}", contadorRequests.get(), requestsMax);
        }
        return puede;
    }

    // -------------------------------------------------------
    // Helper privado
    // -------------------------------------------------------

    private void reiniciarContadorSiEsNuevoDia() {
        LocalDate hoy = LocalDate.now();
        if (!hoy.equals(fechaContador)) {
            contadorRequests.set(0);
            fechaContador = hoy;
            log.info(">>> Contador de requests reiniciado para {}", hoy);
        }
    }
}
