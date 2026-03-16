package co.com.synthax.pos.config;

import co.com.synthax.pos.entity.Usuario;
import co.com.synthax.pos.service.JwtService;
import co.com.synthax.pos.service.UsuarioService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UsuarioService usuarioService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
    	
    	

        final String authHeader = request.getHeader("Authorization");
        
        System.out.println(">>> URI: " + request.getRequestURI());
    	System.out.println(">>> Header: " + authHeader);
    	
    	

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            final String jwt = authHeader.substring(7);

            final String userEmail = jwtService.extractUsername(jwt);
            
           

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                Usuario usuario = usuarioService.buscarPorEmail(userEmail);
                
                System.out.println(">>> Token válido?: " + jwtService.isTokenValid(jwt, usuario));

                if (jwtService.isTokenValid(jwt, usuario)) {

                    String roleWithPrefix = "ROLE_" + usuario.getRol();

                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            usuario.getEmail(), null, List.of(new SimpleGrantedAuthority(roleWithPrefix)));
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);

                } else {
                    System.out.println("❌ Token inválido");
                }
            } else if (userEmail == null) {
                System.out.println("❌ No se pudo extraer email del token");
            } else {
                System.out.println("ℹ️ Usuario ya autenticado en contexto");
            }
        } catch (Exception e) {
            System.out.println("💥 Error en filtro JWT: " + e.getMessage());
            e.printStackTrace();
        }
        
      

        filterChain.doFilter(request, response);
    }
}