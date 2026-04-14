package co.com.synthax.bet.entity;

import co.com.synthax.bet.enums.CanalPick;
import co.com.synthax.bet.enums.CategoriaAnalisis;
import co.com.synthax.bet.enums.ResultadoPick;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "picks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Pick {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_partido", nullable = false)
    private Partido partido;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_analisis")
    private Analisis analisis;

    @Column(name = "nombre_mercado", nullable = false)
    private String nombreMercado;  // "Over 9.5 Corners", "BTTS Sí"

    @Column(name = "probabilidad", precision = 5, scale = 4)
    private BigDecimal probabilidad;  // probabilidad calculada por el motor

    @Column(name = "valor_cuota", precision = 10, scale = 3)
    private BigDecimal valorCuota;  // cuota en la casa de apuestas

    @Column(name = "casa_apuestas")
    private String casaApuestas;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false)
    private CanalPick canal;  // FREE, VIP, PREMIUM

    @Column(name = "publicado_en")
    private LocalDateTime publicadoEn;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado")
    private ResultadoPick resultado = ResultadoPick.PENDIENTE;

    @Column(name = "liquidado_en")
    private LocalDateTime liquidadoEn;

    /** Edge (ventaja estadística) con que se publicó el pick, ej: 0.12 = 12% */
    @Column(name = "edge", precision = 6, scale = 4)
    private BigDecimal edge;

    /** Categoría del mercado: GOLES, CORNERS, TARJETAS, etc. */
    @Enumerated(EnumType.STRING)
    @Column(name = "categoria_mercado")
    private CategoriaAnalisis categoriaMercado;

    // Blockchain (Diferencial #2)
    @Column(name = "hash_blockchain")
    private String hashBlockchain;

    @Column(name = "tx_blockchain")
    private String txBlockchain;

    @PrePersist
    protected void alPublicar() {
        publicadoEn = LocalDateTime.now();
        resultado = ResultadoPick.PENDIENTE;
    }
}
