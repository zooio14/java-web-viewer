package com.example.webviewer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

@RestController
public class ViewerController {

    private static final int MAX_REDIRECTS = 5;
    private static final long MAX_CONTENT_LENGTH = 5_000_000L;

    private final SecurityPolicy securityPolicy;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public ViewerController(SecurityPolicy securityPolicy) {
        this.securityPolicy = securityPolicy;
    }

    @GetMapping(value = "/api/view", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(@RequestParam String url) {
        try {
            URI start = securityPolicy.validate(url);
            FetchResult fetched = fetch(start);

            String contentType = fetched.contentType().toLowerCase();
            if (!contentType.contains("text/html") && !contentType.contains("application/xhtml+xml")) {
                return html(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                        errorPage("Conteúdo não suportado",
                                "Este protótipo exibe apenas páginas HTML. Tipo recebido: " + contentType));
            }

            Document doc = Jsoup.parse(fetched.body(), fetched.uri().toString());
            sanitizeAndRewrite(doc, fetched.uri());

            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(doc.outerHtml());

        } catch (IllegalArgumentException ex) {
            return html(HttpStatus.BAD_REQUEST, errorPage("Não foi possível abrir", ex.getMessage()));
        } catch (Exception ex) {
            return html(HttpStatus.BAD_GATEWAY,
                    errorPage("Falha ao carregar a página",
                            "O site pode bloquear robôs, exigir JavaScript avançado ou ter recusado a conexão."));
        }
    }

    private FetchResult fetch(URI first) throws Exception {
        URI current = first;

        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            current = securityPolicy.validate(current.toString());

            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "JavaWebViewer/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            Optional<String> lengthHeader = response.headers().firstValue("Content-Length");
            if (lengthHeader.isPresent()) {
                try {
                    long declared = Long.parseLong(lengthHeader.get());
                    if (declared > MAX_CONTENT_LENGTH) {
                        throw new IllegalArgumentException("Página grande demais para este protótipo.");
                    }
                } catch (NumberFormatException ignored) {
                }
            }

            if (response.body().length > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("Página grande demais para este protótipo.");
            }

            int status = response.statusCode();

            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalArgumentException("Redirecionamento sem destino."));
                current = current.resolve(location);
                continue;
            }

            if (status < 200 || status >= 300) {
                throw new IllegalArgumentException("O site respondeu com HTTP " + status + ".");
            }

            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("text/html");

            String body = new String(response.body(), StandardCharsets.UTF_8);
            return new FetchResult(current, contentType, body);
        }

        throw new IllegalArgumentException("Redirecionamentos demais.");
    }

    private void sanitizeAndRewrite(Document doc, URI pageUri) {
        // Não executa JavaScript de terceiros dentro do domínio do seu aplicativo.
        doc.select("script, iframe, frame, object, embed, form, meta[http-equiv=refresh]").remove();

        // Remove manipuladores inline como onclick/onload.
        for (Element el : doc.getAllElements()) {
            List<String> attrs = el.attributes().asList().stream()
                    .map(a -> a.getKey())
                    .filter(k -> k.toLowerCase().startsWith("on"))
                    .toList();
            attrs.forEach(el::removeAttr);
        }

        // Evita que um <base> remoto altere a navegação local.
        doc.select("base").remove();

        // Torna imagens, CSS e outros recursos relativos em URLs absolutas.
        absolutize(doc, "img[src]", "src");
        absolutize(doc, "source[src]", "src");
        absolutize(doc, "link[href]", "href");
        absolutize(doc, "video[src]", "src");
        absolutize(doc, "audio[src]", "src");

        // Faz todos os links continuarem dentro do visualizador.
        for (Element link : doc.select("a[href]")) {
            String absolute = link.absUrl("href");
            if (absolute.isBlank()) {
                link.attr("href", "#");
                continue;
            }

            try {
                URI target = URI.create(absolute);
                if (securityPolicy.isAllowed(target)) {
                    String proxied = "/api/view?url=" +
                            java.net.URLEncoder.encode(target.toString(), StandardCharsets.UTF_8);
                    link.attr("href", proxied);
                    link.removeAttr("target");
                } else {
                    link.attr("href", "#");
                    link.attr("title", "Este domínio não está na lista permitida.");
                    link.addClass("viewer-blocked-link");
                }
            } catch (Exception ex) {
                link.attr("href", "#");
            }
        }

        // CSS mínimo para explicar links bloqueados.
        doc.head().append("""
            <style>
              .viewer-blocked-link {
                opacity: .55 !important;
                cursor: not-allowed !important;
              }
            </style>
            """);

        // Script injetado pelo próprio app apenas para sincronizar a barra de endereço.
        doc.body().append("""
            <script>
              try {
                parent.postMessage(
                  { type: 'viewer-location', url: %s },
                  location.origin
                );
              } catch (e) {}
            </script>
            """.formatted(jsString(pageUri.toString())));
    }

    private void absolutize(Document doc, String selector, String attr) {
        for (Element el : doc.select(selector)) {
            String absolute = el.absUrl(attr);
            if (!absolute.isBlank()) {
                el.attr(attr, absolute);
            }
        }
    }

    private String errorPage(String title, String message) {
        return """
            <!doctype html>
            <html lang="pt-BR">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                html,body{margin:0;height:100%%;font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#0b1020;color:#eef4ff}
                body{display:grid;place-items:center;padding:24px}
                .box{max-width:680px;background:#151f35;border:1px solid #2b3b59;border-radius:20px;padding:26px;box-shadow:0 22px 60px #0007}
                h1{margin:0 0 10px;font-size:26px}
                p{color:#b6c4d8;line-height:1.6;margin:0}
              </style>
            </head>
            <body>
              <div class="box">
                <h1>%s</h1>
                <p>%s</p>
              </div>
            </body>
            </html>
            """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(message));
    }

    private ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    private String jsString(String value) {
        return "'" + value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + "'";
    }

    private record FetchResult(URI uri, String contentType, String body) {}
}
