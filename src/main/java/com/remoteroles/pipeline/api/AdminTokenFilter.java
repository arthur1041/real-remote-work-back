package com.remoteroles.pipeline.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * O token compartilhado na frente de {@code /admin/*}.
 *
 * <p>Ate 18 Nov 2026 essas rotas eram protegidas so por o contêiner nao ter
 * porta publicada. Isso e uma camada, nao duas: quem tomasse o contêiner web
 * chegava a {@code http://pipeline:8080/admin/*} sem mais nada, inclusive a
 * {@code /admin/rank/{id}} e a {@code /admin/digest/posting/{id}}, que gastam
 * modelo, e a {@code /admin/newsletter/send}, que manda e-mail para a lista
 * inteira. Achado na revisao de seguranca de 17 Nov 2026.
 *
 * <p>O mesmo {@code INTERNAL_TASK_TOKEN} e o mesmo cabecalho
 * {@code x-internal-token} que a web ja exige nas rotas internas dela e que este
 * processo ja manda quando chama a web: um segredo, dois sentidos. Fecha quando
 * falta: sem token, ou com um menor que 24 caracteres, nada em {@code /admin}
 * responde -- a mesma postura de {@code lib/internal-auth.ts}.
 *
 * <p>{@code /admin/health} fica de fora: nao faz nada e e o que se consulta
 * quando o resto parece quebrado. Para chamar uma rota a mao:
 * o cabecalho {@code x-internal-token} com o valor de {@code $INTERNAL_TASK_TOKEN},
 * de dentro do contêiner.
 */
@Component
public class AdminTokenFilter extends OncePerRequestFilter {

    static final String HEADER = "x-internal-token";
    private static final int MIN_LENGTH = 24;

    private final byte[] expected;

    public AdminTokenFilter(@Value("${admin.token:${INTERNAL_TASK_TOKEN:}}") String token) {
        String t = token == null ? "" : token.trim();
        this.expected = t.length() >= MIN_LENGTH ? t.getBytes(StandardCharsets.UTF_8) : null;
    }

    /**
     * Tudo passa pelo token, menos a saude.
     *
     * <p>Nao e "so o que comeca com /admin": este processo nao serve outra
     * coisa, e casar o prefixo na URI crua deixaria passar {@code /admin;x/rank/1}
     * e {@code //admin/rank/1}, que o roteamento do Spring normaliza e o
     * {@code startsWith} nao. A excecao usa o servlet path, que o Tomcat ja
     * entrega decodificado e sem parametros de caminho.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        return "GET".equals(request.getMethod()) && path.equals("/admin/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!authorised(request.getHeader(HEADER))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    boolean authorised(String offered) {
        if (expected == null || offered == null) return false;
        return MessageDigest.isEqual(expected, offered.getBytes(StandardCharsets.UTF_8));
    }
}
